package com.schwab.agentic.engine;

import com.schwab.agentic.model.WorkflowNode;
import com.schwab.agentic.model.WorkflowState;

/**
 * Decides whether a node may proceed once its entry gate has passed. Spec 05 fills this
 * in with real rules loaded from {@code workflows/policy.json} (change budgets, protected
 * paths, risk-based approval requirements). This spec only defines the interface and a
 * default implementation that always allows, so the engine has somewhere real to call
 * without inventing policy logic that belongs to a later piece.
 */
public interface PolicyEngine {

    Decision evaluate(WorkflowNode node, WorkflowState state);

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
