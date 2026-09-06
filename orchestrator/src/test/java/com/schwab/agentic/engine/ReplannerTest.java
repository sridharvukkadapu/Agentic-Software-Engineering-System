package com.schwab.agentic.engine;

import static com.schwab.agentic.Assertions.assertEquals;
import static com.schwab.agentic.Assertions.assertFalse;
import static com.schwab.agentic.Assertions.assertTrue;

import com.schwab.agentic.graph.WorkflowGraph;
import com.schwab.agentic.model.AcceptanceCriterion;
import com.schwab.agentic.model.AuditEvent;
import com.schwab.agentic.model.Evidence;
import com.schwab.agentic.model.NodeStatus;
import com.schwab.agentic.model.RequirementSpec;
import com.schwab.agentic.model.RiskLevel;
import com.schwab.agentic.model.WorkflowNode;
import com.schwab.agentic.model.WorkflowState;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Spec 06's required tests. The graph mirrors the assignment's own shape:
 * REQUIREMENT -&gt; IMPACT -&gt; DESIGN -&gt; {IMPLEMENT, TEST, DOCUMENT} -&gt; VALIDATE -&gt; RELEASE.
 * Every test drives a real {@link Replanner} against a real {@link WorkflowState} and real
 * files on disk (checkpoints taken with the real {@link Checkpoint} class, real evidence
 * added to real state), never a hand-narrated audit event, so each assertion is checking
 * what {@link Replanner} actually did rather than what it claims to have done.
 */
public class ReplannerTest {

    private static WorkflowGraph sdlcShapedGraph() {
        WorkflowNode requirement = TestEngineFixtures.node("REQUIREMENT", Set.of(), 2);
        WorkflowNode impact = TestEngineFixtures.node("IMPACT", Set.of("REQUIREMENT"), 2);
        WorkflowNode design = TestEngineFixtures.nodeWithWritePaths("DESIGN", Set.of("IMPACT"), 2, Set.of("design"));
        WorkflowNode implement = TestEngineFixtures.nodeWithWritePaths("IMPLEMENT", Set.of("DESIGN"), 2, Set.of("src"));
        WorkflowNode test = TestEngineFixtures.nodeWithWritePaths("TEST", Set.of("DESIGN"), 2, Set.of("test"));
        WorkflowNode document = TestEngineFixtures.nodeWithWritePaths("DOCUMENT", Set.of("DESIGN"), 2, Set.of("docs"));
        WorkflowNode validate = TestEngineFixtures.node("VALIDATE", Set.of("IMPLEMENT", "TEST", "DOCUMENT"), 2);
        WorkflowNode release = TestEngineFixtures.node("RELEASE", Set.of("VALIDATE"), 2);
        return WorkflowGraph.of(List.of(requirement, impact, design, implement, test, document, validate, release));
    }

    private static RequirementSpec revision(int revision) {
        return new RequirementSpec("REQ-1", revision, "raw text rev " + revision, "normalized rev " + revision,
            List.of(new AcceptanceCriterion("AC-1", "It works", RiskLevel.HIGH)));
    }

    /** Completes every node in {@code nodeIds}, in the order given, using PENDING -> RUNNING -> COMPLETED. */
    private static void completeNodes(WorkflowState state, String... nodeIds) {
        for (String nodeId : nodeIds) {
            state.transition(nodeId, NodeStatus.RUNNING, "test", "starting " + nodeId);
            state.transition(nodeId, NodeStatus.COMPLETED, "test", "completed " + nodeId);
        }
    }

