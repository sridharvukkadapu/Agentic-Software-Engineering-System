package com.schwab.agentic.model;

import static com.schwab.agentic.Assertions.assertDoesNotThrow;
import static com.schwab.agentic.Assertions.assertEquals;
import static com.schwab.agentic.Assertions.assertFalse;
import static com.schwab.agentic.Assertions.assertNull;
import static com.schwab.agentic.Assertions.assertThrows;
import static com.schwab.agentic.Assertions.assertTrue;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * Covers spec 01's acceptance criteria for {@link WorkflowState}: setStatus visibility
 * (AC-01-7), audit event correctness on every transition (AC-01-8), and JSON round trip
 * fidelity (AC-01-9), plus the thread safety contract the class documents.
 */
public class WorkflowStateTest {

    public void testSetStatusIsNotPublicAndCannotBeCalledFromOutsideThePackage() throws Exception {
        Method setStatus = WorkflowNode.class.getDeclaredMethod("setStatus", NodeStatus.class);
        assertFalse(Modifier.isPublic(setStatus.getModifiers()), "WorkflowNode.setStatus must not be public");
        assertFalse(Modifier.isProtected(setStatus.getModifiers()), "WorkflowNode.setStatus must not be protected");
    }

    public void testTransitionProducesExactlyOneAuditEventMatchingActualStatuses() {
        WorkflowState state = new WorkflowState("RUN-1", TestFixtures.requirementSpec(),
            List.of(TestFixtures.node("N1")));
        WorkflowNode node = state.getNode("N1");

        state.transition(node, NodeStatus.RUNNING, "agent:implementer", "starting work");

        List<AuditEvent> log = state.getAuditLog();
        assertEquals(1, log.size(), "expected exactly one audit event after one transition");
        AuditEvent event = log.get(0);
        assertEquals("N1", event.nodeId(), "audit event nodeId");
        assertEquals(NodeStatus.PENDING, event.from(), "audit event from");
        assertEquals(NodeStatus.RUNNING, event.to(), "audit event to");
        assertEquals("agent:implementer", event.actor(), "audit event actor");
        assertEquals(AuditEvent.EventType.STATUS_CHANGE, event.type(), "audit event type");
    }

    public void testEachTransitionCarriesTheObservedFromNotAHardcodedValue() {
        WorkflowState state = new WorkflowState("RUN-1", TestFixtures.requirementSpec(),
            List.of(TestFixtures.node("N1")));
        WorkflowNode node = state.getNode("N1");

        state.transition(node, NodeStatus.RUNNING, "system", "step 1");
        state.transition(node, NodeStatus.COMPLETED, "system", "step 2");
        state.transition(node, NodeStatus.INVALIDATED, "system", "step 3");
        state.transition(node, NodeStatus.PENDING, "system", "step 4");
        state.transition(node, NodeStatus.RUNNING, "system", "step 5");
        state.transition(node, NodeStatus.FAILED, "system", "step 6");

        List<AuditEvent> log = state.getAuditLog();
        NodeStatus previousTo = NodeStatus.PENDING;
        for (AuditEvent event : log) {
            assertEquals(previousTo, event.from(),
                "audit event from must equal the previous event's to, event: " + event);
            previousTo = event.to();
        }
    }

    public void testIllegalTransitionThrowsAndAppendsNoAuditEvent() {
        WorkflowState state = new WorkflowState("RUN-1", TestFixtures.requirementSpec(),
            List.of(TestFixtures.node("N1")));
        WorkflowNode node = state.getNode("N1");

        assertThrows(IllegalStateException.class,
            () -> state.transition(node, NodeStatus.COMPLETED, "system", "cannot skip RUNNING"),
            "expected PENDING -> COMPLETED to be rejected");

        assertEquals(0, state.getAuditLog().size(), "no audit event should be appended on rejection");
        assertEquals(NodeStatus.PENDING, node.getStatus(), "node status must be unchanged on rejection");
    }

