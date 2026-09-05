package com.schwab.agentic.engine;

import com.schwab.agentic.graph.WorkflowGraph;
import com.schwab.agentic.model.AuditEvent;
import com.schwab.agentic.model.NodeStatus;
import com.schwab.agentic.model.WorkflowNode;
import com.schwab.agentic.model.WorkflowState;
import com.schwab.agentic.model.WorkflowStatus;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Drives a run to completion, a pause, or a safe stop. This is the class section 4.4 of
 * the assignment calls the critical differentiator: non-linear, stateful execution with
 * governance, not a fixed linear chain.
 *
 * The scheduling loop: ask the graph for ready nodes; if none remain and every node is
 * COMPLETED, the run is COMPLETED; if none are ready but some node is WAITING_APPROVAL,
 * the run pauses at AWAITING_APPROVAL, which is a pause, not a failure; if none are
 * ready and work remains with nothing waiting on approval, the run SAFE_STOPS naming the
 * blocked nodes; otherwise every ready node is submitted to a virtual thread executor,
 * and the loop waits for the entire wave to finish before asking for the next one. That
 * wait-for-all is the synchronization barrier: a join node must never see a partial wave.
 *
 * Approval is checked before execution, never after. A node's entry gate (and the policy
 * check behind it) runs while the node is still PENDING; if it requires approval, the
 * node moves PENDING to WAITING_APPROVAL and is not submitted for execution at all in
 * this wave. There is no path from RUNNING to WAITING_APPROVAL: once a node is RUNNING,
 * an executor has already done the work, so gating approval at that point would make the
 * checkpoint theatre and would defeat a policy rule that must deny before anything is
 * written, not after.
 *
 * A checkpoint is taken per node, not per run: immediately before a node's first attempt
 * (never before a retry of the same node, which would only capture that node's own
 * partial damage rather than the state before it touched anything), the engine snapshots
 * only that node's declared {@link WorkflowNode#writePaths}, not the whole target service
 * working tree, under {@code runs/<runId>/checkpoints/<nodeId>/}, and keeps that node's
 * handle for the life of this engine instance. Rolling back a node restores only those
 * same write paths from that node's own checkpoint, never another node's.
 *
 * Two designs were tried and found wrong before this one. A single run-level checkpoint
 * contradicts spec 06: re-planning must preserve a completed node's output untouched
 * while invalidating and re-running only its downstream nodes, which is impossible if
 * every rollback in the run shares one snapshot. A per-node checkpoint of the *whole*
 * working tree is unsafe the moment nodes execute concurrently, which they do here
 * (IMPLEMENT, TEST and DOCUMENT run in parallel): each node's checkpoint would capture
 * whatever a sibling had already written by that moment, and each node's restore would
 * delete whatever a sibling had written since, including work that node had already
 * completed. Scoping every checkpoint and restore to the node's own declared
 * {@code writePaths} removes the possibility of that interference rather than working
 * around it: two nodes with disjoint write paths can checkpoint, mutate and roll back
 * completely independently, because neither one's {@link Checkpoint} operations ever
 * look at, or touch, a path outside its own declared set. A workflow that gives two
 * concurrently-runnable nodes overlapping write paths has an authoring error this class
 * has no way to detect; it is a workflow-design invariant, not a runtime check.
 *
 * Rolling back a node whose status is INVALIDATED (spec 06's re-planning, not this spec)
 * has no caller anywhere in this class. Re-planning needs to revert a previously-COMPLETED
 * node's output when an upstream change invalidates it, which is a materially different
 * scenario from this spec's rollback (a node failing its own exit gate with no retry
 * budget or fallback left): the checkpoint to restore from is not "immediately before
 * this node's own last attempt," since the node already completed successfully once, and
 * deciding which checkpoint (or whether a fresh one) applies to an invalidated node is
 * spec 06's decision to make, not something this class should guess at now.
 */
public final class WorkflowEngine {

    private static final int MAX_ITERATIONS = 500;

    private final WorkflowGraph graph;
    private final WorkflowState state;
    private final NodeExecutorRegistry executors;
    private final Gates gates;
    private final PolicyEngine policyEngine;
    private final Checkpoint checkpoint;
    private final Path targetServiceDirectory;
    private final Path runsDirectory;
    private final CommandRunner commandRunner;
    private final String buildCommand;
    private final String testCommand;
    private final boolean autoApprove;

