package com.schwab.agentic.engine;

import java.nio.file.Path;

/**
 * Whatever a policy rule needs beyond the node and the run state to make its
 * determination.
 *
 * {@code targetServiceDirectory} lets a rule resolve a reported write's real path and
 * read its real content (protected paths, secrets, dependency manifests). {@code
 * runsDirectory} and {@code runId} let a rule read this run's real artifacts, for
 * instance {@code runs/<runId>/artifacts/impact.json} to learn whether this run is
 * greenfield or brownfield, never an in-memory flag someone forgot to set: the same
 * "derive it from what is actually on disk" contract {@link GateContext} already uses
 * for gates. {@code autoApprove} is true only for {@code --auto-approve} runs, which are
 * permitted only in {@code --replay} mode and are always stamped into the run report,
 * per AC-05-8.
 */
public record PolicyContext(
    Path targetServiceDirectory,
    Path runsDirectory,
    String runId,
    boolean autoApprove
) {
    /** Where this run's real executor artifacts live: {@code runs/<runId>/artifacts/}. */
    public Path artifactsDirectory() {
        if (runsDirectory == null || runId == null) {
            return null;
        }
        return runsDirectory.resolve(runId).resolve("artifacts");
    }
}