    public void testRecordRejectsStatusChangeEventType() {
        WorkflowState state = new WorkflowState("RUN-1", TestFixtures.requirementSpec(),
            List.of(TestFixtures.node("N1")));

        assertThrows(IllegalArgumentException.class,
            () -> state.record(AuditEvent.EventType.STATUS_CHANGE, "system", "reason", Map.of()),
            "record() must reject STATUS_CHANGE, transition() is the only path for that");
    }

    public void testRecordEventsAreRunScopedNotNodeScoped() {
        WorkflowState state = new WorkflowState("RUN-1", TestFixtures.requirementSpec(),
            List.of(TestFixtures.node("N1")));

        state.record(AuditEvent.EventType.AGENT_CALL, "agent:design", "called the design agent",
            Map.of("model", "claude-sonnet-4-6"));

        AuditEvent event = state.getAuditLog().get(0);
        assertNull(event.nodeId(), "record() events must have a null nodeId");
        assertNull(event.from(), "record() events must have a null from");
        assertNull(event.to(), "record() events must have a null to");
        assertEquals("claude-sonnet-4-6", event.details().get("model"), "details map must round trip");
    }

    public void testSequenceIsStrictlyIncreasingAcrossTransitionAndRecord() {
        WorkflowState state = new WorkflowState("RUN-1", TestFixtures.requirementSpec(),
            List.of(TestFixtures.node("N1"), TestFixtures.node("N2")));

        state.transition(state.getNode("N1"), NodeStatus.RUNNING, "system", "a");
        state.record(AuditEvent.EventType.AGENT_CALL, "system", "b", Map.of());
        state.transition(state.getNode("N2"), NodeStatus.RUNNING, "system", "c");

        List<AuditEvent> log = state.getAuditLog();
        for (int i = 1; i < log.size(); i++) {
            assertTrue(log.get(i).sequence() > log.get(i - 1).sequence(),
                "sequence must strictly increase: " + log.get(i - 1).sequence() + " then " + log.get(i).sequence());
        }
    }

    public void testJsonRoundTripPreservesNodesAuditEvidenceAndCounters() {
        WorkflowState state = new WorkflowState("RUN-1", TestFixtures.requirementSpec(),
            List.of(TestFixtures.node("N1"), TestFixtures.node("N2", java.util.Set.of("N1"))));
        WorkflowNode n1 = state.getNode("N1");

        state.transition(n1, NodeStatus.RUNNING, "agent:implementer", "attempt 1");
        state.transition(n1, NodeStatus.FAILED, "agent:implementer", "compile error");
        state.transition(n1, NodeStatus.PENDING, "system", "retry");
        state.transition(n1, NodeStatus.RUNNING, "agent:implementer", "attempt 2");
        state.transition(n1, NodeStatus.COMPLETED, "agent:implementer", "compiled");
        state.record(AuditEvent.EventType.COMMAND_EXECUTED, "system", "ran build",
            Map.of("exitCode", 0.0, "durationMs", 1200.0));
        state.addEvidence(new Evidence(
            Evidence.Origin.EXECUTED, "AC-1", true, "build passed", "mvn compile", "N1", "logs/build.log",
            TestFixtures.fixedInstant()));
        state.addDecision(new DecisionRecord(
            "D-1", "chose approach A over B", "agent:design", TestFixtures.fixedInstant(),
            Map.of("affectsCriteria", List.of("AC-1"))));
        state.setWorkflowStatus(WorkflowStatus.RUNNING);

        String json = state.toJsonString();
        WorkflowState restored = WorkflowState.fromJsonString(json);

        assertEquals(state.getRunId(), restored.getRunId(), "runId must round trip");
        assertEquals(state.getWorkflowStatus(), restored.getWorkflowStatus(), "workflowStatus must round trip");
        assertEquals(state.getRetryCount("N1"), restored.getRetryCount("N1"), "retry count must round trip");
        assertEquals(NodeStatus.COMPLETED, restored.getNode("N1").getStatus(), "N1 status must round trip");
        assertEquals(NodeStatus.PENDING, restored.getNode("N2").getStatus(), "N2 status must round trip");
        assertEquals(java.util.Set.of("N1"), restored.getNode("N2").getDependsOn(), "N2 dependsOn must round trip");

        assertEquals(state.getAuditLog().size(), restored.getAuditLog().size(), "audit log size must round trip");
        for (int i = 0; i < state.getAuditLog().size(); i++) {
            assertEquals(state.getAuditLog().get(i), restored.getAuditLog().get(i),
                "audit event " + i + " must round trip exactly");
        }

        assertEquals(state.getEvidence(), restored.getEvidence(), "evidence must round trip exactly");
        assertEquals(state.getDecisions(), restored.getDecisions(), "decisions must round trip exactly");
        assertEquals(state.getRequirementSpec(), restored.getRequirementSpec(), "requirementSpec must round trip");

        assertDoesNotThrow(
            () -> restored.transition(restored.getNode("N1"), NodeStatus.ROLLED_BACK, "system", "post-resume"),
            "restored state must still enforce and allow legal transitions after round trip");
        assertThrows(IllegalStateException.class,
            () -> restored.transition(restored.getNode("N2"), NodeStatus.COMPLETED, "system", "illegal"),
            "restored state must still reject illegal transitions after round trip");
    }

