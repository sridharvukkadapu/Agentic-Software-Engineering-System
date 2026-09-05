package com.schwab.agentic.engine;

import com.schwab.agentic.model.WorkflowNode;
import com.schwab.agentic.model.WorkflowState;

/**
 * One named, independently testable policy check. {@link PolicyEngine} evaluates every
 * registered rule for a node and combines the results (the most restrictive decision
 * wins: DENY beats REQUIRE_APPROVAL beats ALLOW), so a rule only needs to reason about
 * its own concern and never needs to know what any other rule decided.
 *
 * A rule that runs pre-execution (see {@link Timing#PRE_EXECUTION}) is evaluated before
 * a node's executor is ever called, exactly like {@link Gate} entry gates; it has no real
 * diff to inspect yet, since nothing has been written. A rule that runs post-execution
 * (see {@link Timing#POST_EXECUTION}) is evaluated after the executor returns but before
 * the exit gate decides COMPLETED, so it can inspect what the executor actually reported
 * writing. This split exists because CLAUDE.md rule 1 and this project's own D1 decision
 * both require a DENY to happen before an executor's damage lands wherever possible, but
 * several real rules (protected paths, secrets, dependency additions, change budget)
 * cannot be evaluated without seeing what actually changed, which by definition does not
 * exist until after the executor runs.
 */
public interface PolicyRule {

    String name();

    Timing timing();

    Result evaluate(WorkflowNode node, WorkflowState state, PolicyContext context);

    enum Timing {
        PRE_EXECUTION,
        POST_EXECUTION
    }

    /**
     * A rule's outcome, always naming which rule produced it and why. {@code reason} goes
     * directly into the audit event for this decision (POLICY_DENIED or the
     * WAITING_APPROVAL status change), so it must state the actual condition found, never
     * a generic "policy denied" that leaves a reviewer unable to tell which rule fired or
     * on what evidence.
     */
    record Result(PolicyEngine.Decision decision, String ruleName, String reason) {
        public Result {
            if (decision == null) {
                throw new IllegalArgumentException("PolicyRule.Result decision must not be null");
            }
            if (ruleName == null || ruleName.isBlank()) {
                throw new IllegalArgumentException("PolicyRule.Result ruleName must not be blank");
            }
            if (reason == null || reason.isBlank()) {
                throw new IllegalArgumentException("PolicyRule.Result reason must not be blank");
            }
        }

        public static Result allow(String ruleName, String reason) {
            return new Result(PolicyEngine.Decision.ALLOW, ruleName, reason);
        }

        public static Result requireApproval(String ruleName, String reason) {
            return new Result(PolicyEngine.Decision.REQUIRE_APPROVAL, ruleName, reason);
        }

        public static Result deny(String ruleName, String reason) {
            return new Result(PolicyEngine.Decision.DENY, ruleName, reason);
        }
    }
}
