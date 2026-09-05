# Spec 04: Node executors

## Context

Eight stages. Each calls the agent layer or runs a real command, and each writes an artifact
a reviewer can open. This is where "the agent did work" becomes verifiable instead of
asserted.

Every executor writes to `runs/<runId>/artifacts/` and returns the paths it wrote. The
engine's exit gates check those files exist and that commands exited zero.

## Scope

### `RequirementExecutor`
Input: raw requirement text from `scenarios/<name>/requirement.md`.
Agent produces: normalized intent, acceptance criteria with risk levels, detected
ambiguities, proposed assumptions, explicit out-of-scope list.
Writes `requirement-spec.json`. Replaces the run's `RequirementSpec` at revision 1.
Exit gate: `artifact-written` plus at least one acceptance criterion parsed.

**Ambiguity handling is the point of this stage.** For the ambiguous scenario it must
populate `ambiguities` rather than quietly picking an interpretation. If it returns zero
ambiguities for a genuinely vague requirement, that is a failure worth catching in a test.

### `ImpactExecutor`
Reads the actual target service source. Do not send the whole tree: send a file inventory
plus the contents of files matched by relevance, and record which files were sent.
Agent produces: affected files, affected API contracts, affected data flows, blast radius,
regression risks.
Writes `impact-analysis.md` and `impact.json`.
For greenfield, the impact is on files that do not exist yet; state that rather than
returning empty.

### `DesignExecutor`
Agent produces a **structured spec**: class structure, API contract (OpenAPI fragment), data
model changes, and the chosen approach with rejected alternatives.
Writes `design-spec.json`, `openapi-fragment.yaml`, `design.md`.
Records a `DecisionRecord` naming alternatives rejected, rationale, and which acceptance
criteria the decision affects. That `affectsCriteria` list is what spec 06 uses to scope a
re-plan.

This is the artifact the job description calls spec-driven development. Make it good.

### `ImplementExecutor`
Consumes `design-spec.json`. Agent produces real Java source for the target service.
Writes files into `target-service/` and a unified diff into
`runs/<runId>/artifacts/implementation.diff`.
Takes a checkpoint before writing. Exit gate: `compiles`.
On compile failure the compiler output goes into the retry context. This loop is the single
most convincing thing in the demo, so make sure the error text actually reaches the next
prompt.

### `TestExecutor`
Consumes the design spec and acceptance criteria. Agent produces JUnit tests for the target
service, one or more per acceptance criterion, with the criterion id in the test name so the
mapping is mechanical rather than hardcoded.
Writes test files into `target-service/`, then runs the test command.
Emits `Evidence.executed(...)` per criterion, derived from parsed test results.

**Never hardcode which criterion a test satisfies.** Parse it from the test name or a
declared annotation. A hardcoded map is the failure mode this whole project is arguing
against.

### `DocumentExecutor`
Agent produces API docs and a changelog entry from the design spec and the diff.
Writes `api-docs.md` and `CHANGELOG-entry.md`.

### `ValidateExecutor`
No agent call. Pure deterministic check:
- Every acceptance criterion has passing evidence.
- Every HIGH or CRITICAL criterion has EXECUTED evidence.
- The diff touched only files the impact analysis predicted. An unpredicted file is a
  finding, reported with the file name.
Writes `validation-report.md` and `traceability-matrix.md`, the latter a table of
criterion to evidence to artifact.

### `ReleaseExecutor`
No agent call. Release readiness:
- Validation passed.
- No policy denials in the audit log.
- Human approval recorded for every node that required one.
Writes `release-readiness.md`. This node is CRITICAL risk and always requires approval.

## Command execution

`artifact/CommandRunner.java`: runs a command with a timeout, captures stdout and stderr
separately, returns the exit code, and writes the full output to
`runs/<runId>/commands/<n>-<name>.log`. Every invocation emits an audit event with the
command, exit code and duration.

Build and test commands come from config, defaulting to `mvn -q -B compile` and
`mvn -q -B test` in `target-service/`. The orchestrator never assumes Maven is present; a
missing command is a clear error naming what was expected.

## Acceptance criteria

- AC-04-1: A greenfield run writes all eight artifact groups and each file is non-empty.
- AC-04-2: The ambiguous scenario produces at least two entries in `ambiguities`.
- AC-04-3: `ImplementExecutor` output actually compiles, verified by the build command exit code.
- AC-04-4: An induced compile error causes a retry whose prompt contains the compiler output; assert on the recorded fixture request.
- AC-04-5: Generated test names contain acceptance criterion ids and evidence mapping is parsed, not hardcoded. Assert by renaming a criterion and seeing the mapping follow.
- AC-04-6: Evidence from `TestExecutor` has `Origin.EXECUTED`.
- AC-04-7: `ValidateExecutor` fails when a criterion has only ASSERTED evidence and is HIGH risk.
- AC-04-8: `ValidateExecutor` reports files changed outside the predicted impact set.
- AC-04-9: The traceability matrix contains one row per acceptance criterion with a resolvable artifact path.
- AC-04-10: Every `CommandRunner` invocation has a matching audit event with a real exit code.

## Out of scope

Policy, approvals, re-planning, metrics.

## Verify

```bash
./scripts/run.sh greenfield --replay
ls runs/*/artifacts/
```
