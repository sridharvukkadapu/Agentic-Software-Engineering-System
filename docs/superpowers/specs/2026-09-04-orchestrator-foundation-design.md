# Orchestrator Foundation Layer — Design

Status: approved
Date: 2026-09-04

## Purpose

This is the first of several sequenced pieces that make up the orchestrator described
in `CLAUDE.md`: a zero-dependency, Java 21 engine that drives a requirement through the
SDLC as an explicit dependency graph with entry/exit gates, human approval checkpoints,
bounded retries, rollback, and re-planning, all under audit.

This piece is the **data model only**: the types every later piece (task decomposition,
the execution engine, policy/gates, persistence and resume, re-planning, metrics) is
built on top of. It does not include graph traversal/scheduling logic, gate
implementations, or the agent layer — those are separate pieces with their own design
passes, per the project's stated preference for one architectural design per major
subsystem rather than one upfront design for the whole orchestrator.

No seed code exists in this repository as of this design (verified: no `orchestrator/`
directory on any branch, stash, or tag). Everything described here is written from
scratch, to the conventions in `CLAUDE.md`.

## Non-negotiable rules this design must satisfy

From `CLAUDE.md`:

1. Audit events are derived, never narrated. A node status may only change through
   `WorkflowState.transition(...)`, which emits an audit event containing the observed
   `from` and `to` values.
4. Evidence records its origin. `EXECUTED` means a command ran and returned an exit
   code, or tool output was parsed. `ASSERTED` means someone said so.
6. Policy thresholds must be reachable — no dead branches.

And from the assignment: audit-grade observability, decision lineage, cross-stage
context preservation, human approval checkpoints, bounded retries/rollback, and
dynamic re-planning.

## Package layout

```
orchestrator/
  src/main/java/com/schwab/agentic/model/
    NodeStatus.java
    WorkflowNode.java
    WorkflowGraph.java
    WorkflowState.java
    AuditEvent.java
    Evidence.java
    AcceptanceCriterion.java
    RequirementSpec.java
    DecisionRecord.java
    WorkflowStatus.java
    RiskLevel.java
    Json.java              (new: minimal hand-rolled JSON writer/reader, zero dependencies)
  src/test/java/com/schwab/agentic/testing/
    Assertions.java
    TestRunner.java
  src/test/java/com/schwab/agentic/model/
    NodeStatusTransitionTest.java
    WorkflowStateTest.java
    WorkflowGraphTest.java
    AuditEventTest.java
    EvidenceTest.java
scripts/
  build.sh
  test.sh
```

## Core types

### `NodeStatus`

```java
public enum NodeStatus {
    PENDING,
    RUNNING,
    COMPLETED,
    FAILED,
    WAITING_APPROVAL,
    DENIED,
    ROLLED_BACK,
    INVALIDATED,
    SKIPPED
}
```

`READY` and `BLOCKED` are deliberately absent. Both are derivable from a node's
`dependsOn` set and the current status of its dependencies (a future
`WorkflowGraph.readyNodes()` computes this). Storing them as persisted status would
create a second source of truth that can silently disagree with the graph, and every
dependency-satisfied transition would emit an audit event carrying no actual decision,
diluting the metrics computed from the audit log later.

### Legal transition table

`WorkflowState` owns exactly one such table, used by both the implementation and its
test:

| From | To | Meaning |
|---|---|---|
| PENDING | RUNNING | scheduler starts the node (the only path into RUNNING) |
| PENDING | WAITING_APPROVAL | policy requires approval before execution starts |
| PENDING | DENIED | policy denies before any execution attempt |
| PENDING | SKIPPED | re-plan or scope decision removes the node before it ran |
| WAITING_APPROVAL | PENDING | approved; returns to the ready pool for the next wave |
| WAITING_APPROVAL | DENIED | rejected |
| RUNNING | COMPLETED | success |
| RUNNING | FAILED | execution failed |
| RUNNING | ROLLED_BACK | rolled back while still running (e.g. safe-stop) |
| COMPLETED | ROLLED_BACK | a downstream failure exhausts its retry budget after this node completed |
| COMPLETED | INVALIDATED | re-plan invalidates a previously completed node |
| FAILED | PENDING | bounded retry re-enters the ready pool |
| FAILED | ROLLED_BACK | retries exhausted, rolling back |
| INVALIDATED | PENDING | re-planned node re-enters the normal scheduling loop |

All other ordered pairs (including every self-transition and every transition out of
`DENIED`, `ROLLED_BACK`, or `SKIPPED`) are illegal and `transition()` throws
`IllegalStateException` rather than silently applying them.

`PENDING → SKIPPED` is exercised by the future re-planning piece (spec 06): when a
re-plan removes a node that has not yet started, it moves straight to `SKIPPED` rather
than through `INVALIDATED` (which is reserved for nodes that had already `COMPLETED`
and whose prior work product is now invalid). This piece defines the edge and tests it
mechanically; the re-planning logic that decides *when* to take it is out of scope
here, per rule 6 (a policy branch must be reachable, not necessarily reached by code
written in this piece).

