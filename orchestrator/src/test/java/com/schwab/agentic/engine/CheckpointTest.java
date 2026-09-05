package com.schwab.agentic.engine;

import static com.schwab.agentic.Assertions.assertEquals;
import static com.schwab.agentic.Assertions.assertFalse;
import static com.schwab.agentic.Assertions.assertThrows;
import static com.schwab.agentic.Assertions.assertTrue;

import com.schwab.agentic.model.AuditEvent;
import com.schwab.agentic.model.RequirementSpec;
import com.schwab.agentic.model.WorkflowNode;
import com.schwab.agentic.model.WorkflowState;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Covers {@link Checkpoint} directly, not only through {@link WorkflowEngine}'s rollback
 * scenarios. The corrupted-checkpoint test is the one that actually proves CLAUDE.md rule
 * 5 and AC-02-8's structural argument are real rather than merely asserted in a Javadoc
 * comment: a rollback mechanism that could restore corrupted bytes and report success
 * would be worse than no rollback at all, since it would look like it worked.
 */
public class CheckpointTest {

    public void testTakeThenRestoreReturnsFilesToExactPriorContent() throws IOException {
        Path sourceDir = Files.createTempDirectory("checkpoint-source");
        Path runsDir = Files.createTempDirectory("checkpoint-runs");
        Path fileA = sourceDir.resolve("A.txt");
        Path fileB = sourceDir.resolve("nested/B.txt");
        Files.writeString(fileA, "original A content");
        Files.createDirectories(fileB.getParent());
        Files.writeString(fileB, "original B content");

        Checkpoint checkpoint = new Checkpoint();
        Checkpoint.Handle handle = checkpoint.take(sourceDir, runsDir, "RUN-1", "N1");

        Files.writeString(fileA, "mutated A content, should be reverted");
        Files.writeString(fileB, "mutated B content, should be reverted");
        Files.writeString(sourceDir.resolve("new-file-not-in-checkpoint.txt"), "must be deleted by restore");

        int restoredCount = checkpoint.restore(handle);

        assertEquals(2, restoredCount, "expected exactly the two originally-checkpointed files to be restored");
        assertEquals("original A content", Files.readString(fileA), "A must be restored to its exact prior content");
        assertEquals("original B content", Files.readString(fileB), "B must be restored to its exact prior content");
        assertFalse(Files.exists(sourceDir.resolve("new-file-not-in-checkpoint.txt")),
            "a file created after the checkpoint must be removed by restore, since it was not part of the snapshot");
    }

    /**
     * The test that matters. take() records each file's content hash; this test then
     * mutates the checkpoint copy on disk directly (not the source, the checkpoint
     * itself, simulating disk corruption or a bug that wrote the wrong bytes into the
     * checkpoint), and asserts restore() throws rather than silently copying the
     * corrupted bytes back and reporting success. Without this check, "rollback" would
     * mean nothing more than "we ran some copy commands," which is exactly the failure
     * mode CLAUDE.md rule 5 exists to rule out.
     */
    public void testCorruptedCheckpointFileCausesRestoreToThrow() throws IOException {
        Path sourceDir = Files.createTempDirectory("checkpoint-source");
        Path runsDir = Files.createTempDirectory("checkpoint-runs");
        Path file = sourceDir.resolve("Service.java");
        Files.writeString(file, "original content, this is what restore should verify against");

        Checkpoint checkpoint = new Checkpoint();
        Checkpoint.Handle handle = checkpoint.take(sourceDir, runsDir, "RUN-1", "N1");

        // Corrupt the checkpoint's own copy on disk, after take() already recorded the
        // correct hash for the original content. This is not mutating the source file;
        // it is mutating the backup itself, the one scenario a hash check specifically
        // exists to catch.
        Path checkpointedFile = handle.checkpointDirectory().resolve("Service.java");
        Files.writeString(checkpointedFile, "corrupted checkpoint bytes, do not let this be restored silently");

        Files.writeString(file, "source file mutated by a failing node, needs real rollback");

        IllegalStateException thrown = assertThrows(IllegalStateException.class,
            () -> checkpoint.restore(handle),
            "restore() must throw when a checkpointed file's content hash no longer matches what was recorded");
        assertTrue(thrown.getMessage().contains("Service.java"),
            "the thrown exception must name the specific file that failed verification: " + thrown.getMessage());
    }

