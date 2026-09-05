package com.schwab.agentic.agent;

import static com.schwab.agentic.Assertions.assertEquals;
import static com.schwab.agentic.Assertions.assertThrows;
import static com.schwab.agentic.Assertions.assertTrue;

import com.schwab.agentic.model.AcceptanceCriterion;
import com.schwab.agentic.model.AuditEvent;
import com.schwab.agentic.model.RequirementSpec;
import com.schwab.agentic.model.RiskLevel;
import com.schwab.agentic.model.WorkflowNode;
import com.schwab.agentic.model.WorkflowState;
import java.util.List;
import java.util.Set;

/**
 * Required test: an AGENT_CALL audit event is recorded per invocation, carrying token
 * counts, via {@link AuditingAgentClient}, the seam that connects the client layer to
 * {@link WorkflowState} without making the clients themselves depend on it.
 */
public class AuditingAgentClientTest {

    private static WorkflowState newState() {
        WorkflowNode node = new WorkflowNode("N1", "N1", "noop", Set.of(), null, null, RiskLevel.LOW, 1, Set.of());
        RequirementSpec requirementSpec = new RequirementSpec(
            "REQ-1", 1, "req", "req normalized",
            List.of(new AcceptanceCriterion("AC-1", "criterion", RiskLevel.LOW)));
        return new WorkflowState("RUN-1", requirementSpec, List.of(node));
    }

    public void testAgentCallAuditEventIsRecordedPerInvocationWithTokenCounts() {
        WorkflowState state = newState();
        AgentResponse response = new AgentResponse("response text", 120, 340, 500, Mode.REPLAY, "some-hash");
        AuditingAgentClient client = new AuditingAgentClient(FakeAgentClient.alwaysReturning(response), state);

        client.call(new AgentRequest("system", "user", 100, "N1"));

        List<AuditEvent> agentCallEvents = state.getAuditLog().stream()
            .filter(event -> event.type() == AuditEvent.EventType.AGENT_CALL)
            .toList();
        assertEquals(1, agentCallEvents.size(), "expected exactly one AGENT_CALL event for one invocation");

        AuditEvent event = agentCallEvents.get(0);
        assertEquals(120.0, event.details().get("inputTokens"), "AGENT_CALL details must carry inputTokens");
        assertEquals(340.0, event.details().get("outputTokens"), "AGENT_CALL details must carry outputTokens");
        assertEquals("N1", event.details().get("nodeId"), "AGENT_CALL details must carry the node id");
        assertEquals("REPLAY", event.details().get("mode"), "AGENT_CALL details must carry which mode served it");
    }

    public void testMultipleInvocationsProduceOneAgentCallEventEach() {
        WorkflowState state = newState();
        AgentResponse response = new AgentResponse("text", 1, 1, 1, Mode.REPLAY, "hash");
        AuditingAgentClient client = new AuditingAgentClient(FakeAgentClient.alwaysReturning(response), state);

        client.call(new AgentRequest("system", "user 1", 100, "N1"));
        client.call(new AgentRequest("system", "user 2", 100, "N1"));
        client.call(new AgentRequest("system", "user 3", 100, "N1"));

        long agentCallCount = state.getAuditLog().stream()
            .filter(event -> event.type() == AuditEvent.EventType.AGENT_CALL)
            .count();
        assertEquals(3L, agentCallCount, "expected exactly one AGENT_CALL event per invocation, three total");
    }

    public void testAFailedCallStillRecordsAnAgentCallEventBeforeRethrowing() {
        WorkflowState state = newState();
        RuntimeException failure = new RuntimeException("simulated transport failure");
        AuditingAgentClient client = new AuditingAgentClient(FakeAgentClient.throwing(failure), state);

        assertThrows(RuntimeException.class,
            () -> client.call(new AgentRequest("system", "user", 100, "N1")),
            "the underlying failure must still be rethrown to the caller");

        List<AuditEvent> agentCallEvents = state.getAuditLog().stream()
            .filter(event -> event.type() == AuditEvent.EventType.AGENT_CALL)
            .toList();
        assertEquals(1, agentCallEvents.size(), "a failed call must still produce an AGENT_CALL audit event");
        assertTrue(Boolean.TRUE.equals(agentCallEvents.get(0).details().get("failed")),
            "the audit event for a failed call must mark itself as failed");
    }
}
