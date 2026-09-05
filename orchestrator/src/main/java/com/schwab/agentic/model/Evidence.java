package com.schwab.agentic.model;

import java.time.Instant;

/**
 * A single piece of proof that an acceptance criterion was, or was not, satisfied.
 *
 * Without recording {@link Origin} on every piece of evidence, there would be no way to
 * enforce CLAUDE.md rule 4: that HIGH or CRITICAL risk criteria accept only evidence a
 * command actually produced, not evidence someone merely asserted. A gate that cannot
 * distinguish EXECUTED from ASSERTED evidence cannot refuse to release on an unverified
 * claim, which is the entire point of the rule.
 */
public record Evidence(
    Origin origin,
    String acceptanceCriterionId,
    boolean passed,
    String description,
    String source,
    String producedByNode,
    String artifactPath,
    Instant capturedAt
) {
    public Evidence {
        if (origin == null) {
            throw new IllegalArgumentException("Evidence origin must not be null");
        }
        if (acceptanceCriterionId == null || acceptanceCriterionId.isBlank()) {
            throw new IllegalArgumentException("Evidence acceptanceCriterionId must not be blank");
        }
        if (producedByNode == null || producedByNode.isBlank()) {
            throw new IllegalArgumentException("Evidence producedByNode must not be blank");
        }
        if (capturedAt == null) {
            throw new IllegalArgumentException("Evidence capturedAt must not be null");
        }
    }

    /**
     * Where a piece of evidence came from.
     *
     * EXECUTED means a command ran and returned an exit code, or tool output was parsed.
     * ASSERTED means someone, human or agent, said so without anything running. Conflating
     * the two would let an agent's unverified claim satisfy the same gate as a passing
     * test suite, which is exactly the failure mode CLAUDE.md rule 4 exists to prevent.
     */
    public enum Origin {
        EXECUTED,
        ASSERTED
    }
}
