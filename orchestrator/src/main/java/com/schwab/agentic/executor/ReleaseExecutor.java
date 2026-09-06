package com.schwab.agentic.executor;

import com.schwab.agentic.engine.NodeExecutor;
import com.schwab.agentic.model.AuditEvent;
import com.schwab.agentic.model.NodeStatus;
import com.schwab.agentic.model.WorkflowNode;
import com.schwab.agentic.model.WorkflowState;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Makes no agent call. Release readiness is a deterministic check over what the audit
 * log actually recorded during this run:
 *
 * <ul>
 * <li>Validation passed (read from the VALIDATE node's exit gate outcome, not asked
 *     again here).
 * <li>No {@code POLICY_DENIED} event appears anywhere in the audit log for this run.
 * <li>Every node whose status history shows it entered {@code WAITING_APPROVAL} also has
 *     a matching {@code APPROVAL_GRANTED} event for that node id. A node is never
 *     declared to "require approval" by this class in advance; whether a node required
 *     one is read back from whether the engine actually put it into
 *     {@code WAITING_APPROVAL} at some point in this run, which is itself only possible
 *     because a policy decision (spec 05) said so. This class only checks that a node
 *     which asked for approval also received one, derived from the audit log rather than
 *     asserted.
 * </ul>
 *
 * Reads {@code validationPassed} and the audit log from a live {@link WorkflowState}
 * rather than frozen constructor arguments, since a registry-registered executor (the
 * real CLI) is constructed before VALIDATE has run at all; checking whether the VALIDATE
 * node's status is COMPLETED at {@link #execute} time is exactly the "VALIDATE node's
 * exit gate outcome" this class has always meant to check, just read live instead of
 * pre-computed.
 */
public final class ReleaseExecutor implements NodeExecutor {

    private final Path artifactsDirectory;
    private final WorkflowState workflowState;

    public ReleaseExecutor(Path artifactsDirectory, WorkflowState workflowState) {
        this.artifactsDirectory = artifactsDirectory;
        this.workflowState = workflowState;
    }

    @Override
    public ExecutionOutput execute(WorkflowNode node, Map<String, Object> context) {
        List<String> findings = new ArrayList<>();
        List<AuditEvent> auditLog = workflowState.getAuditLog();
        boolean validationPassed = workflowState.getStatus("VALIDATE") == NodeStatus.COMPLETED;

        if (!validationPassed) {
            findings.add("validation did not pass");
        }

        List<AuditEvent> policyDenials = auditLog.stream()
            .filter(event -> event.type() == AuditEvent.EventType.POLICY_DENIED)
            .toList();
        for (AuditEvent denial : policyDenials) {
            findings.add("policy denial recorded for node " + denial.nodeId() + ": " + denial.reason());
        }

        Set<String> nodesThatEnteredWaitingApproval = new LinkedHashSet<>();
        for (AuditEvent event : auditLog) {
            if (event.type() == AuditEvent.EventType.STATUS_CHANGE
                && event.to() == NodeStatus.WAITING_APPROVAL
                && event.nodeId() != null) {
                nodesThatEnteredWaitingApproval.add(event.nodeId());
            }
        }
        Set<String> nodesWithApprovalGranted = new LinkedHashSet<>();
        for (AuditEvent event : auditLog) {
            if (event.type() == AuditEvent.EventType.APPROVAL_GRANTED && event.nodeId() != null) {
                nodesWithApprovalGranted.add(event.nodeId());
            }
        }
        for (String nodeId : nodesThatEnteredWaitingApproval) {
            if (!nodesWithApprovalGranted.contains(nodeId)) {
                findings.add("node " + nodeId + " entered WAITING_APPROVAL but has no APPROVAL_GRANTED event");
            }
        }

        boolean releaseReady = findings.isEmpty();

        Path reportPath = artifactsDirectory.resolve("release-readiness.md");
        writeFile(reportPath, buildReport(releaseReady, findings));

        Map<String, Object> outputs = new LinkedHashMap<>();
        outputs.put("artifactPath", reportPath.toString());
        outputs.put("releaseReady", releaseReady);
        outputs.put("findings", findings);

        return new ExecutionOutput(releaseReady,
            releaseReady ? "release ready" : "not release ready: " + findings.size() + " finding(s)",
            outputs);
    }

    private String buildReport(boolean releaseReady, List<String> findings) {
        StringBuilder report = new StringBuilder();
        report.append("# Release readiness\n\n");
        report.append("**Result:** ").append(releaseReady ? "READY" : "NOT READY").append("\n\n");
        report.append("## Findings\n\n");
        if (findings.isEmpty()) {
            report.append("No findings.\n");
        } else {
            for (String finding : findings) {
                report.append("- ").append(finding).append('\n');
            }
        }
        return report.toString();
    }

    private void writeFile(Path path, String content) {
        try {
            Files.createDirectories(path.getParent());
            Files.writeString(path, content);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to write " + path, e);
        }
    }
}
