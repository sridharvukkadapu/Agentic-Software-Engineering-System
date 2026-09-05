package com.schwab.agentic.executor;

import com.schwab.agentic.model.RiskLevel;
import com.schwab.agentic.model.WorkflowNode;
import java.util.Set;

/** Shared WorkflowNode construction helpers for executor tests. */
final class TestExecutorFixtures {

    private TestExecutorFixtures() {
    }

    static WorkflowNode implementNode() {
        return new WorkflowNode("IMPLEMENT", "Implementation", "implement", Set.of("DESIGN"),
            "checkpointing-configured", "compiles", RiskLevel.HIGH, 3, Set.of("compiles"), null, Set.of("src/main"));
    }

    static WorkflowNode testGenNode() {
        return new WorkflowNode("TEST", "Test generation", "test", Set.of("DESIGN"),
            "dependencies-complete", "tests-pass", RiskLevel.MEDIUM, 3, Set.of("tests-pass"), null, Set.of("src/test"));
    }

    static WorkflowNode documentNode() {
        return new WorkflowNode("DOCUMENT", "Documentation", "document", Set.of("DESIGN"),
            "dependencies-complete", "artifact-written", RiskLevel.LOW, 2, Set.of());
    }

    static WorkflowNode validateNode() {
        return new WorkflowNode("VALIDATE", "Validation", "validate", Set.of("IMPLEMENT", "TEST", "DOCUMENT"),
            "dependencies-complete", "evidence-complete", RiskLevel.HIGH, 2, Set.of());
    }

    static WorkflowNode releaseNode() {
        return new WorkflowNode("RELEASE", "Release readiness", "release", Set.of("VALIDATE"),
            "dependencies-complete", "executed-evidence-for-high-risk", RiskLevel.CRITICAL, 1, Set.of());
    }
}
