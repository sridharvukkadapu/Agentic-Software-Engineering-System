package com.schwab.agentic.engine;

import com.schwab.agentic.model.WorkflowNode;
import java.util.Map;

/**
 * What a workflow stage actually does. Spec 04 provides the real implementations
 * (calling the agent layer, running build commands); this spec only provides
 * {@link NoopExecutor}, kept in the same package but deliberately named so it can never
 * be mistaken for a real stage.
 *
 * {@code execute} does not decide whether the node succeeded. It reports what happened,
 * via {@link ExecutionOutput}, and the engine evaluates the node's exit gate against that
 * report to decide the outcome. An executor that returned its own pass/fail verdict
 * directly would let it grade its own work, which is the exact shortcut CLAUDE.md rule 2
 * exists to close off.
 *
 * {@code context} carries information across attempts of the same node: on a retry, the
 * engine adds the previous attempt's failure reason and attempt number before calling
 * execute again, so attempt N+1 is informed by attempt N rather than identical to it
 * (spec 02 AC-02-5).
 */
public interface NodeExecutor {

    ExecutionOutput execute(WorkflowNode node, Map<String, Object> context);

    /**
     * What an executor reports after running. {@code executorReportedSuccess} is the
     * executor's own opinion, which the exit gate is free to override; {@code outputs}
     * carries whatever structured facts the exit gate needs to check, such as an exit
     * code or a written file's path, keyed by whatever name the gate implementation
     * expects to find.
     */
    record ExecutionOutput(boolean executorReportedSuccess, String summary, Map<String, Object> outputs) {
        public ExecutionOutput {
            if (summary == null || summary.isBlank()) {
                throw new IllegalArgumentException("ExecutionOutput summary must not be blank");
            }
            outputs = outputs == null ? Map.of() : Map.copyOf(outputs);
        }
    }
}
