package com.schwab.agentic.agent;

import com.schwab.agentic.json.Json;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.NoSuchFileException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Map;

/**
 * Serves recorded fixtures from {@code fixturesDirectory} by the SHA-256 hash of the
 * exact request, and never touches the network. {@code --replay} is the default mode
 * (CLAUDE.md), so this class is what makes a run reproducible for an evaluator with no
 * API key.
 *
 * A cache miss throws, naming the missing hash, rather than falling back to any kind of
 * canned or synthesized response. Silently fabricating a response here would be exactly
 * the mocking the assignment's evaluation is designed to catch: a run that "succeeded"
 * in replay mode must mean every agent call it made was genuinely recorded from a real
 * one, never invented on the spot because nothing was found.
 */
public final class ReplayClient implements AgentClient {

    private final Path fixturesDirectory;

    public ReplayClient(Path fixturesDirectory) {
        if (fixturesDirectory == null) {
            throw new IllegalArgumentException("ReplayClient fixturesDirectory must not be null");
        }
        this.fixturesDirectory = fixturesDirectory;
    }

    @Override
    @SuppressWarnings("unchecked")
    public AgentResponse call(AgentRequest request) {
        String hash = hashOfRequest(request);
        Path fixtureFile = fixturesDirectory.resolve(hash + ".json");

        String fixtureContent;
        try {
            fixtureContent = Files.readString(fixtureFile);
        } catch (NoSuchFileException e) {
            throw new MissingFixtureException(hash, fixtureFile);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read fixture " + fixtureFile, e);
        }

        Map<String, Object> fixture = (Map<String, Object>) Json.parse(fixtureContent);
        Map<String, Object> responseJson = (Map<String, Object>) fixture.get("response");

        return new AgentResponse(
            (String) responseJson.get("text"),
            ((Double) responseJson.get("inputTokens")).intValue(),
            ((Double) responseJson.get("outputTokens")).intValue(),
            ((Double) responseJson.get("latencyMillis")).longValue(),
            Mode.REPLAY,
            hash);
    }

    /**
     * The canonical hash for a request, shared with {@link RecordingClient} so a request
     * built the same way in either mode always resolves to the same fixture file. Hashes
     * the same JSON representation {@link RecordingClient} stores under {@code "request"}
     * in the fixture, over the request's fields directly rather than any
     * HTTP-request-body detail AnthropicClient might add, since a fixture must be keyed
     * by what the caller asked for, not by transport-level formatting that could change
     * without the underlying request changing.
     */
    static String hashOfRequest(AgentRequest request) {
        java.util.LinkedHashMap<String, Object> canonical = new java.util.LinkedHashMap<>();
        canonical.put("systemPrompt", request.systemPrompt());
        canonical.put("userPrompt", request.userPrompt());
        canonical.put("maxTokens", (double) request.maxTokens());
        canonical.put("nodeId", request.nodeId());
        String canonicalJson = Json.write(canonical);

        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(canonicalJson.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : hash) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 must be available on every JDK 21 installation", e);
        }
    }

    /** Thrown when replay mode has no fixture for a request. Names the missing hash and file, with a hint to re-record. */
    public static final class MissingFixtureException extends RuntimeException {
        public MissingFixtureException(String hash, Path fixtureFile) {
            super("No fixture found for request hash " + hash + " at " + fixtureFile
                + ". Re-record it by running with --live and ANTHROPIC_API_KEY set.");
        }
    }
}
