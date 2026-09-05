package com.schwab.agentic.model;

import java.util.Map;
import java.util.Set;

/**
 * The lifecycle states a workflow node can be in.
 *
 * Without this enum being the single source of truth for which states exist and which
 * transitions between them are legal, nothing stops a node from silently ending up in an
 * impossible state (for example RUNNING with no prior PENDING), which is exactly the
 * defect this project treats as disqualifying: an audit event that describes a status
 * change the surrounding code did not actually enforce.
 *
 * READY and BLOCKED are deliberately not states here. Both are derivable at any moment
 * from a node's declared dependencies and the current status of those dependencies
 * (computed on demand by the graph), so storing them as persisted status would create a
 * second source of truth that can silently disagree with the graph, and every
 * dependency-satisfied transition would add an audit event carrying no actual decision.
 */
public enum NodeStatus {
    PENDING,
    RUNNING,
    COMPLETED,
    FAILED,
    WAITING_APPROVAL,
    DENIED,
    ROLLED_BACK,
    INVALIDATED,
    SKIPPED;

    /**
     * The legal transition table. Every ordered pair of statuses not listed here is
     * illegal and {@link WorkflowState#transition} must reject it.
     *
     * Approval is checked before execution, never after: WAITING_APPROVAL is reachable
     * only from PENDING, never from RUNNING. A node already RUNNING has already had an
     * agent do the work, so gating approval on that state would make the checkpoint
     * theatre, and it would defeat a change budget or protected path rule that must deny
     * before anything is written, not after.
     *
     * There is exactly one edge into RUNNING (PENDING to RUNNING). This is what keeps
     * resume simple: a resumed run re-enters the same scheduling loop regardless of why a
     * node is sitting in PENDING, whether it never started, was retried, was approved, or
     * was re-planned.
     */
    private static final Map<NodeStatus, Set<NodeStatus>> LEGAL_TRANSITIONS = Map.of(
        PENDING, Set.of(RUNNING, WAITING_APPROVAL, DENIED, SKIPPED),
        RUNNING, Set.of(COMPLETED, FAILED, ROLLED_BACK),
        WAITING_APPROVAL, Set.of(PENDING, DENIED),
        COMPLETED, Set.of(ROLLED_BACK, INVALIDATED),
        FAILED, Set.of(PENDING, ROLLED_BACK),
        DENIED, Set.of(),
        ROLLED_BACK, Set.of(),
        INVALIDATED, Set.of(PENDING),
        SKIPPED, Set.of()
    );

    /**
     * Whether moving from this status to {@code to} is a legal transition. Used by
     * {@link WorkflowState#transition} to decide whether to apply a change or throw, and
     * by the transition-table test to verify every one of the 81 ordered pairs is
     * accounted for.
     */
    public boolean canTransitionTo(NodeStatus to) {
        return LEGAL_TRANSITIONS.get(this).contains(to);
    }
}
