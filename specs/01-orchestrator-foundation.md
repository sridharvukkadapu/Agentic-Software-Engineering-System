# Spec 01: Orchestrator foundation

## Context

The orchestrator needs a data model and a graph before anything can execute. Several files
already exist under `orchestrator/src/main/java/com/schwab/agentic/`: `json/Json.java`, and
in `model/`: `NodeStatus`, `WorkflowStatus`, `RiskLevel`, `AcceptanceCriterion`, `Evidence`,
`AuditEvent`, `DecisionRecord`, `WorkflowNode`, `RequirementSpec`. Read them first and
follow their conventions.

## Scope

Complete the foundation layer.

### 1. `model/WorkflowState.java`

The single mutable object representing a run in progress. This is the most important class
in the project.

- Holds: run id, `RequirementSpec` (replaceable on amendment), the node map, the audit log,
  evidence list, decision list, workflow status, timestamps, and counters for retries,
  rollbacks and re-plans.
- **`transition(WorkflowNode node, NodeStatus to, String actor, String reason)` is the only
  way a node status changes.** It reads the current status, sets the new one, and appends an
  `AuditEvent` carrying both. `WorkflowNode.setStatus` is package-private so nothing else
  can call it. This is rule 1 in CLAUDE.md and it is structural, not a convention.
- A matching `transition(WorkflowStatus to, String actor, String reason)` for the workflow.
- `record(AuditEvent)` for non-transition events (agent call made, artifact written, command
  executed). These still carry an actor and a reason, and may carry a details map.
- Thread-safe: nodes execute in parallel. Guard the audit log, evidence and decision lists.
  Use a lock or synchronised methods; document the choice.
- `toJson()` / `fromJson()` covering the complete run, because spec 05 persists and resumes
  from it. Round-trip fidelity matters: a resumed run must be indistinguishable from one
  that never stopped.

### 2. `graph/WorkflowGraph.java`

- Loads a node list from a JSON workflow definition file.
- **Validates on load and throws on any of:** an unknown dependency id, a duplicate node id,
  a cycle, an unreachable node, an executor name with no registered implementation.
- `readyNodes(WorkflowState)`: nodes that are schedulable and whose dependencies are all
  COMPLETED.
- `downstreamOf(String nodeId)`: transitive closure following edges forward. Spec 06 depends
  on this being correct, so test it against a diamond shape, not just a chain.
- `topologicalOrder()` for reporting and diagrams.
- `toMermaid()` returning a Mermaid flowchart of the graph with node statuses, for the run
  report.

### 3. `workflows/sdlc-default.json`

The default graph as data, not code. Eight nodes:

| id | executor | dependsOn | risk | maxAttempts |
|----|----------|-----------|------|-------------|
| REQUIREMENT | requirement | - | LOW | 2 |
| IMPACT | impact | REQUIREMENT | MEDIUM | 2 |
| DESIGN | design | IMPACT | MEDIUM | 2 |
| IMPLEMENT | implement | DESIGN | HIGH | 3 |
| TEST | test | DESIGN | MEDIUM | 3 |
| DOCUMENT | document | DESIGN | LOW | 2 |
| VALIDATE | validate | IMPLEMENT, TEST, DOCUMENT | HIGH | 2 |
| RELEASE | release | VALIDATE | CRITICAL | 1 |

IMPLEMENT, TEST and DOCUMENT fan out from DESIGN and rejoin at VALIDATE. That fan-out is
the parallelism and the rejoin is the synchronisation barrier; both must be genuine.

Each node also declares `entryGate` and `exitGate` names and a `producesEvidenceFor` list.

### 4. Test harness

`orchestrator/src/test/java/com/schwab/agentic/TestRunner.java` plus a tiny assertion
helper. No JUnit (zero dependencies). Requirements:

- Prints per-test pass/fail and a final `PASSED n/m` line where **m is the total attempted**,
  so a failure is visible in the count rather than only as a thrown exception.
- Continues after a failure and reports all results.
- Exits non-zero if anything failed, so CI and `scripts/test.sh` behave correctly.

### 5. `scripts/build.sh` and `scripts/test.sh`

`javac --release 21` over `src/main/java` then `src/test/java`, output to `out/`.
`set -euo pipefail`. Fail loudly if `javac` is missing.

## Acceptance criteria

- AC-01-1: Loading a workflow JSON containing a cycle throws with a message naming the nodes in the cycle.
- AC-01-2: Loading a workflow JSON with a dependency on an undeclared node throws.
- AC-01-3: `downstreamOf("DESIGN")` on the default graph returns exactly IMPLEMENT, TEST, DOCUMENT, VALIDATE, RELEASE.
- AC-01-4: `readyNodes` on a fresh state returns only REQUIREMENT.
- AC-01-5: After REQUIREMENT completes, `readyNodes` returns only IMPACT.
- AC-01-6: After DESIGN completes, `readyNodes` returns IMPLEMENT, TEST and DOCUMENT together.
- AC-01-7: `WorkflowNode.setStatus` is not public and cannot be called from outside the model package.
- AC-01-8: Every `transition` call produces exactly one audit event whose `from` and `to` match the actual statuses.
- AC-01-9: `WorkflowState` survives a JSON round trip with all nodes, audit events, evidence and counters intact.
- AC-01-10: `./scripts/test.sh` exits non-zero when a test fails.

## Out of scope

No agent calls, no executors, no policy engine. Nodes can be driven directly in tests.

## Verify

```bash
./scripts/build.sh && ./scripts/test.sh
```