There is exactly one edge into `RUNNING` (`PENDING → RUNNING`). This is what keeps
resume simple: a resumed run re-enters the same scheduling loop regardless of why a
node is sitting in `PENDING` (never-started, retried, approved, or re-planned all look
identical to the scheduler).

Approval is checked before execution, not after: `WAITING_APPROVAL` is only reachable
from `PENDING`, never from `RUNNING`. A node that is already `RUNNING` has already had
an agent do the work, so gating approval on that state would make the checkpoint
theatre and would defeat spec 05's change-budget and protected-path denials, which must
deny before anything is written.

### `WorkflowNode`

```java
public final class WorkflowNode {
    private final String id;
    private final String name;
    private final Set<String> dependsOn;
    private final String entryGate;   // nullable, resolved by name in the engine layer
    private final String exitGate;    // nullable, resolved by name in the engine layer

    private volatile NodeStatus status = NodeStatus.PENDING;

    // package-private setter: only WorkflowState.transition() calls this
    void setStatus(NodeStatus status) { this.status = status; }
}
```

Gates are stored as plain `String` names (e.g. `"compiles"`, `"human-approval"`), not a
sealed interface or object graph. A sealed interface in `model/` would force every gate
implementation to be named in this package, but gate *behavior* belongs to the future
`engine/` package (spec 02), which resolves a name to an implementation via its own
registry. This also makes workflow JSON work as plain data: `"exitGate": "compiles"` is
directly deserializable without the model package knowing what compiling means.

### `WorkflowGraph`

Holds the full node set for a run and validates on construction:

- no duplicate node ids
- no `dependsOn` reference to a nonexistent node id
- no cycles, detected with an actual DFS/topological check, not asserted

Construction throws `IllegalArgumentException` with a specific reason (which ids form
the cycle, or which reference is dangling) on any violation. Traversal/scheduling logic
(computing next-runnable nodes, parallel wave synchronization) is out of scope for this
piece; it is the next design pass.

### `AuditEvent`

```java
public record AuditEvent(
    long sequence,
    String runId,
    String nodeId,           // nullable: some event types are run-scoped, not node-scoped
    EventType type,
    NodeStatus from,         // nullable: only populated for STATUS_CHANGE
    NodeStatus to,           // nullable: only populated for STATUS_CHANGE
    String actor,            // e.g. "human:svukkadapu", "agent:implementer", "system"
    String reason,
    Map<String, Object> details,
    Instant timestamp
) {
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
```

`details` is `Map<String, Object>`, not `Map<String, String>`: spec 06's `REPLAN` event
carries the computed invalidated and preserved node id lists as actual lists, and spec
08 reads structured fields like exit codes and durations. `Json.write` (existing)
handles arbitrary `Object` values; flattening to strings here would just require
re-parsing them back out downstream.

`AuditEvent` is constructed only inside `WorkflowState`: via `transition()` for status
changes, and via `record()` for every other event type. Never at a call site.

### `WorkflowState`

Run-scoped, not node-scoped. One instance per run, owning:

- the current `RequirementSpec` (replaceable on amendment; re-planning bumps its
  `revision`)
- a single `WorkflowGraph` (the node map lives there; `WorkflowState` does not keep a
  second copy, it holds a reference and calls into the graph for node lookup)
- one append-only `List<AuditEvent>` for the entire run, with a monotonic
  `AtomicLong sequence` counter shared across all events (status changes and everything
  else), so the log has one global order even under parallel node execution where
  wall-clock timestamps can collide