    // AC-06-1 / AC-06-7: amending after DESIGN invalidates exactly downstreamOf(DESIGN), node by node.
    // The run has already reached RELEASE COMPLETED once, matching AC-06-1's own premise: a
    // requirement amendment arriving after a full run, not mid-run.
    public void testReplacingRequirementSpecInvalidatesExactlyDownstreamOfAffectedAndNoMore() {
        WorkflowGraph graph = sdlcShapedGraph();
        WorkflowState state = new WorkflowState("RUN-1", revision(1), graph.getAllNodes());
        completeNodes(state, "REQUIREMENT", "IMPACT", "DESIGN", "IMPLEMENT", "TEST", "DOCUMENT", "VALIDATE",
            "RELEASE");

        Replanner replanner = new Replanner(graph, new Checkpoint(), null, null);
        Set<String> invalidated = replanner.replan(state, "DESIGN", revision(2));

        Set<String> expected = graph.downstreamOf("DESIGN");
        assertEquals(expected, invalidated, "invalidated set must equal exactly downstreamOf(DESIGN)");
        for (String nodeId : expected) {
            assertEquals(NodeStatus.PENDING, state.getStatus(nodeId),
                nodeId + " must be PENDING after re-plan, ready for re-execution: " + state.getStatus(nodeId));
        }
        assertTrue(invalidated.contains("VALIDATE"), "VALIDATE is downstream of DESIGN and was COMPLETED");
        assertTrue(invalidated.contains("RELEASE"), "RELEASE is downstream of DESIGN and was COMPLETED");
    }

    // AC-06-2: a completed node NOT downstream of the change stays COMPLETED and keeps its evidence,
    // verified by reading the artifact off disk.
    public void testCompletedNodeNotDownstreamOfChangeStaysCompletedAndKeepsItsEvidenceOnDisk() throws IOException {
        Path targetServiceDir = Files.createTempDirectory("replanner-target-service");
        Path runsDir = Files.createTempDirectory("replanner-runs");
        Path requirementArtifact = targetServiceDir.resolve("requirement-artifact.txt");

        WorkflowGraph graph = sdlcShapedGraph();
        WorkflowState state = new WorkflowState("RUN-1", revision(1), graph.getAllNodes());

        // REQUIREMENT and IMPACT are not checkpointed in this fixture (no declared write
        // paths), matching TestEngineFixtures.node; their real output for this test is a
        // file this test writes directly, standing in for whatever artifact a real
        // executor would have written, so "keeps its evidence" can be checked by reading
        // a real file back rather than trusting the audit log.
        Files.writeString(requirementArtifact, "REQUIREMENT's real artifact, must survive the re-plan");
        Checkpoint checkpointTool = new Checkpoint();
        for (String checkpointedNodeId : List.of("DESIGN", "IMPLEMENT", "TEST", "DOCUMENT")) {
            checkpointTool.take(targetServiceDir, runsDir, "RUN-1", checkpointedNodeId,
                graph.getNode(checkpointedNodeId).writePaths());
        }
        completeNodes(state, "REQUIREMENT", "IMPACT", "DESIGN", "IMPLEMENT", "TEST", "DOCUMENT");
        state.addEvidence(new Evidence(Evidence.Origin.EXECUTED, "AC-1", true, "REQUIREMENT's own evidence",
            "test", "REQUIREMENT", requirementArtifact.toString(), Instant.now()));

        Replanner replanner = new Replanner(graph, checkpointTool, targetServiceDir, runsDir);
        replanner.replan(state, "DESIGN", revision(2));

        assertEquals(NodeStatus.COMPLETED, state.getStatus("REQUIREMENT"),
            "REQUIREMENT is not downstream of DESIGN and must stay COMPLETED");
        assertEquals(NodeStatus.COMPLETED, state.getStatus("IMPACT"),
            "IMPACT is not downstream of DESIGN and must stay COMPLETED");
        assertTrue(state.getEvidence().stream().anyMatch(item -> item.producedByNode().equals("REQUIREMENT")),
            "REQUIREMENT's evidence must survive a re-plan that never touched REQUIREMENT");
        assertEquals("REQUIREMENT's real artifact, must survive the re-plan", Files.readString(requirementArtifact),
            "reading REQUIREMENT's real artifact off disk must show it untouched by the re-plan");
    }

