package com.schwab.agentic.model;

/**
 * How much scrutiny a criterion or node requires before its output can be trusted.
 *
 * Without a risk level, there is no mechanical way to require the stronger evidence and
 * approval rules the assignment demands for high-impact work: CLAUDE.md rule 4 requires
 * EXECUTED evidence at HIGH or CRITICAL risk, and spec 05's policy engine keys its
 * approval requirements to this same scale.
 */
public enum RiskLevel {
    LOW,
    MEDIUM,
    HIGH,
    CRITICAL
}
