package com.schwab.agentic.agent;

import com.schwab.agentic.json.Json;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Wraps a real {@link AgentClient} (in practice, always {@link AnthropicClient}) and
 * writes every request/response pair to {@code fixturesDirectory}, keyed by the SHA-256
 * hash of the exact request body sent. Recording is not optional in live mode: this
 * decorator is unconditionally applied whenever {@code --live} is selected, so a run
 * that made a real call always leaves a fixture behind for later replay.
 *
 * The fixture stores the full request alongside the response, not just the response, so
 * a reviewer opening the file can see exactly what was asked without cross-referencing
 * anything else.
 */
public final class RecordingClient implements AgentClient {

    private final AgentClient delegate;
    private final Path fixturesDirectory;

    public RecordingClient(AgentClient delegate, Path fixturesDirectory) {
        if (delegate == null) {
            throw new IllegalArgumentException("RecordingClient delegate must not be null");
        }
        if (fixturesDirectory == null) {
            throw new IllegalArgumentException("RecordingClient fixturesDirectory must not be null");
        }
        this.delegate = delegate;
        this.fixturesDirectory = fixturesDirectory;
    }

    @Override
    public AgentResponse call(AgentRequest request) {
        AgentResponse response = delegate.call(request);
        writeFixture(request, response);
        return response;
    }

    private void writeFixture(AgentRequest request, AgentResponse response) {
        Map<String, Object> fixture = new LinkedHashMap<>();
        fixture.put("request", requestToJson(request));
        fixture.put("response", responseToJson(response));

        String hash = hashOfRequestBody(request);
        Path fixtureFile = fixturesDirectory.resolve(hash + ".json");
        try {
            Files.createDirectories(fixturesDirectory);
            Files.writeString(fixtureFile, Json.write(fixture));
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to write fixture " + fixtureFile, e);
        }
    }

    /**
     * The hash a fixture is keyed by is computed from the same canonical request
     * representation used everywhere else in this package (see
     * {@link ReplayClient#hashOfRequest}), so a request built the same way always maps
     * to the same fixture file regardless of whether it is being recorded or replayed.
     */
    private String hashOfRequestBody(AgentRequest request) {
        return ReplayClient.hashOfRequest(request);
    }

    private Map<String, Object> requestToJson(AgentRequest request) {
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("systemPrompt", request.systemPrompt());
        json.put("userPrompt", request.userPrompt());
        json.put("maxTokens", (double) request.maxTokens());
        json.put("nodeId", request.nodeId());
        return json;
    }

    private Map<String, Object> responseToJson(AgentResponse response) {
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("text", response.text());
        json.put("inputTokens", (double) response.inputTokens());
        json.put("outputTokens", (double) response.outputTokens());
        json.put("latencyMillis", (double) response.latencyMillis());
        return json;
    }
}
