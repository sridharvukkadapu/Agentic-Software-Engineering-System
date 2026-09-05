package com.schwab.agentic.reporting;

import static com.schwab.agentic.Assertions.assertEquals;
import static com.schwab.agentic.Assertions.assertNotNull;
import static com.schwab.agentic.Assertions.assertTrue;

import com.schwab.agentic.model.AcceptanceCriterion;
import com.schwab.agentic.model.AuditEvent;
import com.schwab.agentic.model.NodeStatus;
import com.schwab.agentic.model.RequirementSpec;
import com.schwab.agentic.model.RiskLevel;
import com.schwab.agentic.model.WorkflowNode;
import com.schwab.agentic.model.WorkflowState;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Lives outside com.schwab.agentic.model on purpose, standing in for spec 08's metrics
 * and reporting code, which reads {@code WorkflowState.getAuditLog()} and computes
 * things like success rate and MTTR from it. If {@link AuditEvent} could not be named
 * or read from another package, this file would not compile at all, which is exactly
 * the failure mode this test exists to catch before spec 08 is written, not after.
 *
 * Every public accessor on AuditEvent is called here, not just the type name, since a
 * type can be public while an individual accessor is accidentally left off or
 * package-private.
 */
public class AuditEventCrossPackageConsumerTest {

    public void testEveryAuditEventAccessorIsReadableFromAnotherPackage() {
        WorkflowNode node = new WorkflowNode(
            "N1", "Node One", "noop", Set.of(), "dependencies-complete", "artifact-written",
            RiskLevel.LOW, 2, Set.of("AC-1"));
        RequirementSpec requirementSpec = new RequirementSpec(
            "REQ-1", 1, "Build a thing", "Build a thing, normalized",
            List.of(new AcceptanceCriterion("AC-1", "It works", RiskLevel.LOW)));
        WorkflowState state = new WorkflowState("RUN-1", requirementSpec, List.of(node));

        state.transition("N1", NodeStatus.RUNNING, "agent:implementer", "starting");
        state.record(AuditEvent.EventType.AGENT_CALL, "agent:implementer", "called the model",
            Map.of("tokensIn", 120.0, "tokensOut", 340.0));

        List<AuditEvent> auditLog = state.getAuditLog();
        assertEquals(2, auditLog.size(), "expected two audit events: one transition, one record");

        AuditEvent statusChange = auditLog.get(0);
        assertTrue(statusChange.sequence() > 0, "sequence() must be readable and positive");
        assertEquals("RUN-1", statusChange.runId(), "runId() must be readable");
        assertEquals("N1", statusChange.nodeId(), "nodeId() must be readable");
        assertEquals(AuditEvent.EventType.STATUS_CHANGE, statusChange.type(), "type() must be readable");
        assertEquals(NodeStatus.PENDING, statusChange.from(), "from() must be readable");
        assertEquals(NodeStatus.RUNNING, statusChange.to(), "to() must be readable");
        assertEquals("agent:implementer", statusChange.actor(), "actor() must be readable");
        assertEquals("starting", statusChange.reason(), "reason() must be readable");
        assertNotNull(statusChange.details(), "details() must be readable");
        assertNotNull(statusChange.timestamp(), "timestamp() must be readable");
        assertNotNull(statusChange.toLogLine(), "toLogLine() must be readable");

        AuditEvent agentCall = auditLog.get(1);
        assertEquals(AuditEvent.EventType.AGENT_CALL, agentCall.type(), "type() on a non-transition event");
        assertEquals(120.0, agentCall.details().get("tokensIn"), "details() values must be readable");
    }

    /**
     * A minimal stand-in for a spec-08-style success rate computation, to prove that
     * real, non-trivial consumer code (iterating the log, branching on type and status,
     * building an aggregate) compiles and runs against AuditEvent from a foreign package,
     * not just that individual accessor calls compile in isolation.
     */
    public void testAuditLogSupportsComputingASimpleSuccessRateFromAnotherPackage() {
        WorkflowNode node = new WorkflowNode(
            "N1", "Node One", "noop", Set.of(), "dependencies-complete", "artifact-written",
            RiskLevel.LOW, 2, Set.of());
        RequirementSpec requirementSpec = new RequirementSpec(
            "REQ-1", 1, "Build a thing", "Build a thing, normalized", List.of());
        WorkflowState state = new WorkflowState("RUN-1", requirementSpec, List.of(node));

        state.transition("N1", NodeStatus.RUNNING, "system", "attempt 1");
        state.transition("N1", NodeStatus.FAILED, "system", "compile error");
        state.transition("N1", NodeStatus.PENDING, "system", "retry");
        state.transition("N1", NodeStatus.RUNNING, "system", "attempt 2");
        state.transition("N1", NodeStatus.COMPLETED, "system", "compiled");

        long completedTransitions = state.getAuditLog().stream()
            .filter(event -> event.type() == AuditEvent.EventType.STATUS_CHANGE)
            .filter(event -> event.to() == NodeStatus.COMPLETED)
            .count();
        long failedTransitions = state.getAuditLog().stream()
            .filter(event -> event.type() == AuditEvent.EventType.STATUS_CHANGE)
            .filter(event -> event.to() == NodeStatus.FAILED)
            .count();

        assertEquals(1L, completedTransitions, "expected exactly one COMPLETED transition");
        assertEquals(1L, failedTransitions, "expected exactly one FAILED transition");
    }
}
