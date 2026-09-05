# Spec 05: Policy engine, approvals, persistence and resume

## Context

Controlled autonomy. Agents execute inside boundaries; a human owns the decisions that
matter. A pause for approval must be a real pause: the run persists, a human decides out of
band, and the run resumes from where it stopped.

## Scope

### 1. `policy/PolicyEngine.java`

Rules loaded from `workflows/policy.json`, not hardcoded, so a reviewer can see the policy as
data. Returns ALLOW, REQUIRE_APPROVAL or DENY with a reason naming the rule that fired.

Rules to implement, covering the three categories the assignment names:

**Change control**
- `critical-risk-requires-approval`: CRITICAL nodes always require approval.
- `high-risk-requires-approval`: HIGH nodes require approval unless the run is in
  `--auto-approve` mode, which is only permitted for `--replay` demos and is stamped into
  the run report.
- `change-budget`: DENY when a diff exceeds N files or M lines for a brownfield scenario.
  Pick thresholds a real run can actually exceed.

**Security**
- `protected-paths`: DENY any write outside `target-service/` and `runs/`. Check the resolved
  canonical path, so `../` cannot escape.
- `no-secrets-in-diff`: DENY when a generated diff matches credential patterns.
- `no-dependency-additions`: REQUIRE_APPROVAL when a diff modifies `pom.xml`.

**Compliance**
- `evidence-before-release`: DENY release when any acceptance criterion lacks evidence.
- `audit-completeness`: DENY release when any node reached a terminal status without a
  matching audit event.

Every rule needs a test that makes it **fire**. A rule with no test proving it can trigger is
assumed dead and should be deleted.

### 2. `engine/ApprovalStore.java`

Approvals live in `runs/<runId>/approvals.json`. Each record: node id, decision, approver,
timestamp, and a reason the approver typed. Granted approvals are keyed to the requirement
revision they were granted against, so a re-plan after approval invalidates that approval
rather than silently reusing it.

### 3. Persistence and resume

- After every wave, write `runs/<runId>/state.json`.
- `./scripts/run.sh <scenario>` starts a run.
- `./scripts/approve.sh <runId> <nodeId> --by "name" --reason "..."` records an approval.
- `./scripts/resume.sh <runId>` loads state and continues.

A resumed run must produce a continuous audit log with an explicit `RUN_RESUMED` event
carrying the elapsed pause duration. Sequence numbers continue from where they stopped.

### 4. CLI

`Main.java` with subcommands: `run`, `resume`, `approve`, `amend` (spec 06), `report`.
`--live` / `--replay`, `--auto-approve`, `--workflow <path>`, `--scenario <name>`.

## Acceptance criteria

- AC-05-1: A HIGH risk node without approval parks the run at AWAITING_APPROVAL and writes `state.json`.
- AC-05-2: `approve.sh` then `resume.sh` completes the run with a continuous audit sequence.
- AC-05-3: The resumed run's final state is equivalent to an uninterrupted `--auto-approve` run, ignoring timestamps and approval records.
- AC-05-4: Each of the eight policy rules has a test in which it fires. No dead rules.
- AC-05-5: A write attempt outside the allowed roots is DENIED, including via `../` traversal.
- AC-05-6: A diff containing an API-key-shaped string is DENIED.
- AC-05-7: An approval granted at revision 1 does not satisfy the same node at revision 2.
- AC-05-8: `--auto-approve` is recorded in the run report so a reviewer can tell a demo run from a governed one.

## Out of scope

Re-planning mechanics. Spec 06.

## Verify

```bash
./scripts/run.sh brownfield --replay          # parks on approval
./scripts/approve.sh <runId> IMPLEMENT --by "Sridhar" --reason "Diff reviewed"
./scripts/resume.sh <runId>
```
