# Spec 06: Re-planning by graph reachability

## Context

The assignment asks the system to "dynamically re-plan when upstream outputs change while
maintaining governance." This is the hardest requirement and the one most submissions fake by
incrementing a counter.

Real version: a requirement amendment mid-run invalidates exactly the downstream work that
depended on it, preserves upstream work, and reschedules.

## Scope

### 1. `engine/Replanner.java`

`replan(WorkflowState state, String changedNodeId, RequirementSpec amended)`:

1. Bump the requirement revision.
2. `downstream = graph.downstreamOf(changedNodeId)`.
3. For each downstream node that is COMPLETED: transition to INVALIDATED, reset attempts,
   and archive its artifacts to `runs/<runId>/superseded/rev<n>/` rather than deleting them,
   so the lineage is inspectable.
4. Revoke evidence produced by invalidated nodes. Stale evidence satisfying a criterion is
   the subtle failure mode here; a re-planned run that releases on pre-amendment evidence has
   defeated its own gate.
5. Revoke approvals granted against the prior revision.
6. Leave upstream nodes untouched and prove it in the audit event.
7. Emit one `REPLAN` audit event whose details map lists, by node id, what was invalidated,
   what was preserved, and what evidence was revoked. **Computed, not written by hand.**
8. Increment the re-plan counter and return to RUNNING.

### 2. Amendment entry points

- `./scripts/amend.sh <runId> --requirement <file>` for a paused run.
- Mid-run amendment for the demo: a scenario may declare
  `amendAfterNode: DESIGN` with a path to the amended requirement, so the ambiguous scenario
  can demonstrate re-planning inside a single command.

### 3. Report section

The run report gains a re-plan section: what changed, which nodes were invalidated, which
were preserved, and the cost in re-executed nodes.

## Acceptance criteria

- AC-06-1: Amending after DESIGN invalidates exactly IMPLEMENT, TEST, DOCUMENT, VALIDATE, RELEASE.
- AC-06-2: REQUIREMENT and IMPACT remain COMPLETED with their original attempt counts.
- AC-06-3: Invalidated nodes re-execute and the run reaches COMPLETED.
- AC-06-4: Evidence produced before the amendment is revoked and does not satisfy the release gate.
- AC-06-5: An approval granted before the amendment does not carry over.
- AC-06-6: Superseded artifacts are archived, not deleted, and are readable after the run.
- AC-06-7: The REPLAN audit event's invalidated list is computed from the graph. Assert by changing the graph shape and seeing the list change with no code edit.
- AC-06-8: Amending a node with no downstream nodes invalidates nothing and does not increment the re-plan counter.
- AC-06-9: A re-plan while a node is RUNNING is handled deterministically. Pick a policy, document it, test it.

## Out of scope

Graph structure changes. This spec re-plans execution over a fixed graph; document adding
nodes at re-plan time as a limitation.

## Verify

```bash
./scripts/run.sh ambiguous --replay --auto-approve
grep -A20 REPLAN runs/*/audit.log
```
