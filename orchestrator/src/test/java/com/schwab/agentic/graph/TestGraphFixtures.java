package com.schwab.agentic.graph;

import com.schwab.agentic.model.AcceptanceCriterion;
import com.schwab.agentic.model.RequirementSpec;
import com.schwab.agentic.model.RiskLevel;
import com.schwab.agentic.model.WorkflowNode;
import com.schwab.agentic.model.WorkflowState;
import java.util.List;
import java.util.Set;

/**
 * Shared construction helpers for graph tests, including the default eight-node SDLC
 * graph and a small diamond-shaped graph used to test downstream reachability against a
 * shape with real fan-out and rejoin, not just a straight chain.
 *
 * {@link #stateOver} builds a {@link WorkflowState} from a graph's own
 * {@link WorkflowGraph#getAllNodes} rather than a second, independently typed-out node
 * list, purely to avoid repeating eight node definitions twice per test. Node identity
 * no longer matters for correctness the way it once did: {@link WorkflowNode} is
 * immutable and status is tracked in {@code WorkflowState} keyed by id, so a graph and a
 * state built from separately-constructed nodes with the same ids will still agree, as
 * long as the caller reads status from the state (via {@code getStatuses()}) rather than
 * from the graph.
 */
final class TestGraphFixtures {

    private TestGraphFixtures() {
    }

    static WorkflowNode node(String id, Set<String> dependsOn) {
        return new WorkflowNode(
            id, id, "noop", dependsOn, "dependencies-complete", "artifact-written",
            RiskLevel.LOW, 2, Set.of());
    }

    /**
     * TOP feeds LEFT and RIGHT, both of which feed BOTTOM. Used to prove downstreamOf
     * handles fan-out and rejoin correctly, which a simple chain could not distinguish
     * from an implementation that only follows a single path.
     */
    static WorkflowGraph diamondGraph() {
        WorkflowNode top = node("TOP", Set.of());
        WorkflowNode left = node("LEFT", Set.of("TOP"));
        WorkflowNode right = node("RIGHT", Set.of("TOP"));
        WorkflowNode bottom = node("BOTTOM", Set.of("LEFT", "RIGHT"));
        return WorkflowGraph.of(List.of(top, left, right, bottom));
    }

    static WorkflowGraph defaultSdlcGraph() {
        WorkflowNode requirement = new WorkflowNode(
            "REQUIREMENT", "Requirement analysis", "requirement", Set.of(),
            "dependencies-complete", "artifact-written", RiskLevel.LOW, 2, Set.of());
        WorkflowNode impact = new WorkflowNode(
            "IMPACT", "Impact analysis", "impact", Set.of("REQUIREMENT"),
            "dependencies-complete", "artifact-written", RiskLevel.MEDIUM, 2, Set.of());
        WorkflowNode design = new WorkflowNode(
            "DESIGN", "Design", "design", Set.of("IMPACT"),
            "requirement-unambiguous-or-approved", "artifact-written", RiskLevel.MEDIUM, 2, Set.of());
        WorkflowNode implement = new WorkflowNode(
            "IMPLEMENT", "Implementation", "implement", Set.of("DESIGN"),
            "checkpoint-exists", "compiles", RiskLevel.HIGH, 3, Set.of("compiles"));
        WorkflowNode test = new WorkflowNode(
            "TEST", "Test generation", "test", Set.of("DESIGN"),
            "dependencies-complete", "tests-pass", RiskLevel.MEDIUM, 3, Set.of("tests-pass"));
        WorkflowNode document = new WorkflowNode(
            "DOCUMENT", "Documentation", "document", Set.of("DESIGN"),
            "dependencies-complete", "artifact-written", RiskLevel.LOW, 2, Set.of());
        WorkflowNode validate = new WorkflowNode(
            "VALIDATE", "Validation", "validate", Set.of("IMPLEMENT", "TEST", "DOCUMENT"),
            "dependencies-complete", "evidence-complete", RiskLevel.HIGH, 2, Set.of());
        WorkflowNode release = new WorkflowNode(
            "RELEASE", "Release readiness", "release", Set.of("VALIDATE"),
            "dependencies-complete", "executed-evidence-for-high-risk", RiskLevel.CRITICAL, 1, Set.of());

        return WorkflowGraph.of(
            List.of(requirement, impact, design, implement, test, document, validate, release));
    }

    /**
     * A {@link WorkflowState} for a fresh run over the given graph, built from that
     * graph's own node instances via {@link WorkflowGraph#getAllNodes} so the graph and
     * the state passed to {@link WorkflowGraph#readyNodes} are always looking at the
     * same objects. A caller must pass the exact same {@code graph} instance to both
     * this method and to {@code readyNodes}, not a second graph built from the same node
     * definitions, or the sharing this method exists to guarantee is defeated again one
     * level up.
     */
    static WorkflowState stateOver(WorkflowGraph graph) {
        RequirementSpec requirementSpec = new RequirementSpec(
            "REQ-1", 1, "Add a feature", "Add a feature, normalized",
            List.of(new AcceptanceCriterion("AC-1", "It works", RiskLevel.LOW)));
        return new WorkflowState("RUN-1", requirementSpec, graph.getAllNodes());
    }
}
