# Decisions

Short entries, written as the build happened, not reconstructed afterward. Each one is a
place the first design was wrong, why it was wrong, what replaced it, and what that
replacement costs.

## D1. Approval blocks a node before it runs, not while it is running

**Problem.** The first version of the transition table let a node move from RUNNING to
WAITING_APPROVAL: start the node, then pause for a human partway through.

**Why it mattered.** By the time a node is RUNNING, an agent has already written files.
Asking a human to approve at that point is asking them to approve something that already
happened. It also makes the policy rules in spec 05 pointless: a change-budget or
protected-path rule is supposed to stop a write before it lands, and a checkpoint that
only fires after the write is not a gate, it is a formality.

**Decision.** Approval is decided while a node is still PENDING, before its executor is
ever called. The legal edges are PENDING to WAITING_APPROVAL, WAITING_APPROVAL back to
PENDING on approval, and WAITING_APPROVAL to DENIED on rejection. Approval never resolves
straight into RUNNING; it resolves back to PENDING and the scheduler picks the node up on
its next pass. That keeps exactly one edge into RUNNING, which is also what makes resuming
a paused run simple: a resumed node re-enters the same PENDING path regardless of why it
was sitting there.

**Trade-off.** A human approving a PENDING node is approving a plan, not a diff, since
nothing has been written yet. That is a weaker form of review than "look at what changed
and decide." It is the correct weakness to accept, though: reviewing the diff after the
fact cannot prevent anything, it can only regret it.

## D2. Checkpoints are per node and scoped to each node's declared write paths

**Problem.** The first implementation took one checkpoint for the whole run. Rolling back
any single node restored the entire target service tree, which meant a later node's
failure could silently undo an earlier node's completed, unrelated work. Scoping the
checkpoint to one node at a time fixed that, but the fix still copied and restored the
entire working tree per node, so it broke again the moment two nodes ran at once: each
node's checkpoint could capture a sibling's half-finished write, and each node's restore
could delete a sibling's work that had nothing to do with it.

**Why it mattered.** IMPLEMENT, TEST and DOCUMENT are designed to run concurrently in the
default workflow, so this was not a hypothetical edge case, it was the normal path. Spec 06
also needs this to be right: re-planning has to preserve a completed node's output while
invalidating and re-running only what depended on the change, which a single shared
snapshot can never support no matter how carefully it is scoped in time.

**Decision.** Every node declares `writePaths` in the workflow definition: the specific
paths it is allowed to touch. Checkpoint.take copies only those paths, and
Checkpoint.restore deletes and restores only those same paths, never anything outside
them. A node with no declared write paths is never checkpointed at all, since it has
nothing to protect. Two nodes with disjoint write paths can now checkpoint, mutate and
roll back completely independently, in parallel, without either one's operations ever
looking at the other's files.

**Trade-off.** The workflow definition now carries a permission surface someone has to
keep accurate: if a node writes outside the paths it declared, that write is simply not
protected, and nothing in this layer would catch the mismatch. Spec 05 turns this into a
second use rather than a wasted cost, by reusing the same declared paths to enforce the
protected-path policy rule, so the accuracy requirement pays for itself once instead of
twice.

## D3. A green concurrency test is not evidence until you have tried to make it fail

**Problem.** The first concurrency test for `WorkflowState` spun up 20 threads, one per
node, each touching only its own node's status twice. It passed. On its own that proved
nothing: with 20 threads never sharing a single mutable field, there was no actual race
for `synchronized` to prevent, so the test would have passed identically with the locking
removed.

**Why it mattered.** A test that cannot fail for the reason it claims to test is worse than
no test, because it looks like coverage. The whole audit-log guarantee this class exists to
provide (`WorkflowState.transition` never producing a gap or a duplicate sequence number
under concurrent access) had exactly one test standing behind it, and that test was
checking the wrong thing.

**Decision.** Before trusting the test, I removed `synchronized` from `WorkflowState`'s
mutators and reran it. It still passed, 5 times in a row, which confirmed the test was not
exercising real contention rather than confirming the locking was unnecessary. The test was
rewritten to force genuine contention: a small handful of nodes, many threads per node,
each thread driving hundreds of legal transition cycles on the same node's status entry and
the same audit log. Reran the same removal experiment against the new test: it now failed
reliably, every time, with a concrete symptom (a gap in the sequence numbers). Restored the
locking and confirmed the new test passes consistently with it in place. The same
before/after removal check became the standard for every safety-critical mechanism added
afterward, including the checkpoint hash verification and the per-node write-path scoping.

