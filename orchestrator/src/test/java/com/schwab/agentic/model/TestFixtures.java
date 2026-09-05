package com.schwab.agentic.model;

import java.time.Instant;
import java.util.List;
import java.util.Set;

/**
 * Shared construction helpers for model tests.
 *
 * Centralizing fixture construction here means a change to a constructor's required
 * fields (for example adding a new required field to {@link RequirementSpec}) only needs
 * updating in one place, rather than in every test file that builds one.
 */
final class TestFixtures {

    private TestFixtures() {
    }

    static RequirementSpec requirementSpec() {
        return new RequirementSpec(
            "REQ-1",
            1,
            "Build a thing",
            "Build a thing, normalized",
            List.of(new AcceptanceCriterion("AC-1", "It works", RiskLevel.LOW)));
    }

    static WorkflowNode node(String id) {
        return new WorkflowNode(
            id,
            id,
            "noop",
            Set.of(),
            "dependencies-complete",
            "artifact-written",
            RiskLevel.LOW,
            3,
            Set.of());
    }

    static WorkflowNode node(String id, Set<String> dependsOn) {
        return new WorkflowNode(
            id,
            id,
            "noop",
            dependsOn,
            "dependencies-complete",
            "artifact-written",
            RiskLevel.LOW,
            3,
            Set.of());
    }

    /**
     * A single-node {@link WorkflowState} whose node has already been driven, through a
     * sequence of legal transitions only, to the given starting status. Used by the
     * transition-table test so every one of the 81 ordered pairs can be exercised from a
     * real starting point rather than one reached by reaching into the node directly.
     */
    static WorkflowState singleNodeState(NodeStatus startingStatus) {
        WorkflowState state = new WorkflowState("RUN-1", requirementSpec(), List.of(node("N1")));
        WorkflowNode node = state.getNode("N1");
        for (NodeStatus step : pathTo(startingStatus)) {
            state.transition(node, step, "system", "fixture setup: reaching " + startingStatus);
        }
        return state;
    }

    /**
     * A legal sequence of transitions, starting from PENDING, that ends at the given
     * status. Every one of the nine statuses is reachable this way, since PENDING can
     * reach RUNNING, WAITING_APPROVAL, DENIED and SKIPPED directly, RUNNING can reach
     * COMPLETED, FAILED and ROLLED_BACK, and COMPLETED can reach INVALIDATED, which loops
     * back to PENDING.
     */
    private static List<NodeStatus> pathTo(NodeStatus target) {
        return switch (target) {
            case PENDING -> List.of();
            case RUNNING -> List.of(NodeStatus.RUNNING);
            case COMPLETED -> List.of(NodeStatus.RUNNING, NodeStatus.COMPLETED);
            case FAILED -> List.of(NodeStatus.RUNNING, NodeStatus.FAILED);
            case WAITING_APPROVAL -> List.of(NodeStatus.WAITING_APPROVAL);
            case DENIED -> List.of(NodeStatus.WAITING_APPROVAL, NodeStatus.DENIED);
            case ROLLED_BACK -> List.of(NodeStatus.RUNNING, NodeStatus.ROLLED_BACK);
            case INVALIDATED -> List.of(NodeStatus.RUNNING, NodeStatus.COMPLETED, NodeStatus.INVALIDATED);
            case SKIPPED -> List.of(NodeStatus.SKIPPED);
        };
    }

    static Instant fixedInstant() {
        return Instant.parse("2026-01-01T00:00:00Z");
    }
}