    // AC-06-4 (part 1): evidence from an invalidated node cannot satisfy the re-run's exit gate.
    public void testEvidenceFromAnInvalidatedNodeCannotSatisfyTheRerunsExitGate() {
        WorkflowGraph graph = sdlcShapedGraph();
        WorkflowState state = new WorkflowState("RUN-1", revision(1), graph.getAllNodes());
        completeNodes(state, "REQUIREMENT", "IMPACT", "DESIGN", "IMPLEMENT", "TEST", "DOCUMENT");
        state.addEvidence(new Evidence(Evidence.Origin.EXECUTED, "AC-1", true, "TEST's pre-amendment evidence",
            "./gradlew test", "TEST", "runs/RUN-1/artifacts/test-results.log", Instant.now()));

        assertTrue(state.getEvidence().stream().anyMatch(item -> item.producedByNode().equals("TEST")),
            "sanity check: TEST's evidence exists before the re-plan");

        Replanner replanner = new Replanner(graph, new Checkpoint(), null, null);
        replanner.replan(state, "DESIGN", revision(2));

        assertFalse(state.getEvidence().stream().anyMatch(item -> item.producedByNode().equals("TEST")),
            "TEST's pre-amendment evidence must be revoked, not merely marked, so a gate reading"
                + " state.getEvidence() after the re-plan can never see it again");
    }

    // AC-06-5: an approval granted at revision 1 is re-requested after the re-plan (via spec 05's
    // own (nodeId, requirementRevision) keying, exercised here through the real ApprovalStore).
    public void testApprovalGrantedAtRevisionOneIsRerequestedAfterTheReplan() {
        WorkflowGraph graph = sdlcShapedGraph();
        WorkflowState state = new WorkflowState("RUN-1", revision(1), graph.getAllNodes());
        completeNodes(state, "REQUIREMENT", "IMPACT", "DESIGN", "IMPLEMENT", "TEST", "DOCUMENT");

        ApprovalStore approvalStore = new ApprovalStore();
        approvalStore.record(new ApprovalRecord("IMPLEMENT", 1, ApprovalRecord.Decision.APPROVED,
            "alice", "looked fine at rev 1", Instant.now()));
        assertTrue(approvalStore.hasValidApproval("IMPLEMENT", 1),
            "sanity check: the rev-1 approval is valid at rev 1");

        Replanner replanner = new Replanner(graph, new Checkpoint(), null, null);
        replanner.replan(state, "DESIGN", revision(2));

        assertFalse(approvalStore.hasValidApproval("IMPLEMENT", state.getRequirementSpec().revision()),
            "the rev-1 approval must not satisfy hasValidApproval at the new revision;"
                + " IMPLEMENT must be re-requested, not carried over");
    }

