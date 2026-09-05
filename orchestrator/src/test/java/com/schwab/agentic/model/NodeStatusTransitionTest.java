package com.schwab.agentic.model;

import static com.schwab.agentic.Assertions.assertEquals;
import static com.schwab.agentic.Assertions.assertThrows;
import static com.schwab.agentic.Assertions.assertTrue;

import java.util.List;
import java.util.Set;

/**
 * Enumerates all 81 ordered pairs of {@link NodeStatus} and checks each against
 * {@link WorkflowState#transition}, so a wrong edge in the transition table is caught
 * here rather than surfacing three specs later as a rejected retry or an unreachable
 * re-plan.
 */
public class NodeStatusTransitionTest {

    /**
     * The same table {@link NodeStatus#canTransitionTo} encodes, restated independently
     * here so this test cannot pass merely by agreeing with itself: if someone edits the
     * production table without noticing this one, the two will disagree and every
     * illegal pair that became legal, or vice versa, will fail.
     */
    private static boolean expectedLegal(NodeStatus from, NodeStatus to) {
        return switch (from) {
            case PENDING -> to == NodeStatus.RUNNING
                || to == NodeStatus.WAITING_APPROVAL
                || to == NodeStatus.DENIED
                || to == NodeStatus.SKIPPED;
            case RUNNING -> to == NodeStatus.COMPLETED
                || to == NodeStatus.FAILED
                || to == NodeStatus.ROLLED_BACK;
            case WAITING_APPROVAL -> to == NodeStatus.PENDING || to == NodeStatus.DENIED;
            case COMPLETED -> to == NodeStatus.ROLLED_BACK || to == NodeStatus.INVALIDATED;
            case FAILED -> to == NodeStatus.PENDING || to == NodeStatus.ROLLED_BACK;
            case INVALIDATED -> to == NodeStatus.PENDING;
            case DENIED, ROLLED_BACK, SKIPPED -> false;
        };
    }

    public void testAllEightyOneOrderedPairsMatchTheExpectedTable() {
        List<NodeStatus> allStatuses = List.of(NodeStatus.values());
        assertEquals(9, allStatuses.size(), "expected exactly nine NodeStatus values");

        int checked = 0;
        for (NodeStatus from : allStatuses) {
            for (NodeStatus to : allStatuses) {
                boolean expected = expectedLegal(from, to);
                boolean actual = from.canTransitionTo(to);
                assertEquals(expected, actual,
                    "canTransitionTo mismatch for " + from + " -> " + to);
                checked++;
            }
        }
        assertEquals(81, checked, "expected to check all 81 ordered pairs");
    }

    public void testEveryLegalPairIsActuallyAppliedByTransition() {
        for (NodeStatus from : NodeStatus.values()) {
            for (NodeStatus to : NodeStatus.values()) {
                if (!expectedLegal(from, to)) {
                    continue;
                }
                WorkflowState state = TestFixtures.singleNodeState(from);

                int auditSizeBefore = state.getAuditLog().size();
                state.transition("N1", to, "system", "table-driven test " + from + " -> " + to);

                assertEquals(to, state.getStatus("N1"), "node status after legal transition " + from + " -> " + to);
                assertEquals(auditSizeBefore + 1, state.getAuditLog().size(),
                    "expected exactly one new audit event for " + from + " -> " + to);
                AuditEvent event = state.getAuditLog().get(state.getAuditLog().size() - 1);
                assertEquals(from, event.from(), "audit event from for " + from + " -> " + to);
                assertEquals(to, event.to(), "audit event to for " + from + " -> " + to);
            }
        }
    }

    public void testEveryIllegalPairThrowsAndLeavesStateUnchanged() {
        for (NodeStatus from : NodeStatus.values()) {
            for (NodeStatus to : NodeStatus.values()) {
                if (expectedLegal(from, to)) {
                    continue;
                }
                WorkflowState state = TestFixtures.singleNodeState(from);
                int auditSizeBefore = state.getAuditLog().size();

                assertThrows(IllegalStateException.class,
                    () -> state.transition("N1", to, "system", "should be rejected"),
                    "expected " + from + " -> " + to + " to be rejected");

                assertEquals(from, state.getStatus("N1"),
                    "node status must be unchanged after illegal transition " + from + " -> " + to);
                assertEquals(auditSizeBefore, state.getAuditLog().size(),
                    "audit log must be unchanged after illegal transition " + from + " -> " + to);
            }
        }
    }

    public void testTerminalStatusesHaveNoOutgoingLegalTransitions() {
        for (NodeStatus terminal : Set.of(NodeStatus.DENIED, NodeStatus.ROLLED_BACK, NodeStatus.SKIPPED)) {
            for (NodeStatus to : NodeStatus.values()) {
                assertTrue(!terminal.canTransitionTo(to),
                    terminal + " must have no outgoing transitions, but allows -> " + to);
            }
        }
    }
}