    // ConcurrentHashMap, not HashMap: nodes in the same wave execute on separate virtual
    // threads (see executeWaveAndWaitForAll), and takeCheckpointForNodeIfConfigured's
    // computeIfAbsent call is reached concurrently from each of them. A plain HashMap
    // under concurrent computeIfAbsent calls throws ConcurrentModificationException at
    // best and silently corrupts its internal structure at worst; this was caught by
    // testTwoParallelNodesWithDisjointWritePathsRollBackIndependently, which is the
    // first test in this file to actually have two nodes take a checkpoint at the same
    // moment rather than one after another.
    private final Map<String, Checkpoint.Handle> checkpointHandlesByNodeId = new ConcurrentHashMap<>();
    // volatile: written from the virtual threads executing individual nodes
    // (executeOneNode, rollBackAndFail), read from the main scheduling thread in run().
    // The wait-for-all barrier (future.get() before the next read) already provides a
    // happens-before edge here, so this is defense in depth rather than a fix for an
    // observed failure, but relying on that barrier implicitly for visibility, with no
    // marker on the field itself, is exactly the kind of thing worth making explicit
    // after finding the checkpointHandlesByNodeId concurrency bug in this same class.
    private volatile boolean safeStopRequested;

    public WorkflowEngine(WorkflowGraph graph, WorkflowState state, NodeExecutorRegistry executors,
                           Gates gates, PolicyEngine policyEngine, Checkpoint checkpoint,
                           Path targetServiceDirectory, Path runsDirectory,
                           CommandRunner commandRunner, String buildCommand, String testCommand) {
        this(graph, state, executors, gates, policyEngine, checkpoint, targetServiceDirectory, runsDirectory,
            commandRunner, buildCommand, testCommand, false);
    }

    /**
     * {@code autoApprove}, when true, tells the HIGH-risk-requires-approval policy rule
     * to skip its approval requirement (CRITICAL risk still always requires approval
     * regardless). Spec 05 restricts {@code --auto-approve} to {@code --replay} runs and
     * always stamps it into the run report (AC-05-8), so a reviewer can always tell a
     * demo run from a governed one; this constructor does not itself enforce the
     * replay-only restriction, since that is a property of how the CLI assembles a run,
     * not of the engine's own scheduling logic.
     */
    public WorkflowEngine(WorkflowGraph graph, WorkflowState state, NodeExecutorRegistry executors,
                           Gates gates, PolicyEngine policyEngine, Checkpoint checkpoint,
                           Path targetServiceDirectory, Path runsDirectory,
                           CommandRunner commandRunner, String buildCommand, String testCommand,
                           boolean autoApprove) {
        this.graph = graph;
        this.state = state;
        this.executors = executors;
        this.gates = gates;
        this.policyEngine = policyEngine;
        this.checkpoint = checkpoint;
        this.targetServiceDirectory = targetServiceDirectory;
        this.runsDirectory = runsDirectory;
        this.commandRunner = commandRunner;
        this.buildCommand = buildCommand;
        this.testCommand = testCommand;
        this.autoApprove = autoApprove;
        validateEveryGateAndExecutorIsResolvable();
    }

    /**
     * Every entry gate, exit gate and executor name declared anywhere in the graph must
     * resolve before scheduling starts. An unknown gate or executor name is a load-time
     * configuration error naming the offending node and name, never a runtime surprise
     * on whichever node happens to reach it first.
     */
    private void validateEveryGateAndExecutorIsResolvable() {
        for (WorkflowNode node : graph.getAllNodes()) {
            if (node.entryGate() != null && !gates.isRegistered(node.entryGate())) {
                throw new IllegalArgumentException(
                    "Node " + node.id() + " declares unknown entry gate: " + node.entryGate());
            }
            if (node.exitGate() != null && !gates.isRegistered(node.exitGate())) {
                throw new IllegalArgumentException(
                    "Node " + node.id() + " declares unknown exit gate: " + node.exitGate());
            }
            if (!executors.isRegistered(node.executor())) {
                throw new IllegalArgumentException(
                    "Node " + node.id() + " declares unknown executor: " + node.executor());
            }
            if (node.hasFallback() && !executors.isRegistered(node.fallbackExecutor())) {
                throw new IllegalArgumentException(
                    "Node " + node.id() + " declares unknown fallback executor: " + node.fallbackExecutor());
            }
        }
    }

