package com.schwab.agentic.agent;

/**
 * What came back from an {@link AgentClient} call: the model's text, how many tokens the
 * call cost in each direction (read directly from the API's own usage figures, never
 * estimated), how long it took, which {@link Mode} served it, and the fixture key the
 * call was recorded or replayed under.
 *
 * {@code fixtureKey} is present even for a {@link Mode#LIVE} response, not only a replayed
 * one: it is the same hash {@link RecordingClient} used to name the fixture file, so a
 * caller can always say exactly which file on disk backs this response.
 */
public record AgentResponse(
    String text,
    int inputTokens,
    int outputTokens,
    long latencyMillis,
    Mode mode,
    String fixtureKey
) {
    public AgentResponse {
        if (text == null) {
            throw new IllegalArgumentException("AgentResponse text must not be null");
        }
        if (inputTokens < 0) {
            throw new IllegalArgumentException("AgentResponse inputTokens must not be negative, got " + inputTokens);
        }
        if (outputTokens < 0) {
            throw new IllegalArgumentException("AgentResponse outputTokens must not be negative, got " + outputTokens);
        }
        if (latencyMillis < 0) {
            throw new IllegalArgumentException("AgentResponse latencyMillis must not be negative, got " + latencyMillis);
        }
        if (mode == null) {
            throw new IllegalArgumentException("AgentResponse mode must not be null");
        }
        if (fixtureKey == null || fixtureKey.isBlank()) {
            throw new IllegalArgumentException("AgentResponse fixtureKey must not be blank");
        }
    }
}
