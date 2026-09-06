package com.schwab.agentic.executor;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads back the real content of the files named in a real {@code implementation.diff}
 * (the exact files {@link ImplementExecutor} reported writing), so {@link TestExecutor}
 * can be told the real class names, package names, and signatures it must test (see
 * {@code TestExecutor}'s {@code implementationSource} context key) instead of inventing
 * its own. Reading the diff's own file list, rather than guessing a fixed package root,
 * is what keeps this correct regardless of which real package name the model's real
 * output happens to choose (which has varied between recordings: {@code com.example.preview},
 * {@code com.schwab.urlshortener.preview}, and others, none of them predictable in
 * advance) and avoids pulling in target-service's own large, pre-existing, unrelated
 * source tree.
 *
 * Kept as a standalone class, not a private method on either executor, since both
 * {@code Main}'s CLI wiring and {@code FixtureRecorder}'s fixture-recording pipeline need
 * the exact same read, purely from disk artifacts, and the two must never quietly diverge
 * (a real request's prompt hash depends on this text being byte-identical to what a
 * fixture was recorded against).
 */
public final class ImplementationSourceReader {

    private static final Pattern DIFF_FILE_HEADER = Pattern.compile("^--- a/(.+)$", Pattern.MULTILINE);

    private ImplementationSourceReader() {
    }

    /** Reads the real content of every file named in {@code implementationDiffPath}, relative to {@code targetDirectory}. */
    public static String readFromDiff(Path targetDirectory, Path implementationDiffPath) {
        if (!Files.isRegularFile(implementationDiffPath)) {
            return "(no new source files found)";
        }
        String diffContent;
        try {
            diffContent = Files.readString(implementationDiffPath);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read " + implementationDiffPath, e);
        }
        Set<String> relativePaths = new LinkedHashSet<>();
        Matcher matcher = DIFF_FILE_HEADER.matcher(diffContent);
        while (matcher.find()) {
            relativePaths.add(matcher.group(1));
        }
        return read(targetDirectory, List.copyOf(relativePaths));
    }

    /** Reads the real content of each of {@code filesWritten}, relative to {@code targetDirectory}. */
    public static String read(Path targetDirectory, List<String> filesWritten) {
        if (filesWritten == null || filesWritten.isEmpty()) {
            return "(no new source files found)";
        }
        StringBuilder combined = new StringBuilder();
        for (String relativePath : filesWritten) {
            Path absolute = targetDirectory.resolve(relativePath);
            if (!Files.isRegularFile(absolute)) {
                continue;
            }
            combined.append("// FILE: ").append(relativePath).append('\n');
            try {
                combined.append(Files.readString(absolute)).append("\n\n");
            } catch (IOException e) {
                throw new UncheckedIOException("Failed to read " + absolute, e);
            }
        }
        return combined.toString();
    }
}
