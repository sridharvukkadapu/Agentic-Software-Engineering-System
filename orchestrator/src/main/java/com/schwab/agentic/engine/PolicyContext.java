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
 * for gates. {@code approvalStore}, when non-null, lets a pre-execution approval rule
 * check whether a currently valid approval already exists for this node at the run's
 * current requirement revision before requiring a new one; a null store (as in most of
 * this class's own unit tests, which are not exercising the approval-clearing behavior)
 * makes every such rule behave as if no approval has ever been granted, which is the
 * safe default. {@code autoApprove} is true only for {@code --auto-approve} runs, which
 * are permitted only in {@code --replay} mode and are always stamped into the run report,
 * per AC-05-8.
 */
public record PolicyContext(
    Path targetServiceDirectory,
    Path runsDirectory,
    String runId,
    boolean autoApprove,
    ApprovalStore approvalStore
) {
    public PolicyContext(Path targetServiceDirectory, Path runsDirectory, String runId, boolean autoApprove) {
        this(targetServiceDirectory, runsDirectory, runId, autoApprove, null);
    }

    /** Where this run's real executor artifacts live: {@code runs/<runId>/artifacts/}. */
    public Path artifactsDirectory() {
        if (runsDirectory == null || runId == null) {
            return null;
        }
        return runsDirectory.resolve(runId).resolve("artifacts");
    }

    /** Whether a currently valid approval already exists for {@code nodeId} at {@code requirementRevision}. */
    public boolean hasValidApproval(String nodeId, int requirementRevision) {
        return approvalStore != null && approvalStore.hasValidApproval(nodeId, requirementRevision);
    }
}
