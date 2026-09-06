package com.schwab.agentic.engine;

import com.schwab.agentic.graph.WorkflowGraph;
import com.schwab.agentic.model.AuditEvent;
import com.schwab.agentic.model.Evidence;
import com.schwab.agentic.model.NodeStatus;
import com.schwab.agentic.model.RequirementSpec;
import com.schwab.agentic.model.WorkflowNode;
import com.schwab.agentic.model.WorkflowState;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Re-planning is graph reachability, nothing else (CLAUDE.md rule 7). When an amended
 * requirement changes what a node produced, {@link #replan} computes the transitive
 * downstream closure of that node from {@link WorkflowGraph#downstreamOf}, invalidates
 * exactly the members of that closure that are currently COMPLETED, and leaves every
 * other node, upstream or not yet reached, untouched. No agent, and no code in this
 * class, decides by judgment what "should" re-run; the graph's own edges are the only
 * input to that decision.
 *
 * Four things happen to an invalidated node, in this order, because each one depends on
 * the one before it having already happened:
 * <ol>
 * <li>Its current write-path content is archived to {@code runs/<runId>/archive/rev<n>/<nodeId>/}
 * (never deleted), so the lineage of what an earlier revision actually produced stays
 * inspectable after the re-plan, per this project's explicit correction over the spec
 * doc's own {@code superseded/rev<n>/} path.</li>
 * <li>Its checkpoint, taken before its own first attempt against the prior revision, is
 * restored via {@link Checkpoint#restoreFromDisk}: the COMPLETED to ROLLED_BACK edge
 * {@link WorkflowEngine} never exercises (a completed node never fails its own exit
 * gate), so a re-run starts from the same clean state that node's own first attempt did,
 * not stacked on top of stale output.</li>
 * <li>Every evidence record it produced is revoked via {@link WorkflowState#revokeEvidenceFrom},
 * a real removal, not a flag, so a re-run's exit gate can never be satisfied by
 * pre-amendment proof.</li>
 * <li>Its status moves COMPLETED to INVALIDATED to PENDING and its retry count resets,
 * so the scheduler picks it back up as a fresh attempt on the engine's next pass.</li>
 * </ol>
 *
 * Approvals granted against the prior revision are not touched by this class at all.
 * {@link ApprovalStore#hasValidApproval} already keys every record by
 * {@code (nodeId, requirementRevision)}, so the moment {@link WorkflowState#replaceRequirementSpec}
 * bumps the revision (step one of {@link #replan}), every approval recorded against the
 * old revision stops satisfying that check on its own: this is spec 05's own mechanism
 * doing spec 06's job, not a new one.
 *
 * A node currently RUNNING when a re-plan arrives is deliberately left alone even if it
 * falls inside the downstream set: {@link NodeStatus#canTransitionTo} has no legal edge
 * from RUNNING to INVALIDATED (only COMPLETED, FAILED, or ROLLED_BACK), because an
 * in-flight attempt has not yet produced anything this class could safely archive,
 * checkpoint-restore, or revoke evidence for. The chosen, deterministic policy is: let it
 * finish its current attempt against whatever context it was given, then let whatever
 * outcome it reaches (COMPLETED, FAILED, or otherwise) stand; it is not retroactively
 * invalidated by this re-plan and is not a target of a later one unless a future amendment
 * names it or something upstream of it again. This is a real, documented limitation
 * (spec 06's AC-06-9), not silently undefined behavior.
 */
public final class Replanner {

    private final WorkflowGraph graph;
    private final Checkpoint checkpoint;
    private final Path targetServiceDirectory;
    private final Path runsDirectory;

    public Replanner(WorkflowGraph graph, Checkpoint checkpoint, Path targetServiceDirectory, Path runsDirectory) {
        this.graph = graph;
        this.checkpoint = checkpoint;
        this.targetServiceDirectory = targetServiceDirectory;
        this.runsDirectory = runsDirectory;
    }

    /**
     * Re-plans {@code state} after {@code amended} replaces the requirement spec that was
     * in effect when {@code changedNodeId} last ran. Returns the set of node ids actually
     * invalidated (empty if {@code changedNodeId} has no COMPLETED downstream nodes, in
     * which case the re-plan counter is not incremented at all: AC-06-8's "invalidates
     * nothing and does not increment the re-plan counter" is this method returning before
     * touching the counter, not a special case bolted on afterward).
     */
    public Set<String> replan(WorkflowState state, String changedNodeId, RequirementSpec amended) {
        int previousRevision = state.getRequirementSpec().revision();
        state.replaceRequirementSpec(amended);

        Set<String> downstream = graph.downstreamOf(changedNodeId);
        Set<String> invalidated = new LinkedHashSet<>();
        Set<String> preserved = new LinkedHashSet<>();
        for (String nodeId : downstream) {
            if (state.getStatus(nodeId) == NodeStatus.COMPLETED) {
                invalidated.add(nodeId);
            } else {
                preserved.add(nodeId);
            }
        }
        for (String nodeId : state.getNodes().keySet()) {
            if (!downstream.contains(nodeId)) {
                preserved.add(nodeId);
            }
        }

        if (invalidated.isEmpty()) {
            return Set.of();
        }

        List<Evidence> allRevokedEvidence = new ArrayList<>();
        for (String nodeId : invalidated) {
            WorkflowNode node = state.getNode(nodeId);
            String archiveLabel = "rev" + previousRevision + "/" + nodeId;
            if (node.isCheckpointed() && targetServiceDirectory != null && runsDirectory != null) {
                checkpoint.archive(targetServiceDirectory, runsDirectory, state.getRunId(), archiveLabel,
                    node.writePaths());
                checkpoint.restoreFromDisk(targetServiceDirectory, runsDirectory, state.getRunId(), nodeId,
                    node.writePaths());
            }

            List<Evidence> revokedForThisNode = state.revokeEvidenceFrom(Set.of(nodeId));
            allRevokedEvidence.addAll(revokedForThisNode);

            state.transition(nodeId, NodeStatus.INVALIDATED, "replanner",
                "invalidated by re-plan: downstream of " + changedNodeId + ", amended to revision "
                    + amended.revision());
            state.resetRetryCount(nodeId);
            state.transition(nodeId, NodeStatus.PENDING, "replanner",
                "returned to PENDING for re-execution against revision " + amended.revision());
        }

        state.incrementReplanCount();

        Map<String, Object> details = new LinkedHashMap<>();
        details.put("changedNodeId", changedNodeId);
        details.put("previousRevision", (double) previousRevision);
        details.put("newRevision", (double) amended.revision());
        details.put("invalidated", new ArrayList<Object>(invalidated));
        details.put("preserved", new ArrayList<Object>(preserved.stream().sorted().toList()));
        details.put("evidenceRevoked", evidenceRevokedSummary(allRevokedEvidence));
        state.record(AuditEvent.EventType.REPLAN, "replanner",
            "re-plan after amending requirement to revision " + amended.revision()
                + ": invalidated " + invalidated.size() + " node(s) downstream of " + changedNodeId,
            details);

        return Set.copyOf(invalidated);
    }

    private List<Object> evidenceRevokedSummary(List<Evidence> revoked) {
        List<Object> summary = new ArrayList<>();
        for (Evidence item : revoked) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("acceptanceCriterionId", item.acceptanceCriterionId());
            entry.put("producedByNode", item.producedByNode());
            entry.put("passed", item.passed());
            summary.add(entry);
        }
        return summary;
    }
}
