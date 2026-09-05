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
