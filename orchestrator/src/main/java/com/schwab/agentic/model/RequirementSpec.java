package com.schwab.agentic.model;

import java.util.List;

/**
 * The normalized, versioned statement of what a run is building.
 *
 * Without a revision counter, there would be no mechanical way to detect that an approval
 * or a piece of evidence was granted against an earlier version of the requirement. Spec
 * 05 keys human approvals to the revision they were granted against, and spec 06's
 * re-planning bumps this field on every amendment, which is what makes a stale approval
 * or stale evidence detectable instead of silently reused after the requirement changed
 * underneath it.
 */
public record RequirementSpec(
    String id,
    int revision,
    String rawText,
    String normalizedProblem,
    List<AcceptanceCriterion> acceptanceCriteria
) {
    public RequirementSpec {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("RequirementSpec id must not be blank");
        }
        if (revision < 1) {
            throw new IllegalArgumentException("RequirementSpec revision must start at 1, got " + revision);
        }
        if (rawText == null || rawText.isBlank()) {
            throw new IllegalArgumentException("RequirementSpec rawText must not be blank");
        }
        acceptanceCriteria = acceptanceCriteria == null ? List.of() : List.copyOf(acceptanceCriteria);
    }

    /**
     * A copy of this requirement at the next revision, used by re-planning when a
     * requirement is amended mid-run. The original instance is left untouched, since
     * spec 06 needs to compare the prior and amended requirement to compute what changed.
     */
    public RequirementSpec withNextRevision(String amendedRawText, String amendedNormalizedProblem,
                                             List<AcceptanceCriterion> amendedCriteria) {
        return new RequirementSpec(id, revision + 1, amendedRawText, amendedNormalizedProblem, amendedCriteria);
    }
}
