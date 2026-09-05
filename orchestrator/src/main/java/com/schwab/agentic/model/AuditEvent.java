package com.schwab.agentic.model;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;

/**
 * One entry in a run's audit log.
 *
 * This is the mechanism behind CLAUDE.md rule 1: audit events are derived, never
 * narrated. The constructor is private, and {@link #create} is package-private, so only
 * {@link WorkflowState}, in the same package, can build one, and it only does so inside
 * {@code transition} (for a status change) or {@code record} (for everything else). No
 * other class can fabricate an event describing a change that did not actually happen,
 * because no other class can reach the one factory method that builds this type.
 *
 * Unlike an earlier version of this class, {@code AuditEvent} itself is public: specs 08
 * and 09 compute metrics and render reports from {@link WorkflowState#getAuditLog}, and
 * both live in other packages. A record's canonical constructor cannot be less visible
 * than the record type, so making construction package-private while keeping the type
 * itself public required moving off the record's generated constructor entirely: this is
 * a plain final class with a private constructor, a package-private static factory, and
 * hand-written accessors and {@code equals}/{@code hashCode}/{@code toString}, which is
 * more code than a record but is what lets the type be freely named, held in variables,
 * and read by other packages while remaining impossible to construct there.
 *
 * {@code from} and {@code to} are populated only for {@link EventType#STATUS_CHANGE}
 * events; every other event type leaves them null and carries its information in
 * {@code details} instead. {@code nodeId} is null for events that are scoped to the run
 * rather than to a single node, such as {@code REPLAN} or {@code RUN_RESUMED}.
 */
public final class AuditEvent {

    private final long sequence;
    private final String runId;
    private final String nodeId;
    private final EventType type;
    private final NodeStatus from;
    private final NodeStatus to;
    private final String actor;
    private final String reason;
    private final Map<String, Object> details;
    private final Instant timestamp;

    private AuditEvent(long sequence, String runId, String nodeId, EventType type, NodeStatus from, NodeStatus to,
                        String actor, String reason, Map<String, Object> details, Instant timestamp) {
        if (runId == null || runId.isBlank()) {
            throw new IllegalArgumentException("AuditEvent runId must not be blank");
        }
        if (type == null) {
            throw new IllegalArgumentException("AuditEvent type must not be null");
        }
        if (actor == null || actor.isBlank()) {
            throw new IllegalArgumentException("AuditEvent actor must not be blank");
        }
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("AuditEvent reason must not be blank");
        }
        if (timestamp == null) {
            throw new IllegalArgumentException("AuditEvent timestamp must not be null");
        }
        if (type == EventType.STATUS_CHANGE) {
            if (nodeId == null || nodeId.isBlank()) {
                throw new IllegalArgumentException("STATUS_CHANGE events must carry a nodeId");
            }
            if (from == null || to == null) {
                throw new IllegalArgumentException("STATUS_CHANGE events must carry both from and to");
            }
        } else {
            if (from != null || to != null) {
                throw new IllegalArgumentException(
                    "Only STATUS_CHANGE events may carry from/to, got type " + type);
            }
        }
        this.sequence = sequence;
        this.runId = runId;
        this.nodeId = nodeId;
        this.type = type;
        this.from = from;
        this.to = to;
        this.actor = actor;
        this.reason = reason;
        this.details = details == null ? Map.of() : Map.copyOf(details);
        this.timestamp = timestamp;
    }

    /**
     * The only way to build an {@link AuditEvent}. Package-private: only
     * {@link WorkflowState#transition} and {@link WorkflowState#record}, in this same
     * package, may call it.
     */
    static AuditEvent create(long sequence, String runId, String nodeId, EventType type, NodeStatus from,
                              NodeStatus to, String actor, String reason, Map<String, Object> details,
                              Instant timestamp) {
        return new AuditEvent(sequence, runId, nodeId, type, from, to, actor, reason, details, timestamp);
    }

    public long sequence() {
        return sequence;
    }

    public String runId() {
        return runId;
    }

    public String nodeId() {
        return nodeId;
    }

    public EventType type() {
        return type;
    }

    public NodeStatus from() {
        return from;
    }

    public NodeStatus to() {
        return to;
    }

    public String actor() {
        return actor;
    }

    public String reason() {
        return reason;
    }

    public Map<String, Object> details() {
        return details;
    }

    public Instant timestamp() {
        return timestamp;
    }

    /**
     * A human-readable single line summarizing this event, used by the run's
     * human-readable audit.log (spec 08). Kept here rather than in the reporting layer so
     * the format used for on-disk audit and the format used anywhere else this event is
     * printed cannot drift apart.
     */
    public String toLogLine() {
        StringBuilder line = new StringBuilder();
        line.append('[').append(timestamp).append("] ");
        line.append("seq=").append(sequence).append(' ');
        line.append(type);
        if (nodeId != null) {
            line.append(" node=").append(nodeId);
        }
        if (type == EventType.STATUS_CHANGE) {
            line.append(' ').append(from).append(" -> ").append(to);
        }
        line.append(" actor=").append(actor);
        line.append(" reason=\"").append(reason).append('"');
        return line.toString();
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (!(obj instanceof AuditEvent other)) {
            return false;
        }
        return sequence == other.sequence
            && runId.equals(other.runId)
            && Objects.equals(nodeId, other.nodeId)
            && type == other.type
            && from == other.from
            && to == other.to
            && actor.equals(other.actor)
            && reason.equals(other.reason)
            && details.equals(other.details)
            && timestamp.equals(other.timestamp);
    }

    @Override
    public int hashCode() {
        return Objects.hash(sequence, runId, nodeId, type, from, to, actor, reason, details, timestamp);
    }

    @Override
    public String toString() {
        return "AuditEvent[sequence=" + sequence + ", runId=" + runId + ", nodeId=" + nodeId
            + ", type=" + type + ", from=" + from + ", to=" + to + ", actor=" + actor
            + ", reason=" + reason + ", details=" + details + ", timestamp=" + timestamp + ']';
    }

    /**
     * What kind of thing happened. STATUS_CHANGE is the only type that carries
     * {@code from}/{@code to}; every other type is a fact about the run that is not
     * itself a node status transition, and carries its specifics in {@code details}.
     */
    public enum EventType {
        STATUS_CHANGE,
        AGENT_CALL,
        COMMAND_EXECUTED,
        ARTIFACT_WRITTEN,
        POLICY_DENIED,
        APPROVAL_GRANTED,
        REPLAN,
        RUN_RESUMED
    }
}
