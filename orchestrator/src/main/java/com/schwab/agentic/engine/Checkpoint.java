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
import java.util.stream.Stream;

/**
 * Real rollback, not a status change.
 *
 * {@link #take} copies a working tree to a checkpoint directory before a node that
 * mutates it runs; {@link #restore} deletes the current contents of that working tree
 * and copies the checkpoint back, then verifies every restored file's content hash
 * matches what was checkpointed. CLAUDE.md rule 5 says rollback must actually restore: a
 * class that emitted a ROLLBACK audit event without touching the filesystem, or that
 * copied files back but never checked they arrived intact, would both violate that rule
 * silently. Verification here is what turns "we tried to restore" into "we confirmed the
 * restore."
 *
 * {@code .git} and common build output directories are excluded from both the copy and
 * the restore, since a checkpoint exists to protect source changes an agent made, not to
 * snapshot a VCS directory or rebuildable artifacts.
 */
public final class Checkpoint {

    private static final List<String> EXCLUDED_DIRECTORY_NAMES = List.of(".git", "target", "build", "node_modules", "out");

    /**
     * Copies {@code sourceDirectory}'s working tree into
     * {@code runsDirectory}/&lt;runId&gt;/checkpoints/&lt;label&gt;/, recording each
     * copied file's relative path and content hash so {@link #restore} can verify
     * fidelity later. Returns a handle identifying this checkpoint.
     */
    public Handle take(Path sourceDirectory, Path runsDirectory, String runId, String label) {
        if (!Files.isDirectory(sourceDirectory)) {
            throw new IllegalArgumentException("Checkpoint source directory does not exist: " + sourceDirectory);
        }
        Path checkpointDirectory = runsDirectory.resolve(runId).resolve("checkpoints").resolve(label);

        List<FileRecord> records = new ArrayList<>();
        try {
            Files.createDirectories(checkpointDirectory);
            try (Stream<Path> walk = Files.walk(sourceDirectory)) {
                for (Path path : (Iterable<Path>) walk::iterator) {
                    if (isExcluded(sourceDirectory, path)) {
                        continue;
                    }
                    Path relative = sourceDirectory.relativize(path);
                    Path destination = checkpointDirectory.resolve(relative);
                    if (Files.isDirectory(path)) {
                        Files.createDirectories(destination);
                    } else {
                        Files.createDirectories(destination.getParent());
                        Files.copy(path, destination, StandardCopyOption.REPLACE_EXISTING);
                        records.add(new FileRecord(relative.toString(), hashOf(path)));
                    }
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to take checkpoint " + label + " for run " + runId, e);
        }

        return new Handle(runId, label, sourceDirectory, checkpointDirectory, List.copyOf(records));
    }

    /**
     * Restores {@code handle.sourceDirectory()} from its checkpoint: deletes every
     * non-excluded file currently in the source directory, copies the checkpoint's files
     * back, then reads each restored file back and compares its content hash against the
     * one recorded at {@link #take} time. Any mismatch, or any file that fails to
     * restore, throws rather than returning a result the caller might treat as success;
     * a rollback that silently restored the wrong bytes would be worse than no rollback
     * at all, since it would look like it worked.
     *
     * Returns the count of files restored, which the caller (the execution engine) puts
     * in the ROLLBACK audit event's details map. This method does not emit that event
     * itself; only {@link com.schwab.agentic.model.WorkflowState#record} can create an
     * {@link com.schwab.agentic.model.AuditEvent}, and only after this method has
     * returned successfully, so the event is never emitted before the files are back.
     */
    public int restore(Handle handle) {
        try {
            deleteExistingContents(handle.sourceDirectory());
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

    private void deleteExistingContents(Path directory) throws IOException {
        if (!Files.isDirectory(directory)) {
            Files.createDirectories(directory);
            return;
        }
        try (Stream<Path> entries = Files.list(directory)) {
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
     * Identifies one checkpoint and everything needed to restore it. {@code files} is
     * the exact set of files {@link #take} captured, each with the hash {@link #restore}
     * verifies against.
     */
    public record Handle(String runId, String label, Path sourceDirectory, Path checkpointDirectory,
                          List<FileRecord> files) {
    }
}
