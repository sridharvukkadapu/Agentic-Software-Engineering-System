package com.schwab.agentic.engine;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Real rollback, not a status change.
 *
 * {@link #take} copies only the caller-declared {@code writePaths} of a working tree to
 * a checkpoint directory before a node that mutates them runs; {@link #restore} deletes
 * the current contents of exactly those same paths and copies the checkpoint back, then
 * verifies every restored file's content hash matches what was checkpointed. CLAUDE.md
 * rule 5 says rollback must actually restore: a class that emitted a ROLLBACK audit event
 * without touching the filesystem, or that copied files back but never checked they
 * arrived intact, would both violate that rule silently. Verification here is what turns
 * "we tried to restore" into "we confirmed the restore."
 *
 * Scoping every operation to {@code writePaths} rather than the whole working tree is
 * not an optimization, it is a correctness requirement once nodes execute in parallel
 * (spec 02's execution engine runs independent nodes, such as IMPLEMENT, TEST and
 * DOCUMENT, concurrently). A checkpoint or restore that touched the entire tree would
 * capture, or destroy, a sibling's in-flight or already-completed writes that happen to
 * fall outside the node actually being checkpointed: two nodes with disjoint
 * {@code writePaths} must be able to checkpoint, mutate and roll back independently
 * without either one observing the other's files at all. A node's declared
 * {@code writePaths} is exactly the contract that makes that independence sound: if two
 * nodes' declared paths ever overlap, that is a workflow authoring error, not something
 * this class can detect or fix.
 *
 * {@code .git} and common build output directories are excluded from both the copy and
 * the restore, since a checkpoint exists to protect source changes an agent made, not to
 * snapshot a VCS directory or rebuildable artifacts.
 */
public final class Checkpoint {

    private static final List<String> EXCLUDED_DIRECTORY_NAMES = List.of(".git", "target", "build", "node_modules", "out");

    /**
     * Copies only {@code writePaths} (each relative to {@code sourceDirectory}, and each
     * either a single file or a directory walked recursively) into
     * {@code runsDirectory}/&lt;runId&gt;/checkpoints/&lt;label&gt;/, recording each
     * copied file's relative path and content hash so {@link #restore} can verify
     * fidelity later. A path in {@code writePaths} that does not yet exist on disk is not
     * an error: a node may be about to create a new file, which has nothing to
     * checkpoint until it exists, and restoring it later means deleting it, not
     * restoring nonexistent prior content. Returns a handle identifying this checkpoint.
     */
    public Handle take(Path sourceDirectory, Path runsDirectory, String runId, String label, Set<String> writePaths) {
        if (!Files.isDirectory(sourceDirectory)) {
            throw new IllegalArgumentException("Checkpoint source directory does not exist: " + sourceDirectory);
        }
        if (writePaths == null || writePaths.isEmpty()) {
            throw new IllegalArgumentException(
                "Checkpoint.take requires at least one write path; a node with no declared writePaths"
                    + " should never be checkpointed at all (see WorkflowNode.isCheckpointed)");
        }
        Path checkpointDirectory = runsDirectory.resolve(runId).resolve("checkpoints").resolve(label);

        List<FileRecord> records = new ArrayList<>();
        try {
            Files.createDirectories(checkpointDirectory);
            for (String writePath : writePaths) {
                Path absolute = sourceDirectory.resolve(writePath);
                if (!Files.exists(absolute)) {
                    continue;
                }
                if (Files.isRegularFile(absolute)) {
                    copyOneFile(sourceDirectory, absolute, checkpointDirectory, records);
                } else {
                    try (Stream<Path> walk = Files.walk(absolute)) {
                        for (Path path : (Iterable<Path>) walk::iterator) {
                            if (isExcluded(sourceDirectory, path) || Files.isDirectory(path)) {
                                continue;
                            }
                            copyOneFile(sourceDirectory, path, checkpointDirectory, records);
                        }
                    }
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to take checkpoint " + label + " for run " + runId, e);
        }

        return new Handle(runId, label, sourceDirectory, checkpointDirectory, Set.copyOf(writePaths), List.copyOf(records));
    }

    private void copyOneFile(Path sourceDirectory, Path absoluteSourceFile, Path checkpointDirectory,
                              List<FileRecord> records) throws IOException {
        Path relative = sourceDirectory.relativize(absoluteSourceFile);
        Path destination = checkpointDirectory.resolve(relative);
        Files.createDirectories(destination.getParent());
        Files.copy(absoluteSourceFile, destination, StandardCopyOption.REPLACE_EXISTING);
        records.add(new FileRecord(relative.toString(), hashOf(absoluteSourceFile)));
    }

    /**
     * Restores {@code handle.sourceDirectory()} from its checkpoint, touching only
     * {@code handle.writePaths()}: deletes the current contents of exactly those paths
     * (never anything outside them), copies the checkpointed files back, then reads each
     * restored file back and compares its content hash against the one recorded at
     * {@link #take} time. Any mismatch, or any file that fails to restore, throws rather
     * than returning a result the caller might treat as success; a rollback that silently
     * restored the wrong bytes would be worse than no rollback at all, since it would
     * look like it worked.
     *
     * Returns the count of files restored, which the caller (the execution engine) puts
     * in the ROLLBACK audit event's details map. This method does not emit that event
     * itself; only {@link com.schwab.agentic.model.WorkflowState#record} can create an
     * {@link com.schwab.agentic.model.AuditEvent}, and only after this method has
     * returned successfully, so the event is never emitted before the files are back.
     */
    public int restore(Handle handle) {
        try {
            for (String writePath : handle.writePaths()) {
                deleteExistingContents(handle.sourceDirectory().resolve(writePath));
            }
            for (FileRecord record : handle.files()) {
                Path from = handle.checkpointDirectory().resolve(record.relativePath());
                Path to = handle.sourceDirectory().resolve(record.relativePath());
                Files.createDirectories(to.getParent());
                Files.copy(from, to, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to restore checkpoint " + handle.label(), e);
        }

        for (FileRecord record : handle.files()) {
            Path restored = handle.sourceDirectory().resolve(record.relativePath());
            String restoredHash = hashOf(restored);
            if (!restoredHash.equals(record.contentHash())) {
                throw new IllegalStateException(
                    "Checkpoint restore verification failed for " + record.relativePath()
                        + ": expected content hash " + record.contentHash() + " but found " + restoredHash);
            }
        }

        return handle.files().size();
    }

    /** Every checkpoint taken for a run, most recent last, by scanning the checkpoints directory. */
    public List<String> list(Path runsDirectory, String runId) {
        Path checkpointsRoot = runsDirectory.resolve(runId).resolve("checkpoints");
        if (!Files.isDirectory(checkpointsRoot)) {
            return List.of();
        }
        try (Stream<Path> entries = Files.list(checkpointsRoot)) {
            return entries.filter(Files::isDirectory)
                .map(path -> path.getFileName().toString())
                .sorted()
                .toList();
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to list checkpoints for run " + runId, e);
        }
    }

    /**
     * Deletes {@code path} if it is a file, or everything under it if it is a directory,
     * leaving excluded directory names (see {@link #EXCLUDED_DIRECTORY_NAMES}) untouched
     * either way. A nonexistent path is not an error: restoring a node whose write path
     * declared a file it never got around to creating has nothing to delete.
     */
    private void deleteExistingContents(Path path) throws IOException {
        if (!Files.exists(path)) {
            return;
        }
        if (Files.isRegularFile(path)) {
            Files.delete(path);
            return;
        }
        try (Stream<Path> entries = Files.list(path)) {
            for (Path entry : (Iterable<Path>) entries::iterator) {
                if (isExcludedTopLevel(entry)) {
                    continue;
                }
                deleteRecursively(entry);
            }
        }
    }

    private void deleteRecursively(Path path) throws IOException {
        if (!Files.exists(path)) {
            return;
        }
        Files.walkFileTree(path, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.delete(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                Files.delete(dir);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private boolean isExcluded(Path root, Path path) {
        Path relative = root.relativize(path);
        for (Path part : relative) {
            if (EXCLUDED_DIRECTORY_NAMES.contains(part.toString())) {
                return true;
            }
        }
        return false;
    }

    private boolean isExcludedTopLevel(Path path) {
        return EXCLUDED_DIRECTORY_NAMES.contains(path.getFileName().toString());
    }

    private String hashOf(Path file) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(Files.readAllBytes(file));
            StringBuilder hex = new StringBuilder();
            for (byte b : hash) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 must be available on every JDK 21 installation", e);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to hash file " + file, e);
        }
    }

    /** One file captured by a checkpoint: its path relative to the working tree root, and its content hash. */
    public record FileRecord(String relativePath, String contentHash) {
    }

    /**
     * Identifies one checkpoint and everything needed to restore it. {@code writePaths}
     * is the exact set of paths this checkpoint is scoped to, the same set restore uses
     * to know what it is and is not allowed to delete; {@code files} is the exact set of
     * files {@link #take} found under those paths, each with the hash {@link #restore}
     * verifies against.
     */
    public record Handle(String runId, String label, Path sourceDirectory, Path checkpointDirectory,
                          Set<String> writePaths, List<FileRecord> files) {
    }
}
