package com.schwab.agentic.model;

import java.time.Instant;
import java.util.Map;

/**
 * One entry in a run's audit log.
 *
 * This is the mechanism behind CLAUDE.md rule 1: audit events are derived, never
 * narrated. Both the type and its canonical constructor are package-private, so only
 * {@link WorkflowState}, in the same package, can create one, and it only does so inside
 * {@code transition} (for a status change) or {@code record} (for everything else). Java
 * does not allow a record's canonical constructor to be less visible than the record
 * itself, so keeping the constructor package-private required making the type
 * package-private too; a class outside this package can still read events returned from
 * {@link WorkflowState#getAuditLog} and call their accessors, it just cannot construct
 * one or declare a variable of this type by name. If a later piece needs to name this
 * type from another package (for example a metrics or reporting module), that is the
 * point to widen visibility, not a reason to widen it now.
 *
 * {@code from} and {@code to} are populated only for {@link EventType#STATUS_CHANGE}
 * events; every other event type leaves them null and carries its information in
 * {@code details} instead. {@code nodeId} is null for events that are scoped to the run
 * rather than to a single node, such as {@code REPLAN} or {@code RUN_RESUMED}.
 */
record AuditEvent(
    long sequence,
    String runId,
    String nodeId,
    EventType type,
    NodeStatus from,
    NodeStatus to,
    String actor,
    String reason,
    Map<String, Object> details,
    Instant timestamp
) {
    AuditEvent {
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
        details = details == null ? Map.of() : Map.copyOf(details);
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
