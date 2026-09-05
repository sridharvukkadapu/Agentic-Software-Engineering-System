package com.schwab.agentic.agent;

import com.schwab.agentic.model.AuditEvent;
import com.schwab.agentic.model.WorkflowState;
import java.util.Map;

/**
 * Wraps any {@link AgentClient} and records one AGENT_CALL audit event per invocation,
 * carrying the node id, token counts, latency, mode and fixture key. This is the seam
 * that connects the client layer to the audit log without making
 * {@link AnthropicClient}, {@link RecordingClient} or {@link ReplayClient} depend on
 * {@link WorkflowState} themselves: those three stay simply testable in isolation, and
 * any executor (spec 04) that is handed an {@code AuditingAgentClient} gets audit
 * coverage for free just by calling it normally.
 *
 * The event is recorded whether the call succeeded or the delegate threw: an audit trail
 * that only shows successful calls would hide exactly the failed attempts a reviewer
 * most wants to see (a missing fixture, a malformed live response), so a failed call is
 * recorded with the failure reason before the exception is rethrown.
 */
public final class AuditingAgentClient implements AgentClient {

    private final AgentClient delegate;
    private final WorkflowState state;

    public AuditingAgentClient(AgentClient delegate, WorkflowState state) {
        if (delegate == null) {
            throw new IllegalArgumentException("AuditingAgentClient delegate must not be null");
        }
        if (state == null) {
            throw new IllegalArgumentException("AuditingAgentClient state must not be null");
        }
        this.delegate = delegate;
        this.state = state;
    }

    @Override
    public AgentResponse call(AgentRequest request) {
        try {
            AgentResponse response = delegate.call(request);
            state.record(AuditEvent.EventType.AGENT_CALL, "agent:" + request.nodeId(),
                "agent call for node " + request.nodeId() + " succeeded",
                Map.of(
                    "nodeId", request.nodeId(),
                    "inputTokens", (double) response.inputTokens(),
                    "outputTokens", (double) response.outputTokens(),
                    "latencyMillis", (double) response.latencyMillis(),
                    "mode", response.mode().name(),
                    "fixtureKey", response.fixtureKey()));
            return response;
        } catch (RuntimeException e) {
            state.record(AuditEvent.EventType.AGENT_CALL, "agent:" + request.nodeId(),
                "agent call for node " + request.nodeId() + " failed: " + e.getMessage(),
                Map.of("nodeId", request.nodeId(), "failed", true));
            throw e;
        }
    }
}