    public void testConcurrentTransitionsOnDifferentNodesProduceAConsistentAuditLog() throws InterruptedException {
        int nodeCount = 20;
        List<WorkflowNode> nodes = new java.util.ArrayList<>();
        for (int i = 0; i < nodeCount; i++) {
            nodes.add(TestFixtures.node("N" + i));
        }
        WorkflowState state = new WorkflowState("RUN-1", TestFixtures.requirementSpec(), nodes);

        ExecutorService pool = Executors.newFixedThreadPool(nodeCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(nodeCount);

        for (int i = 0; i < nodeCount; i++) {
            String nodeId = "N" + i;
            pool.submit(() -> {
                try {
                    startLatch.await();
                    WorkflowNode node = state.getNode(nodeId);
                    state.transition(node, NodeStatus.RUNNING, "system", "concurrent start");
                    state.transition(node, NodeStatus.COMPLETED, "system", "concurrent finish");
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        boolean finished = doneLatch.await(10, TimeUnit.SECONDS);
        pool.shutdown();
        assertTrue(finished, "all concurrent transitions must complete within the timeout");

        List<AuditEvent> log = state.getAuditLog();
        assertEquals(nodeCount * 2, log.size(), "expected exactly two audit events per node");

        List<Long> sequences = log.stream().map(AuditEvent::sequence).collect(Collectors.toList());
        List<Long> sortedUnique = sequences.stream().distinct().sorted().collect(Collectors.toList());
        assertEquals(sequences.size(), sortedUnique.size(), "sequence numbers must be unique under concurrency");
        for (int i = 0; i < nodeCount * 2; i++) {
            assertEquals((long) (i + 1), sortedUnique.get(i), "sequence numbers must be contiguous starting at 1");
        }

        for (int i = 0; i < nodeCount; i++) {
            assertEquals(NodeStatus.COMPLETED, state.getNode("N" + i).getStatus(),
                "node N" + i + " must have reached COMPLETED");
        }
    }

    public void testReplaceRequirementSpecRejectsNonIncreasingRevision() {
        WorkflowState state = new WorkflowState("RUN-1", TestFixtures.requirementSpec(),
            List.of(TestFixtures.node("N1")));

        RequirementSpec sameRevision = TestFixtures.requirementSpec();
        assertThrows(IllegalArgumentException.class,
            () -> state.replaceRequirementSpec(sameRevision),
            "replacing with a non-increasing revision must be rejected");
    }

    public void testTransitionRejectsANodeThatDoesNotBelongToThisState() {
        WorkflowState state = new WorkflowState("RUN-1", TestFixtures.requirementSpec(),
            List.of(TestFixtures.node("N1")));
        WorkflowNode foreignNode = TestFixtures.node("N1");

        assertThrows(IllegalArgumentException.class,
            () -> state.transition(foreignNode, NodeStatus.RUNNING, "system", "foreign node"),
            "transitioning a node instance not owned by this state must be rejected");
    }
}
