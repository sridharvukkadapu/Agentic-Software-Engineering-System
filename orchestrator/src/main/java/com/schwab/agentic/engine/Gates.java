package com.schwab.agentic.engine;

import com.schwab.agentic.model.AcceptanceCriterion;
import com.schwab.agentic.model.Evidence;
import com.schwab.agentic.model.NodeStatus;
import com.schwab.agentic.model.RiskLevel;
import com.schwab.agentic.model.WorkflowNode;
import com.schwab.agentic.model.WorkflowState;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Registers every named gate spec 02 requires and resolves a workflow-JSON gate name to
 * an implementation. A name with no registered implementation throws at
 * {@link #resolve}, called while a {@link com.schwab.agentic.graph.WorkflowGraph} is
 * being prepared for execution: a workflow referencing a gate that does not exist is a
 * load-time failure naming the unknown gate, never a runtime default to pass. Defaulting
 * an unrecognized gate to pass would mean a typo in a workflow file silently disables the
 * exact control the gate was supposed to provide.
 */
public final class Gates {

    private final Map<String, Gate> gatesByName = new HashMap<>();

    public Gates() {
        register("dependencies-complete", new DependenciesCompleteGate());
        register("requirement-unambiguous-or-approved", new RequirementUnambiguousOrApprovedGate());
        register("checkpointing-configured", new CheckpointingConfiguredGate());
        register("artifact-written", new ArtifactWrittenGate());
        register("compiles", new CompilesGate());
        register("tests-pass", new TestsPassGate());
        register("evidence-complete", new EvidenceCompleteGate());
        register("executed-evidence-for-high-risk", new ExecutedEvidenceForHighRiskGate());
    }

    private void register(String name, Gate gate) {
        gatesByName.put(name, gate);
    }

    /**
     * Resolves a gate name to its implementation, or throws naming the unknown gate.
     * Called at graph-preparation time (before any node runs) for every entry and exit
     * gate a workflow declares, so an unknown name is caught before scheduling starts,
     * per spec 02's requirement that this never be a silent runtime pass.
     */
    public Gate resolve(String name) {
        Gate gate = gatesByName.get(name);
        if (gate == null) {
            throw new IllegalArgumentException(
                "Unknown gate: " + name + ". Registered gates: " + gatesByName.keySet());
        }
        return gate;
    }

    public boolean isRegistered(String name) {
        return gatesByName.containsKey(name);
    }

    /** The default entry gate: every declared dependency must be COMPLETED. */
    private static final class DependenciesCompleteGate implements Gate {
        @Override
        public Result evaluate(WorkflowNode node, WorkflowState state, GateContext context) {
            for (String dependencyId : node.dependsOn()) {
                if (state.getStatus(dependencyId) != NodeStatus.COMPLETED) {
                    return Result.fail("dependency " + dependencyId + " is "
                        + state.getStatus(dependencyId) + ", not COMPLETED");
                }
            }
            return Result.pass("all " + node.dependsOn().size() + " declared dependencies are COMPLETED");
        }
    }

    /**
     * Blocks a node (IMPLEMENT in the default workflow) when the requirement still has
     * unresolved ambiguity and no human decision was recorded. "Unresolved ambiguity" is
     * read from the requirement spec's normalizedProblem and the run's decision records:
     * an ambiguity is considered resolved once a DecisionRecord exists whose context
     * marks it as addressing that requirement's revision.
     */
    private static final class RequirementUnambiguousOrApprovedGate implements Gate {
        @Override
        public Result evaluate(WorkflowNode node, WorkflowState state, GateContext context) {
            List<AcceptanceCriterion> criteria = state.getRequirementSpec().acceptanceCriteria();
            if (!criteria.isEmpty()) {
                return Result.pass("requirement has " + criteria.size() + " acceptance criteria, treated as unambiguous");
            }
            boolean hasAmbiguityDecision = state.getDecisions().stream()
                .anyMatch(decision -> "ambiguity-resolution".equals(decision.context().get("kind")));
            if (hasAmbiguityDecision) {
                return Result.pass("requirement has no acceptance criteria yet, but an ambiguity-resolution"
                    + " decision was recorded");
            }
            return Result.fail("requirement has no acceptance criteria and no ambiguity-resolution decision"
                + " was recorded: this looks like unresolved ambiguity");
        }
    }

    /**
     * Checks that checkpointing is actually configured for this run at all, so a node
     * that mutates the target service cannot run in a configuration where rollback is
     * structurally impossible. Named for what it actually checks (configuration), not
     * "checkpoint-exists": it does not, and cannot, check that this specific node's own
     * checkpoint already exists. {@link WorkflowEngine} takes a node's checkpoint
     * automatically immediately before calling its executor, which happens strictly
     * after this entry gate has already passed, so the node's own checkpoint by
     * construction does not exist yet at the moment this gate runs. Checking for it here
     * would make this gate permanently and vacuously fail on every node's first attempt.
     * What genuinely varies, and is worth gating on, is whether a target service
     * directory and runs directory were wired up for this run in the first place.
     */
    private static final class CheckpointingConfiguredGate implements Gate {
        @Override
        public Result evaluate(WorkflowNode node, WorkflowState state, GateContext context) {
            if (context.targetServiceDirectory() == null || context.runsDirectory() == null) {
                return Result.fail("checkpointing is not configured for this run (no target service directory"
                    + " and/or runs directory wired up): a node that mutates the target service cannot run"
                    + " until rollback is possible");
            }
            return Result.pass("checkpointing is configured for this run; the engine takes this node's own"
                + " checkpoint automatically immediately before it executes");
        }
    }

    /** The node's declared output file exists and is non-empty. */
    private static final class ArtifactWrittenGate implements Gate {
        @Override
        public Result evaluate(WorkflowNode node, WorkflowState state, GateContext context) {
            Object artifactPathValue = context.executorOutputs().get("artifactPath");
            if (!(artifactPathValue instanceof String artifactPathString) || artifactPathString.isBlank()) {
                return Result.fail("executor did not report an artifactPath in its output");
            }
            Path artifactPath = Path.of(artifactPathString);
            if (!Files.isRegularFile(artifactPath)) {
                return Result.fail("declared artifact does not exist: " + artifactPath);
            }
            try {
                if (Files.size(artifactPath) == 0) {
                    return Result.fail("declared artifact is empty: " + artifactPath);
                }
            } catch (IOException e) {
                throw new UncheckedIOException("Failed to check artifact size: " + artifactPath, e);
            }
            return Result.pass("artifact exists and is non-empty: " + artifactPath);
        }
    }

    /** The target service build command exits zero. */
    private static final class CompilesGate implements Gate {
        @Override
        public Result evaluate(WorkflowNode node, WorkflowState state, GateContext context) {
            if (context.buildCommand() == null || context.buildCommand().isBlank()) {
                return Result.fail("no build command configured for this run");
            }
            CommandRunner.Result result = context.commandRunner().run(
                context.buildCommand(), context.targetServiceDirectory(), Duration.ofMinutes(5));
            if (result.timedOut()) {
                return Result.fail("build command \"" + context.buildCommand() + "\" timed out");
            }
            if (result.exitCode() != 0) {
                return Result.fail("build command \"" + context.buildCommand() + "\" exited " + result.exitCode());
            }
            return Result.pass("build command \"" + context.buildCommand() + "\" exited 0");
        }
    }

    /** The target service test command exits zero. */
    private static final class TestsPassGate implements Gate {
        @Override
        public Result evaluate(WorkflowNode node, WorkflowState state, GateContext context) {
            if (context.testCommand() == null || context.testCommand().isBlank()) {
                return Result.fail("no test command configured for this run");
            }
            CommandRunner.Result result = context.commandRunner().run(
                context.testCommand(), context.targetServiceDirectory(), Duration.ofMinutes(10));
            if (result.timedOut()) {
                return Result.fail("test command \"" + context.testCommand() + "\" timed out");
            }
            if (result.exitCode() != 0) {
                return Result.fail("test command \"" + context.testCommand() + "\" exited " + result.exitCode());
            }
            return Result.pass("test command \"" + context.testCommand() + "\" exited 0");
        }
    }

    /** Every criterion in the node's producesEvidenceFor list has passing evidence. */
    private static final class EvidenceCompleteGate implements Gate {
        @Override
        public Result evaluate(WorkflowNode node, WorkflowState state, GateContext context) {
            for (String criterionId : node.producesEvidenceFor()) {
                boolean hasPassingEvidence = state.getEvidence().stream()
                    .anyMatch(evidence -> evidence.acceptanceCriterionId().equals(criterionId) && evidence.passed());
                if (!hasPassingEvidence) {
                    return Result.fail("criterion " + criterionId + " has no passing evidence");
                }
            }
            return Result.pass("all " + node.producesEvidenceFor().size() + " declared criteria have passing evidence");
        }
    }

    /** Every HIGH or CRITICAL acceptance criterion has EXECUTED evidence. */
    private static final class ExecutedEvidenceForHighRiskGate implements Gate {
        @Override
        public Result evaluate(WorkflowNode node, WorkflowState state, GateContext context) {
            List<AcceptanceCriterion> highRiskCriteria = state.getRequirementSpec().acceptanceCriteria().stream()
                .filter(criterion -> criterion.riskLevel() == RiskLevel.HIGH || criterion.riskLevel() == RiskLevel.CRITICAL)
                .toList();
            for (AcceptanceCriterion criterion : highRiskCriteria) {
                boolean hasExecutedEvidence = state.getEvidence().stream()
                    .anyMatch(evidence -> evidence.acceptanceCriterionId().equals(criterion.id())
                        && evidence.origin() == Evidence.Origin.EXECUTED
                        && evidence.passed());
                if (!hasExecutedEvidence) {
                    return Result.fail("HIGH/CRITICAL criterion " + criterion.id() + " has no passing EXECUTED evidence");
                }
            }
            return Result.pass(highRiskCriteria.size() + " HIGH/CRITICAL criteria all have passing EXECUTED evidence");
        }
    }
}
