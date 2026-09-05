package com.schwab.agentic.engine;

import com.schwab.agentic.model.WorkflowNode;
import com.schwab.agentic.model.WorkflowState;
import java.util.Map;

/**
 * Decides whether a node may proceed once its entry gate has passed, and separately
 * whether a node's actual output, once produced, violates a policy that could only be
 * checked after the fact.
 *
 * {@link #evaluate} is the pre-execution check spec 02 already wired into the engine's
 * admission step: it runs before a node's executor is ever called, so it can only reason
 * about the node's declaration (risk level, dependencies) and the run's accumulated state
 * (evidence, requirement revision), never about a diff that does not exist yet.
 *
 * {@link #evaluatePostExecution} is new in spec 05: several real rules (protected paths,
 * secrets in a diff, dependency additions, a change budget) cannot be evaluated without
 * seeing what the executor actually wrote, which by definition happens after it runs.
 * D1 (docs/decisions.md) requires a DENY to happen before an executor's damage lands
 * wherever that is possible; where it genuinely is not possible, because the rule's whole
 * point is to inspect real output, the engine treats a post-execution DENY the same way
 * it treats an exit gate failure with no retry budget left: real rollback via the
 * checkpoint already taken for this node, never a silent pass.
 */
public interface PolicyEngine {

    Decision evaluate(WorkflowNode node, WorkflowState state);

    /**
     * The pre-execution decision together with the reason and the name of whichever rule
     * produced it, and with access to {@link PolicyContext} (in particular
     * {@code autoApprove}, which the HIGH-risk rule must see to know whether to require
     * approval at all), for a caller (the engine) that wants to record a real audit event
     * naming the actual rule that fired rather than a generic "policy denied." Defaults
     * to wrapping {@link #evaluate}'s bare decision with a generic reason, so a test-only
     * {@link PolicyEngine} built as a lambda (only implementing {@link #evaluate}) keeps
     * working without needing to implement this too.
     */
    default PolicyRule.Result evaluatePreExecutionWithReason(WorkflowNode node, WorkflowState state, PolicyContext context) {
        Decision decision = evaluate(node, state);
        return switch (decision) {
            case ALLOW -> PolicyRule.Result.allow("none", "policy allows node " + node.id());
            case REQUIRE_APPROVAL -> PolicyRule.Result.requireApproval("unnamed",
                "policy requires approval for node " + node.id() + " before execution");
            case DENY -> PolicyRule.Result.deny("unnamed", "policy denied node " + node.id() + " before execution");
        };
    }

    /**
     * Evaluates every registered post-execution rule against what {@code executorOutputs}
     * reports the node actually wrote. Returns {@link PolicyRule.Result#allow} naming no
     * particular rule if every rule allows.
     */
    default PolicyRule.Result evaluatePostExecution(WorkflowNode node, WorkflowState state,
                                                      Map<String, Object> executorOutputs, PolicyContext context) {
        return PolicyRule.Result.allow("none", "no post-execution rules registered");
    }

    /**
     * ALLOW lets the node proceed to execution immediately. REQUIRE_APPROVAL parks the
     * node at WAITING_APPROVAL before it ever runs, per spec 02's correction that
     * approval is checked before execution, never after. DENY moves the node straight
     * to DENIED without ever calling its executor.
     */
    enum Decision {
        ALLOW,
        REQUIRE_APPROVAL,
        DENY
    }

    /** Always allows. The engine calls a real PolicyEngine once spec 05 provides one. */
    final class AllowAllPolicyEngine implements PolicyEngine {
        @Override
        public Decision evaluate(WorkflowNode node, WorkflowState state) {
            return Decision.ALLOW;
        }
    }
}