    // AC-06-3 / AC-06-7: the REPLAN audit event carries both the invalidated and preserved node lists,
    // computed from the graph (changing the graph shape changes the lists with no code edit).
    public void testReplanAuditEventCarriesBothInvalidatedAndPreservedNodeLists() {
        WorkflowGraph graph = sdlcShapedGraph();
        WorkflowState state = new WorkflowState("RUN-1", revision(1), graph.getAllNodes());
        completeNodes(state, "REQUIREMENT", "IMPACT", "DESIGN", "IMPLEMENT", "TEST", "DOCUMENT", "VALIDATE",
            "RELEASE");

        Replanner replanner = new Replanner(graph, new Checkpoint(), null, null);
        replanner.replan(state, "DESIGN", revision(2));

        List<AuditEvent> replanEvents = state.getAuditLog().stream()
            .filter(event -> event.type() == AuditEvent.EventType.REPLAN)
            .toList();
        assertEquals(1, replanEvents.size(), "exactly one REPLAN event must be recorded");

        Map<String, Object> details = replanEvents.get(0).details();
        @SuppressWarnings("unchecked")
        List<String> invalidatedList = (List<String>) details.get("invalidated");
        @SuppressWarnings("unchecked")
        List<String> preservedList = (List<String>) details.get("preserved");

        assertEquals(Set.copyOf(graph.downstreamOf("DESIGN")), Set.copyOf(invalidatedList),
            "the REPLAN event's invalidated list must equal downstreamOf(DESIGN)");
        assertTrue(preservedList.contains("REQUIREMENT"), "preserved must name REQUIREMENT");
        assertTrue(preservedList.contains("IMPACT"), "preserved must name IMPACT");
        assertTrue(invalidatedList.contains("VALIDATE"), "invalidated must name VALIDATE (downstream of DESIGN, was COMPLETED)");
        assertTrue(invalidatedList.contains("RELEASE"), "invalidated must name RELEASE (downstream of DESIGN, was COMPLETED)");

        // AC-06-7's own assertion: change the graph shape (TEST now depends on IMPACT
        // directly instead of DESIGN, so TEST is no longer downstream of DESIGN) and
        // confirm the invalidated list changes accordingly, with no edit to Replanner
        // itself. TEST still depends on something (IMPACT), keeping the graph's
        // single-root-node invariant intact.
        WorkflowNode requirement = TestEngineFixtures.node("REQUIREMENT", Set.of(), 2);
        WorkflowNode impact = TestEngineFixtures.node("IMPACT", Set.of("REQUIREMENT"), 2);
        WorkflowNode design = TestEngineFixtures.nodeWithWritePaths("DESIGN", Set.of("IMPACT"), 2, Set.of("design"));
        WorkflowNode implement = TestEngineFixtures.nodeWithWritePaths("IMPLEMENT", Set.of("DESIGN"), 2, Set.of("src"));
        WorkflowNode test = TestEngineFixtures.nodeWithWritePaths("TEST", Set.of("IMPACT"), 2, Set.of("test"));
        WorkflowNode document = TestEngineFixtures.nodeWithWritePaths("DOCUMENT", Set.of("DESIGN"), 2, Set.of("docs"));
        WorkflowNode validate = TestEngineFixtures.node("VALIDATE", Set.of("IMPLEMENT", "TEST", "DOCUMENT"), 2);
        WorkflowNode release = TestEngineFixtures.node("RELEASE", Set.of("VALIDATE"), 2);
        WorkflowGraph reshapedGraph = WorkflowGraph.of(
            List.of(requirement, impact, design, implement, test, document, validate, release));

        WorkflowState reshapedState = new WorkflowState("RUN-2", revision(1), reshapedGraph.getAllNodes());
        completeNodes(reshapedState, "REQUIREMENT", "IMPACT", "DESIGN", "IMPLEMENT", "TEST", "DOCUMENT");
        Replanner reshapedReplanner = new Replanner(reshapedGraph, new Checkpoint(), null, null);
        // targetServiceDirectory and runsDirectory are both null here, so Replanner never
        // attempts a checkpoint archive or restore for this graph (see the null-check in
        // Replanner.replan), which is fine: this part of the test only exercises the
        // graph-shape-drives-the-invalidated-set behavior, not checkpointing.
        Set<String> reshapedInvalidated = reshapedReplanner.replan(reshapedState, "DESIGN", revision(2));

        assertFalse(reshapedInvalidated.contains("TEST"),
            "with TEST's dependency on DESIGN removed, TEST is no longer downstream of DESIGN and must"
                + " not be invalidated, purely because the graph shape changed, not because of any code edit");
    }

