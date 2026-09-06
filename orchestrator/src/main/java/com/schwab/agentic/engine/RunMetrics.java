package com.schwab.agentic.engine;

import com.schwab.agentic.model.AuditEvent;
import com.schwab.agentic.model.NodeStatus;
import com.schwab.agentic.model.WorkflowState;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Every metric here is computed fresh from {@link WorkflowState#getAuditLog} on every
 * call, never accumulated in a counter field anywhere in this class or in
 * {@link WorkflowState} itself. A counter that increments as events happen can drift from
 * what the log actually says (a bug in the increment site, a code path that forgets to
 * increment, a re-plan that should reset a counter but does not); a value recomputed from
 * the same persisted events {@code report.md} and {@code audit.json} are built from
 * cannot, by construction, ever disagree with either of them. This is also what makes
 * these metrics testable against a hand-built audit log fixture on disk with no live run
 * involved: every method here takes only a {@link WorkflowState}, most of them restored
 * via {@link WorkflowState#fromJsonString} from a file a test wrote by hand.
 *
 * "Terminal" status, used by {@link #successRate} and elsewhere, means a node's most
 * recent recorded status is one this project's own transition table gives no further
 * legal moves from as this run's log stands: {@code COMPLETED}, {@code DENIED},
 * {@code ROLLED_BACK}, or {@code SKIPPED}. {@code COMPLETED} itself can legally move on to
 * {@code ROLLED_BACK} or {@code INVALIDATED} in a different context (a later rollback, a
 * later re-plan), so it is not one of {@link NodeStatus}'s own zero-outgoing-edge terminal
 * statuses; here it counts as terminal for exactly this run because nothing in this run's
 * actual log moved it any further. A node still {@code PENDING}, {@code RUNNING},
 * {@code WAITING_APPROVAL}, {@code FAILED}, or {@code INVALIDATED} at the end of the log is
 * mid-flight or safe-stopped, not yet at an outcome this run's own history has settled.
 */
public final class RunMetrics {

    private static final Set<NodeStatus> TERMINAL_STATUSES =
        Set.of(NodeStatus.COMPLETED, NodeStatus.DENIED, NodeStatus.ROLLED_BACK, NodeStatus.SKIPPED);

    private final WorkflowState state;

    public RunMetrics(WorkflowState state) {
        this.state = state;
    }

    /**
     * COMPLETED nodes over terminal nodes (see the class javadoc for what counts as
     * terminal). A node still mid-flight is excluded from both the numerator and the
     * denominator, since it has not yet produced an outcome to score; a run report for a
     * safe-stopped or awaiting-approval run should not have its success rate diluted by
     * work this log has not finished describing.
     */
    public double successRate() {
        Map<String, NodeStatus> statuses = state.getStatuses();
        long terminalCount = statuses.values().stream().filter(TERMINAL_STATUSES::contains).count();
        if (terminalCount == 0) {
            return 0.0;
        }
        long completedCount = statuses.values().stream().filter(status -> status == NodeStatus.COMPLETED).count();
        return (double) completedCount / terminalCount;
    }

    /**
     * How many times each node was retried: every {@code FAILED -> PENDING} transition in
     * the log for that node, counted directly from the recorded {@code STATUS_CHANGE}
     * events rather than from {@link WorkflowState#getRetryCount}, so this metric survives
     * being recomputed from a restored {@code WorkflowState} exactly as it would from the
     * live one (both read the same events; this method just does not depend on that
     * accessor existing or behaving a particular way).
     */
    public Map<String, Integer> retryCountByNode() {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (AuditEvent event : state.getAuditLog()) {
            if (event.type() == AuditEvent.EventType.STATUS_CHANGE
                && event.from() == NodeStatus.FAILED && event.to() == NodeStatus.PENDING) {
                counts.merge(event.nodeId(), 1, Integer::sum);
            }
        }
        return counts;
    }

    /** Total retries across every node in the run: the sum of {@link #retryCountByNode}'s values. */
    public int totalRetryCount() {
        return retryCountByNode().values().stream().mapToInt(Integer::intValue).sum();
    }

    /** Retries per attempted node: {@link #totalRetryCount} divided by the number of nodes that reached RUNNING at least once. */
    public double retryFrequency() {
        long attemptedNodeCount = state.getNodes().keySet().stream()
            .filter(nodeId -> state.getAuditLog().stream()
                .anyMatch(event -> event.type() == AuditEvent.EventType.STATUS_CHANGE
                    && nodeId.equals(event.nodeId()) && event.to() == NodeStatus.RUNNING))
            .count();
        if (attemptedNodeCount == 0) {
            return 0.0;
        }
        return (double) totalRetryCount() / attemptedNodeCount;
    }

    /** Every node id whose log shows a {@code STATUS_CHANGE} into {@code ROLLED_BACK}, in the order first rolled back. */
    public List<String> rolledBackNodes() {
        List<String> nodeIds = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (AuditEvent event : state.getAuditLog()) {
            if (event.type() == AuditEvent.EventType.STATUS_CHANGE && event.to() == NodeStatus.ROLLED_BACK
                && seen.add(event.nodeId())) {
                nodeIds.add(event.nodeId());
            }
        }
        return nodeIds;
    }

    public int rollbackCount() {
        return rolledBackNodes().size();
    }

    /** Rollbacks per node in the run: {@link #rollbackCount} divided by the total node count. */
    public double rollbackFrequency() {
        int nodeCount = state.getNodes().size();
        if (nodeCount == 0) {
            return 0.0;
        }
        return (double) rollbackCount() / nodeCount;
    }

    /**
     * Wall-clock time from this run's first audit event to its last, or {@link Duration#ZERO}
     * if the log has fewer than two events (nothing to measure a span across).
     */
    public Duration endToEndLatency() {
        List<AuditEvent> auditLog = state.getAuditLog();
        if (auditLog.size() < 2) {
            return Duration.ZERO;
        }
        Instant first = auditLog.get(0).timestamp();
        Instant last = auditLog.get(auditLog.size() - 1).timestamp();
        return Duration.between(first, last);
    }

    /**
     * Mean time to recovery: the mean, across every node whose log shows at least one
     * {@code STATUS_CHANGE} into {@code FAILED}, of the time from that node's first
     * {@code FAILED} event to its next {@code COMPLETED} event afterward. A node that
     * failed and never later completed (rolled back instead, or the log simply ends before
     * it recovers) contributes no episode to the mean at all; it is not scored as an
     * infinite or zero recovery time, which would silently distort the average either way.
     *
     * Returns {@code null}, not zero, when no node in the run ever needed more than one
     * attempt: zero would misreport "recovery was instant" for a run that never had
     * anything to recover from, a materially different, better claim than "no data."
     */
    public Duration mttr() {
        List<Duration> recoveryTimes = new ArrayList<>();
        for (String nodeId : state.getNodes().keySet()) {
            List<AuditEvent> nodeEvents = state.getAuditLog().stream()
                .filter(event -> event.type() == AuditEvent.EventType.STATUS_CHANGE && nodeId.equals(event.nodeId()))
                .toList();
            Instant firstFailedAt = nodeEvents.stream()
                .filter(event -> event.to() == NodeStatus.FAILED)
                .map(AuditEvent::timestamp)
                .findFirst()
                .orElse(null);
            if (firstFailedAt == null) {
                continue;
            }
            nodeEvents.stream()
                .filter(event -> event.to() == NodeStatus.COMPLETED && event.timestamp().isAfter(firstFailedAt))
                .map(AuditEvent::timestamp)
                .findFirst()
                .ifPresent(recoveredAt -> recoveryTimes.add(Duration.between(firstFailedAt, recoveredAt)));
        }
        if (recoveryTimes.isEmpty()) {
            return null;
        }
        long totalMillis = recoveryTimes.stream().mapToLong(Duration::toMillis).sum();
        return Duration.ofMillis(totalMillis / recoveryTimes.size());
    }

    /** Node ids that failed at least once but whose log shows no later recovery to COMPLETED: excluded from {@link #mttr}. */
    public List<String> unrecoveredNodes() {
        List<String> unrecovered = new ArrayList<>();
        for (String nodeId : state.getNodes().keySet()) {
            List<AuditEvent> nodeEvents = state.getAuditLog().stream()
                .filter(event -> event.type() == AuditEvent.EventType.STATUS_CHANGE && nodeId.equals(event.nodeId()))
                .toList();
            Instant firstFailedAt = nodeEvents.stream()
                .filter(event -> event.to() == NodeStatus.FAILED)
                .map(AuditEvent::timestamp)
                .findFirst()
                .orElse(null);
            if (firstFailedAt == null) {
                continue;
            }
            boolean recovered = nodeEvents.stream()
                .anyMatch(event -> event.to() == NodeStatus.COMPLETED && event.timestamp().isAfter(firstFailedAt));
            if (!recovered) {
                unrecovered.add(nodeId);
            }
        }
        return unrecovered;
    }
}
