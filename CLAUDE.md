# CLAUDE.md

Project context for Claude Code. Read this before any task.

## What this is

An interview assignment for Charles Schwab: an **agentic software engineering system**
that drives a requirement through the full SDLC under governance, operating on a
URL shortener service as its target codebase.

The URL shortener is scenery. The orchestration layer is the deliverable.

## The one design principle

**Agents propose. Deterministic code decides.**

An LLM writes specs, code, tests and docs. The orchestrator owns the dependency graph,
the gates, the policy, the retries, the approvals and the audit. Nothing an agent says is
trusted. Every claim is checked by code that runs.

## Non-negotiable rules

These exist because a competing submission failed on each one. Violating any of them
defeats the purpose of the project.

1. **Audit events are derived, never narrated.** A node status may only change through
   `WorkflowState.transition(...)`, which emits an audit event containing the observed
   `from` and `to` values. Never build an `AuditEvent` at a call site. Never write a log
   message that describes behaviour the surrounding code does not perform.

2. **No hardcoded agent responses.** If a node claims to analyse, design, implement,
   test or document, it must call the agent layer or run a real command. A method that
   returns a canned success string is a bug, not a stub.

3. **Every node writes artifacts to disk** under `runs/<runId>/`. A reviewer must be able
   to `cat` the spec, the diff, the generated tests and the audit log.

4. **Evidence records its origin.** `Evidence.Origin.EXECUTED` means a command ran and
   returned an exit code, or tool output was parsed. `ASSERTED` means someone said so.
   Criteria at HIGH or CRITICAL risk accept only EXECUTED evidence.

5. **Rollback must actually restore.** If the audit says artifacts were reverted, a
   checkpoint existed and files were restored from it. No exceptions.

6. **Policy thresholds must be reachable.** Do not write a policy branch that no node in
   any workflow can ever trip. Dead branches are not controls.

7. **Re-planning is graph reachability.** Compute the transitive downstream closure of
   the changed node, invalidate exactly those, preserve upstream. Do not increment a
   counter and log that a re-plan occurred.

## Layout

```
orchestrator/     Zero-dependency Java 21. Builds with javac, no Maven, no network.
target-service/   Spring Boot URL shortener (Postgres + Mongo). The codebase agents modify.
workflows/        DAG definitions as JSON data. Not code.
scenarios/        Requirement inputs for the three demo scenarios.
runs/             Generated per-run artifacts. Git-ignored except committed demo runs.
specs/            Task specs. One per unit of work.
docs/             Architecture, testing approach, limitations.
scripts/          build.sh, test.sh, run.sh, resume.sh
```

The orchestrator has **zero external dependencies** on purpose: a reviewer can build and
run it with only a JDK, and it works in restricted network environments. Do not add
Maven, Gradle, Jackson, JUnit or SLF4J to `orchestrator/`. The target service is a normal
Spring Boot project and has whatever dependencies it needs.

## Build and verify

```bash
./scripts/build.sh        # javac the orchestrator
./scripts/test.sh         # run the orchestrator test suite
./scripts/run.sh greenfield --replay
```

The orchestrator shells out to a configurable build command to validate the target
service, so it does not need Maven itself.

## Agent layer

Anthropic Messages API, called with `java.net.http.HttpClient`. Two modes:

- `--live` reads `ANTHROPIC_API_KEY` and makes real calls, caching every
  request/response pair into `fixtures/`.
- `--replay` (default) serves from `fixtures/` with no network and no API key.

Replay must produce byte-identical runs so an evaluator with no key gets the same result.
Cache key is a hash of the full request body.

Model: `claude-sonnet-4-6`.

## Code style

- One statement per line. No cramming multiple statements onto a line to save space.
- Javadoc on every public type explaining *why* it exists, not what the fields are.
- Records for immutable data, classes for things with lifecycle.
- No em dashes in any comments, docs or generated prose. Use commas, colons or
  parentheses.
- Meaningful names over short ones. `attemptsRemaining()` beats `rem()`.

## What gets scored

From the assignment's evaluation criteria, in order of weight:
1. Effectiveness of the agentic orchestration
2. Architecture and system design quality
3. Depth of decomposition and execution quality
4. Realism and quality of outputs
5. Validation and risk management rigour
6. Clarity and defensibility of decisions

Optimise for a reviewer who opens the repo, runs one command, and reads the generated
artifacts. Make the orchestration impossible to miss.