**Trade-off.** None, really; this one is closer to a piece of technique than a design
trade-off. It costs a few extra minutes per mechanism to run the "does this test fail if I
break the thing it claims to test" experiment. Skipping that check is what let the weak
version of this test through in the first place.

## D4. Policy splits into pre-execution and post-execution, not one evaluation point

**Problem.** The spec 02 `PolicyEngine` interface had one method,
`evaluate(node, state)`, called once before a node's executor runs. Several of spec 05's
eight rules (protected paths, secrets in a diff, dependency additions, a change budget)
cannot be evaluated there: they need to inspect what the executor actually wrote, which
does not exist until after it runs. But D1 requires a DENY to land before an executor's
damage happens wherever that is possible.

**Decision.** Split policy evaluation into two points. `evaluate`/
`evaluatePreExecutionWithReason` stays exactly where spec 02 put it (admission, before the
executor is ever called) for rules that only need the node's declaration and the run's
accumulated state (risk level, evidence, requirement revision). A new
`evaluatePostExecution` runs after the executor returns but before the exit gate decides
COMPLETED, for rules that need the real reported output. A post-execution DENY is treated
exactly like an exit gate failing with no retry budget left: real rollback via the
checkpoint already taken for this node. A post-execution REQUIRE_APPROVAL (only
`no-dependency-additions` needs this) routes the node RUNNING to FAILED to PENDING to
WAITING_APPROVAL in one tick, using only edges the transition table already allows, so
approval-and-resume needed no new legal transition.

**Trade-off.** On approval after a post-execution REQUIRE_APPROVAL, the node returns to
PENDING and the engine re-runs its executor from scratch on the next pass. The diff a
human actually reviewed (left on disk, not rolled back) is not guaranteed to be
byte-identical to what the re-run produces, since this project has no artifact-freezing
mechanism. Documented plainly in `WorkflowEngine.applyPostExecutionPolicyIfViolated`'s
own comment rather than hidden: a real gap, not a design this session considers finished.

## D5. `protected-paths` splits into two rules, evaluated in a fixed order

**Problem.** The spec doc's own `protected-paths` rule (DENY any write outside
`target-service/` and `runs/`, catching `../` traversal) and a later correction
("protected-path enforcement reads the same writePaths declaration the checkpoints
use... a node that writes outside its declared paths is a policy violation") describe two
different things: one is a global security boundary, the other enforces the per-node
checkpoint contract. Treating them as one rule would blur a workspace escape (a real
security incident) with a node merely writing somewhere its own declaration did not
predict (a contract violation, potentially benign).

**Decision.** Two rules: `protected-paths-global` (every reported write, canonically
resolved, must fall under `target-service/` or `runs/`) evaluated first, then
`write-paths-contract` (every reported write must fall under the node's own declared
`writePaths`) evaluated second. A path that escapes the workspace is always reported as
the security violation it is, never merely as "outside this node's declared paths."
Neither rule diffs the filesystem: both check the *paths an executor reports having
written* (`filesWritten`, resolved and normalized), sidestepping the concurrency hazard a
real pre/post filesystem diff would reintroduce (a sibling node's legitimate concurrent
write to its own `writePaths` would otherwise look like this node writing outside its own).

**Trade-off.** `audit-completeness`, the spec doc's eighth rule, was dropped to keep the
total at eight after this split, rather than shipping a ninth rule. Every status
transition already goes through `WorkflowState.transition`, which always appends exactly
one audit event by construction; a node cannot reach a terminal status through any other
path in this codebase. `audit-completeness`'s DENY branch would therefore be unreachable
without deliberately corrupting `WorkflowState`'s internal state to fabricate a gap, which
would make its required "prove it can fire" test artificial rather than a real reachable
threshold, exactly what CLAUDE.md rule 6 argues against.

## D6. Cross-process resume needs a real CLI, scoped to what spec 05 actually tests

**Problem.** "Resume must work across a process boundary" cannot be tested by calling
`WorkflowState.fromJsonString(state.toJsonString())` inside one test method; that proves
JSON round-trip fidelity, not that a genuinely separate JVM process can pick a paused run
back up. Building that meant a real CLI entry point (`Main.java`) capable of starting,
resuming, and approving a run for real. The full spec doc's CLI describes running the
complete eight-node `sdlc-default.json` workflow, but that needs each stage's real output
threaded into the next stage's context (a real design spec into `ImplementExecutor`, a
real diff into `DocumentExecutor`), which today only happens by hand in
`FixtureRecorder`, not inside `WorkflowEngine` itself.