    /**
     * After a corrupted-checkpoint restore throws, no ROLLBACK-shaped audit event may
     * exist: only a successful restore may ever be followed by one (WorkflowEngine
     * records it after restore() returns, never before, and never in a catch block for a
     * failed restore). This exercises that invariant at the WorkflowState level directly,
     * independent of the engine, by simulating exactly what a caller must not do:
     * catching the restore failure and recording success anyway would be the bug; this
     * test proves that if restore() throws and the caller does nothing further (the
     * correct behavior), the audit log carries no record of a rollback having happened.
     */
    public void testNoRollbackShapedAuditEventExistsAfterACorruptedRestoreThrows() throws IOException {
        Path sourceDir = Files.createTempDirectory("checkpoint-source");
        Path runsDir = Files.createTempDirectory("checkpoint-runs");
        Path file = sourceDir.resolve("Service.java");
        Files.writeString(file, "original content");

        Checkpoint checkpoint = new Checkpoint();
        Checkpoint.Handle handle = checkpoint.take(sourceDir, runsDir, "RUN-1", "N1");
        Files.writeString(handle.checkpointDirectory().resolve("Service.java"), "corrupted");

        WorkflowNode node = new WorkflowNode("N1", "N1", "noop", Set.of(), null, null,
            com.schwab.agentic.model.RiskLevel.LOW, 1, Set.of());
        RequirementSpec requirementSpec = new RequirementSpec(
            "REQ-1", 1, "req", "req normalized", List.of());
        WorkflowState state = new WorkflowState("RUN-1", requirementSpec, List.of(node));

        try {
            checkpoint.restore(handle);
            state.record(AuditEvent.EventType.ARTIFACT_WRITTEN, "engine",
                "rollback restored files for node N1", Map.of("nodeId", "N1"));
            throw new AssertionError("restore() was expected to throw but did not");
        } catch (IllegalStateException expected) {
            // Correct behavior: restore() threw, and because it threw, the line that
            // would record the rollback-shaped audit event was never reached.
        }

        boolean anyRollbackShapedEvent = state.getAuditLog().stream()
            .anyMatch(event -> event.reason() != null && event.reason().toLowerCase().contains("rollback"));
        assertFalse(anyRollbackShapedEvent,
            "no audit event mentioning rollback may exist when restore() threw before completing");
        assertEquals(0, state.getAuditLog().size(),
            "the audit log must be entirely empty: nothing was ever successfully recorded");
    }

    public void testGitAndBuildOutputDirectoriesAreExcludedFromTheCopy() throws IOException {
        Path sourceDir = Files.createTempDirectory("checkpoint-source");
        Path runsDir = Files.createTempDirectory("checkpoint-runs");

        Files.writeString(sourceDir.resolve("Service.java"), "real source file");
        Files.createDirectories(sourceDir.resolve(".git"));
        Files.writeString(sourceDir.resolve(".git/HEAD"), "ref: refs/heads/main");
        Files.createDirectories(sourceDir.resolve("target/classes"));
        Files.writeString(sourceDir.resolve("target/classes/Service.class"), "compiled bytecode, not source");
        Files.createDirectories(sourceDir.resolve("build"));
        Files.writeString(sourceDir.resolve("build/output.jar"), "build output, not source");

        Checkpoint checkpoint = new Checkpoint();
        Checkpoint.Handle handle = checkpoint.take(sourceDir, runsDir, "RUN-1", "N1");

        List<String> checkpointedPaths = handle.files().stream().map(Checkpoint.FileRecord::relativePath).toList();
        assertTrue(checkpointedPaths.contains("Service.java"), "the real source file must be checkpointed");
        assertFalse(checkpointedPaths.stream().anyMatch(path -> path.contains(".git")),
            ".git contents must never be checkpointed: " + checkpointedPaths);
        assertFalse(checkpointedPaths.stream().anyMatch(path -> path.startsWith("target")),
            "target/ (build output) contents must never be checkpointed: " + checkpointedPaths);
        assertFalse(checkpointedPaths.stream().anyMatch(path -> path.startsWith("build")),
            "build/ (build output) contents must never be checkpointed: " + checkpointedPaths);

        assertFalse(Files.exists(handle.checkpointDirectory().resolve(".git")),
            "the checkpoint directory on disk must not contain a .git subdirectory at all");
        assertFalse(Files.exists(handle.checkpointDirectory().resolve("target")),
            "the checkpoint directory on disk must not contain a target subdirectory at all");
    }

    public void testRestoreThrowsWhenARecordedFileIsMissingFromTheCheckpointDirectory() throws IOException {
        Path sourceDir = Files.createTempDirectory("checkpoint-source");
        Path runsDir = Files.createTempDirectory("checkpoint-runs");
        Files.writeString(sourceDir.resolve("Service.java"), "original content");

        Checkpoint checkpoint = new Checkpoint();
        Checkpoint.Handle handle = checkpoint.take(sourceDir, runsDir, "RUN-1", "N1");

        // Simulate a checkpoint file that was deleted from disk after being taken, a
        // different corruption mode than mutated bytes: the file the handle still claims
        // to have is simply gone.
        Files.delete(handle.checkpointDirectory().resolve("Service.java"));

        assertThrows(java.io.UncheckedIOException.class,
            () -> checkpoint.restore(handle),
            "restore() must throw, not silently skip, when a checkpointed file is missing from disk");
    }
}