    // AC-06-6: archived artifacts from revision 1 still exist on disk after the revision 2 run completes.
    public void testArchivedArtifactsFromRevisionOneStillExistOnDiskAfterRevisionTwoRunCompletes() throws IOException {
        Path targetServiceDir = Files.createTempDirectory("replanner-target-service");
        Path runsDir = Files.createTempDirectory("replanner-runs");
        Path implementSourceFile = targetServiceDir.resolve("src/Service.java");
        Files.createDirectories(implementSourceFile.getParent());
        Files.writeString(implementSourceFile, "revision 1's real implementation");

        WorkflowGraph graph = sdlcShapedGraph();
        WorkflowState state = new WorkflowState("RUN-1", revision(1), graph.getAllNodes());

        // Checkpoint every checkpointed node before its (simulated) first attempt,
        // exactly as WorkflowEngine does before a node's real attempt, then complete them.
        Checkpoint checkpointTool = new Checkpoint();
        for (String checkpointedNodeId : List.of("DESIGN", "IMPLEMENT", "TEST", "DOCUMENT")) {
            checkpointTool.take(targetServiceDir, runsDir, "RUN-1", checkpointedNodeId,
                graph.getNode(checkpointedNodeId).writePaths());
        }
        completeNodes(state, "REQUIREMENT", "IMPACT", "DESIGN", "IMPLEMENT", "TEST", "DOCUMENT");

        Replanner replanner = new Replanner(graph, checkpointTool, targetServiceDir, runsDir);
        replanner.replan(state, "DESIGN", revision(2));

        // Simulate revision 2's IMPLEMENT re-run writing new content, standing in for a
        // real executor's second attempt against the amended requirement.
        Files.writeString(implementSourceFile, "revision 2's real implementation, replacing revision 1's");
        state.transition("IMPLEMENT", NodeStatus.RUNNING, "test", "revision 2 attempt starting");
        state.transition("IMPLEMENT", NodeStatus.COMPLETED, "test", "revision 2 attempt completed");

        Path archivedFile = runsDir.resolve("RUN-1").resolve("archive").resolve("rev1").resolve("IMPLEMENT")
            .resolve("src/Service.java");
        assertTrue(Files.isRegularFile(archivedFile),
            "revision 1's archived IMPLEMENT artifact must still exist on disk at " + archivedFile
                + " after the revision 2 run completes");
        assertEquals("revision 1's real implementation", Files.readString(archivedFile),
            "the archived file must hold revision 1's real content, not revision 2's overwrite");
        assertEquals("revision 2's real implementation, replacing revision 1's", Files.readString(implementSourceFile),
            "the working tree file must hold revision 2's real content; archiving must not have left"
                + " revision 1's content in place of a fresh re-run");
    }

    // AC-06-8: amending a node with no downstream nodes invalidates nothing and does not increment
    // the re-plan counter.
    public void testAmendingANodeWithNoDownstreamNodesInvalidatesNothingAndDoesNotIncrementReplanCounter() {
        WorkflowGraph graph = sdlcShapedGraph();
        WorkflowState state = new WorkflowState("RUN-1", revision(1), graph.getAllNodes());
        completeNodes(state, "REQUIREMENT", "IMPACT", "DESIGN", "IMPLEMENT", "TEST", "DOCUMENT", "VALIDATE",
            "RELEASE");
        assertEquals(0, state.getReplanCount(), "sanity check: re-plan counter starts at zero");

        Replanner replanner = new Replanner(graph, new Checkpoint(), null, null);
        Set<String> invalidated = replanner.replan(state, "RELEASE", revision(2));

        assertTrue(invalidated.isEmpty(), "RELEASE has no downstream nodes, so nothing can be invalidated");
        assertEquals(0, state.getReplanCount(),
            "the re-plan counter must not increment when nothing was actually invalidated");
        assertEquals(NodeStatus.COMPLETED, state.getStatus("RELEASE"),
            "RELEASE itself is never invalidated by amending it; only its downstream nodes would be, and it has none");
        assertTrue(state.getAuditLog().stream().noneMatch(event -> event.type() == AuditEvent.EventType.REPLAN),
            "no REPLAN audit event should be recorded for a re-plan that invalidated nothing");
    }

    // AC-06-9: a re-plan while a node is RUNNING is handled deterministically: it is left alone,
    // not forcibly invalidated, since NodeStatus has no legal RUNNING -> INVALIDATED edge.
    public void testReplanWhileANodeIsRunningLeavesItAloneRatherThanForciblyInvalidatingIt() {
        WorkflowGraph graph = sdlcShapedGraph();
        WorkflowState state = new WorkflowState("RUN-1", revision(1), graph.getAllNodes());
        completeNodes(state, "REQUIREMENT", "IMPACT", "DESIGN");
        state.transition("IMPLEMENT", NodeStatus.RUNNING, "test", "IMPLEMENT's attempt is in flight");

        Replanner replanner = new Replanner(graph, new Checkpoint(), null, null);
        Set<String> invalidated = replanner.replan(state, "DESIGN", revision(2));

        assertFalse(invalidated.contains("IMPLEMENT"),
            "a RUNNING node must not appear in the invalidated set: there is no legal"
                + " RUNNING -> INVALIDATED transition, and forcing one would corrupt the status model");
        assertEquals(NodeStatus.RUNNING, state.getStatus("IMPLEMENT"),
            "IMPLEMENT must remain RUNNING, left to finish its in-flight attempt deterministically");
    }
}