    /**
     * Runs the scheduling loop until the workflow reaches a terminal outcome or pauses.
     * Returns the final {@link WorkflowStatus}, which is also recorded on {@code state}.
     */
    public WorkflowStatus run() {
        int iteration = 0;
        while (true) {
            iteration++;
            if (iteration > MAX_ITERATIONS) {
                List<String> stillRunning = state.getNodes().keySet().stream()
                    .filter(nodeId -> state.getStatus(nodeId) != NodeStatus.COMPLETED
                        && state.getStatus(nodeId) != NodeStatus.DENIED
                        && state.getStatus(nodeId) != NodeStatus.SKIPPED)
                    .sorted()
                    .toList();
                state.record(AuditEvent.EventType.COMMAND_EXECUTED, "system",
                    "safe stop: exceeded " + MAX_ITERATIONS + " scheduling iterations",
                    Map.of("iterations", (double) iteration, "unfinishedNodes", new ArrayList<Object>(stillRunning)));
                state.setWorkflowStatus(WorkflowStatus.SAFE_STOPPED);
                return WorkflowStatus.SAFE_STOPPED;
            }

            if (safeStopRequested) {
                state.setWorkflowStatus(WorkflowStatus.SAFE_STOPPED);
                return WorkflowStatus.SAFE_STOPPED;
            }

            List<WorkflowNode> readyNodes = graph.readyNodes(state.getStatuses());
            List<WorkflowNode> runnableThisWave = admitReadyNodes(readyNodes);

            if (runnableThisWave.isEmpty()) {
                WorkflowStatus outcome = decideOutcomeWithNothingToRun();
                if (outcome != WorkflowStatus.RUNNING) {
                    state.setWorkflowStatus(outcome);
                    return outcome;
                }
                continue;
            }

            executeWaveAndWaitForAll(runnableThisWave);
        }
    }

    /**
     * Takes a checkpoint for this node under {@code runs/<runId>/checkpoints/<nodeId>/}
     * if one has not already been taken for it, capturing only {@code node.writePaths()}
     * as they stand immediately before this node's first attempt. Called once per node,
     * not once per retry: a retry re-executes the same node, so re-checkpointing before a
     * retry would capture that node's own prior (failed) attempt's damage rather than the
     * clean state from before the node ever ran, which is what rollback needs to restore
     * to.
     *
     * A node with no declared write paths is never checkpointed at all: it declares it
     * writes nothing, so there is nothing to protect, and taking a checkpoint scoped to
     * an empty path set would be meaningless. This is also what makes concurrent nodes
     * safe: two nodes whose {@code writePaths} do not overlap checkpoint, and later
     * restore, entirely disjoint slices of the tree, so one node's snapshot can never
     * capture a sibling's in-flight writes and one node's rollback can never touch a
     * sibling's files.
     */
    private void takeCheckpointForNodeIfConfigured(WorkflowNode node) {
        if (targetServiceDirectory == null || runsDirectory == null || !node.isCheckpointed()) {
            return;
        }
        checkpointHandlesByNodeId.computeIfAbsent(node.id(),
            nodeId -> checkpoint.take(targetServiceDirectory, runsDirectory, state.getRunId(), nodeId, node.writePaths()));
    }

    /**
     * For every node the graph reports ready, evaluates its entry gate and, if that
     * passes, its policy decision. A node whose entry gate fails is left PENDING (spec
     * 02: a failed entry gate is a scheduling problem, not an execution failure, so it
     * does not consume a retry attempt and does not change status at all). A node the
     * policy denies is denied outright; a node the policy says needs approval moves to
     * WAITING_APPROVAL and is excluded from this wave. Only nodes that clear both checks
     * are returned for execution.
     */
    private List<WorkflowNode> admitReadyNodes(List<WorkflowNode> readyNodes) {
        List<WorkflowNode> admitted = new ArrayList<>();
        for (WorkflowNode node : readyNodes) {
            if (node.entryGate() != null) {
                Gate.Result entryResult = gates.resolve(node.entryGate()).evaluate(node, state, gateContext(Map.of()));
                if (!entryResult.passed()) {
                    continue;
                }
            }

            PolicyContext policyContext = new PolicyContext(targetServiceDirectory, runsDirectory,
                state.getRunId(), autoApprove);
            PolicyRule.Result result = policyEngine.evaluatePreExecutionWithReason(node, state, policyContext);
            switch (result.decision()) {
                case DENY -> {
                    state.record(AuditEvent.EventType.POLICY_DENIED, node.id(), "policy", result.reason(),
                        Map.of("rule", result.ruleName()));
                    state.transition(node.id(), NodeStatus.DENIED, "policy",
                        "policy denied node " + node.id() + " before execution: " + result.reason());
                }
                case REQUIRE_APPROVAL -> state.transition(node.id(), NodeStatus.WAITING_APPROVAL, "policy",
                    result.reason());
                case ALLOW -> admitted.add(node);
            }
        }
        return admitted;
    }

