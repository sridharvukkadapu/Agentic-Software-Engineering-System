package com.schwab.agentic.model;

import com.schwab.agentic.json.Json;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The single mutable object representing one run in progress.
 *
 * This is the most important class in the project. Every other piece of governance the
 * assignment asks for, audit-grade observability, decision lineage, resume after a pause,
 * re-planning, metrics, reads from or writes through this one object. It is intentionally
 * not split per node: an audit log split across per-node objects cannot produce one
 * globally ordered sequence, which spec 05's persistence and spec 08's metrics both
 * require, and a re-plan (spec 06) needs to reason about evidence and approvals across
 * every node in the run at once, not one node's private state.
 *
 * {@link #transition(WorkflowNode, NodeStatus, String, String)} is the only way a node's
 * status changes, and {@link #record(AuditEvent.EventType, String, String, Map)} is the
 * only way any other kind of audit event is created. Both methods are synchronized on
 * this instance. Nodes execute in parallel starting with the execution engine (spec 02),
 * and without synchronization two nodes transitioning at the same moment could each read
 * a stale "current status" or interleave sequence numbers, corrupting the one thing this
 * class exists to guarantee: an audit log that is both complete and correctly ordered.
 * This is a deliberate coarse-grained lock, not a performance-tuned one: correctness and
 * auditability of the single global log matter more here than transition throughput.
 */
public final class WorkflowState {

    private final String runId;
    private final Instant startedAt;
    private final Map<String, WorkflowNode> nodes;
    private final List<AuditEvent> auditLog = new ArrayList<>();
    private final List<Evidence> evidence = new ArrayList<>();
    private final List<DecisionRecord> decisions = new ArrayList<>();
    private final AtomicLong sequence = new AtomicLong(0);
    private final Map<String, Integer> retryCounts = new HashMap<>();

    private RequirementSpec requirementSpec;
    private WorkflowStatus workflowStatus;
    private int rollbackCount;
    private int replanCount;

    /**
     * Builds a run over the given nodes, taking the exact {@link WorkflowNode} instances
     * passed in rather than copying them. This constructor exists for tests and for
     * {@link #fromJson}, which both need to hand this class nodes it did not receive
     * from a {@link com.schwab.agentic.graph.WorkflowGraph}. Production code should
     * prefer building a graph first and constructing state from it (a future overload,
     * once the graph package is wired up by the execution engine), so the graph and the
     * state a run tracks are guaranteed to be looking at the same node objects: a
     * WorkflowGraph asked for ready nodes against a WorkflowState built from copies of
     * its nodes would never see a status change the state recorded, since it would be
     * reading a different object's field.
     */
    public WorkflowState(String runId, RequirementSpec requirementSpec, List<WorkflowNode> nodes) {
        if (runId == null || runId.isBlank()) {
            throw new IllegalArgumentException("WorkflowState runId must not be blank");
        }
        if (requirementSpec == null) {
            throw new IllegalArgumentException("WorkflowState requirementSpec must not be null");
        }
        if (nodes == null || nodes.isEmpty()) {
            throw new IllegalArgumentException("WorkflowState nodes must not be empty");
        }
        this.runId = runId;
        this.requirementSpec = requirementSpec;
        this.startedAt = Instant.now();
        this.workflowStatus = WorkflowStatus.RUNNING;
        Map<String, WorkflowNode> byId = new LinkedHashMap<>();
        for (WorkflowNode node : nodes) {
            if (byId.put(node.getId(), node) != null) {
                throw new IllegalArgumentException("Duplicate node id in WorkflowState: " + node.getId());
            }
        }
        this.nodes = byId;
    }

    /**
     * Changes one node's status. This is the only way a node status may change: it reads
     * the node's current status as the observed "from", validates the transition against
     * {@link NodeStatus#canTransitionTo}, applies it, and appends exactly one
     * {@link AuditEvent} carrying the observed from and to. An illegal transition throws
     * and leaves both the node and the audit log unchanged, so a caller can never observe
     * a partially-applied transition.
     */
    public synchronized void transition(WorkflowNode node, NodeStatus to, String actor, String reason) {
        if (node == null) {
            throw new IllegalArgumentException("transition node must not be null");
        }
        if (!nodes.containsKey(node.getId()) || nodes.get(node.getId()) != node) {
            throw new IllegalArgumentException(
                "Node " + node.getId() + " does not belong to this WorkflowState");
        }
        NodeStatus from = node.getStatus();
        if (!from.canTransitionTo(to)) {
            throw new IllegalStateException(
                "Illegal transition for node " + node.getId() + ": " + from + " -> " + to);
        }
        node.setStatus(to);
        if (to == NodeStatus.PENDING && from == NodeStatus.FAILED) {
            retryCounts.merge(node.getId(), 1, Integer::sum);
        }
        if (to == NodeStatus.ROLLED_BACK) {
            rollbackCount++;
        }
        auditLog.add(newAuditEvent(node.getId(), AuditEvent.EventType.STATUS_CHANGE, from, to, actor, reason, Map.of()));
    }

    /**
     * Records an audit event that is not a node status change: an agent call, a command
     * execution, an artifact written to disk, a policy denial, an approval, a re-plan, or
     * a resume. This, together with {@link #transition}, is the only way an
     * {@link AuditEvent} can come into existence, since {@code AuditEvent}'s constructor
     * is package-private and this is the only other class in the package that builds one.
     */
    public synchronized void record(AuditEvent.EventType type, String actor, String reason,
                                     Map<String, Object> details) {
        if (type == AuditEvent.EventType.STATUS_CHANGE) {
            throw new IllegalArgumentException("Use transition() to record a STATUS_CHANGE event");
        }
        auditLog.add(newAuditEvent(null, type, null, null, actor, reason, details));
    }

    private AuditEvent newAuditEvent(String nodeId, AuditEvent.EventType type, NodeStatus from, NodeStatus to,
                                      String actor, String reason, Map<String, Object> details) {
        return new AuditEvent(
            sequence.incrementAndGet(),
            runId,
            nodeId,
            type,
            from,
            to,
            actor,
            reason,
            details,
            Instant.now());
    }

    public String getRunId() {
        return runId;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public synchronized RequirementSpec getRequirementSpec() {
        return requirementSpec;
    }

    /**
     * Replaces the requirement spec, used when an amendment arrives mid-run (spec 06).
     * The new spec's revision must be strictly greater than the current one, so a
     * requirement can never silently move backward or be replaced with a duplicate
     * revision.
     */
    public synchronized void replaceRequirementSpec(RequirementSpec updated) {
        if (updated == null) {
            throw new IllegalArgumentException("replaceRequirementSpec updated must not be null");
        }
        if (updated.revision() <= requirementSpec.revision()) {
            throw new IllegalArgumentException(
                "New requirement revision " + updated.revision()
                    + " must be greater than current revision " + requirementSpec.revision());
        }
        this.requirementSpec = updated;
    }

    public WorkflowNode getNode(String nodeId) {
        WorkflowNode node = nodes.get(nodeId);
        if (node == null) {
            throw new IllegalArgumentException("No such node: " + nodeId);
        }
        return node;
    }

    public Map<String, WorkflowNode> getNodes() {
        return Map.copyOf(nodes);
    }

    public synchronized List<AuditEvent> getAuditLog() {
        return List.copyOf(auditLog);
    }

    public synchronized void addEvidence(Evidence item) {
        if (item == null) {
            throw new IllegalArgumentException("addEvidence item must not be null");
        }
        evidence.add(item);
    }

    public synchronized List<Evidence> getEvidence() {
        return List.copyOf(evidence);
    }

    public synchronized void addDecision(DecisionRecord decision) {
        if (decision == null) {
            throw new IllegalArgumentException("addDecision decision must not be null");
        }
        decisions.add(decision);
    }

    public synchronized List<DecisionRecord> getDecisions() {
        return List.copyOf(decisions);
    }

    public synchronized WorkflowStatus getWorkflowStatus() {
        return workflowStatus;
    }

    public synchronized void setWorkflowStatus(WorkflowStatus workflowStatus) {
        if (workflowStatus == null) {
            throw new IllegalArgumentException("setWorkflowStatus workflowStatus must not be null");
        }
        this.workflowStatus = workflowStatus;
    }

    public synchronized int getRetryCount(String nodeId) {
        return retryCounts.getOrDefault(nodeId, 0);
    }

    public synchronized int getRollbackCount() {
        return rollbackCount;
    }

    public synchronized int getReplanCount() {
        return replanCount;
    }

    public synchronized void incrementReplanCount() {
        replanCount++;
    }

    /**
     * Serializes the complete run to a JSON-compatible value tree. Round trip fidelity
     * with {@link #fromJson} matters because spec 05 persists this after every scheduling
     * wave and resumes a paused run from it: a resumed run must be indistinguishable from
     * one that never stopped, which is only true if every field here comes back exactly
     * as it was.
     */
    public synchronized Map<String, Object> toJson() {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("runId", runId);
        root.put("startedAt", startedAt.toString());
        root.put("workflowStatus", workflowStatus.name());
        root.put("rollbackCount", (double) rollbackCount);
        root.put("replanCount", (double) replanCount);
        root.put("sequence", (double) sequence.get());
        root.put("requirementSpec", requirementSpecToJson(requirementSpec));

        List<Object> nodesJson = new ArrayList<>();
        for (WorkflowNode node : nodes.values()) {
            nodesJson.add(nodeToJson(node));
        }
        root.put("nodes", nodesJson);

        List<Object> retryCountsJson = new ArrayList<>();
        for (Map.Entry<String, Integer> entry : retryCounts.entrySet()) {
            Map<String, Object> retryEntry = new LinkedHashMap<>();
            retryEntry.put("nodeId", entry.getKey());
            retryEntry.put("count", (double) entry.getValue());
            retryCountsJson.add(retryEntry);
        }
        root.put("retryCounts", retryCountsJson);

        List<Object> auditJson = new ArrayList<>();
        for (AuditEvent event : auditLog) {
            auditJson.add(auditEventToJson(event));
        }
        root.put("auditLog", auditJson);

        List<Object> evidenceJson = new ArrayList<>();
        for (Evidence item : evidence) {
            evidenceJson.add(evidenceToJson(item));
        }
        root.put("evidence", evidenceJson);

        List<Object> decisionsJson = new ArrayList<>();
        for (DecisionRecord decision : decisions) {
            decisionsJson.add(decisionToJson(decision));
        }
        root.put("decisions", decisionsJson);

        return root;
    }

    /** Serializes {@link #toJson} to a JSON string via {@link Json#write}. */
    public String toJsonString() {
        return Json.write(toJson());
    }

    /**
     * Reconstructs a run from a value tree previously produced by {@link #toJson}. Node
     * statuses, the audit log, evidence, decisions, counters and the sequence counter are
     * all restored exactly, so the returned instance behaves identically to the one that
     * produced the JSON, including rejecting the same illegal transitions.
     */
    @SuppressWarnings("unchecked")
    public static WorkflowState fromJson(Map<String, Object> root) {
        String runId = (String) root.get("runId");
        RequirementSpec requirementSpec = requirementSpecFromJson((Map<String, Object>) root.get("requirementSpec"));

        List<WorkflowNode> nodeList = new ArrayList<>();
        for (Object nodeObj : (List<Object>) root.get("nodes")) {
            nodeList.add(nodeFromJson((Map<String, Object>) nodeObj));
        }

        WorkflowState state = new WorkflowState(runId, requirementSpec, nodeList);
        state.workflowStatus = WorkflowStatus.valueOf((String) root.get("workflowStatus"));
        state.rollbackCount = ((Double) root.get("rollbackCount")).intValue();
        state.replanCount = ((Double) root.get("replanCount")).intValue();
        state.sequence.set(((Double) root.get("sequence")).longValue());

        for (Object entryObj : (List<Object>) root.get("retryCounts")) {
            Map<String, Object> entry = (Map<String, Object>) entryObj;
            state.retryCounts.put((String) entry.get("nodeId"), ((Double) entry.get("count")).intValue());
        }

        for (Object eventObj : (List<Object>) root.get("auditLog")) {
            state.auditLog.add(auditEventFromJson((Map<String, Object>) eventObj));
        }

        for (Object evidenceObj : (List<Object>) root.get("evidence")) {
            state.evidence.add(evidenceFromJson((Map<String, Object>) evidenceObj));
        }

        for (Object decisionObj : (List<Object>) root.get("decisions")) {
            state.decisions.add(decisionFromJson((Map<String, Object>) decisionObj));
        }

        return state;
    }

    /** Parses a JSON string previously produced by {@link #toJsonString}. */
    @SuppressWarnings("unchecked")
    public static WorkflowState fromJsonString(String json) {
        return fromJson((Map<String, Object>) Json.parse(json));
    }

    private static Map<String, Object> requirementSpecToJson(RequirementSpec spec) {
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("id", spec.id());
        json.put("revision", (double) spec.revision());
        json.put("rawText", spec.rawText());
        json.put("normalizedProblem", spec.normalizedProblem());
        List<Object> criteria = new ArrayList<>();
        for (AcceptanceCriterion criterion : spec.acceptanceCriteria()) {
            Map<String, Object> criterionJson = new LinkedHashMap<>();
            criterionJson.put("id", criterion.id());
            criterionJson.put("description", criterion.description());
            criterionJson.put("riskLevel", criterion.riskLevel().name());
            criteria.add(criterionJson);
        }
        json.put("acceptanceCriteria", criteria);
        return json;
    }

    @SuppressWarnings("unchecked")
    private static RequirementSpec requirementSpecFromJson(Map<String, Object> json) {
        List<AcceptanceCriterion> criteria = new ArrayList<>();
        for (Object criterionObj : (List<Object>) json.get("acceptanceCriteria")) {
            Map<String, Object> criterionJson = (Map<String, Object>) criterionObj;
            criteria.add(new AcceptanceCriterion(
                (String) criterionJson.get("id"),
                (String) criterionJson.get("description"),
                RiskLevel.valueOf((String) criterionJson.get("riskLevel"))));
        }
        return new RequirementSpec(
            (String) json.get("id"),
            ((Double) json.get("revision")).intValue(),
            (String) json.get("rawText"),
            (String) json.get("normalizedProblem"),
            criteria);
    }

    private static Map<String, Object> nodeToJson(WorkflowNode node) {
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("id", node.getId());
        json.put("name", node.getName());
        json.put("executor", node.getExecutor());
        json.put("dependsOn", new ArrayList<Object>(node.getDependsOn()));
        json.put("entryGate", node.getEntryGate());
        json.put("exitGate", node.getExitGate());
        json.put("riskLevel", node.getRiskLevel().name());
        json.put("maxAttempts", (double) node.getMaxAttempts());
        json.put("producesEvidenceFor", new ArrayList<Object>(node.getProducesEvidenceFor()));
        json.put("status", node.getStatus().name());
        return json;
    }

    @SuppressWarnings("unchecked")
    private static WorkflowNode nodeFromJson(Map<String, Object> json) {
        List<Object> dependsOnRaw = (List<Object>) json.get("dependsOn");
        java.util.Set<String> dependsOn = new java.util.LinkedHashSet<>();
        for (Object dep : dependsOnRaw) {
            dependsOn.add((String) dep);
        }
        List<Object> evidenceForRaw = (List<Object>) json.get("producesEvidenceFor");
        java.util.Set<String> producesEvidenceFor = new java.util.LinkedHashSet<>();
        for (Object criterionId : evidenceForRaw) {
            producesEvidenceFor.add((String) criterionId);
        }
        WorkflowNode node = new WorkflowNode(
            (String) json.get("id"),
            (String) json.get("name"),
            (String) json.get("executor"),
            dependsOn,
            (String) json.get("entryGate"),
            (String) json.get("exitGate"),
            RiskLevel.valueOf((String) json.get("riskLevel")),
            ((Double) json.get("maxAttempts")).intValue(),
            producesEvidenceFor);
        node.setStatus(NodeStatus.valueOf((String) json.get("status")));
        return node;
    }

    private static Map<String, Object> auditEventToJson(AuditEvent event) {
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("sequence", (double) event.sequence());
        json.put("runId", event.runId());
        json.put("nodeId", event.nodeId());
        json.put("type", event.type().name());
        json.put("from", event.from() == null ? null : event.from().name());
        json.put("to", event.to() == null ? null : event.to().name());
        json.put("actor", event.actor());
        json.put("reason", event.reason());
        json.put("details", event.details());
        json.put("timestamp", event.timestamp().toString());
        return json;
    }

    @SuppressWarnings("unchecked")
    private static AuditEvent auditEventFromJson(Map<String, Object> json) {
        return new AuditEvent(
            ((Double) json.get("sequence")).longValue(),
            (String) json.get("runId"),
            (String) json.get("nodeId"),
            AuditEvent.EventType.valueOf((String) json.get("type")),
            json.get("from") == null ? null : NodeStatus.valueOf((String) json.get("from")),
            json.get("to") == null ? null : NodeStatus.valueOf((String) json.get("to")),
            (String) json.get("actor"),
            (String) json.get("reason"),
            (Map<String, Object>) json.get("details"),
            Instant.parse((String) json.get("timestamp")));
    }

    private static Map<String, Object> evidenceToJson(Evidence item) {
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("origin", item.origin().name());
        json.put("acceptanceCriterionId", item.acceptanceCriterionId());
        json.put("passed", item.passed());
        json.put("description", item.description());
        json.put("source", item.source());
        json.put("producedByNode", item.producedByNode());
        json.put("artifactPath", item.artifactPath());
        json.put("capturedAt", item.capturedAt().toString());
        return json;
    }

    private static Evidence evidenceFromJson(Map<String, Object> json) {
        return new Evidence(
            Evidence.Origin.valueOf((String) json.get("origin")),
            (String) json.get("acceptanceCriterionId"),
            (Boolean) json.get("passed"),
            (String) json.get("description"),
            (String) json.get("source"),
            (String) json.get("producedByNode"),
            (String) json.get("artifactPath"),
            Instant.parse((String) json.get("capturedAt")));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> decisionToJson(DecisionRecord decision) {
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("id", decision.id());
        json.put("description", decision.description());
        json.put("actor", decision.actor());
        json.put("decidedAt", decision.decidedAt().toString());
        json.put("context", decision.context());
        return json;
    }

    @SuppressWarnings("unchecked")
    private static DecisionRecord decisionFromJson(Map<String, Object> json) {
        return new DecisionRecord(
            (String) json.get("id"),
            (String) json.get("description"),
            (String) json.get("actor"),
            Instant.parse((String) json.get("decidedAt")),
            (Map<String, Object>) json.get("context"));
    }
}
