package com.schwab.agentic.graph;

import static com.schwab.agentic.Assertions.assertDoesNotThrow;
import static com.schwab.agentic.Assertions.assertEquals;
import static com.schwab.agentic.Assertions.assertThrows;
import static com.schwab.agentic.Assertions.assertTrue;

import com.schwab.agentic.model.NodeStatus;
import com.schwab.agentic.model.WorkflowNode;
import com.schwab.agentic.model.WorkflowState;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Covers spec 01's acceptance criteria for {@link WorkflowGraph}: cycle detection
 * (AC-01-1), dangling dependency detection (AC-01-2), downstream reachability on a
 * diamond shape (AC-01-3), and readyNodes computed against the default eight-node graph
 * (AC-01-4, AC-01-5, AC-01-6).
 */
public class WorkflowGraphTest {

    public void testCyclicGraphThrowsNamingTheNodesInTheCycle() {
        WorkflowNode a = TestGraphFixtures.node("A", Set.of("B"));
        WorkflowNode b = TestGraphFixtures.node("B", Set.of("A"));

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
            () -> WorkflowGraph.of(List.of(a, b)),
            "a two-node cycle must be rejected");
        assertTrue(error.getMessage().contains("A"), "error must name node A: " + error.getMessage());
        assertTrue(error.getMessage().contains("B"), "error must name node B: " + error.getMessage());
    }

    public void testDependencyOnUndeclaredNodeThrows() {
        WorkflowNode a = TestGraphFixtures.node("A", Set.of("NONEXISTENT"));

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
            () -> WorkflowGraph.of(List.of(a)),
            "a dependency on an undeclared node must be rejected");
        assertTrue(error.getMessage().contains("NONEXISTENT"),
            "error must name the undeclared node: " + error.getMessage());
    }

    public void testDuplicateNodeIdThrows() {
        WorkflowNode a1 = TestGraphFixtures.node("A", Set.of());
        WorkflowNode a2 = TestGraphFixtures.node("A", Set.of());

        assertThrows(IllegalArgumentException.class,
            () -> WorkflowGraph.of(List.of(a1, a2)),
            "a duplicate node id must be rejected");
    }

    public void testTwoIndependentRootsJoiningAtAThirdNodeThrows() {
        WorkflowNode rootA = TestGraphFixtures.node("ROOT_A", Set.of());
        WorkflowNode rootB = TestGraphFixtures.node("ROOT_B", Set.of());
        WorkflowNode join = TestGraphFixtures.node("JOIN", Set.of("ROOT_A", "ROOT_B"));

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
            () -> WorkflowGraph.of(List.of(rootA, rootB, join)),
            "a graph with two independent roots must be rejected: acyclic, no dangling"
                + " references, fully reachable, but no single entry point");
        assertTrue(error.getMessage().contains("ROOT_A"), "error must name ROOT_A: " + error.getMessage());
        assertTrue(error.getMessage().contains("ROOT_B"), "error must name ROOT_B: " + error.getMessage());
    }

    public void testOneRootFanningToTwoLeavesThatNeverRejoinThrows() {
        WorkflowNode root = TestGraphFixtures.node("ROOT", Set.of());
        WorkflowNode leafA = TestGraphFixtures.node("LEAF_A", Set.of("ROOT"));
        WorkflowNode leafB = TestGraphFixtures.node("LEAF_B", Set.of("ROOT"));

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
            () -> WorkflowGraph.of(List.of(root, leafA, leafB)),
            "a graph with two leaves that never rejoin must be rejected: acyclic, no"
                + " dangling references, fully reachable, but no single exit point");
        assertTrue(error.getMessage().contains("LEAF_A"), "error must name LEAF_A: " + error.getMessage());
        assertTrue(error.getMessage().contains("LEAF_B"), "error must name LEAF_B: " + error.getMessage());
    }

    public void testASingleNodeGraphIsBothEntryAndTerminalAndIsValid() {
        WorkflowNode onlyNode = TestGraphFixtures.node("ONLY", Set.of());

        assertDoesNotThrow(() -> WorkflowGraph.of(List.of(onlyNode)),
            "a single node with no dependencies is a valid graph: it is its own entry and terminal");
    }

    public void testDownstreamOfOnADiamondShapeReturnsExactlyTheDownstreamNodes() {
        WorkflowGraph graph = TestGraphFixtures.diamondGraph();

        Set<String> downstream = graph.downstreamOf("TOP");

        assertEquals(Set.of("LEFT", "RIGHT", "BOTTOM"), downstream, "downstream of TOP in a diamond");
    }

    public void testDownstreamOfALeafReturnsEmpty() {
        WorkflowGraph graph = TestGraphFixtures.diamondGraph();

        Set<String> downstream = graph.downstreamOf("BOTTOM");

        assertEquals(Set.of(), downstream, "a leaf node has no downstream nodes");
    }

    public void testDownstreamOfOnTheDefaultGraphMatchesTheSpecifiedSet() {
        WorkflowGraph graph = TestGraphFixtures.defaultSdlcGraph();

        Set<String> downstream = graph.downstreamOf("DESIGN");

        assertEquals(Set.of("IMPLEMENT", "TEST", "DOCUMENT", "VALIDATE", "RELEASE"), downstream,
            "downstreamOf(DESIGN) on the default graph");
    }

    public void testReadyNodesOnAFreshStateReturnsOnlyTheRootNode() {
        WorkflowGraph graph = TestGraphFixtures.defaultSdlcGraph();
        WorkflowState state = TestGraphFixtures.stateOver(graph);

        List<WorkflowNode> ready = graph.readyNodes(state.getStatuses());

        assertEquals(1, ready.size(), "expected exactly one ready node on a fresh state");
        assertEquals("REQUIREMENT", ready.get(0).id(), "the only ready node on a fresh state");
    }

    public void testReadyNodesAfterRequirementCompletesReturnsOnlyImpact() {
        WorkflowGraph graph = TestGraphFixtures.defaultSdlcGraph();
        WorkflowState state = TestGraphFixtures.stateOver(graph);
        completeNode(state, "REQUIREMENT");

        List<WorkflowNode> ready = graph.readyNodes(state.getStatuses());

        assertEquals(1, ready.size(), "expected exactly one ready node after REQUIREMENT completes");
        assertEquals("IMPACT", ready.get(0).id(), "the only ready node after REQUIREMENT completes");
    }

    public void testReadyNodesAfterDesignCompletesReturnsImplementTestAndDocumentTogether() {
        WorkflowGraph graph = TestGraphFixtures.defaultSdlcGraph();
        WorkflowState state = TestGraphFixtures.stateOver(graph);
        completeNode(state, "REQUIREMENT");
        completeNode(state, "IMPACT");
        completeNode(state, "DESIGN");

        List<WorkflowNode> ready = graph.readyNodes(state.getStatuses());
        Set<String> readyIds = ready.stream().map(WorkflowNode::id).collect(java.util.stream.Collectors.toSet());

        assertEquals(Set.of("IMPLEMENT", "TEST", "DOCUMENT"), readyIds,
            "expected IMPLEMENT, TEST and DOCUMENT to fan out together after DESIGN completes");
    }

    public void testValidateIsNotReadyUntilAllThreeFanOutNodesComplete() {
        WorkflowGraph graph = TestGraphFixtures.defaultSdlcGraph();
        WorkflowState state = TestGraphFixtures.stateOver(graph);
        completeNode(state, "REQUIREMENT");
        completeNode(state, "IMPACT");
        completeNode(state, "DESIGN");
        completeNode(state, "IMPLEMENT");
        completeNode(state, "TEST");

        List<WorkflowNode> ready = graph.readyNodes(state.getStatuses());
        Set<String> readyIds = ready.stream().map(WorkflowNode::id).collect(java.util.stream.Collectors.toSet());

        assertTrue(!readyIds.contains("VALIDATE"),
            "VALIDATE must not be ready until DOCUMENT also completes, ready was: " + readyIds);

        completeNode(state, "DOCUMENT");
        List<WorkflowNode> readyAfterAll = graph.readyNodes(state.getStatuses());
        assertEquals(1, readyAfterAll.size(), "expected only VALIDATE to be ready once all three complete");
        assertEquals("VALIDATE", readyAfterAll.get(0).id(), "VALIDATE must become ready once the fan-out joins");
    }

    public void testReadyNodesThrowsWhenStatusesMapDoesNotCoverEveryNode() {
        WorkflowGraph graph = TestGraphFixtures.defaultSdlcGraph();

        assertThrows(IllegalArgumentException.class,
            () -> graph.readyNodes(Map.of("REQUIREMENT", NodeStatus.PENDING)),
            "readyNodes must reject a statuses map missing entries for some nodes in the graph");
    }

    public void testTopologicalOrderPlacesEveryNodeAfterItsDependencies() {
        WorkflowGraph graph = TestGraphFixtures.defaultSdlcGraph();
        List<String> order = graph.topologicalOrder();

        java.util.Map<String, Integer> position = new java.util.HashMap<>();
        for (int i = 0; i < order.size(); i++) {
            position.put(order.get(i), i);
        }
        for (String id : order) {
            WorkflowNode node = graph.getNode(id);
            for (String dependencyId : node.dependsOn()) {
                assertTrue(position.get(dependencyId) < position.get(id),
                    dependencyId + " must appear before " + id + " in topological order");
            }
        }
    }

    public void testToMermaidContainsEveryNodeIdAndEdge() {
        WorkflowGraph graph = TestGraphFixtures.diamondGraph();
        Map<String, NodeStatus> statuses = Map.of(
            "TOP", NodeStatus.COMPLETED, "LEFT", NodeStatus.RUNNING,
            "RIGHT", NodeStatus.PENDING, "BOTTOM", NodeStatus.PENDING);

        String mermaid = graph.toMermaid(statuses);

        assertTrue(mermaid.contains("flowchart TD"), "mermaid output must declare a flowchart");
        for (String id : Set.of("TOP", "LEFT", "RIGHT", "BOTTOM")) {
            assertTrue(mermaid.contains(id), "mermaid output must mention node " + id);
        }
        assertTrue(mermaid.contains("TOP --> LEFT"), "mermaid output must contain the TOP -> LEFT edge");
        assertTrue(mermaid.contains("TOP --> RIGHT"), "mermaid output must contain the TOP -> RIGHT edge");
        assertTrue(mermaid.contains("COMPLETED"), "mermaid output must reflect the supplied status for TOP");
    }

    public void testLoadFromJsonRejectsAMalformedWorkflowRoot() {
        assertThrows(IllegalArgumentException.class,
            () -> WorkflowGraph.loadFromJson("[]"),
            "a JSON array instead of an object must be rejected");
    }

    private static void completeNode(WorkflowState state, String nodeId) {
        state.transition(nodeId, NodeStatus.RUNNING, "system", "test setup");
        state.transition(nodeId, NodeStatus.COMPLETED, "system", "test setup");
    }
}