**Decision.** Built `Main.java` for real, with `run`, `resume`, and `approve` genuinely
wired to the real executor registry, real `AgentClientFactory`, and a real
`WorkflowEngine` (`amend` and `report` are declared but print "not yet implemented,"
since building either out is spec 06's and spec 08's job respectively). Rather than
wiring the full eight-node pipeline's cross-node context-threading (a materially larger
piece of work spec 05 does not ask for), the CLI's own demo workflow
(`workflows/approval-demo.json`) uses two real spec-04 executors, `RequirementExecutor`
(LOW risk) and `DocumentExecutor` (declared HIGH risk in this demo workflow specifically,
an honest workflow-authoring choice, not a fabricated property of the executor itself),
proving the real thing spec 05 is about: persistence and resume across a real process
boundary. `WorkflowEngine` gained `withInitialContext(nodeId, Supplier<Map>)` so the CLI
can seed a node's real input (a requirement file path; later, a downstream node's real
upstream artifact) without the engine needing to know what any particular executor
expects in its context, and without blocking `run`'s single call to wait between waves
for the caller to compute a later node's input.

**Trade-off.** The CLI's own demo pipeline is real but small: it does not exercise the
full eight-stage SDLC end to end, only two stages, because that is what spec 05 actually
asked to prove. Wiring the full pipeline's cross-node context threading into
`WorkflowEngine` (or a dedicated context-building layer) is left for whichever later spec
needs it, named here so it is not rediscovered as a surprise.

## Open items

Gaps identified during the build that are deliberately deferred, not forgotten. Each one
gets closed by a specific later spec, named here so it does not have to be rediscovered.

- **CLOSED (spec 05): AC-03-8's repo-wide scan of `runs/`.** Spec 03 could only deliver
  the narrower claim that `AnthropicClient`'s own exception path redacts the key,
  verified against a real HTTP response from a local test server, since no run had yet
  persisted real content to `runs/<runId>/`. Spec 05 makes both prerequisites real
  (executors write real artifacts, `WorkflowEngine` persists `state.json` after every
  wave), so `ApiKeyNeverLeaksIntoRunsTest` now does the actual scan: it makes one real,
  live call to the Anthropic API through the real CLI (`Main.java run --live`), then
  recursively scans every file under the resulting `runs/<runId>/` tree and the fixture
  it recorded for the literal key value, asserting zero matches. Skips (does not silently
  pass) when `ANTHROPIC_API_KEY` is not set.

- **Cross-node context threading is not wired into `WorkflowEngine`.** Real spec-04
  executors depend on the previous stage's real output (a design spec into
  `ImplementExecutor`, a real diff into `DocumentExecutor`); today that threading only
  happens by hand, once, in `FixtureRecorder`. `Main.java`'s CLI (spec 05) proves
  persistence and resume against a small two-node demo workflow specifically to avoid
  needing this; running the real eight-node `sdlc-default.json` through the CLI end to
  end will need either `WorkflowEngine` itself to track and forward each completed node's
  outputs to its dependents, or a dedicated context-building layer sitting between them.
  Not attempted here since no spec has asked for it yet; named so it is not rediscovered
  as a surprise when one does.

