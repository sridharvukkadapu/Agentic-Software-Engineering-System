package com.schwab.agentic.engine;

import static com.schwab.agentic.Assertions.assertEquals;
import static com.schwab.agentic.Assertions.assertFalse;
import static com.schwab.agentic.Assertions.assertThrows;
import static com.schwab.agentic.Assertions.assertTrue;

import com.schwab.agentic.graph.WorkflowGraph;
import com.schwab.agentic.model.AuditEvent;
import com.schwab.agentic.model.NodeStatus;
import com.schwab.agentic.model.WorkflowNode;
import com.schwab.agentic.model.WorkflowState;
import com.schwab.agentic.model.WorkflowStatus;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Covers spec 02's acceptance criteria and its explicitly required tests: retry to
 * exhaustion (AC-02-4, AC-02-5), rollback that actually restores files (AC-02-7, AC-02-8),
 * rollback of a COMPLETED node, parallel fan-out and join (AC-02-1, AC-02-2), one
 * parallel node failing not hanging its siblings, an unknown gate name failing at load
 * (AC-02-3 is exit-gate-fails-completes-anyway, covered separately), and fallback
 * producing a different artifact than a retry would.
 */
public class WorkflowEngineTest {

    public void testNodeRetriedExactlyMaxAttemptsTimesThenTransitionsToFailed() throws IOException {
        Path tempDir = Files.createTempDirectory("engine-test");
        WorkflowNode node = TestEngineFixtures.node("N1", Set.of(), 3);
        WorkflowGraph graph = WorkflowGraph.of(List.of(node));
        WorkflowState state = new WorkflowState("RUN-1", TestEngineFixtures.requirementSpec(), graph.getAllNodes());

        ControllableExecutor executor = new ControllableExecutor();
        executor.alwaysReturn("N1", ControllableExecutor.Outcome.failure("always fails in this test"));

        WorkflowEngine engine = buildEngine(graph, state, executor, tempDir, null);
        WorkflowStatus outcome = engine.run();

        assertEquals(3, executor.callCount("N1"), "expected exactly maxAttempts (3) calls to the executor");
        assertEquals(NodeStatus.FAILED, state.getStatus("N1"), "node must end FAILED after exhausting retries");
        assertEquals(WorkflowStatus.SAFE_STOPPED, outcome, "a run with no fallback and no checkpoint must safe-stop");
    }

    public void testFailureReasonFromAttemptNIsPresentInContextForAttemptNPlusOne() throws IOException {
        Path tempDir = Files.createTempDirectory("engine-test");
        WorkflowNode node = TestEngineFixtures.node("N1", Set.of(), 3);
        WorkflowGraph graph = WorkflowGraph.of(List.of(node));
        WorkflowState state = new WorkflowState("RUN-1", TestEngineFixtures.requirementSpec(), graph.getAllNodes());

        ControllableExecutor executor = new ControllableExecutor();
        executor.failThenSucceed("N1", 2, tempDir.resolve("artifact.txt"));

        WorkflowEngine engine = buildEngine(graph, state, executor, tempDir, null);
        WorkflowStatus outcome = engine.run();

        assertEquals(WorkflowStatus.COMPLETED, outcome, "the node succeeds on attempt 3, so the run completes");
        assertEquals(NodeStatus.COMPLETED, state.getStatus("N1"), "node status after eventual success");
        assertEquals(2, state.getRetryCount("N1"), "run's retry counter must read 2 (two retries before success)");

        List<ControllableExecutor.Invocation> invocations = executor.invocations();
        assertEquals(3, invocations.size(), "expected three invocations: two failures, one success");
        ControllableExecutor.Invocation secondAttempt = invocations.get(1);
        ControllableExecutor.Invocation thirdAttempt = invocations.get(2);

        // The context's previousFailureReason is the exit gate's verdict, not the
        // executor's own claim: the executor does not decide its own success (CLAUDE.md
        // rule 2). Both failed attempts write a real, empty artifact file, so the gate's
        // reason names the actual thing it found wrong ("declared artifact is empty"),
        // proving the reason flows from a genuine gate evaluation, not the executor's
        // self-reported summary string.
        String expectedGateReason = "declared artifact is empty: " + tempDir.resolve("artifact.txt");
        assertEquals(expectedGateReason, secondAttempt.context().get("previousFailureReason"),
            "the second attempt's context must carry the first attempt's exit-gate failure reason");
        assertEquals(expectedGateReason, thirdAttempt.context().get("previousFailureReason"),
            "the third attempt's context must carry the second attempt's exit-gate failure reason");
        assertEquals(1.0, secondAttempt.context().get("previousAttempt"),
            "the second attempt's context must record that attempt 1 preceded it");
        assertEquals(2.0, thirdAttempt.context().get("previousAttempt"),
            "the third attempt's context must record that attempt 2 preceded it");
    }

