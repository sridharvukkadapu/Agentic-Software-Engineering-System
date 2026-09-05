package com.schwab.agentic.engine;

import com.schwab.agentic.model.WorkflowNode;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.Function;

/**
 * A test-only {@link NodeExecutor} whose timing and outcome per node id can be
 * configured, so tests can prove real concurrency (overlapping start/end timestamps),
 * real independence (one node failing does not block a sibling's execution), and real
 * retry behavior (failing N times then succeeding), rather than asserting only on final
 * status.
 *
 * Success and failure are made real, not simulated: a configured success writes an
 * actual, non-empty file at the node's declared artifact path, and a configured failure
 * does not, so tests use the engine's real {@code artifact-written} exit gate against
 * genuine files rather than a fake always-pass gate invented only for tests.
 *
 * Kept entirely under src/test so it can never be registered under a name a real
 * workflow JSON might reference, mirroring {@link NoopExecutor}'s naming precaution.
 */
final class ControllableExecutor implements NodeExecutor {

    private final Map<String, Function<Integer, Outcome>> outcomesByNodeId = new ConcurrentHashMap<>();
    private final Map<String, Duration> delaysByNodeId = new ConcurrentHashMap<>();
    private final Map<String, Integer> callCounts = new ConcurrentHashMap<>();
    private final ConcurrentLinkedQueue<Invocation> invocations = new ConcurrentLinkedQueue<>();

    /** Always produces the same outcome for the given node, regardless of attempt number. */
    void alwaysReturn(String nodeId, Outcome outcome) {
        outcomesByNodeId.put(nodeId, attempt -> outcome);
    }

    /**
     * Fails the first {@code failuresBeforeSuccess} attempts by writing an empty file at
     * {@code artifactPath} (a real, distinguishable failure the artifact-written gate
     * reports as "artifact is empty," different from "no artifactPath reported" at all),
     * then succeeds on the next attempt by writing real content to the same path.
     */
    void failThenSucceed(String nodeId, int failuresBeforeSuccess, Path artifactPath) {
        outcomesByNodeId.put(nodeId, attempt -> attempt <= failuresBeforeSuccess
            ? Outcome.failureWithEmptyArtifact("attempt " + attempt + " deliberately fails in this test", artifactPath)
            : Outcome.success("attempt " + attempt + " succeeds", artifactPath, "written by attempt " + attempt));
    }

    void withDelay(String nodeId, Duration delay) {
        delaysByNodeId.put(nodeId, delay);
    }

    List<Invocation> invocations() {
        return List.copyOf(invocations);
    }

    int callCount(String nodeId) {
        return callCounts.getOrDefault(nodeId, 0);
    }

    @Override
    public ExecutionOutput execute(WorkflowNode node, Map<String, Object> context) {
        Instant start = Instant.now();
        int attempt = callCounts.merge(node.id(), 1, Integer::sum);

        Duration delay = delaysByNodeId.get(node.id());
        if (delay != null) {
            try {
                Thread.sleep(delay.toMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException(e);
            }
        }

        Function<Integer, Outcome> outcomeFn = outcomesByNodeId.get(node.id());
        Outcome outcome = outcomeFn == null
            ? Outcome.failure("no outcome configured for node " + node.id())
            : outcomeFn.apply(attempt);

        if (outcome.artifactPath != null && (outcome.succeeded || outcome.writeEmptyArtifact)) {
            try {
                Files.createDirectories(outcome.artifactPath.getParent());
                Files.writeString(outcome.artifactPath, outcome.artifactContent == null ? "" : outcome.artifactContent);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        Instant end = Instant.now();
        // Snapshot context now: the engine reuses and further mutates the same map
        // reference across retry attempts of one node, so storing the reference itself
        // would let a later attempt's mutation retroactively change what an earlier
        // recorded invocation appears to have seen.
        invocations.add(new Invocation(node.id(), attempt, start, end, outcome.succeeded, Map.copyOf(context)));

        Map<String, Object> outputs = new java.util.HashMap<>();
        if (outcome.artifactPath != null) {
            outputs.put("artifactPath", outcome.artifactPath.toString());
        }
        outputs.put("exitCode", outcome.succeeded ? 0.0 : 1.0);
        return new ExecutionOutput(outcome.succeeded, outcome.summary, outputs);
    }

    record Outcome(boolean succeeded, String summary, Path artifactPath, String artifactContent,
                    boolean writeEmptyArtifact) {
        Outcome(boolean succeeded, String summary, Path artifactPath, String artifactContent) {
            this(succeeded, summary, artifactPath, artifactContent, false);
        }

        static Outcome success(String summary, Path artifactPath, String artifactContent) {
            return new Outcome(true, summary, artifactPath, artifactContent);
        }

        static Outcome failure(String summary) {
            return new Outcome(false, summary, null, null);
        }

        static Outcome failureWithEmptyArtifact(String summary, Path artifactPath) {
            return new Outcome(false, summary, artifactPath, null, true);
        }
    }

    record Invocation(String nodeId, int attempt, Instant start, Instant end, boolean succeeded,
                       Map<String, Object> context) {
    }
}
