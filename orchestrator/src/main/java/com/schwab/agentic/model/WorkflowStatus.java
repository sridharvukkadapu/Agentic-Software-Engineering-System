package com.schwab.agentic.model;

/**
 * The overall status of a run, distinct from any single node's {@link NodeStatus}.
 *
 * Without a status for the run as a whole, there would be no way to represent a run that
 * is paused waiting on a human, or one that has stopped itself safely rather than failed
 * outright, both of which the assignment requires as first-class outcomes rather than
 * error cases.
 */
public enum WorkflowStatus {
    RUNNING,
    AWAITING_APPROVAL,
    SAFE_STOPPED,
    COMPLETED,
    FAILED,
    ROLLED_BACK
}
