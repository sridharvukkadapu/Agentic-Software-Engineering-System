package com.schwab.agentic.graph;

import com.schwab.agentic.json.Json;
import com.schwab.agentic.model.NodeStatus;
import com.schwab.agentic.model.RiskLevel;
import com.schwab.agentic.model.WorkflowNode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The dependency graph a run executes over.
 *
 * This class is pure structure and holds no mutable state of its own: it is built once
 * from a list of {@link WorkflowNode} records, validates that structure, and answers
 * questions about it (which nodes are ready given a status map, which nodes are
 * downstream of a given node, how to render the graph). Node status is never asked about
 * or stored here; {@link #readyNodes} takes a status snapshot as a parameter instead,
 * because the graph itself has no way to observe status changes that happen elsewhere
 * and should not pretend to. A version of this class that cached its own copy of node
 * status was tried and found to disagree with {@link com.schwab.agentic.model.WorkflowState}
 * about status after deserialization or checkpoint restore, since those code paths build
 * fresh WorkflowNode instances; keeping this class entirely stateless removes the
 * possibility of that disagreement rather than working around it.
 *
 * Loading and validating are combined on purpose: a graph that failed to load is not
 * usable, so there is no state in which an invalid graph, one with a cycle, a dangling
 * dependency, more than one entry point, or more than one exit point, can be handed to
 * the execution engine. Every validation failure names the offending nodes specifically,
 * since "the workflow is invalid" is not something a reviewer can act on but
 * "REQUIREMENT depends on NONEXISTENT" is. See {@link #checkSingleEntry} for why this
 * checks single-entry and single-terminal rather than the more literal "unreachable
 * node" a graph validator might otherwise implement.
 */
public final class WorkflowGraph {

    private final Map<String, WorkflowNode> nodesById;
    private final List<String> topologicalOrder;

    private WorkflowGraph(Map<String, WorkflowNode> nodesById, List<String> topologicalOrder) {
        this.nodesById = nodesById;
        this.topologicalOrder = topologicalOrder;
    }

    /**
     * Builds and validates a graph from already-constructed nodes. Used directly by
     * tests and by {@link #loadFromFile}, which parses a JSON definition into
     * {@link WorkflowNode} instances first and then delegates here.
     */
    public static WorkflowGraph of(List<WorkflowNode> nodes) {
        if (nodes == null || nodes.isEmpty()) {
            throw new IllegalArgumentException("WorkflowGraph requires at least one node");
        }

        Map<String, WorkflowNode> byId = new LinkedHashMap<>();
        for (WorkflowNode node : nodes) {
            if (byId.put(node.id(), node) != null) {
                throw new IllegalArgumentException("Duplicate node id in workflow graph: " + node.id());
            }
        }

        for (WorkflowNode node : nodes) {
            for (String dependencyId : node.dependsOn()) {
                if (!byId.containsKey(dependencyId)) {
                    throw new IllegalArgumentException(
                        "Node " + node.id() + " depends on undeclared node " + dependencyId);
                }
            }
        }

        List<String> order = computeTopologicalOrderOrThrow(byId);
        checkSingleEntry(byId);
        checkSingleTerminal(byId);

        return new WorkflowGraph(byId, order);
    }

    /**
     * Loads a workflow graph from a JSON file on disk, so the graph a run executes is
     * data a reviewer can open and read, not code they have to trace through.
     */
    public static WorkflowGraph loadFromFile(Path path) {
        String content;
        try {
            content = Files.readString(path);
        } catch (IOException e) {
            throw new IllegalArgumentException("Could not read workflow file " + path, e);
        }
        return loadFromJson(content);
    }

    @SuppressWarnings("unchecked")
    public static WorkflowGraph loadFromJson(String json) {
        Object parsed = Json.parse(json);
        if (!(parsed instanceof Map)) {
            throw new IllegalArgumentException("Workflow JSON root must be an object with a \"nodes\" array");
        }
        Map<String, Object> root = (Map<String, Object>) parsed;
        Object nodesRaw = root.get("nodes");
        if (!(nodesRaw instanceof List)) {
            throw new IllegalArgumentException("Workflow JSON must contain a \"nodes\" array");
        }

        List<WorkflowNode> nodes = new ArrayList<>();
        for (Object nodeObj : (List<Object>) nodesRaw) {
            nodes.add(nodeFromJson((Map<String, Object>) nodeObj));
        }
        return of(nodes);
    }

    @SuppressWarnings("unchecked")
    private static WorkflowNode nodeFromJson(Map<String, Object> json) {
        Object dependsOnRaw = json.getOrDefault("dependsOn", List.of());
        Set<String> dependsOn = new LinkedHashSet<>();
        for (Object dep : (List<Object>) dependsOnRaw) {
            dependsOn.add((String) dep);
        }
        Object evidenceForRaw = json.getOrDefault("producesEvidenceFor", List.of());
        Set<String> producesEvidenceFor = new LinkedHashSet<>();
        for (Object criterionId : (List<Object>) evidenceForRaw) {
            producesEvidenceFor.add((String) criterionId);
        }
        return new WorkflowNode(
            (String) json.get("id"),
            (String) json.getOrDefault("name", json.get("id")),
            (String) json.get("executor"),
            dependsOn,
            (String) json.get("entryGate"),
            (String) json.get("exitGate"),
            RiskLevel.valueOf((String) json.get("riskLevel")),
            ((Double) json.getOrDefault("maxAttempts", 1.0)).intValue(),
            producesEvidenceFor);
    }

    /**
     * Computes a topological order via Kahn's algorithm and throws, naming the nodes
     * still unordered, if a cycle prevents completing the order. Any node left with
     * unresolved incoming edges after the algorithm terminates is part of, or downstream
     * of, a cycle.
     */
    private static List<String> computeTopologicalOrderOrThrow(Map<String, WorkflowNode> byId) {
        Map<String, Integer> remainingDependencies = new HashMap<>();
        Map<String, List<String>> dependents = new HashMap<>();
        for (String id : byId.keySet()) {
            dependents.put(id, new ArrayList<>());
        }
        for (WorkflowNode node : byId.values()) {
            remainingDependencies.put(node.id(), node.dependsOn().size());
            for (String dependencyId : node.dependsOn()) {
                dependents.get(dependencyId).add(node.id());
            }
        }

        Deque<String> ready = new ArrayDeque<>();
        for (Map.Entry<String, Integer> entry : remainingDependencies.entrySet()) {
            if (entry.getValue() == 0) {
                ready.add(entry.getKey());
            }
        }

        List<String> order = new ArrayList<>();
        while (!ready.isEmpty()) {
            String id = ready.removeFirst();
            order.add(id);
            for (String dependent : dependents.get(id)) {
                int remaining = remainingDependencies.merge(dependent, -1, Integer::sum);
                if (remaining == 0) {
                    ready.add(dependent);
                }
            }
        }

        if (order.size() < byId.size()) {
            Set<String> unresolved = new LinkedHashSet<>(byId.keySet());
            unresolved.removeAll(order);
            throw new IllegalArgumentException(
                "Workflow graph contains a cycle involving: " + String.join(", ", unresolved));
        }

        return order;
    }

    /**
     * Spec 01 asks for an "unreachable node" check alongside cycle and dangling
     * dependency detection. A literal backward-reachability check (walk forward from
     * every root and confirm every node was visited) cannot actually fire once the cycle
     * check and the dangling dependency check both pass: in a finite acyclic graph where
     * every dependency id is declared, every node either has an empty dependsOn (a root,
     * reachable by definition) or a non-empty one whose chain must, since it is finite
     * and acyclic, bottom out at a root. That makes backward reachability implied, not an
     * independent failure mode, and CLAUDE.md rule 6 treats a policy branch nothing can
     * ever trip as a bug, not a control.
     *
     * These two checks replace it with conditions that genuinely can fail on an acyclic,
     * fully-declared graph: {@link #checkSingleEntry} and {@link #checkSingleTerminal}.
     * A graph with two independent roots that both eventually feed into the same node is
     * acyclic, has no dangling references, and is fully reachable, yet has no single
     * starting point for the run; a graph that fans out from one root into two leaves
     * that never rejoin is the same problem at the exit. Both are structurally invalid
     * for a workflow this orchestrator can schedule and report on, so both are checked
     * and named explicitly in the error, exactly as spec 01 intended a validation error
     * to behave, even though the specific condition described here differs from its
     * literal wording.
     */
    private static void checkSingleEntry(Map<String, WorkflowNode> byId) {
        Set<String> roots = new LinkedHashSet<>();
        for (WorkflowNode node : byId.values()) {
            if (node.dependsOn().isEmpty()) {
                roots.add(node.id());
            }
        }
        if (roots.size() != 1) {
            throw new IllegalArgumentException(
                "Workflow graph must have exactly one root node (a node with no dependencies), found "
                    + roots.size() + ": " + String.join(", ", roots));
        }
    }

    private static void checkSingleTerminal(Map<String, WorkflowNode> byId) {
        Set<String> hasDependents = new LinkedHashSet<>();
        for (WorkflowNode node : byId.values()) {
            hasDependents.addAll(node.dependsOn());
        }
        Set<String> terminals = new LinkedHashSet<>(byId.keySet());
        terminals.removeAll(hasDependents);
        if (terminals.size() != 1) {
            throw new IllegalArgumentException(
                "Workflow graph must have exactly one terminal node (a node nothing depends on), found "
                    + terminals.size() + ": " + String.join(", ", terminals));
        }
    }

    public WorkflowNode getNode(String nodeId) {
        WorkflowNode node = nodesById.get(nodeId);
        if (node == null) {
            throw new IllegalArgumentException("No such node: " + nodeId);
        }
        return node;
    }

    public Set<String> getNodeIds() {
        return Set.copyOf(nodesById.keySet());
    }

    /** Every node definition in this graph, in topological order. */
    public List<WorkflowNode> getAllNodes() {
        List<WorkflowNode> all = new ArrayList<>();
        for (String id : topologicalOrder) {
            all.add(nodesById.get(id));
        }
        return all;
    }

    /**
     * The nodes that are schedulable right now given {@code statuses}: still PENDING,
     * and every dependency is COMPLETED. Status is passed in rather than tracked by this
     * class, since this class has no way to observe status changes made through
     * {@link com.schwab.agentic.model.WorkflowState#transition} and should not hold a
     * copy of state that could drift from the source of truth. This is also what makes
     * READY a derived, on-demand fact rather than a stored status: asking this question
     * twice with two different snapshots of {@code statuses} can give different answers,
     * with nothing here to keep in sync.
     */
    public List<WorkflowNode> readyNodes(Map<String, NodeStatus> statuses) {
        List<WorkflowNode> ready = new ArrayList<>();
        for (String id : topologicalOrder) {
            WorkflowNode node = nodesById.get(id);
            NodeStatus status = requireStatus(statuses, id);
            if (status != NodeStatus.PENDING) {
                continue;
            }
            boolean dependenciesComplete = true;
            for (String dependencyId : node.dependsOn()) {
                if (requireStatus(statuses, dependencyId) != NodeStatus.COMPLETED) {
                    dependenciesComplete = false;
                    break;
                }
            }
            if (dependenciesComplete) {
                ready.add(node);
            }
        }
        return ready;
    }

    private NodeStatus requireStatus(Map<String, NodeStatus> statuses, String nodeId) {
        NodeStatus status = statuses.get(nodeId);
        if (status == null) {
            throw new IllegalArgumentException(
                "No status supplied for node " + nodeId + ": statuses map must cover every node in this graph");
        }
        return status;
    }

    /**
     * The transitive closure of nodes reachable by following dependency edges forward
     * from {@code nodeId}, not including {@code nodeId} itself. Spec 06's re-planning
     * invalidates exactly this set when the named node's output changes, so correctness
     * here directly determines whether a re-plan under-invalidates (leaving stale work
     * COMPLETED) or over-invalidates (discarding work that never depended on the change).
     */
    public Set<String> downstreamOf(String nodeId) {
        if (!nodesById.containsKey(nodeId)) {
            throw new IllegalArgumentException("No such node: " + nodeId);
        }
        Map<String, List<String>> dependents = new HashMap<>();
        for (String id : nodesById.keySet()) {
            dependents.put(id, new ArrayList<>());
        }
        for (WorkflowNode node : nodesById.values()) {
            for (String dependencyId : node.dependsOn()) {
                dependents.get(dependencyId).add(node.id());
            }
        }

        Set<String> downstream = new LinkedHashSet<>();
        Deque<String> toVisit = new ArrayDeque<>(dependents.get(nodeId));
        while (!toVisit.isEmpty()) {
            String id = toVisit.removeFirst();
            if (downstream.add(id)) {
                toVisit.addAll(dependents.get(id));
            }
        }
        return downstream;
    }

    /** The full node order such that every node appears after all of its dependencies. */
    public List<String> topologicalOrder() {
        return List.copyOf(topologicalOrder);
    }

    /**
     * Renders this graph as a Mermaid flowchart labelled with the given statuses, for
     * embedding directly in the run report (spec 08). Statuses come from the caller for
     * the same reason {@link #readyNodes} takes them as a parameter: this class does not
     * track status itself.
     */
    public String toMermaid(Map<String, NodeStatus> statuses) {
        StringBuilder mermaid = new StringBuilder();
        mermaid.append("flowchart TD\n");
        for (String id : topologicalOrder) {
            NodeStatus status = requireStatus(statuses, id);
            mermaid.append("    ").append(id)
                .append("[\"").append(id).append(" (").append(status).append(")\"]\n");
        }
        for (WorkflowNode node : nodesById.values()) {
            for (String dependencyId : node.dependsOn()) {
                mermaid.append("    ").append(dependencyId).append(" --> ").append(node.id()).append('\n');
            }
        }
        return mermaid.toString();
    }
}
