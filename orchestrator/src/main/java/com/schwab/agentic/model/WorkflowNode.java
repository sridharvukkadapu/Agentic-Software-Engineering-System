package com.schwab.agentic.model;

import java.util.Set;

/**
 * One stage in the workflow graph: an id, its dependencies, the gates that guard entry
 * and exit, and its current status.
 *
 * {@code setStatus} is package-private on purpose. If any class outside this package
 * could change a node's status directly, CLAUDE.md rule 1 would be unenforceable: nothing
 * would stop code from flipping a node to COMPLETED without ever calling
 * {@link WorkflowState#transition}, which is the only place an {@link AuditEvent} gets
 * created. Confining the setter to this package, and confining its only caller to
 * {@code WorkflowState}, is what makes rule 1 a structural guarantee rather than a
 * convention someone could forget to follow.
 *
 * Entry and exit gates are stored as plain names, not objects, so a workflow JSON file
 * can reference a gate the same way it references an executor: as data. Resolving a name
 * to an actual gate implementation is the execution engine's job (spec 02), not this
 * package's, so this class never needs to know what "compiles" or "human-approval"
 * actually check.
 */
public final class WorkflowNode {

    private final String id;
    private final String name;
    private final String executor;
    private final Set<String> dependsOn;
    private final String entryGate;
    private final String exitGate;
    private final RiskLevel riskLevel;
    private final int maxAttempts;
    private final Set<String> producesEvidenceFor;

    private volatile NodeStatus status;

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
        this.id = id;
        this.name = name;
        this.executor = executor;
        this.dependsOn = dependsOn == null ? Set.of() : Set.copyOf(dependsOn);
        this.entryGate = entryGate;
        this.exitGate = exitGate;
        this.riskLevel = riskLevel;
        this.maxAttempts = maxAttempts;
        this.producesEvidenceFor = producesEvidenceFor == null ? Set.of() : Set.copyOf(producesEvidenceFor);
        this.status = NodeStatus.PENDING;
    }

    public String getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public String getExecutor() {
        return executor;
    }

    public Set<String> getDependsOn() {
        return dependsOn;
    }

    public String getEntryGate() {
        return entryGate;
    }

    public String getExitGate() {
        return exitGate;
    }

    public RiskLevel getRiskLevel() {
        return riskLevel;
    }

    public int getMaxAttempts() {
        return maxAttempts;
    }

    public Set<String> getProducesEvidenceFor() {
        return producesEvidenceFor;
    }

    public NodeStatus getStatus() {
        return status;
    }

    /**
     * Sets this node's status directly. Package-private: the only caller allowed to be
     * outside this file is {@link WorkflowState#transition}, which is in the same
     * package. See the class Javadoc for why this boundary exists.
     */
    void setStatus(NodeStatus status) {
        this.status = status;
    }
}
