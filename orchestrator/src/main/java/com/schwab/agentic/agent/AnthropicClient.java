package com.schwab.agentic.agent;

import com.schwab.agentic.json.Json;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Makes one real call to the Anthropic Messages API per {@link #call}, using only
 * {@link HttpClient}: no SDK, no Jackson, matching the orchestrator's zero-dependency
 * constraint. Never retries internally. A 429 or 5xx is reported to the caller as an
 * {@link AgentCallException} naming the status code; retrying belongs to the execution
 * engine (spec 02), which already re-runs a node's whole executor on exit gate failure,
 * so every attempt, including one that would have been an internal backoff retry, must
 * appear as its own audit event rather than being absorbed silently in here.
 *
 * The model string is hardcoded rather than configurable, per the scope reduction
 * applied to this spec: a template abstraction for swapping models was cut, since the
 * assignment does not ask for multi-model support and this orchestrator targets exactly
 * one model.
 */
public final class AnthropicClient implements AgentClient {

    private static final String MODEL = "claude-sonnet-4-6";
    private static final String DEFAULT_API_URL = "https://api.anthropic.com/v1/messages";
    private static final String ANTHROPIC_VERSION = "2023-06-01";

    private final HttpClient httpClient;
    private final String apiKey;
    private final String apiUrl;

    public AnthropicClient(String apiKey) {
        this(apiKey, DEFAULT_API_URL);
    }

    /**
     * Package-private: lets a test point this client at a local HTTP server instead of
     * the real Anthropic API, so behavior like non-200 handling and API key redaction
     * can be exercised against a real HTTP response without ever making a real network
     * call to Anthropic.
     */
    AnthropicClient(String apiKey, String apiUrl) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalArgumentException("AnthropicClient apiKey must not be blank");
        }
        this.apiKey = apiKey;
        this.apiUrl = apiUrl;
        this.httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(30))
            .build();
    }

    @Override
    public AgentResponse call(AgentRequest request) {
        String requestBody = buildRequestBody(request);
        // Uses the same canonical hash ReplayClient and RecordingClient use, not a hash
        // of this HTTP-level request body: the fixture a RecordingClient wrapping this
        // client writes is named by ReplayClient.hashOfRequest, so this response's own
        // fixtureKey must match that same name, or the two would silently disagree about
        // which file backs this call.
        String fixtureKey = ReplayClient.hashOfRequest(request);

        HttpRequest httpRequest = HttpRequest.newBuilder()
            .uri(URI.create(apiUrl))
            .header("x-api-key", apiKey)
            .header("anthropic-version", ANTHROPIC_VERSION)
            .header("content-type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(requestBody))
            .timeout(java.time.Duration.ofMinutes(5))
            .build();

        Instant start = Instant.now();
        HttpResponse<String> httpResponse;
        try {
            httpResponse = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new AgentCallException("Failed to reach Anthropic API: " + e.getMessage(), e);
        }
        long latencyMillis = Duration.between(start, Instant.now()).toMillis();

        if (httpResponse.statusCode() != 200) {
            throw new AgentCallException(
                "Anthropic API returned HTTP " + httpResponse.statusCode() + ": " + redactApiKey(httpResponse.body()),
                httpResponse.statusCode());
        }

        return parseResponse(httpResponse.body(), latencyMillis, fixtureKey);
    }

    private String buildRequestBody(AgentRequest request) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", MODEL);
        body.put("max_tokens", (double) request.maxTokens());
        body.put("system", request.systemPrompt());

        Map<String, Object> userMessage = new LinkedHashMap<>();
        userMessage.put("role", "user");
        userMessage.put("content", request.userPrompt());
        body.put("messages", List.of(userMessage));

        return Json.write(body);
    }

    @SuppressWarnings("unchecked")
    private AgentResponse parseResponse(String responseBody, long latencyMillis, String fixtureKey) {
        Map<String, Object> parsed = (Map<String, Object>) Json.parse(responseBody);

        List<Object> contentBlocks = (List<Object>) parsed.get("content");
        String text = extractText(contentBlocks);

        Map<String, Object> usage = (Map<String, Object>) parsed.get("usage");
        int inputTokens = usage == null ? 0 : ((Double) usage.getOrDefault("input_tokens", 0.0)).intValue();
        int outputTokens = usage == null ? 0 : ((Double) usage.getOrDefault("output_tokens", 0.0)).intValue();

        return new AgentResponse(text, inputTokens, outputTokens, latencyMillis, Mode.LIVE, fixtureKey);
    }

    /**
     * Concatenates every {@code type == "text"} content block, not just the first
     * (AC-03-6): the API can return multiple text blocks, and indexing position zero
     * would silently drop content a reader never sees was missing.
     */
    @SuppressWarnings("unchecked")
    static String extractText(List<Object> contentBlocks) {
        if (contentBlocks == null) {
            return "";
        }
        List<String> textParts = new ArrayList<>();
        for (Object blockObj : contentBlocks) {
            Map<String, Object> block = (Map<String, Object>) blockObj;
            if ("text".equals(block.get("type"))) {
                textParts.add((String) block.get("text"));
            }
        }
        return String.join("", textParts);
    }

    /**
     * Never logs the API key: if a request or error body is ever included in an
     * exception message, the key is redacted first. CLAUDE.md rule and AC-03-8 both
     * require the key to never appear in any audit event, log line or artifact.
     */
    private String redactApiKey(String text) {
        return text == null ? null : text.replace(apiKey, "[REDACTED]");
    }

    /** Thrown when a call to the Anthropic API fails, at the transport level or with a non-200 status. */
    public static final class AgentCallException extends RuntimeException {
        private final int statusCode;

        public AgentCallException(String message, Throwable cause) {
            super(message, cause);
            this.statusCode = -1;
        }

        public AgentCallException(String message, int statusCode) {
            super(message);
            this.statusCode = statusCode;
        }

        public int statusCode() {
            return statusCode;
        }
    }
}
