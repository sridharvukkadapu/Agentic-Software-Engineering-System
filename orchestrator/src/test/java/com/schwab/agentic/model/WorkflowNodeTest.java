package com.schwab.agentic.model;

import static com.schwab.agentic.Assertions.assertFalse;
import static com.schwab.agentic.Assertions.assertThrows;

import java.lang.reflect.Method;
import java.util.Set;

/**
 * Covers {@link WorkflowNode}'s own invariants: it is fully immutable (no status field
 * or setter at all, unlike an earlier version of this class), and its constructor
 * validation rejects blank ids, blank names, blank executors, a null risk level, and a
 * maxAttempts below 1.
 */
public class WorkflowNodeTest {

    public void testWorkflowNodeCarriesNoMutableStatusField() {
        for (Method method : WorkflowNode.class.getDeclaredMethods()) {
            assertFalse(method.getName().equals("setStatus"),
                "WorkflowNode must not carry a status setter; status lives in WorkflowState, keyed by id");
        }
    }

    public void testRejectsBlankId() {
        assertThrows(IllegalArgumentException.class,
            () -> new WorkflowNode("", "Name", "noop", Set.of(), null, null, RiskLevel.LOW, 1, Set.of()),
            "WorkflowNode must reject a blank id");
    }

    public void testRejectsBlankName() {
        assertThrows(IllegalArgumentException.class,
            () -> new WorkflowNode("N1", "", "noop", Set.of(), null, null, RiskLevel.LOW, 1, Set.of()),
            "WorkflowNode must reject a blank name");
    }

    public void testRejectsBlankExecutor() {
        assertThrows(IllegalArgumentException.class,
            () -> new WorkflowNode("N1", "Name", "", Set.of(), null, null, RiskLevel.LOW, 1, Set.of()),
            "WorkflowNode must reject a blank executor");
    }

    public void testRejectsNullRiskLevel() {
        assertThrows(IllegalArgumentException.class,
            () -> new WorkflowNode("N1", "Name", "noop", Set.of(), null, null, null, 1, Set.of()),
            "WorkflowNode must reject a null riskLevel");
    }

    public void testRejectsMaxAttemptsBelowOne() {
        assertThrows(IllegalArgumentException.class,
            () -> new WorkflowNode("N1", "Name", "noop", Set.of(), null, null, RiskLevel.LOW, 0, Set.of()),
            "WorkflowNode must reject maxAttempts below 1");
    }

    public void testNullDependsOnAndProducesEvidenceForDefaultToEmptySets() {
        WorkflowNode node = new WorkflowNode("N1", "Name", "noop", null, null, null, RiskLevel.LOW, 1, null);

        com.schwab.agentic.Assertions.assertEquals(Set.of(), node.dependsOn(), "null dependsOn must default to empty");
        com.schwab.agentic.Assertions.assertEquals(Set.of(), node.producesEvidenceFor(),
            "null producesEvidenceFor must default to empty");
    }
}
