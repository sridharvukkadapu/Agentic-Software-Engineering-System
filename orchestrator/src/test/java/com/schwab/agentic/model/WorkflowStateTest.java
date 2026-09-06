package com.schwab.agentic.model;

import static com.schwab.agentic.Assertions.assertDoesNotThrow;
import static com.schwab.agentic.Assertions.assertEquals;
import static com.schwab.agentic.Assertions.assertNull;
import static com.schwab.agentic.Assertions.assertThrows;
import static com.schwab.agentic.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/**
 * Covers spec 01's acceptance criteria for {@link WorkflowState}: audit event
 * correctness on every transition (AC-01-8) and JSON round trip fidelity (AC-01-9),
 * plus the thread safety contract the class documents. Construction-visibility checks
 * for {@link AuditEvent} and immutability checks for {@link WorkflowNode} live in their
 * own {@link AuditEventTest} and {@link WorkflowNodeTest} respectively, not here.
 */
public class WorkflowStateTest {

    public void testTransitionProducesExactlyOneAuditEventMatchingActualStatuses() {
        WorkflowState state = new WorkflowState("RUN-1", TestFixtures.requirementSpec(),
            List.of(TestFixtures.node("N1")));

        state.transition("N1", NodeStatus.RUNNING, "agent:implementer", "starting work");

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

        state.transition("N1", NodeStatus.RUNNING, "system", "step 1");
        state.transition("N1", NodeStatus.COMPLETED, "system", "step 2");
        state.transition("N1", NodeStatus.INVALIDATED, "system", "step 3");
        state.transition("N1", NodeStatus.PENDING, "system", "step 4");
        state.transition("N1", NodeStatus.RUNNING, "system", "step 5");
        state.transition("N1", NodeStatus.FAILED, "system", "step 6");

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

        assertThrows(IllegalStateException.class,
            () -> state.transition("N1", NodeStatus.COMPLETED, "system", "cannot skip RUNNING"),
            "expected PENDING -> COMPLETED to be rejected");

        assertEquals(0, state.getAuditLog().size(), "no audit event should be appended on rejection");
        assertEquals(NodeStatus.PENDING, state.getStatus("N1"), "node status must be unchanged on rejection");
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

    public void testNodeScopedRecordAttachesTheRealNodeId() {
        WorkflowState state = new WorkflowState("RUN-1", TestFixtures.requirementSpec(),
            List.of(TestFixtures.node("N1")));

        state.record(AuditEvent.EventType.APPROVAL_GRANTED, "N1", "human:reviewer", "approved", Map.of());

        AuditEvent event = state.getAuditLog().get(0);
        assertEquals("N1", event.nodeId(), "the node-scoped record overload must attach the real node id");
    }

    public void testNodeScopedRecordRejectsANodeThatDoesNotBelongToThisState() {
        WorkflowState state = new WorkflowState("RUN-1", TestFixtures.requirementSpec(),
            List.of(TestFixtures.node("N1")));

        assertThrows(IllegalArgumentException.class,
            () -> state.record(AuditEvent.EventType.APPROVAL_GRANTED, "NOT-A-REAL-NODE", "human:reviewer",
                "approved", Map.of()),
            "the node-scoped record overload must reject a node id this state does not know about");
    }

    public void testRecordDetailsCarryNestedListsAndMapsNotJustStrings() {
        WorkflowState state = new WorkflowState("RUN-1", TestFixtures.requirementSpec(),
            List.of(TestFixtures.node("N1"), TestFixtures.node("N2")));

        state.record(AuditEvent.EventType.REPLAN, "system", "amendment after DESIGN", Map.of(
            "invalidated", List.of("N2"),
            "preserved", List.of("N1"),
            "revokedEvidence", List.of(Map.of("criterionId", "AC-1", "producedByNode", "N2"))));

        AuditEvent event = state.getAuditLog().get(0);
        assertEquals(List.of("N2"), event.details().get("invalidated"), "nested list must round trip in details");
        assertEquals(List.of("N1"), event.details().get("preserved"), "nested list must round trip in details");
        @SuppressWarnings("unchecked")
        List<Object> revoked = (List<Object>) event.details().get("revokedEvidence");
        assertEquals(1, revoked.size(), "nested list of maps must round trip in details");
        assertEquals(Map.of("criterionId", "AC-1", "producedByNode", "N2"), revoked.get(0),
            "nested map inside a list inside details must round trip");
    }

    public void testSequenceIsStrictlyIncreasingAcrossTransitionAndRecord() {
        WorkflowState state = new WorkflowState("RUN-1", TestFixtures.requirementSpec(),
            List.of(TestFixtures.node("N1"), TestFixtures.node("N2")));

        state.transition("N1", NodeStatus.RUNNING, "system", "a");
        state.record(AuditEvent.EventType.AGENT_CALL, "system", "b", Map.of());
        state.transition("N2", NodeStatus.RUNNING, "system", "c");

        List<AuditEvent> log = state.getAuditLog();
        for (int i = 1; i < log.size(); i++) {
            assertTrue(log.get(i).sequence() > log.get(i - 1).sequence(),
                "sequence must strictly increase: " + log.get(i - 1).sequence() + " then " + log.get(i).sequence());
        }
    }

    public void testJsonRoundTripPreservesNodesAuditEvidenceAndCounters() {
        WorkflowState state = new WorkflowState("RUN-1", TestFixtures.requirementSpec(),
            List.of(TestFixtures.node("N1"), TestFixtures.node("N2", java.util.Set.of("N1"))));

        state.transition("N1", NodeStatus.RUNNING, "agent:implementer", "attempt 1");
        state.transition("N1", NodeStatus.FAILED, "agent:implementer", "compile error");
        state.transition("N1", NodeStatus.PENDING, "system", "retry");
        state.transition("N1", NodeStatus.RUNNING, "agent:implementer", "attempt 2");
        state.transition("N1", NodeStatus.COMPLETED, "agent:implementer", "compiled");
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
        assertEquals(NodeStatus.COMPLETED, restored.getStatus("N1"), "N1 status must round trip");
        assertEquals(NodeStatus.PENDING, restored.getStatus("N2"), "N2 status must round trip");
        assertEquals(java.util.Set.of("N1"), restored.getNode("N2").dependsOn(), "N2 dependsOn must round trip");

        assertEquals(state.getAuditLog().size(), restored.getAuditLog().size(), "audit log size must round trip");
        for (int i = 0; i < state.getAuditLog().size(); i++) {
            assertEquals(state.getAuditLog().get(i), restored.getAuditLog().get(i),
                "audit event " + i + " must round trip exactly");
        }

        assertEquals(state.getEvidence(), restored.getEvidence(), "evidence must round trip exactly");
        assertEquals(state.getDecisions(), restored.getDecisions(), "decisions must round trip exactly");
        assertEquals(state.getRequirementSpec(), restored.getRequirementSpec(), "requirementSpec must round trip");

        assertDoesNotThrow(
            () -> restored.transition("N1", NodeStatus.ROLLED_BACK, "system", "post-resume"),
            "restored state must still enforce and allow legal transitions after round trip");
        assertThrows(IllegalStateException.class,
            () -> restored.transition("N2", NodeStatus.COMPLETED, "system", "illegal"),
            "restored state must still reject illegal transitions after round trip");
    }

    /**
     * Restores a WorkflowState from JSON, loads the same workflow definition
     * independently through WorkflowGraph, and confirms readyNodes computed against the
     * restored statuses matches what the pre-persistence state would have produced. This
     * is the scenario the earlier instance-aliasing fix did not actually cover: fromJson
     * always builds brand new WorkflowNode objects, so if status lived on those objects
     * instead of in WorkflowState's own map, a graph loaded separately (as the execution
     * engine will do on resume) would have no way to see the restored statuses at all.
     */
    public void testReadyNodesAfterJsonRestoreReflectsRestoredStatusesAgainstAnIndependentlyLoadedGraph() {
        WorkflowNode requirement = TestFixtures.node("REQUIREMENT");
        WorkflowNode impact = TestFixtures.node("IMPACT", java.util.Set.of("REQUIREMENT"));
        WorkflowNode design = TestFixtures.node("DESIGN", java.util.Set.of("IMPACT"));
        WorkflowState original = new WorkflowState("RUN-1", TestFixtures.requirementSpec(),
            List.of(requirement, impact, design));

        original.transition("REQUIREMENT", NodeStatus.RUNNING, "system", "start");
        original.transition("REQUIREMENT", NodeStatus.COMPLETED, "system", "done");
        original.transition("IMPACT", NodeStatus.RUNNING, "system", "start");
        original.transition("IMPACT", NodeStatus.COMPLETED, "system", "done");

        String json = original.toJsonString();
        WorkflowState restored = WorkflowState.fromJsonString(json);

        com.schwab.agentic.graph.WorkflowGraph independentlyLoadedGraph =
            com.schwab.agentic.graph.WorkflowGraph.of(List.of(
                TestFixtures.node("REQUIREMENT"),
                TestFixtures.node("IMPACT", java.util.Set.of("REQUIREMENT")),
                TestFixtures.node("DESIGN", java.util.Set.of("IMPACT"))));

        List<WorkflowNode> ready = independentlyLoadedGraph.readyNodes(restored.getStatuses());

        assertEquals(1, ready.size(), "expected exactly DESIGN to be ready after restore");
        assertEquals("DESIGN", ready.get(0).id(),
            "readyNodes against a status map from a restored WorkflowState, checked against an"
                + " independently loaded WorkflowGraph, must reflect the restored statuses");
    }

    /**
     * 20 threads earlier only ever touched their own distinct node, so there was no
     * shared mutable field for two threads to race on beyond the audit log itself, and
     * removing synchronized from WorkflowState did not make that version of this test
     * fail. This version drives many threads through repeated legal transition cycles on
     * a small, shared set of nodes with a high iteration count, so the same node's status
     * entry and the same audit log are genuinely contended. Verified to fail reliably
     * with synchronized removed before being written this way; see the session record.
     */
    /**
     * Runs the contention phase itself (fresh state, fresh thread pool) up to
     * {@code maxAttempts} times, keeping the first attempt that actually observes a real
     * race, rather than asserting a race on a single fixed run. Whether 32 threads racing
     * 500 cycles each on 4 nodes actually collide even once is a function of the real
     * scheduler and how quiet the machine is; on a fast, uncontended CI box or a quiet
     * laptop, one run can legitimately interleave cleanly and see zero illegal
     * transitions, which does not mean the synchronization guarantee is untested, only
     * that this particular attempt did not happen to exercise it. Looping (bounded, so a
     * genuinely broken lock still fails loudly instead of spinning forever) is what turns
     * "contention was not observed this time" into "contention could not be forced even
     * after repeated tries," which is the only version of that finding actually worth
     * failing the build over.
     */
    public void testConcurrentTransitionsUnderHeavyContentionProduceAConsistentAuditLog() throws InterruptedException {
        int nodeCount = 4;
        int threadsPerNode = 8;
        int cyclesPerThread = 500;
        int maxAttempts = 5;

        WorkflowState state = null;
        int observedIllegalTransitionCount = 0;

        for (int attempt = 1; attempt <= maxAttempts && observedIllegalTransitionCount == 0; attempt++) {
            List<WorkflowNode> nodes = new java.util.ArrayList<>();
            for (int i = 0; i < nodeCount; i++) {
                nodes.add(TestFixtures.node("N" + i));
            }
            state = new WorkflowState("RUN-1", TestFixtures.requirementSpec(), nodes);

            int totalThreads = nodeCount * threadsPerNode;
            ExecutorService pool = Executors.newFixedThreadPool(totalThreads);
            CountDownLatch startLatch = new CountDownLatch(1);
            CountDownLatch doneLatch = new CountDownLatch(totalThreads);
            AtomicInteger observedIllegalTransitions = new AtomicInteger(0);
            WorkflowState attemptState = state;

            for (int t = 0; t < totalThreads; t++) {
                String nodeId = "N" + (t % nodeCount);
                pool.submit(() -> {
                    try {
                        startLatch.await();
                        for (int cycle = 0; cycle < cyclesPerThread; cycle++) {
                            try {
                                attemptState.transition(nodeId, NodeStatus.RUNNING, "system", "cycle start");
                                attemptState.transition(nodeId, NodeStatus.FAILED, "system", "cycle fail");
                                attemptState.transition(nodeId, NodeStatus.PENDING, "system", "cycle retry");
                            } catch (IllegalStateException raceLost) {
                                // Another thread's transition on the same node interleaved with
                                // this one: expected under contention, since only one thread can
                                // legally advance a given node at a time. Counted, not treated as
                                // a failure by itself; what matters is whether the audit log
                                // stays consistent.
                                observedIllegalTransitions.incrementAndGet();
                            }
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        doneLatch.countDown();
                    }
                });
            }

            startLatch.countDown();
            boolean finished = doneLatch.await(30, TimeUnit.SECONDS);
            pool.shutdown();
            assertTrue(finished, "all concurrent transitions must complete within the timeout (attempt " + attempt + ")");

            observedIllegalTransitionCount = observedIllegalTransitions.get();

            List<AuditEvent> log = attemptState.getAuditLog();
            List<Long> sequences = log.stream().map(AuditEvent::sequence).collect(Collectors.toList());
            List<Long> sortedUnique = sequences.stream().distinct().sorted().collect(Collectors.toList());
            assertEquals(sequences.size(), sortedUnique.size(),
                "sequence numbers must be unique under heavy contention (attempt " + attempt + "), found "
                    + sequences.size() + " events but only " + sortedUnique.size() + " unique sequence numbers");
            for (int i = 0; i < sequences.size(); i++) {
                assertEquals((long) (i + 1), sortedUnique.get(i),
                    "sequence numbers must be contiguous starting at 1 with no gaps under heavy contention (attempt "
                        + attempt + ")");
            }
        }

        assertTrue(observedIllegalTransitionCount > 0,
            "expected genuine contention within " + maxAttempts + " attempts: multiple threads racing the same"
                + " node's transitions should eventually cause some of them to lose the race and see an illegal"
                + " transition");
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

        assertThrows(IllegalArgumentException.class,
            () -> state.transition("N2", NodeStatus.RUNNING, "system", "unknown node"),
            "transitioning a node id not tracked by this state must be rejected");
    }
}