    private WorkflowStatus decideOutcomeWithNothingToRun() {
        boolean allComplete = state.getNodes().keySet().stream()
            .allMatch(nodeId -> state.getStatus(nodeId) == NodeStatus.COMPLETED);
        if (allComplete) {
            return WorkflowStatus.COMPLETED;
        }

        boolean anyAwaitingApproval = state.getNodes().keySet().stream()
            .anyMatch(nodeId -> state.getStatus(nodeId) == NodeStatus.WAITING_APPROVAL);
        if (anyAwaitingApproval) {
            return WorkflowStatus.AWAITING_APPROVAL;
        }

        List<String> blocked = state.getNodes().keySet().stream()
            .filter(nodeId -> state.getStatus(nodeId) != NodeStatus.COMPLETED
                && state.getStatus(nodeId) != NodeStatus.DENIED
                && state.getStatus(nodeId) != NodeStatus.SKIPPED)
            .sorted()
            .toList();
        state.record(AuditEvent.EventType.COMMAND_EXECUTED, "system",
            "safe stop: no node ready to run and nothing awaiting approval, blocked nodes: " + blocked,
            Map.of("blockedNodes", new ArrayList<Object>(blocked)));
        return WorkflowStatus.SAFE_STOPPED;
    }

    /**
     * Submits every node in this wave to a virtual thread executor and waits for all of
     * them to finish before returning. This wait-for-all is the synchronization barrier
     * a join node depends on: {@link #run} never asks for the next wave's ready nodes
     * until every node in this one has reached a terminal-for-this-attempt outcome. Each
     * node's execution is fully isolated in its own try/catch inside {@link #executeOneNode},
     * so one node throwing never prevents {@code future.get()} from returning for the
     * others; this loop only re-throws if waiting itself was interrupted.
     */
    private void executeWaveAndWaitForAll(List<WorkflowNode> wave) {
        try (ExecutorService virtualThreadExecutor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<?>> futures = new ArrayList<>();
            for (WorkflowNode node : wave) {
                futures.add(virtualThreadExecutor.submit(() -> executeOneNode(node)));
            }
            for (Future<?> future : futures) {
                try {
                    future.get();
                } catch (ExecutionException e) {
                    // executeOneNode already catches everything it can and turns it into a
                    // FAILED transition; reaching here means something escaped that catch,
                    // which is itself a defect worth surfacing rather than silently ignoring.
                    throw new IllegalStateException("Node execution failed unexpectedly", e.getCause());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("Interrupted while waiting for node execution", e);
                }
            }
        }
    }

    /**
     * Runs one node through the full sequence spec 02 defines: transition to RUNNING,
     * call the executor, evaluate the exit gate against its output, then apply the
     * resulting outcome (complete, retry, fallback, or roll back and fail). Every
     * exception this method's body could throw is caught here and turned into a FAILED
     * node plus a run-level safe-stop request, so a defect in one node's execution can
     * never hang or crash the wave for its siblings.
     */
    private void executeOneNode(WorkflowNode node) {
        takeCheckpointForNodeIfConfigured(node);
        Map<String, Object> context = new HashMap<>();
        try {
            runAttemptsUntilOutcome(node, context);
        } catch (RuntimeException e) {
            state.record(AuditEvent.EventType.COMMAND_EXECUTED, "system",
                "node " + node.id() + " execution threw an unexpected exception: " + e,
                Map.of("nodeId", node.id()));
            if (state.getStatus(node.id()).canTransitionTo(NodeStatus.FAILED)) {
                state.transition(node.id(), NodeStatus.FAILED, "engine", "unexpected exception: " + e);
            }
            safeStopRequested = true;
        }
    }