- accumulated `Evidence`
- accumulated `DecisionRecord`s
- overall `WorkflowStatus`
- counters (attempts, retries, rollbacks — read by spec 08's metrics)

```java
public final class WorkflowState {
    public synchronized void transition(WorkflowNode node, NodeStatus to, String actor, String reason) {
        NodeStatus from = node.getStatus();
        if (!isLegalTransition(from, to)) {
            throw new IllegalStateException(
                "Illegal transition for node " + node.getId() + ": " + from + " -> " + to);
        }
        node.setStatus(to);
        auditLog.add(new AuditEvent(
            sequence.incrementAndGet(), runId, node.getId(),
            AuditEvent.EventType.STATUS_CHANGE, from, to, actor, reason,
            Map.of(), Instant.now()));
    }

    public synchronized void record(AuditEvent.EventType type, String actor, String reason,
                                     Map<String, Object> details) {
        auditLog.add(new AuditEvent(
            sequence.incrementAndGet(), runId, null,
            type, null, null, actor, reason, details, Instant.now()));
    }
}
```

**Concurrency contract** (documented in the class Javadoc): both mutating methods are
`synchronized` on the `WorkflowState` instance. Nodes execute in parallel starting with
spec 02's engine; without synchronization, two nodes transitioning concurrently could
each read a stale `from` value or interleave sequence numbers. This is a deliberate
coarse-grained lock, not a performance-tuned one: the model layer chooses correctness
and auditability of the single global log over throughput.

### `Evidence`

```java
public record Evidence(
    Origin origin,
    String acceptanceCriterionId,
    boolean passed,
    String description,
    String source,
    String producedByNode,
    String artifactPath,      // nullable
    Instant capturedAt
) {
    public enum Origin { EXECUTED, ASSERTED }
}
```

`acceptanceCriterionId` links evidence back to the specific criterion it satisfies or
fails, completing the requirement → criterion → evidence → gate chain. `passed` is what
lets an exit gate actually decide something. `producedByNode` and `artifactPath` tie
evidence to what generated it and where the reviewer can find it on disk.

### `AcceptanceCriterion`, `RequirementSpec`, `DecisionRecord`, `WorkflowStatus`, `RiskLevel`

Minimal but real, not stubs:

```java
public record AcceptanceCriterion(
    String id,
    String description,
    RiskLevel riskLevel
) {}

public record RequirementSpec(
    String id,
    int revision,             // bumped on re-plan; approvals key against the revision they were granted for
    String rawText,
    String normalizedProblem,
    List<AcceptanceCriterion> acceptanceCriteria
) {}

public enum RiskLevel { LOW, MEDIUM, HIGH, CRITICAL }

public enum WorkflowStatus { RUNNING, PAUSED, COMPLETED, FAILED, ROLLED_BACK }

public record DecisionRecord(
    String id,
    String description,
    String actor,
    Instant decidedAt,
    Map<String, Object> context
) {}
```

`RequirementSpec.revision` is load-bearing: spec 05 keys human approvals to the
revision they were granted against, and spec 06 increments it on re-plan. Without a
revision counter there is no mechanical way to detect that an approval has gone stale.

## Testing

### Harness (zero dependencies)

- `Assertions`: static helpers (`assertEquals`, `assertTrue`, `assertThrows`, etc.),
  each throwing a plain `AssertionError` with a descriptive message on failure.
- `TestRunner`: discovers test classes, finds methods via
  `Class.getDeclaredMethods()` filtered to a `test*` naming convention, **sorts them by
  name before running** (declaration order from reflection is unspecified, and
  unordered runs are not reproducible), invokes each inside a try/catch so one failure
  does not abort the run, and prints a summary line `PASSED n/m` where `m` is the
  number of tests attempted, not just the number that passed. Per-failure detail
  (test name, assertion message) prints as it happens.

### Required coverage for this piece

- **Full transition-table test**: enumerate all 9×9 = 81 ordered `NodeStatus` pairs.
  For each, assert it is legal (and `transition()` actually applies it, updating status
  and appending exactly one `AuditEvent` with the correct `from`/`to`) or illegal (and
  `transition()` throws `IllegalStateException` without mutating status or appending an
  event). Driven off the same table `WorkflowState` uses internally, so the test cannot
  silently drift from the implementation.
- Audit events always carry the *observed* `from`, never a hardcoded or assumed value
  (verified by transitioning a node through multiple states and checking each event's
  `from` matches the prior `to`).
- `WorkflowGraph` rejects a cyclic graph (constructed with an actual cycle, not
  asserted) and rejects a dangling `dependsOn` reference.
- `record()` events have `nodeId == null` and `from == to == null`, `sequence`
  strictly increasing across a mix of `transition()` and `record()` calls.
- Concurrent transitions on different nodes of the same `WorkflowState` don't corrupt
  the sequence counter or the audit log (a test that fires transitions from multiple
  threads and asserts the final audit log size and sequence contiguity).

## Build scripts

`scripts/build.sh` and `scripts/test.sh`:

- Resolve the Java toolchain as: `$JAVA_HOME` if set, else `java`/`javac` on `PATH`.
  Never a hardcoded absolute path (a hardcoded path fails on the reviewer's first
  command).
- **Check the compiler version, not just presence.** Run `javac -version`, parse the
  major version, and require 21+. Fail fast with an explicit message: `"Java 21+
  required, found <version>. Set JAVA_HOME to a Java 21+ installation."` Java 17 would
  otherwise fail deep inside compiling `Json.writeValue`'s pattern-matching switch with
  a confusing generic compiler error instead of a clear one.
- `build.sh` compiles `src/main` only. `test.sh` compiles `src/main` and `src/test`,
  then runs `TestRunner`, exiting non-zero if any test failed.

## Out of scope for this piece

- Graph traversal/scheduling (computing ready nodes, parallel wave execution) — next
  design pass.
- Gate implementations (what `"compiles"` or `"human-approval"` actually do) — spec 02,
  in `engine/`.
- The agent layer, policy/risk scoring, persistence/resume, re-planning logic, and
  metrics computation — each gets its own design pass per the project's stated
  preference for one architectural design per major subsystem.
