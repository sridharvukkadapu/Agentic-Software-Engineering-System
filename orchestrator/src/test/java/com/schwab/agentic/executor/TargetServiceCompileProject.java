package com.schwab.agentic.executor;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.stream.Stream;

/**
 * Copies the real {@code target-service/} project into a fresh temp directory, excluding
 * {@code build/} and {@code .gradle/} (regeneratable build caches, not source). Unlike
 * {@link ThrowawayCompileProject} (a minimal, dependency-free stand-in used to test
 * TestExecutor quickly), this is ImplementExecutor's real, actual target in production:
 * a real Spring Boot project with real Spring, Jackson, and other dependencies genuinely
 * available on its classpath. A test that wants to prove ImplementExecutor's real output
 * actually compiles must do so against a real copy of what ImplementExecutor is really
 * pointed at, never a stand-in that lacks the frameworks the real target genuinely has.
 *
 * Copying into a fresh temp directory each time, rather than writing into the real
 * {@code target-service/} working tree directly, is what keeps a test free to write
 * whatever an executor produces without ever risking corrupting the actual, git-tracked
 * project a developer is working in.
 */
final class TargetServiceCompileProject {

    private static final List<String> EXCLUDED_DIRECTORY_NAMES = List.of("build", ".gradle", ".git");

    private TargetServiceCompileProject() {
    }

    static Path copyFresh() {
        Path source = findSource();
        Path destination;
        try {
            destination = Files.createTempDirectory("target-service-compile-project");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        copyRecursively(source, destination);
        return destination;
    }

    private static Path findSource() {
        Path current = Path.of("").toAbsolutePath();
        while (current != null) {
            Path candidate = current.resolve("target-service");
            if (candidate.resolve("build.gradle.kts").toFile().isFile()) {
                return candidate;
            }
            current = current.getParent();
        }
        throw new IllegalStateException(
            "Could not find target-service by walking up from " + Path.of("").toAbsolutePath());
    }

    private static void copyRecursively(Path source, Path destination) {
        try (Stream<Path> walk = Files.walk(source)) {
            for (Path path : (Iterable<Path>) walk::iterator) {
                if (isExcluded(source, path)) {
                    continue;
                }
                Path relative = source.relativize(path);
                Path target = destination.resolve(relative);
                if (Files.isDirectory(path)) {
                    Files.createDirectories(target);
                } else {
                    Files.createDirectories(target.getParent());
                    Files.copy(path, target, StandardCopyOption.COPY_ATTRIBUTES);
                    target.toFile().setExecutable(path.toFile().canExecute());
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to copy target-service", e);
        }
    }

    private static boolean isExcluded(Path root, Path path) {
        Path relative = root.relativize(path);
        for (Path part : relative) {
            if (EXCLUDED_DIRECTORY_NAMES.contains(part.toString())) {
                return true;
            }
        }
        return false;
    }
}