    private void runAttemptsUntilOutcome(WorkflowNode node, Map<String, Object> context) {
        state.transition(node.id(), NodeStatus.RUNNING, "engine", "attempt starting for " + node.id());

        NodeExecutor.ExecutionOutput output = executors.get(node.executor()).execute(node, context);

        if (applyPostExecutionPolicyIfViolated(node, output.outputs())) {
            return;
        }

        Gate.Result exitResult = evaluateExitGate(node, output.outputs());

        if (exitResult.passed()) {
            state.transition(node.id(), NodeStatus.COMPLETED, "engine",
                "exit gate passed: " + exitResult.reason());
            return;
        }

        int attemptsSoFar = state.getRetryCount(node.id()) + 1;
        boolean budgetRemains = attemptsSoFar < node.maxAttempts();

        if (budgetRemains) {
            state.transition(node.id(), NodeStatus.FAILED, "engine",
                "exit gate failed on attempt " + attemptsSoFar + ": " + exitResult.reason());
            context.put("previousFailureReason", exitResult.reason());
            context.put("previousAttempt", (double) attemptsSoFar);
            state.transition(node.id(), NodeStatus.PENDING, "engine",
                "retrying node " + node.id() + " after attempt " + attemptsSoFar + " failed");
            runAttemptsUntilOutcome(node, context);
            return;
        }

        state.transition(node.id(), NodeStatus.FAILED, "engine",
            "exit gate failed on final attempt " + attemptsSoFar + ": " + exitResult.reason()
                + (node.hasFallback() ? "; retry budget exhausted, trying fallback " + node.fallbackExecutor()
                                       : "; retry budget exhausted, no fallback declared"));

        if (node.hasFallback()) {
            state.transition(node.id(), NodeStatus.PENDING, "engine",
                "moving to fallback executor " + node.fallbackExecutor() + " for node " + node.id());
            runFallback(node, context, exitResult.reason());
        } else {
            rollBackAndFail(node, exitResult.reason());
        }
    }

    /**
     * Checks the node's real, reported output against every post-execution policy rule,
     * since several real rules (protected paths, secrets in a diff, dependency
     * additions, a change budget) cannot be evaluated until the executor has actually
     * written something. Returns true if this node's outcome was fully decided here (the
     * caller must not go on to evaluate the exit gate), false if the node cleared policy
     * and the caller should proceed normally.
     *
     * A DENY is treated exactly like an exit gate failing with no retry budget or
     * fallback left: real rollback via this node's own checkpoint, then a safe stop,
     * never a silent pass and never a retry (retrying would just let the same violation
     * happen again). A REQUIRE_APPROVAL routes the node RUNNING to FAILED to PENDING to
     * WAITING_APPROVAL in this one tick, using only legal transitions the table already
     * allows, so the node is held before the scheduler's next pass could otherwise pick
     * it back up and re-run it. The real diff that triggered the approval requirement is
     * left on disk exactly as the executor wrote it (nothing here rolls it back); on
     * approval, the node returns to PENDING and the engine re-runs its executor from
     * scratch, which means an approved run is not guaranteed to reproduce byte-for-byte
     * the same diff a human reviewed, since this project has no artifact-freezing
     * mechanism. That is a real, acknowledged limitation, not something hidden by this
     * comment.
     */
    private boolean applyPostExecutionPolicyIfViolated(WorkflowNode node, Map<String, Object> executorOutputs) {
        PolicyContext policyContext = new PolicyContext(targetServiceDirectory, runsDirectory, state.getRunId(), autoApprove);
        PolicyRule.Result result = policyEngine.evaluatePostExecution(node, state, executorOutputs, policyContext);

        if (result.decision() == PolicyEngine.Decision.DENY) {
            state.record(AuditEvent.EventType.POLICY_DENIED, node.id(), "policy", result.reason(),
                Map.of("rule", result.ruleName()));
            state.transition(node.id(), NodeStatus.FAILED, "policy",
                "post-execution policy denied node " + node.id() + ": " + result.reason());
            rollBackAndFail(node, result.reason());
            return true;
        }

        if (result.decision() == PolicyEngine.Decision.REQUIRE_APPROVAL) {
            state.transition(node.id(), NodeStatus.FAILED, "policy",
                "post-execution policy requires approval for node " + node.id()
                    + " (rule " + result.ruleName() + "): " + result.reason());
            state.transition(node.id(), NodeStatus.PENDING, "policy",
                "parking node " + node.id() + " for approval after post-execution policy check");
            state.transition(node.id(), NodeStatus.WAITING_APPROVAL, "policy", result.reason());
            return true;
        }

        return false;
    }

