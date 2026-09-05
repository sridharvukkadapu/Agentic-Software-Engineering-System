package com.schwab.agentic.engine;

import static com.schwab.agentic.Assertions.assertEquals;
import static com.schwab.agentic.Assertions.assertFalse;
import static com.schwab.agentic.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;

/**
 * Covers {@link ApprovalStore}: an approval is keyed to the exact requirement revision it
 * was granted against (AC-05-7, required test), and the store survives a real file
 * round trip.
 */
public class ApprovalStoreTest {

    /**
     * Required test: an approval granted at revision 1 does not satisfy the same node at
     * revision 2. This is the mechanism spec 06's re-planning depends on: a re-plan bumps
     * the requirement's revision, and a stale approval must never be silently reused
     * against a requirement it was never actually reviewed against.
     */
    public void testApprovalGrantedAtRevisionOneDoesNotSatisfyRevisionTwo() {
        ApprovalStore store = new ApprovalStore();
        store.record(new ApprovalRecord("IMPLEMENT", 1, ApprovalRecord.Decision.APPROVED,
            "human:reviewer", "looks fine", Instant.now()));

        assertTrue(store.hasValidApproval("IMPLEMENT", 1),
            "the approval must satisfy the exact revision it was granted against");
        assertFalse(store.hasValidApproval("IMPLEMENT", 2),
            "an approval granted at revision 1 must not satisfy a check at revision 2");
    }

    public void testApprovalGrantedAtTheNewRevisionSatisfiesTheNewRevision() {
        ApprovalStore store = new ApprovalStore();
        store.record(new ApprovalRecord("IMPLEMENT", 1, ApprovalRecord.Decision.APPROVED,
            "human:reviewer", "looks fine at rev 1", Instant.now()));
        store.record(new ApprovalRecord("IMPLEMENT", 2, ApprovalRecord.Decision.APPROVED,
            "human:reviewer", "re-reviewed after amendment", Instant.now()));

        assertTrue(store.hasValidApproval("IMPLEMENT", 1), "the original approval still stands for revision 1");
        assertTrue(store.hasValidApproval("IMPLEMENT", 2), "a fresh approval at revision 2 satisfies revision 2");
    }

    public void testANodeDeniedAtARevisionDoesNotCountAsApproved() {
        ApprovalStore store = new ApprovalStore();
        store.record(new ApprovalRecord("IMPLEMENT", 1, ApprovalRecord.Decision.DENIED,
            "human:reviewer", "not ready", Instant.now()));

        assertFalse(store.hasValidApproval("IMPLEMENT", 1), "a denial must not be read as an approval");
    }

    /**
     * A later decision at the same revision supersedes an earlier one, but the earlier
     * one's record is never deleted: both survive in getRecords, only the answer to
     * hasValidApproval changes.
     */
    public void testALaterDecisionAtTheSameRevisionSupersedesAnEarlierOneWithoutDeletingIt() {
        ApprovalStore store = new ApprovalStore();
        store.record(new ApprovalRecord("IMPLEMENT", 1, ApprovalRecord.Decision.DENIED,
            "human:first-reviewer", "concerns about scope", Instant.now()));
        store.record(new ApprovalRecord("IMPLEMENT", 1, ApprovalRecord.Decision.APPROVED,
            "human:second-reviewer", "concerns addressed", Instant.now()));

        assertTrue(store.hasValidApproval("IMPLEMENT", 1),
            "the most recent decision at this revision is approved, so it must now count as approved");
        assertEquals(2, store.getRecords().size(), "both the denial and the later approval must remain in history");
    }

    public void testStoreRoundTripsThroughARealFile() throws IOException {
        Path runsDirectory = Files.createTempDirectory("approval-store-test");
        String runId = "RUN-1";

        ApprovalStore original = new ApprovalStore();
        original.record(new ApprovalRecord("IMPLEMENT", 1, ApprovalRecord.Decision.APPROVED,
            "human:reviewer", "diff reviewed", Instant.now()));
        original.saveToFile(runsDirectory, runId);

        Path approvalsFile = runsDirectory.resolve(runId).resolve("approvals.json");
        assertTrue(Files.isRegularFile(approvalsFile), "approvals.json must actually be written to disk");

        ApprovalStore loaded = ApprovalStore.loadFromFile(runsDirectory, runId);
        assertTrue(loaded.hasValidApproval("IMPLEMENT", 1),
            "an approval saved to a real file and loaded back in a fresh ApprovalStore instance must still be valid");
        assertEquals(1, loaded.getRecords().size(), "exactly one record must round trip");
    }

    public void testLoadFromFileReturnsAnEmptyStoreWhenNoFileExistsYet() throws IOException {
        Path runsDirectory = Files.createTempDirectory("approval-store-test-empty");
        ApprovalStore store = ApprovalStore.loadFromFile(runsDirectory, "RUN-NEVER-APPROVED-ANYTHING");
        assertFalse(store.hasValidApproval("ANY-NODE", 1), "a run with no approvals.json file has no valid approvals");
        assertEquals(0, store.getRecords().size(), "a fresh run has no approval records at all");
    }
}