- **Live fixtures for the node executors (spec 04) are not real recordings.** The
  account behind `ANTHROPIC_API_KEY` in this environment has no credit balance
  (confirmed directly against the API, not a code issue: every real call returns "Your
  credit balance is too low to access the Anthropic API"), so spec 04's instruction to
  record a real `--live` fixture per executor could not be carried out. Every executor's
  fixture was instead produced by running the same `RecordingClient` a real `--live` run
  would use, backed by a test-only `AgentClient` standing in for the network rather than
  a genuine Anthropic response. This proves the record/replay mechanism and every
  executor's parsing, gate, and artifact-writing logic exactly as a real fixture would,
  since `RecordingClient` and `ReplayClient` do not know or care whether the response
  they are given came from a real call. What it does not prove is that a real model
  actually produces output in the shape these prompts expect. Once the account has
  credit, re-running each scenario once with `--live` will overwrite these placeholder
  fixtures with genuine recordings without touching any executor code, since the seam
  between "a client that returns a response" and "where that response came from" is
  exactly what `AgentClient` exists to hide.

- **TestExecutor's criterion-to-test mapping is containment, not regex extraction.**
  The first version tried to regex-extract a criterion id directly out of a test method
  name (`AC[A-Za-z0-9_]*`), greedily consuming the whole rest of the identifier (e.g.
  parsing `testAC_99_RENAMED_ProvesGreetingWorks` as criterion id
  `AC_99_RENAMED_ProvesGreetingWorks`, not `AC_99_RENAMED`), which is fundamentally
  ambiguous: nothing distinguishes where an arbitrary criterion id ends and the
  descriptive rest of the method name begins. Fixed by checking, for each criterion id
  the requirement actually declares in this run, whether any real test method name
  contains that exact identifier-safe string, rather than trying to parse an unknown-
  length id out of freeform text. This is still fully derived rather than hardcoded
  (AC-04-5's actual requirement): the set of ids checked against comes from
  `context.get("acceptanceCriteria")` for this run, not a table baked into
  `TestExecutor`, so a rename changes what is checked for automatically. Verified non-
  vacuously by deliberately replacing the check with "assume every declared criterion
  was found" and confirming `testACriterionWithNoMatchingTestMethodProducesNoEvidenceAndReportsFailure`
  then fails, before restoring the real check.

- **Live fixtures were re-recorded for real once the Anthropic account had credit.**
  Every fixture under `fixtures/` was replaced with a real recording made by
  `com.schwab.agentic.tools.FixtureRecorder`, a standalone one-time tool (not a unit
  test: it makes real, paid API calls and produces non-deterministic output, so it is
  deliberately not wired into `./scripts/test.sh`) using the exact same
  `AgentClientFactory.createLive` composition the real orchestrator uses in `--live`
  mode. `FakeAgentClient` no longer appears anywhere a fixture is produced; it remains
  only in unit tests that exercise an executor's own logic in isolation.

  Real model output failed real gates in ways the placeholder fixtures, written by hand,
  never could:
  - The real greenfield and ambiguous requirement text genuinely leaves several things
    unanswered (cache TTL, timeout duration, eviction policy, and others for
    greenfield); a real model correctly found these as `openQuestions`, and
    `requirement-complete` correctly fails, safe-stopping the run. This is the mechanism
    working as designed, not a fixture to fix, so it is kept and asserted directly by
    `GreenfieldEndToEndTest.testRealGreenfieldRequirementFixtureReplaysTheRealSafeStop`.
    `workflows/sdlc-default.json`'s REQUIREMENT node was also found, during this pass, to
    still declare `artifact-written` as its exit gate instead of `requirement-complete`,
    meaning the real workflow graph could never actually reach this safe-stop path even
    though the gate and its tests existed; fixed by wiring the correct gate into the
    workflow file.
  - The real design spec for a Spring Boot URL shortener naturally led the model toward
    Spring/Jackson/SLF4J-flavored implementation code on its first attempt, which failed
    to compile in the plain `throwaway-compile-project` fixture (a bare `java` plugin
    project with no Spring dependencies, chosen for fast, hermetic ImplementExecutor
    testing). The retry, given the real compiler output, produced simpler self-contained
    code that did compile, exercising AC-04-4 for real. `TestExecutor`'s test-writing
    attempts for the same scenario were less lucky: both the first attempt and its retry
    produced Spring/Jackson-referencing test code that also cannot compile in the plain
    project. This is recorded and asserted as a genuine, known environment-fidelity gap
    (`GreenfieldEndToEndTest.testRealTestFixtureReplaysTheRealRecordedFailure`), not
    patched by adding Spring dependencies to the throwaway project or by coaching the
    prompt away from a design choice that is otherwise reasonable for this codebase.
  - The first live recording pass also surfaced a design bug in the recorder itself, not
    in any executor: `FixtureRecorder`'s original `recordTest`/`recordImplement`/
    `recordDocument` each fed a stage a short, hand-written, disconnected description of
    an earlier stage's output instead of that stage's real recorded text. This produced
    a `TestExecutor` fixture where the model invented its own repository-backed
    `PreviewService` shape that disagreed with what `ImplementExecutor`'s model had
    actually written, for a reason that had nothing to do with either executor: neither
    was shown the other's real output. Fixed by threading every stage's real, actual
    output into the next stage's context (`recordDesign` returns the real
    `design-spec.json` text, `recordImplement` returns the real diff and reuses its own
    write-target directory as the compile project `recordTest` writes into), turning the
    recorder into a genuine sequential pipeline instead of eight independent calls with
    invented connective tissue.
  - `TestExecutor.buildUserPrompt` embedded `context.get("acceptanceCriteria")` via
    `Object.toString()`. For a caller building that value with `Map.of(...)` (the
    ordinary way to construct an immutable map, and what both `FixtureRecorder` and its
    tests did), `Map.of()`'s iteration order is not stable across JVM invocations: it
    depends on the JVM's per-run hash seed. The exact same logical criteria could
    therefore serialize to a different literal prompt, and hash to a different fixture,
    from one process run to the next, which is a real reproducibility defect in
    `--replay` mode, not just a test-harness inconvenience: a genuinely unchanged run
    could non-deterministically fail to find its own fixture depending on nothing more
    than JVM startup noise. Fixed by normalizing each criterion's keys into a fixed order
    before serializing with `Json.write`, independent of whatever map or list
    implementation the caller happened to pass. The `greenfield/test` fixture was
    re-recorded once (real cost: two calls, first attempt and retry) using
    `FixtureRecorder --only-test`, a mode added so re-recording one affected stage does
    not require re-spending on every other already-correct stage; the other nine
    fixtures were untouched by this fix and were not re-recorded.
  - A real retry's prompt embeds the real compiler output of the previous attempt, and a
    real compiler error names the real, unique temp directory path the attempt ran in.
    That makes a retry request fundamentally non-reproducible byte-for-byte in a new
    process (a fresh temp directory gets a fresh path every time), so a retry fixture can
    never be found again through `ReplayClient`'s normal hash lookup once the original
    recording process has exited. This is not a bug to fix: the compiler output is real
    and correct, and the path really did differ. Where a test needs the real, already-
    compiling result of a recorded retry (not a byte-exact replay of the retry call
    itself), it reads the retry fixture's stored response text directly and serves it
    through a fixed-response `AgentClient`, rather than trying to reconstruct a
    byte-identical retry request.

  Per-fixture outcome of the final live recording (10 fixtures: 3 requirement scenarios,
  2 impact scenarios, and design/implement/test/document for the greenfield pipeline):

  | Fixture | Recorded live | First attempt passed its gate |
  |---|---|---|
  | greenfield/requirement | yes | no (real open questions; correct safe-stop, no retry helps) |
  | ambiguous/requirement | yes | no (real open questions; correct safe-stop, no retry helps) |
  | brownfield/requirement | yes | no first attempt, yes on retry |
  | greenfield/impact | yes | yes |
  | brownfield/impact | yes | yes |
  | greenfield/design | yes | yes |
  | greenfield/implement | yes | no first attempt (Spring-flavored code), yes on retry |
  | greenfield/test | yes | no first attempt, no on retry (environment-fidelity gap, see above) |
  | greenfield/document | yes | yes |

  Verified zero fixtures still trace back to `FakeAgentClient`: every file under
  `fixtures/` was deleted before this recording pass (`FixtureRecorder.deleteExistingFixtures`),
  and `grep -rl FakeAgentClient fixtures/` (and a content grep for the literal strings
  `FakeAgentClient` and `unused-fake-fixture-key`, the sentinel value the old
  `FakeAgentClient.alwaysReturningText` factory stamped into every placeholder response)
  both return nothing.
