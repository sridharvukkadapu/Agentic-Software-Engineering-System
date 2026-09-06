# Engineering summary

## Plan and rationale

The assignment scores the orchestration layer, not the URL shortener it operates on
(section 6's weighting puts "effectiveness of the agentic orchestration" and
"architecture and system design quality" first). The plan followed from that directly:
build the orchestrator's governance machinery first, in isolation, with a zero-dependency
Java 21 codebase so a reviewer can build and run it with only a JDK; treat the target
service as scenery a real agent operates on, not a second product to polish; and spend
the assignment's own suggested nine-unit decomposition (`specs/01` through `specs/09`) in
dependency order, one spec per session, verifying and committing before moving on, per
this project's own stated convention that long sessions drift.

The one design principle threaded through every spec: agents propose, deterministic code
decides. Every spec's acceptance criteria were written to make that checkable, not just
statable, an executor that returns a canned success string, a hardcoded audit event, or a
rollback that does not actually restore a file are each named directly in `CLAUDE.md` as
disqualifying, and each has a corresponding non-vacuous test (deliberately break the
mechanism, confirm the specific test fails, restore it) rather than a test that merely
constructs an object and asserts a field.

## Artifacts produced

- **Orchestrator** (`orchestrator/`): the graph, engine, policy, agent, and reporting
  layers described in full in [design.md](design.md), 166 hand-rolled tests
  (`./scripts/test.sh`), zero external dependencies.
- **Target service** (`target-service/`): a Spring Boot URL shortener, restructured into
  layered packages in spec 07, with expiry, an injected `Clock`, a correlation-id filter,
  a global exception handler, and 24 tests against H2 (no Docker, no Testcontainers).
- **Three scenario definitions** (`scenarios/`) and their real, committed runs
  (`runs/GREENFIELD-DEMO/`, `runs/BROWNFIELD-DEMO/`, `runs/AMBIGUOUS-DEMO/`), each with a
  real `state.json`, a derived `audit.jsonl`, `approvals.json`, and the real artifacts
  REQUIREMENT wrote before the run safe-stopped.
- **A standalone, credit-free policy-denial demonstration**
  (`runs/POLICY-DENIAL-DEMO/`, produced by the test-scoped
  `orchestrator/src/test/java/com/schwab/agentic/tools/PolicyDenialDemoRunner.java`),
  showing a real `POLICY_DENIED` audit event and a real, verified rollback with no agent
  call involved.
- **Ten real, live-recorded fixtures** under `fixtures/`, produced by
  `orchestrator/src/main/java/com/schwab/agentic/tools/FixtureRecorder.java` against the
  real Anthropic API (documented in `docs/decisions.md` D6-D7), six of which are
  currently stale relative to `target-service`'s post-spec-07 file layout (see
  [design.md](design.md) section 3.2) and would need one credited re-recording pass to
  refresh.
- **This documentation set**: [README.md](../README.md) (the twelve-plus-one capability
  table), [design.md](design.md) (architecture, testing, limitations, trade-offs), and
  `docs/decisions.md` (the day-by-day record of specific defects found and fixed as the
  build actually happened).

## Risks and trade-offs

The most consequential trade-offs are recorded in [design.md](design.md) section 4; the
short version: the CLI's default demo graph is deliberately small because spec 05's
actual target (proving resume across a process boundary) did not need the full pipeline,
and a real, successful full-pipeline live run (D7) is not reproducible from a fresh clone
today because two later, independent, and necessary changes (spec 07's package
restructure, the spec 09 CLI routing fix) each invalidated a different real fixture by
changing what a real request hashes to. Neither was reverted to keep old fixtures valid;
a fixture that constrains real code changes has stopped serving its purpose.

The largest unmitigated risk is that no committed run currently demonstrates the full
eight-node pipeline reaching RELEASE COMPLETED from a fresh clone, only that it did once
(D7), under conditions that have since changed. This is stated here directly rather than
implied by omission.

