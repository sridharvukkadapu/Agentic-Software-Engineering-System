package com.schwab.agentic.engine;

import java.util.HashMap;
import java.util.Map;

/**
 * Maps executor names declared in a workflow JSON to the {@link NodeExecutor}
 * implementation that runs them. A name with no registered implementation is a load-time
 * configuration error, not something the engine discovers mid-run: {@link #get} throws
 * rather than returning null or a default, so a typo in a workflow file fails immediately
 * and names the offending executor, the same way {@link com.schwab.agentic.graph.WorkflowGraph}
 * fails immediately on a dangling dependency.
 */
public final class NodeExecutorRegistry {

    private final Map<String, NodeExecutor> executorsByName = new HashMap<>();

    public NodeExecutorRegistry register(String name, NodeExecutor executor) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Executor name must not be blank");
        }
        if (executor == null) {
            throw new IllegalArgumentException("Executor must not be null");
        }
        if (executorsByName.containsKey(name)) {
            throw new IllegalArgumentException("Executor already registered under name: " + name);
        }
        executorsByName.put(name, executor);
        return this;
    }

    public NodeExecutor get(String name) {
        NodeExecutor executor = executorsByName.get(name);
        if (executor == null) {
            throw new IllegalArgumentException(
                "No executor registered for name: " + name + ". Registered names: " + executorsByName.keySet());
        }
        return executor;
    }

    public boolean isRegistered(String name) {
        return executorsByName.containsKey(name);
    }
}
