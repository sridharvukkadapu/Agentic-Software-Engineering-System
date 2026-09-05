package com.schwab.agentic.engine;

import com.schwab.agentic.model.WorkflowNode;
import com.schwab.agentic.model.WorkflowState;

/**
 * A named, checkable condition guarding a node's entry into execution or its exit from
 * it. Workflow JSON references gates by name (see {@link WorkflowNode#entryGate} and
 * {@link WorkflowNode#exitGate}); {@link Gates} resolves a name to an implementation of
 * this interface.
 *
 * A gate never defaults to passing when it cannot make a determination. If evaluation
 * cannot proceed (missing data, unreadable file), that is a failing result with a reason
 * saying so, never a silent pass: a gate that can be made to pass by giving it nothing
 * to check is not a control.
 */
public interface Gate {

    Result evaluate(WorkflowNode node, WorkflowState state, GateContext context);

    /**
     * The outcome of one gate evaluation. {@code reason} must state what was checked and
     * what was found, since it goes directly into the audit event for this decision, and
     * CLAUDE.md rule 1 treats an audit event that does not reflect what the surrounding
     * code actually did as a defect.
     */
    record Result(boolean passed, String reason) {
        public Result {
            if (reason == null || reason.isBlank()) {
                throw new IllegalArgumentException("Gate.Result reason must not be blank");
            }
        }

        public static Result pass(String reason) {
            return new Result(true, reason);
        }

        public static Result fail(String reason) {
            return new Result(false, reason);
        }
    }
}
