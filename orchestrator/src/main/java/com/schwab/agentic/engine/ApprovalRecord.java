package com.schwab.agentic.engine;

import java.time.Instant;

/**
 * One human approval or denial decision, keyed to the exact requirement revision it was
 * granted against.
 *
 * The revision is not metadata here, it is the whole point: spec 06's re-planning bumps
 * the requirement's revision on every amendment, and an approval granted against revision
 * 1 must never be read as satisfying the same node at revision 2, since the requirement
 * that approval was actually reviewed against no longer describes what the node is being
 * asked to do. Without the revision baked into the record itself, an {@link ApprovalStore}
 * would have no way to tell a still-valid approval from a stale one; it would have to
 * trust whatever the caller currently believes the revision is, which is exactly the kind
 * of asserted-not-derived gap CLAUDE.md rule 1 argues against.
 */
public record ApprovalRecord(
    String nodeId,
    int requirementRevision,
    Decision decision,
    String approver,
    String reason,
    Instant decidedAt
) {
    public ApprovalRecord {
        if (nodeId == null || nodeId.isBlank()) {
            throw new IllegalArgumentException("ApprovalRecord nodeId must not be blank");
        }
        if (requirementRevision < 1) {
            throw new IllegalArgumentException("ApprovalRecord requirementRevision must be at least 1");
        }
        if (decision == null) {
            throw new IllegalArgumentException("ApprovalRecord decision must not be null");
        }
        if (approver == null || approver.isBlank()) {
            throw new IllegalArgumentException("ApprovalRecord approver must not be blank");
        }
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("ApprovalRecord reason must not be blank");
        }
        if (decidedAt == null) {
            throw new IllegalArgumentException("ApprovalRecord decidedAt must not be null");
        }
    }

    public enum Decision {
        APPROVED,
        DENIED
    }
}
