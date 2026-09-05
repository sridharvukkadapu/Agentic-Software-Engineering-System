package com.schwab.agentic.model;

import java.time.Instant;
import java.util.Map;

/**
 * A record of a specific choice made during a run, and why.
 *
 * Without capturing decisions as data, "decision lineage" (a capability the assignment
 * names explicitly) would only exist as prose scattered through generated documents,
 * unrecoverable by anything except a human re-reading them. A structured record lets a
 * report reconstruct exactly what was decided, by whom or what, and which acceptance
 * criteria that decision touches, which is what spec 06 uses to scope a re-plan
 * correctly instead of guessing.
 */
public record DecisionRecord(
    String id,
    String description,
    String actor,
    Instant decidedAt,
    Map<String, Object> context
) {
    public DecisionRecord {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("DecisionRecord id must not be blank");
        }
        if (description == null || description.isBlank()) {
            throw new IllegalArgumentException("DecisionRecord description must not be blank");
        }
        if (actor == null || actor.isBlank()) {
            throw new IllegalArgumentException("DecisionRecord actor must not be blank");
        }
        if (decidedAt == null) {
            throw new IllegalArgumentException("DecisionRecord decidedAt must not be null");
        }
        context = context == null ? Map.of() : Map.copyOf(context);
    }
}
