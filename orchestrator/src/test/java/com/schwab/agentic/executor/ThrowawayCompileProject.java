package com.schwab.agentic.executor;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.stream.Stream;

/**
 * Copies the committed throwaway Gradle project (src/test/resources/throwaway-compile-project)
 * into a fresh temp directory per test, so ImplementExecutor and TestExecutor tests exercise
 * a real, hermetic compile/test cycle without depending on Maven being installed globally
 * (it is not, in this environment) or compiling the real, much larger target-service/
 * Spring Boot project on every test run.
 */
final class ThrowawayCompileProject {

    private ThrowawayCompileProject() {
    }

    static Path copyFresh() {
        Path source = findFixtureSource();
        Path destination;
        try {
            destination = Files.createTempDirectory("throwaway-compile-project");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        copyRecursively(source, destination);
        return destination;
    }

    private static Path findFixtureSource() {
        Path current = Path.of("").toAbsolutePath();
        while (current != null) {
            Path candidate = current.resolve("orchestrator/src/test/resources/throwaway-compile-project");
            if (candidate.toFile().isDirectory()) {
                return candidate;
            }
            current = current.getParent();
        }
        throw new IllegalStateException(
            "Could not find orchestrator/src/test/resources/throwaway-compile-project by walking up from "
                + Path.of("").toAbsolutePath());
    }

    private static void copyRecursively(Path source, Path destination) {
        try (Stream<Path> walk = Files.walk(source)) {
            for (Path path : (Iterable<Path>) walk::iterator) {
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
            throw new UncheckedIOException("Failed to copy throwaway compile project fixture", e);
        }
    }
}
