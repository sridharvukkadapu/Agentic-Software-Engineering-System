package com.schwab.agentic.engine;

import com.schwab.agentic.graph.WorkflowGraph;
import java.nio.file.Path;
import java.util.Map;

/**
 * Whatever a gate needs beyond the node and the run state to make its determination.
 *
 * {@code executorOutputs} is the executor's reported output for an exit gate evaluation;
 * it is empty for an entry gate, since entry gates run before any execution happens.
 * {@code graph} lets a gate reason about dependencies or graph shape.
 * {@code targetServiceDirectory} is where the target service working tree lives, for
 * gates that check the filesystem or run a build. {@code runsDirectory} is the root
 * under which this run's checkpoints and artifacts live, so the {@code checkpoint-exists}
 * gate can check for a checkpoint without guessing a path relative to the target service
 * directory. {@code commandRunner}, {@code buildCommand} and {@code testCommand} are
 * what the {@code compiles} and {@code tests-pass} gates use to actually invoke a real
 * command rather than assuming one succeeded.
 */
public record GateContext(
    Map<String, Object> executorOutputs,
    WorkflowGraph graph,
    Path targetServiceDirectory,
    Path runsDirectory,
    CommandRunner commandRunner,
    String buildCommand,
    String testCommand
) {
    public GateContext {
        executorOutputs = executorOutputs == null ? Map.of() : Map.copyOf(executorOutputs);
    }
}