    public void testRollbackRestoresAModifiedFileAssertedByReadingItBackNotTheAuditLog() throws IOException {
        Path targetServiceDir = Files.createTempDirectory("engine-target-service");
        Path runsDir = Files.createTempDirectory("engine-runs");
        Path mutableFile = targetServiceDir.resolve("Service.java");
        Files.writeString(mutableFile, "original content");

        WorkflowNode node = TestEngineFixtures.node("N1", Set.of(), 1);
        WorkflowGraph graph = WorkflowGraph.of(List.of(node));
        WorkflowState state = new WorkflowState("RUN-1", TestEngineFixtures.requirementSpec(), graph.getAllNodes());

        // A single-shot executor: on its one and only attempt it corrupts the mutable
        // file (as a real implementation writing bad code might) and then reports
        // failure, so the engine's checkpoint (taken before this attempt, over the
        // original content) is what rollback must restore from.
        NodeExecutor corruptingExecutor = (n, context) -> {
            try {
                Files.writeString(mutableFile, "corrupted by a failing node, should be rolled back");
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            return new NodeExecutor.ExecutionOutput(false, "wrote bad content then failed", Map.of("exitCode", 1.0));
        };

        WorkflowEngine engine = new WorkflowEngine(graph, state, registryWith(corruptingExecutor), new Gates(),
            new PolicyEngine.AllowAllPolicyEngine(), new Checkpoint(), targetServiceDir, runsDir,
            new CommandRunner(), null, null);

        WorkflowStatus outcome = engine.run();

        assertEquals(WorkflowStatus.SAFE_STOPPED, outcome, "maxAttempts=1, no fallback: exhausts immediately");
        assertEquals(NodeStatus.ROLLED_BACK, state.getStatus("N1"), "node must show ROLLED_BACK after restore");

        String contentAfterRollback = Files.readString(mutableFile);
        assertEquals("original content", contentAfterRollback,
            "reading the file back directly must show the pre-attempt content, not the corrupted content"
                + " the failing executor wrote; got: " + contentAfterRollback);
    }

    public void testRollbackOfACompletedNodeActuallyRestoresFiles() throws IOException {
        Path targetServiceDir = Files.createTempDirectory("engine-target-service");
        Path runsDir = Files.createTempDirectory("engine-runs");
        Path mutableFile = targetServiceDir.resolve("Service.java");
        Files.writeString(mutableFile, "original content");

        WorkflowNode completedNode = TestEngineFixtures.node("N1", Set.of(), 1);
        WorkflowNode failingNode = TestEngineFixtures.node("N2", Set.of("N1"), 1);
        WorkflowGraph graph = WorkflowGraph.of(List.of(completedNode, failingNode));
        WorkflowState state = new WorkflowState("RUN-1", TestEngineFixtures.requirementSpec(), graph.getAllNodes());

        ControllableExecutor executor = new ControllableExecutor();
        Path artifactForN1 = targetServiceDir.resolve("n1-artifact.txt");
        executor.alwaysReturn("N1", ControllableExecutor.Outcome.success("N1 completes", artifactForN1, "n1 output"));
        executor.alwaysReturn("N2", ControllableExecutor.Outcome.failure("N2 fails after N1 already completed"));

        Checkpoint checkpoint = new Checkpoint();
        WorkflowEngine engine = new WorkflowEngine(graph, state, registryWith(executor), new Gates(),
            new PolicyEngine.AllowAllPolicyEngine(), checkpoint, targetServiceDir, runsDir,
            new CommandRunner(), null, null);

        WorkflowStatus outcome = engine.run();

        assertEquals(WorkflowStatus.SAFE_STOPPED, outcome, "N2 exhausts its budget with no fallback");

        String restoredContent = Files.readString(mutableFile);
        assertEquals("original content", restoredContent,
            "rollback must restore the tree to its state when the checkpoint was taken, reading the file back directly");
        assertFalse(Files.exists(artifactForN1),
            "N1's artifact was created after the checkpoint (during N1's own COMPLETED run), so rollback"
                + " triggered by N2's failure must remove it too: rollback undoes a COMPLETED node's work,"
                + " not just a RUNNING one's");
    }

    public void testThreeParallelNodesAllReachCompletedBeforeTheJoinNodeStartsAndAuditLogProvesIt() throws IOException {
        Path tempDir = Files.createTempDirectory("engine-test");
        WorkflowNode root = TestEngineFixtures.node("ROOT", Set.of(), 1);
        WorkflowNode a = TestEngineFixtures.node("A", Set.of("ROOT"), 1);
        WorkflowNode b = TestEngineFixtures.node("B", Set.of("ROOT"), 1);
        WorkflowNode c = TestEngineFixtures.node("C", Set.of("ROOT"), 1);
        WorkflowNode join = TestEngineFixtures.node("JOIN", Set.of("A", "B", "C"), 1);
        WorkflowGraph graph = WorkflowGraph.of(List.of(root, a, b, c, join));
        WorkflowState state = new WorkflowState("RUN-1", TestEngineFixtures.requirementSpec(), graph.getAllNodes());

        ControllableExecutor executor = new ControllableExecutor();
        for (String id : List.of("ROOT", "A", "B", "C", "JOIN")) {
            executor.alwaysReturn(id, ControllableExecutor.Outcome.success(
                id + " completes", tempDir.resolve(id + ".txt"), id + " output"));
        }
        executor.withDelay("A", Duration.ofMillis(150));
        executor.withDelay("B", Duration.ofMillis(150));
        executor.withDelay("C", Duration.ofMillis(150));

        WorkflowEngine engine = buildEngine(graph, state, executor, tempDir, null);
        WorkflowStatus outcome = engine.run();

        assertEquals(WorkflowStatus.COMPLETED, outcome, "all five nodes complete");

        List<ControllableExecutor.Invocation> invocations = executor.invocations();
        ControllableExecutor.Invocation invA = findInvocation(invocations, "A");
        ControllableExecutor.Invocation invB = findInvocation(invocations, "B");
        ControllableExecutor.Invocation invC = findInvocation(invocations, "C");
        ControllableExecutor.Invocation invJoin = findInvocation(invocations, "JOIN");

        assertTrue(invA.start().isBefore(invB.end()) && invB.start().isBefore(invA.end()),
            "A and B must have overlapped in wall-clock time: A=[" + invA.start() + "," + invA.end()
                + "] B=[" + invB.start() + "," + invB.end() + "]");
        assertTrue(invA.start().isBefore(invC.end()) && invC.start().isBefore(invA.end()),
            "A and C must have overlapped in wall-clock time");

        assertTrue(invJoin.start().isAfter(invA.end()) || invJoin.start().equals(invA.end()),
            "JOIN must not start executing before A finished");
        assertTrue(invJoin.start().isAfter(invB.end()) || invJoin.start().equals(invB.end()),
            "JOIN must not start executing before B finished");
        assertTrue(invJoin.start().isAfter(invC.end()) || invJoin.start().equals(invC.end()),
            "JOIN must not start executing before C finished");

        List<AuditEvent> log = state.getAuditLog();
        long joinRunningIndex = indexOfTransitionTo(log, "JOIN", NodeStatus.RUNNING);
        long aCompletedIndex = indexOfTransitionTo(log, "A", NodeStatus.COMPLETED);
        long bCompletedIndex = indexOfTransitionTo(log, "B", NodeStatus.COMPLETED);
        long cCompletedIndex = indexOfTransitionTo(log, "C", NodeStatus.COMPLETED);
        assertTrue(joinRunningIndex > aCompletedIndex, "audit log must show A COMPLETED before JOIN started running");
        assertTrue(joinRunningIndex > bCompletedIndex, "audit log must show B COMPLETED before JOIN started running");
        assertTrue(joinRunningIndex > cCompletedIndex, "audit log must show C COMPLETED before JOIN started running");
    }

    public void testOneParallelNodeFailingDoesNotLeaveTheOthersHung() throws IOException {
        Path tempDir = Files.createTempDirectory("engine-test");
        WorkflowNode root = TestEngineFixtures.node("ROOT", Set.of(), 1);
        WorkflowNode ok1 = TestEngineFixtures.node("OK1", Set.of("ROOT"), 1);
        WorkflowNode failing = TestEngineFixtures.node("FAILING", Set.of("ROOT"), 1);
        WorkflowNode ok2 = TestEngineFixtures.node("OK2", Set.of("ROOT"), 1);
        WorkflowGraph graph = WorkflowGraph.of(List.of(root, ok1, failing, ok2,
            TestEngineFixtures.node("JOIN", Set.of("OK1", "FAILING", "OK2"), 1)));
        WorkflowState state = new WorkflowState("RUN-1", TestEngineFixtures.requirementSpec(), graph.getAllNodes());

        ControllableExecutor executor = new ControllableExecutor();
        executor.alwaysReturn("ROOT", ControllableExecutor.Outcome.success("root", tempDir.resolve("root.txt"), "x"));
        executor.alwaysReturn("OK1", ControllableExecutor.Outcome.success("ok1", tempDir.resolve("ok1.txt"), "x"));
        executor.alwaysReturn("OK2", ControllableExecutor.Outcome.success("ok2", tempDir.resolve("ok2.txt"), "x"));
        executor.alwaysReturn("FAILING", ControllableExecutor.Outcome.failure("deliberately fails"));

        WorkflowEngine engine = buildEngine(graph, state, executor, tempDir, null);

        long startedAt = System.nanoTime();
        WorkflowStatus outcome = engine.run();
        long elapsedMillis = (System.nanoTime() - startedAt) / 1_000_000;

        assertTrue(elapsedMillis < 10_000, "run() must return promptly, not hang; took " + elapsedMillis + "ms");
        assertEquals(NodeStatus.COMPLETED, state.getStatus("OK1"), "OK1 must still reach COMPLETED");
        assertEquals(NodeStatus.COMPLETED, state.getStatus("OK2"), "OK2 must still reach COMPLETED");
        assertEquals(NodeStatus.FAILED, state.getStatus("FAILING"), "FAILING must reach FAILED, not hang at RUNNING");
        assertEquals(WorkflowStatus.SAFE_STOPPED, outcome, "JOIN can never become ready since FAILING never completes");
    }

    public void testUnknownGateNameInWorkflowFailsAtLoad() throws IOException {
        Path tempDir = Files.createTempDirectory("engine-test");
        WorkflowNode node = new WorkflowNode(
            "N1", "N1", "controllable", Set.of(), "dependencies-complete", "this-gate-does-not-exist",
            com.schwab.agentic.model.RiskLevel.LOW, 1, Set.of());
        WorkflowGraph graph = WorkflowGraph.of(List.of(node));
        WorkflowState state = new WorkflowState("RUN-1", TestEngineFixtures.requirementSpec(), graph.getAllNodes());
        ControllableExecutor executor = new ControllableExecutor();

        assertThrows(IllegalArgumentException.class,
            () -> buildEngine(graph, state, executor, tempDir, null),
            "constructing the engine over a graph with an unknown exit gate name must throw immediately");
    }

    public void testFallbackPathExecutesAndProducesADifferentArtifactThanTheRetryWould() throws IOException {
        Path tempDir = Files.createTempDirectory("engine-test");
        Path primaryArtifact = tempDir.resolve("primary-output.txt");
        Path fallbackArtifact = tempDir.resolve("fallback-output.txt");

        WorkflowNode node = TestEngineFixtures.nodeWithFallback("N1", Set.of(), 1, "controllable-fallback");
        WorkflowGraph graph = WorkflowGraph.of(List.of(node));
        WorkflowState state = new WorkflowState("RUN-1", TestEngineFixtures.requirementSpec(), graph.getAllNodes());

        ControllableExecutor primaryExecutor = new ControllableExecutor();
        primaryExecutor.alwaysReturn("N1", ControllableExecutor.Outcome.failure(
            "primary always fails; if this ran again as a retry it would write " + primaryArtifact));

        ControllableExecutor fallbackExecutor = new ControllableExecutor();
        fallbackExecutor.alwaysReturn("N1", ControllableExecutor.Outcome.success(
            "fallback succeeds", fallbackArtifact, "produced by the fallback strategy, not a retry"));

        NodeExecutorRegistry registry = new NodeExecutorRegistry()
            .register("controllable", primaryExecutor)
            .register("controllable-fallback", fallbackExecutor);

        WorkflowEngine engine = new WorkflowEngine(graph, state, registry, new Gates(),
            new PolicyEngine.AllowAllPolicyEngine(), new Checkpoint(), tempDir, null,
            new CommandRunner(), null, null);

        WorkflowStatus outcome = engine.run();

        assertEquals(WorkflowStatus.COMPLETED, outcome, "the fallback's success must complete the run");
        assertEquals(NodeStatus.COMPLETED, state.getStatus("N1"), "node completes via fallback");
        assertFalse(Files.exists(primaryArtifact), "the primary executor's artifact must not exist: it never succeeded");
        assertTrue(Files.exists(fallbackArtifact), "the fallback executor's distinct artifact must exist on disk");
        assertEquals("produced by the fallback strategy, not a retry", Files.readString(fallbackArtifact),
            "the fallback artifact's content proves a different code path ran, not attempt N+1 of the primary");

        boolean fallbackUsedRecorded = state.getAuditLog().stream()
            .anyMatch(event -> Boolean.TRUE.equals(event.details().get("fallbackUsed")));
        assertTrue(fallbackUsedRecorded, "an audit event must record that the fallback was used");
    }

    public void testAWaitingApprovalNodeEndsTheRunAwaitingApprovalNotSafeStopped() throws IOException {
        Path tempDir = Files.createTempDirectory("engine-test");
        WorkflowNode node = TestEngineFixtures.node("N1", Set.of(), 1);
        WorkflowGraph graph = WorkflowGraph.of(List.of(node));
        WorkflowState state = new WorkflowState("RUN-1", TestEngineFixtures.requirementSpec(), graph.getAllNodes());
        ControllableExecutor executor = new ControllableExecutor();

        WorkflowEngine engine = new WorkflowEngine(graph, state, registryWith(executor), new Gates(),
            (n, s) -> PolicyEngine.Decision.REQUIRE_APPROVAL, new Checkpoint(), tempDir, null,
            new CommandRunner(), null, null);

        WorkflowStatus outcome = engine.run();

        assertEquals(WorkflowStatus.AWAITING_APPROVAL, outcome, "a run parked on approval must not look like a failure");
        assertEquals(NodeStatus.WAITING_APPROVAL, state.getStatus("N1"), "node must be WAITING_APPROVAL, never RUNNING first");
        assertEquals(0, executor.callCount("N1"), "the executor must never run before approval is granted");
    }

    /**
     * A node that clears the approval requirement (policy allows on this run's second
     * evaluation) must reach RUNNING only after passing back through PENDING, per the
     * corrected transition table: WAITING_APPROVAL -> PENDING is the approved edge, and
     * PENDING -> RUNNING is the only path into RUNNING. This proves the engine actually
     * routes an approved node back through PENDING rather than jumping it straight to
     * RUNNING from WAITING_APPROVAL, which the model layer would reject outright since no
     * such edge exists.
     */
    public void testApprovedNodeReturnsToPendingBeforeRunningNeverDirectlyFromWaitingApproval() throws IOException {
        Path tempDir = Files.createTempDirectory("engine-test");
        WorkflowNode node = TestEngineFixtures.node("N1", Set.of(), 1);
        WorkflowGraph graph = WorkflowGraph.of(List.of(node));
        WorkflowState state = new WorkflowState("RUN-1", TestEngineFixtures.requirementSpec(), graph.getAllNodes());
        ControllableExecutor executor = new ControllableExecutor();
        executor.alwaysReturn("N1", ControllableExecutor.Outcome.success("ok", tempDir.resolve("a.txt"), "x"));

        WorkflowEngine parkingEngine = new WorkflowEngine(graph, state, registryWith(executor), new Gates(),
            (n, s) -> PolicyEngine.Decision.REQUIRE_APPROVAL, new Checkpoint(), tempDir, null,
            new CommandRunner(), null, null);
        parkingEngine.run();
        assertEquals(NodeStatus.WAITING_APPROVAL, state.getStatus("N1"), "node must be parked awaiting approval");

        // Simulate approval exactly as spec 05's approve.sh will: WAITING_APPROVAL -> PENDING.
        state.transition("N1", NodeStatus.PENDING, "human:reviewer", "approved");
        assertEquals(NodeStatus.PENDING, state.getStatus("N1"), "approval returns the node to PENDING, not RUNNING");

        WorkflowEngine resumedEngine = buildEngine(graph, state, executor, tempDir, null);
        WorkflowStatus outcome = resumedEngine.run();

        assertEquals(WorkflowStatus.COMPLETED, outcome, "the now-approved node runs and completes on resume");
        assertEquals(1, executor.callCount("N1"), "the executor runs exactly once, after approval, never before");
    }

    public void testExecutorSucceedingButExitGateFailingEndsAsFailedNotCompleted() throws IOException {
        Path tempDir = Files.createTempDirectory("engine-test");
        WorkflowNode node = TestEngineFixtures.node("N1", Set.of(), 1);
        WorkflowGraph graph = WorkflowGraph.of(List.of(node));
        WorkflowState state = new WorkflowState("RUN-1", TestEngineFixtures.requirementSpec(), graph.getAllNodes());

        ControllableExecutor executor = new ControllableExecutor();
        // The executor reports success but never actually writes the artifact file, so
        // the real artifact-written exit gate fails despite executorReportedSuccess=true.
        executor.alwaysReturn("N1", new ControllableExecutor.Outcome(true, "executor claims success", null, null));

        WorkflowEngine engine = buildEngine(graph, state, executor, tempDir, null);
        engine.run();

        assertEquals(NodeStatus.FAILED, state.getStatus("N1"),
            "an executor that reports success but produces no artifact must still fail the exit gate");
    }

    private static ControllableExecutor.Invocation findInvocation(
            List<ControllableExecutor.Invocation> invocations, String nodeId) {
        return invocations.stream().filter(inv -> inv.nodeId().equals(nodeId)).findFirst()
            .orElseThrow(() -> new AssertionError("no invocation recorded for " + nodeId));
    }

    private static long indexOfTransitionTo(List<AuditEvent> log, String nodeId, NodeStatus to) {
        for (int i = 0; i < log.size(); i++) {
            AuditEvent event = log.get(i);
            if (nodeId.equals(event.nodeId()) && event.type() == AuditEvent.EventType.STATUS_CHANGE && event.to() == to) {
                return i;
            }
        }
        throw new AssertionError("no STATUS_CHANGE to " + to + " found for node " + nodeId);
    }

    private static NodeExecutorRegistry registryWith(NodeExecutor executor) {
        return new NodeExecutorRegistry().register("controllable", executor);
    }

    /**
     * Builds an engine with no checkpoint configured at all: {@code targetServiceDirectory}
     * and {@code runsDirectory} are both null, so tests focused purely on retry, fallback,
     * approval and parallelism semantics never accidentally trigger a real checkpoint or
     * rollback as a side effect of reusing a scratch directory for two purposes.
     * {@code scratchDirectory} is where the executor may write artifact files it declares
     * for the artifact-written exit gate; it has nothing to do with checkpointing.
     */
    private static WorkflowEngine buildEngine(WorkflowGraph graph, WorkflowState state, NodeExecutor executor,
                                               Path scratchDirectory, Path unusedRunsDirectory) {
        return new WorkflowEngine(graph, state, registryWith(executor), new Gates(),
            new PolicyEngine.AllowAllPolicyEngine(), new Checkpoint(), null, null,
            new CommandRunner(), null, null);
    }
}
