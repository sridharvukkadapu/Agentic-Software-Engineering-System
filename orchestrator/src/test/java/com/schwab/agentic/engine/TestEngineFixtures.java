package com.schwab.agentic.engine;

import com.schwab.agentic.model.AcceptanceCriterion;
import com.schwab.agentic.model.RequirementSpec;
import com.schwab.agentic.model.RiskLevel;
import com.schwab.agentic.model.WorkflowNode;
import java.util.List;
import java.util.Set;

/** Shared construction helpers for engine tests. Every node uses the real artifact-written exit gate. */
final class TestEngineFixtures {

    private TestEngineFixtures() {
    }

    static RequirementSpec requirementSpec() {
        return new RequirementSpec(
            "REQ-1", 1, "Build a thing", "Build a thing, normalized",
            List.of(new AcceptanceCriterion("AC-1", "It works", RiskLevel.LOW)));
    }

    static WorkflowNode node(String id, Set<String> dependsOn, int maxAttempts) {
        return new WorkflowNode(
            id, id, "controllable", dependsOn, "dependencies-complete", "artifact-written",
            RiskLevel.LOW, maxAttempts, Set.of());
    }

    static WorkflowNode nodeWithFallback(String id, Set<String> dependsOn, int maxAttempts, String fallbackExecutor) {
        return new WorkflowNode(
            id, id, "controllable", dependsOn, "dependencies-complete", "artifact-written",
            RiskLevel.LOW, maxAttempts, Set.of(), fallbackExecutor);
    }

    /** A node with declared write paths, so the engine actually checkpoints it when a target service directory is configured. */
    static WorkflowNode nodeWithWritePaths(String id, Set<String> dependsOn, int maxAttempts, Set<String> writePaths) {
        return new WorkflowNode(
            id, id, "controllable", dependsOn, "dependencies-complete", "artifact-written",
            RiskLevel.LOW, maxAttempts, Set.of(), null, writePaths);
    }
}
