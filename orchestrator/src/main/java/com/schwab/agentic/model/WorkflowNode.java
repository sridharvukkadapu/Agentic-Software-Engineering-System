package com.schwab.agentic.model;

import java.util.Set;

/**
 * One stage in the workflow graph: an id, its dependencies, the gates that guard entry
 * and exit, its risk profile, an optional fallback executor, and which acceptance
 * criteria it produces evidence for.
 *
 * This type is fully immutable and carries no status. Status lives in
 * {@link WorkflowState}, keyed by node id, because a {@code WorkflowNode} instance is not
 * guaranteed to be the only one that will ever exist for a given id: the execution
 * engine's checkpoint and resume machinery (spec 02) and JSON deserialization
 * ({@link WorkflowState#fromJson}) both construct fresh {@code WorkflowNode} objects from
 * a workflow definition or a persisted run. If status lived on the node itself, a graph
 * holding one set of node instances and a state holding a second, independently
 * constructed set with the same ids would silently disagree about status, since a field
 * mutation on one object is invisible to any other object, no matter how identical their
 * ids are. Keying status by node id in one shared map sidesteps that entirely: identity
 * of the WorkflowNode object stops mattering, only the id does.
 *
 * Entry and exit gates are stored as plain names, not objects, so a workflow JSON file
 * can reference a gate the same way it references an executor: as data. Resolving a name
 * to an actual gate implementation is the execution engine's job (spec 02), not this
 * package's, so this class never needs to know what "compiles" or "human-approval"
 * actually check.
 *
 * {@code fallbackExecutor}, when non-null, names a second executor the engine runs when
 * this node exhausts its retry budget (spec 02). A fallback is a materially different
 * code path from a retry, not another attempt at the same thing: retry re-runs
 * {@code executor} with the accumulated failure history in context, while fallback runs
 * {@code fallbackExecutor} instead, once, as a deliberately different strategy for
 * getting the node to a completed state.
 */
public record WorkflowNode(
    String id,
    String name,
    String executor,
    Set<String> dependsOn,
    String entryGate,
    String exitGate,
    RiskLevel riskLevel,
    int maxAttempts,
    Set<String> producesEvidenceFor,
    String fallbackExecutor
) {
    public WorkflowNode {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("WorkflowNode id must not be blank");
        }
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("WorkflowNode name must not be blank");
        }
        if (executor == null || executor.isBlank()) {
            throw new IllegalArgumentException("WorkflowNode executor must not be blank");
        }
        if (riskLevel == null) {
            throw new IllegalArgumentException("WorkflowNode riskLevel must not be null");
        }
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("WorkflowNode maxAttempts must be at least 1, got " + maxAttempts);
        }
        dependsOn = dependsOn == null ? Set.of() : Set.copyOf(dependsOn);
        producesEvidenceFor = producesEvidenceFor == null ? Set.of() : Set.copyOf(producesEvidenceFor);
    }

    /** Convenience constructor for nodes with no declared fallback. */
    public WorkflowNode(
        String id,
        String name,
        String executor,
        Set<String> dependsOn,
        String entryGate,
        String exitGate,
        RiskLevel riskLevel,
        int maxAttempts,
        Set<String> producesEvidenceFor
    ) {
        this(id, name, executor, dependsOn, entryGate, exitGate, riskLevel, maxAttempts, producesEvidenceFor, null);
    }

    public boolean hasFallback() {
        return fallbackExecutor != null && !fallbackExecutor.isBlank();
    }
}
