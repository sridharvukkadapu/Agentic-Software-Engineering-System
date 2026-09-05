package com.schwab.agentic.agent;

import com.schwab.agentic.model.AuditEvent;
import com.schwab.agentic.model.WorkflowState;
import java.nio.file.Path;
import java.util.Map;

/**
 * Builds the right {@link AgentClient} for a run's mode and records that choice as an
 * audit event, so a run report can always state which mode produced it (spec 03 item 7)
 * without a reader having to infer it from which fixtures happen to exist.
 *
 * {@code --live} requires an API key and wraps {@link AnthropicClient} in
 * {@link RecordingClient}, so every real call is unconditionally recorded.
 * {@code --replay} is the default and uses {@link ReplayClient}, making no network calls
 * and requiring no key.
 */
public final class AgentClientFactory {

    private AgentClientFactory() {
    }

    public static AgentClient createLive(String apiKey, Path fixturesDirectory, WorkflowState state) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalArgumentException(
                "createLive requires an API key. Set ANTHROPIC_API_KEY or use --replay.");
        }
        state.record(AuditEvent.EventType.COMMAND_EXECUTED, "system",
            "agent mode selected: LIVE, recording to " + fixturesDirectory,
            Map.of("mode", "LIVE", "fixturesDirectory", fixturesDirectory.toString()));
        return new RecordingClient(new AnthropicClient(apiKey), fixturesDirectory);
    }

    public static AgentClient createReplay(Path fixturesDirectory, WorkflowState state) {
        state.record(AuditEvent.EventType.COMMAND_EXECUTED, "system",
            "agent mode selected: REPLAY, serving from " + fixturesDirectory,
            Map.of("mode", "REPLAY", "fixturesDirectory", fixturesDirectory.toString()));
        return new ReplayClient(fixturesDirectory);
    }
}
