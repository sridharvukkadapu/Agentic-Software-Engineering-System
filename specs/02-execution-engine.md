# Spec 02: Execution engine

## Context

Spec 01 gave us a validated graph and a state object where every status change is audited.
This spec makes it run: scheduling, parallelism, gates, retries, fallback, rollback and
safe-stop.

This is the class a reviewer will read most closely. Section 4.4 of the assignment is the
"critical differentiator" and this file is where it lives.

## Scope

### 1. `engine/WorkflowEngine.java`

The scheduling loop:

1. Ask the graph for ready nodes.
2. If none and all nodes are COMPLETED, the workflow is COMPLETED.
3. If none and some node is WAITING_APPROVAL, the workflow is AWAITING_APPROVAL. Persist and
   return. This is a pause, not a failure.
4. If none and work remains, SAFE_STOPPED with a reason naming the blocked nodes.
5. Otherwise submit every ready node to a virtual thread executor, wait for all of them,
   then loop.

The wait-for-all is the synchronisation barrier. Do not start the next wave before the
current one finishes.

Include an iteration guard, but make exceeding it a distinct SAFE_STOP reason rather than a
silent exit.

### 2. Node execution sequence

For one node, in order:

1. **Entry gate.** Evaluate the named entry gate. A failed entry gate does not consume a
   retry attempt; it is a scheduling problem, not an execution failure.
2. **Policy check.** Spec 05 fills this in. For now call a `PolicyEngine` interface that
   returns ALLOW by default, and honour REQUIRE_APPROVAL and DENY when it does not.
3. **Execute.** Transition to RUNNING, record the attempt, call the executor.
4. **Exit gate.** Evaluate the named exit gate against the executor's output. **The executor
   does not decide its own success.** An executor returning "done" with an exit gate that
   fails is a failed node.
5. **Outcome.**
   - Gate passed: COMPLETED, evidence recorded.
   - Gate failed and retry budget remains: back to PENDING, increment the run's retry counter,
     and pass the failure reason into the next attempt's context so the retry is informed
     rather than identical.
   - Gate failed, no budget, fallback declared: run the fallback, record which fallback was
     used on the node, COMPLETED with a `fallbackUsed` marker.
   - Gate failed, no budget, no fallback: rollback, then FAILED, then SAFE_STOPPED.

### 3. `engine/Gate.java` and `engine/Gates.java`

Gates are named and registered, so a workflow JSON can reference them as data.

Entry gates to implement:
- `dependencies-complete` (default)
- `requirement-unambiguous-or-approved`: blocks IMPLEMENT when the requirement still has
  unresolved ambiguity and no human decision was recorded.
- `checkpoint-exists`: blocks any node that mutates the target service unless a checkpoint
  was taken, so rollback is always possible.

Exit gates to implement:
- `artifact-written`: the node's declared output file exists and is non-empty.
- `compiles`: the target service build command exits zero.
- `tests-pass`: the target service test command exits zero.
- `evidence-complete`: every criterion in `producesEvidenceFor` has passing evidence.
- `executed-evidence-for-high-risk`: every HIGH or CRITICAL criterion has EXECUTED evidence.

Each gate returns a result carrying pass/fail plus a reason string that goes into the audit
event. The reason must state what was checked and what was found.

### 4. `engine/Checkpoint.java`

Real rollback, not a status change.

- `take(runId, label)`: copy the target service working tree (excluding build output and
  `.git`) into `runs/<runId>/checkpoints/<label>/`. Return a handle.
- `restore(handle)`: delete and restore those files from the checkpoint.
- `list(runId)`.

Taken before the first node that mutates the target service. `Checkpoint.restore` is the
only thing allowed to emit a ROLLBACK audit event, and it emits it after the files are back,
with the count of files restored in the details map.

### 5. `engine/NodeExecutor.java`

The interface executors implement, plus a registry mapping executor names from the workflow
JSON to implementations. Spec 04 provides the real ones. Ship a `NoopExecutor` here only
for tests, and name it so it can never be mistaken for a real stage.

## Acceptance criteria

- AC-02-1: A workflow of independent nodes executes them concurrently; assert overlapping start/end timestamps.
- AC-02-2: VALIDATE does not start until IMPLEMENT, TEST and DOCUMENT are all COMPLETED.
- AC-02-3: A node whose executor succeeds but whose exit gate fails ends as FAILED, not COMPLETED.
- AC-02-4: A node failing twice with `maxAttempts: 3` succeeds on the third attempt and the run's retry counter reads 2.
- AC-02-5: The failure reason from attempt N is present in the context passed to attempt N+1.
- AC-02-6: A node exhausting its budget with a declared fallback completes with `fallbackUsed` set.
- AC-02-7: A node exhausting its budget with no fallback triggers a real file restore; assert file contents actually reverted.
- AC-02-8: A ROLLBACK audit event is emitted only by `Checkpoint.restore`, and only after files are restored.
- AC-02-9: Every audit event's `from` matches the node's status immediately prior. Assert by replaying the log.
- AC-02-10: A run with a node WAITING_APPROVAL ends AWAITING_APPROVAL, not SAFE_STOPPED.

## Out of scope

Real agent calls (spec 03), real executors (spec 04), policy rules (spec 05), re-planning
(spec 06). Use test doubles registered under obviously-fake executor names.

## Verify

```bash
./scripts/build.sh && ./scripts/test.sh
```