## Validation approach

Three layers, detailed in [design.md](design.md) section 2: unit tests against single
classes' real logic, engine-level integration tests that run a real `WorkflowEngine`
against real files and real audit events, and cross-process/end-to-end tests that shell
out to a real CLI subprocess or chain real recorded fixtures through real executors.
Every layer runs with `./scripts/test.sh`, no network, no API key, from a fresh clone.

The single piece of evidence this project treats as strongest for "the agent layer is
genuinely live, not a rehearsed answer": `docs/decisions.md` D9. Running the full
eight-node pipeline for the first time, a real model's first live response to
`ImplementExecutor`'s prompt imported `com.github.benmanes.caffeine.cache.Caffeine`,
`org.jsoup.Jsoup`, and a Redis-backed cache class, none of which exist anywhere in
`target-service`'s real, declared dependencies (Spring web, data-jpa, validation,
Flyway; no Caffeine, no Jsoup, no Redis). The real `compileJava` gate failed with a real
`cannot find symbol` error for each one. No hand-written fixture author would ever
produce this failure: a person crafting a canned response already knows what compiles
and has no reason to reach for a plausible-sounding library that happens not to be
declared. A real model, reasoning genuinely about a real but incomplete prompt, filled
the gap with the most idiomatic real answer it knew, which was wrong given a
classpath it was never shown, and a real compiler caught the real mismatch. The fix was a
real prompt correction (stating the actual declared dependencies and naming Caffeine,
Jsoup, Guava, Apache Commons, Redis and spring-data-redis as explicitly unavailable), not
a fixture edit or a loosened gate. This is documented at length, not summarized away,
because it is the clearest evidence in the whole build that a live model, not a
stand-in, was actually behind the wheel.

## Assumptions

- **The target service is hand-seeded so `brownfield` is possible.** The brownfield
  scenario's premise (a deliberate regression already committed to `target-service`) is
  an assumption about how the assignment intends brownfield work to be demonstrated: a
  real, pre-existing defect for the agent layer to find via impact analysis, not one
  introduced live during the run.
- **Replay mode is an acceptable basis for evaluation.** Given that a reviewer running
  this repo cold may have no `ANTHROPIC_API_KEY` and no interest in spending credit to
  verify it, every committed run and every default script path uses `--replay` against
  real, previously-recorded fixtures, and `--live` is offered as an explicit opt-in, not
  the default.
- **A single, hardcoded model (`claude-sonnet-4-6`) is sufficient.** The assignment does
  not ask for multi-model support, so `AnthropicClient` hardcodes the model string rather
  than exposing a configuration surface for a capability nothing in the assignment
  exercises.

## What was cut for time, and why

- **`amend` and `report` CLI subcommands were declared before they were implemented**,
  printing "not yet implemented" until specs 06 and 08 respectively built them out, so
  the CLI's real surface area was visible from spec 05 onward rather than appearing all
  at once.
- **Rate limiting, SSRF validation, idempotency keys, click analytics, and OpenAPI
  configuration were explicitly cut from target-service's spec 07 scope.** None of these
  bear on what the assignment scores (the orchestration layer), and building them would
  have spent session time the governance machinery needed more.
- **Cross-stage context threading for the full eight-node graph was deferred past spec
  05**, once it became clear that spec 05's actual acceptance criteria (resume survives
  a real process boundary) did not require it; building it anyway would have been scope
  creep against that spec's own stated goal, not diligence.
- **A full second live-recording pass to refresh the six stale post-spec-07 fixtures
  was not attempted**, since the account behind `ANTHROPIC_API_KEY` in this environment
  currently has no credit, confirmed directly against the real API
  (`AnthropicClientTest.testLiveCallAgainstTheRealApiReturnsText`) rather than assumed.
  `FixtureRecorder --only-*` flags exist specifically so this re-recording, once
  possible, costs credit only on the affected stages.
