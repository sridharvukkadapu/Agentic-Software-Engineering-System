package com.schwab.agentic.engine;

import com.schwab.agentic.model.WorkflowNode;
import java.util.Map;

/**
 * An executor that does nothing and reports success, registered under the obviously-fake
 * name {@code noop-test-only} so a workflow JSON can never reference it by accident and
 * mistake it for a real stage. Exists only so tests of the scheduling and gating logic
 * (this spec) do not need spec 04's real executors to exist yet.
 */
public final class NoopExecutor implements NodeExecutor {

    public static final String NAME = "noop-test-only";

    @Override
    public ExecutionOutput execute(WorkflowNode node, Map<String, Object> context) {
        return new ExecutionOutput(true, "noop executor did nothing for " + node.id(), Map.of());
    }
}
