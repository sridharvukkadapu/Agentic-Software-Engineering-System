package com.schwab.agentic.model;

/**
 * One measurable condition a requirement must satisfy before it can be released.
 *
 * Without a distinct criterion type, evidence would have nothing specific to attach to,
 * and there would be no way to check the CLAUDE.md rule that HIGH or CRITICAL criteria
 * require EXECUTED evidence, since that rule is a property of the criterion's risk level,
 * not of the requirement as a whole.
 */
public record AcceptanceCriterion(
    String id,
    String description,
    RiskLevel riskLevel
) {
    public AcceptanceCriterion {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("AcceptanceCriterion id must not be blank");
        }
        if (description == null || description.isBlank()) {
            throw new IllegalArgumentException("AcceptanceCriterion description must not be blank");
        }
        if (riskLevel == null) {
            throw new IllegalArgumentException("AcceptanceCriterion riskLevel must not be null");
        }
    }
}
