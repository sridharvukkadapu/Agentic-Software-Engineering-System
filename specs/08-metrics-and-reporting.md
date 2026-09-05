# Spec 08: Metrics and reporting

## Context

The assignment names four metrics explicitly: success rate, retry/rollback frequency, MTTR,
and end-to-end latency. MTTR is the one most submissions omit because it needs failure and
recovery timestamps, which only exist if failures are modelled properly.

## Scope

### 1. `engine/RunMetrics.java`

Computed from the audit log, never accumulated in ad hoc counters, so the metrics and the
audit can never disagree.

- **Success rate**: nodes COMPLETED over nodes attempted. Report first-attempt success
  separately from eventual success; the gap is the interesting number.
- **Retry frequency**: retries per node and per run.
- **Rollback frequency**: rollbacks per run, with the triggering node.
- **MTTR**: mean over recovery episodes, where an episode runs from the first failing
  transition of a node to its subsequent COMPLETED. Nodes that never recovered are reported
  as unrecovered rather than folded into the mean.
- **End-to-end latency**: run wall clock, minus time parked awaiting approval, reported both
  ways. A governed run that waited two hours for a human should not look slow.
- **Per-stage latency** and **agent latency and token spend** per node.
- **Gate statistics**: how often each gate passed and failed. A gate that never fails across
  all scenarios is worth flagging.

### 2. `artifact/RunReport.java`

Writes `runs/<runId>/report.md`:

1. Header: run id, scenario, mode (live or replay), auto-approve flag, final status.
2. Requirement, final revision, with ambiguities and assumptions.
3. Mermaid graph with final node statuses colour-coded.
4. Execution timeline showing which nodes ran in parallel.
5. Decision lineage.
6. Traceability matrix: criterion, evidence, origin, artifact.
7. Re-plan section when one occurred.
8. Approvals: who approved what, when, and why.
9. Metrics table.
10. Policy events: what fired, what was denied.
11. Limitations for this specific run.

Also `runs/<runId>/audit.log`, human-readable, one line per event via
`AuditEvent.toLogLine`, plus `audit.json` for machines.

### 3. Cross-run summary

`./scripts/report.sh` produces `runs/SUMMARY.md` comparing all committed demo runs side by
side. This is the page a reviewer with ten minutes will read, so put the metric comparison
at the top.

## Acceptance criteria

- AC-08-1: Metrics recomputed from `audit.json` alone match the metrics in `report.md`.
- AC-08-2: MTTR is non-zero in a run containing a failure and a recovery.
- AC-08-3: Nodes that failed and never recovered are excluded from MTTR and reported separately.
- AC-08-4: Latency excluding approval wait is strictly less than wall clock in a run that parked.
- AC-08-5: The Mermaid graph renders and node colours match final statuses.
- AC-08-6: The report names the mode, and a replay run says so.
- AC-08-7: First-attempt success rate and eventual success rate are reported separately and differ in a run with a retry.

## Out of scope

Dashboards, Prometheus, time-series storage. Files on disk are the interface.

## Verify

```bash
./scripts/report.sh && cat runs/SUMMARY.md
```