    /**
     * Runs the node's declared fallback executor: a materially different strategy from
     * a retry, invoked once, using its own executor rather than the primary one, with a
     * context flag ({@code isFallback}) and the original failure reason
     * ({@code primaryFailureReason}) so an executor that behaves identically for a retry
     * and a fallback is observably distinguishable in what it was told, not just in name.
     */
    private void runFallback(WorkflowNode node, Map<String, Object> context, String primaryFailureReason) {
        state.transition(node.id(), NodeStatus.RUNNING, "engine",
            "running fallback executor " + node.fallbackExecutor() + " for node " + node.id());

        Map<String, Object> fallbackContext = new HashMap<>(context);
        fallbackContext.put("isFallback", true);
        fallbackContext.put("primaryFailureReason", primaryFailureReason);

        NodeExecutor.ExecutionOutput fallbackOutput =
            executors.get(node.fallbackExecutor()).execute(node, fallbackContext);
        Gate.Result exitResult = evaluateExitGate(node, fallbackOutput.outputs());

        if (exitResult.passed()) {
            state.transition(node.id(), NodeStatus.COMPLETED, "engine",
                "fallback executor's output passed the exit gate: " + exitResult.reason());
            state.record(AuditEvent.EventType.ARTIFACT_WRITTEN, "engine",
                "fallbackUsed for node " + node.id(),
                Map.of("nodeId", node.id(), "fallbackExecutor", node.fallbackExecutor(), "fallbackUsed", true));
            return;
        }

        state.transition(node.id(), NodeStatus.FAILED, "engine",
            "fallback executor's output also failed the exit gate: " + exitResult.reason());
        rollBackAndFail(node, "fallback also failed: " + exitResult.reason());
    }

    private Gate.Result evaluateExitGate(WorkflowNode node, Map<String, Object> executorOutputs) {
        if (node.exitGate() == null) {
            return Gate.Result.pass("node declares no exit gate");
        }
        return gates.resolve(node.exitGate()).evaluate(node, state, gateContext(executorOutputs));
    }

    private GateContext gateContext(Map<String, Object> executorOutputs) {
        return new GateContext(executorOutputs, graph, targetServiceDirectory, runsDirectory,
            commandRunner, buildCommand, testCommand);
    }

    /**
     * Rolls back this node's own checkpoint, then marks the node FAILED and requests a
     * safe stop. Restoring only this node's checkpoint, never another node's, is what
     * lets an unaffected completed node's output survive a sibling's rollback: A's
     * checkpoint is never touched by B's failure, because B's rollback only knows about
     * B's own handle. The rollback is a real file restore verified by content hash,
     * performed by {@link Checkpoint#restore}, which is the only thing allowed to emit a
     * ROLLBACK-style audit event, and only after the files are actually back; this
     * method only decides that a rollback should happen and reacts to its result. If no
     * checkpoint was ever taken for this node (no target service directory configured
     * for this run), rollback is skipped and the node simply fails, since there is
     * nothing to restore.
     */
    private void rollBackAndFail(WorkflowNode node, String reason) {
        Checkpoint.Handle handle = checkpointHandlesByNodeId.get(node.id());
        if (handle != null) {
            int restoredCount = checkpoint.restore(handle);
            state.record(AuditEvent.EventType.ARTIFACT_WRITTEN, "engine",
                "rollback restored " + restoredCount + " file(s) for node " + node.id() + " after: " + reason,
                Map.of("nodeId", node.id(), "restoredFileCount", (double) restoredCount,
                    "checkpoint", handle.label()));
            if (state.getStatus(node.id()).canTransitionTo(NodeStatus.ROLLED_BACK)) {
                state.transition(node.id(), NodeStatus.ROLLED_BACK, "engine", "rolled back after: " + reason);
            }
        }
        safeStopRequested = true;
    }
}
