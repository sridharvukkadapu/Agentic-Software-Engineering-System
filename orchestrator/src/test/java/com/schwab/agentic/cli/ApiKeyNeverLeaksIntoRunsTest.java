package com.schwab.agentic.cli;

import static com.schwab.agentic.Assertions.assertEquals;
import static com.schwab.agentic.Assertions.assertTrue;

import com.schwab.agentic.SkippedException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/**
 * Closes the open item recorded in docs/decisions.md: AC-03-8 requires a repo-wide scan
 * of {@code runs/} proving the Anthropic API key never appears in any audit event, log
 * line, or artifact. That scan could not exist when spec 03 was built, since no run had
 * yet persisted real content to {@code runs/<runId>/} (no executor existed, and no run
 * state was written to disk). Spec 05 makes both real, so this is where the scan spec 03
 * asked for is finally possible to write.
 *
 * Makes one real, live call to the Anthropic API (via {@code Main.java run --live}, the
 * same real CLI path a user would invoke) so there is genuine persisted content to scan,
 * not a fixture a test wrote by hand. Skips (does not fail, per this project's earlier
 * fix distinguishing a real skip from a silent pass) when {@code ANTHROPIC_API_KEY} is
 * not set, exactly like {@code AnthropicClientTest.testLiveCallAgainstTheRealApiReturnsText}.
 */
public class ApiKeyNeverLeaksIntoRunsTest {

    private static final Path CLASSPATH = findCompiledMainClasspath();

    private static Path findCompiledMainClasspath() {
        Path current = Path.of("").toAbsolutePath();
        while (current != null) {
            Path candidate = current.resolve("orchestrator/out/main");
            if (candidate.toFile().isDirectory()) {
                return candidate;
            }
            current = current.getParent();
        }
        throw new IllegalStateException("Could not find orchestrator/out/main by walking up from "
            + Path.of("").toAbsolutePath());
    }

    private static Path findRepoRoot() {
        Path current = Path.of("").toAbsolutePath();
        while (current != null) {
            if (current.resolve("workflows").toFile().isDirectory()
                && current.resolve("scenarios").toFile().isDirectory()) {
                return current;
            }
            current = current.getParent();
        }
        throw new IllegalStateException("Could not find repo root by walking up from " + Path.of("").toAbsolutePath());
    }

    public void testApiKeyNeverAppearsAnywhereUnderRunsAfterARealLiveRun() throws IOException, InterruptedException {
        String apiKey = System.getenv("ANTHROPIC_API_KEY");
        if (apiKey == null || apiKey.isBlank()) {
            throw new SkippedException("ANTHROPIC_API_KEY is not set");
        }

        Path repoRoot = findRepoRoot();
        Path runsDirectory = Files.createTempDirectory("api-key-leak-scan-runs");
        Path fixturesDirectory = Files.createTempDirectory("api-key-leak-scan-fixtures");
        String runId = "AC-03-8-SCAN-" + System.nanoTime();

        List<String> command = List.of(
            System.getProperty("java.home") + "/bin/java", "-cp", CLASSPATH.toString(),
            "com.schwab.agentic.cli.Main", "run",
            "--workflow", "workflows/approval-demo.json",
            "--requirement", "scenarios/_smoke/requirement.md",
            "--live", "--run-id", runId,
            "--runs", runsDirectory.toString(),
            "--fixtures", fixturesDirectory.toString());

        ProcessBuilder builder = new ProcessBuilder(command);
        builder.directory(repoRoot.toFile());
        builder.redirectErrorStream(true);
        Process process = builder.start();
        String output = new String(process.getInputStream().readAllBytes());
        boolean finished = process.waitFor(60, TimeUnit.SECONDS);
        if (!finished) {
            process.destroyForcibly();
            throw new IllegalStateException("The real --live run did not finish within 60 seconds");
        }
        assertEquals(0, process.exitValue(), "the real --live run must exit 0: " + output);

        Path runDirectory = runsDirectory.resolve(runId);
        assertTrue(Files.isDirectory(runDirectory), "the real run must have written something under " + runDirectory);

        List<Path> allFilesUnderRun;
        try (Stream<Path> walk = Files.walk(runDirectory)) {
            allFilesUnderRun = walk.filter(Files::isRegularFile).toList();
        }
        assertTrue(!allFilesUnderRun.isEmpty(), "the real run must have written at least one real file to scan");

        List<Path> filesContainingTheRealKey = new java.util.ArrayList<>();
        for (Path file : allFilesUnderRun) {
            String content = Files.readString(file);
            if (content.contains(apiKey)) {
                filesContainingTheRealKey.add(file);
            }
        }

        // Also scan the fixture the real live call wrote, which lives outside runs/ but
        // is exactly the kind of "artifact" AC-03-8 names: a persisted record of what the
        // agent layer actually sent and received.
        List<Path> allFixtureFiles;
        try (Stream<Path> walk = Files.walk(fixturesDirectory)) {
            allFixtureFiles = walk.filter(Files::isRegularFile).toList();
        }
        for (Path file : allFixtureFiles) {
            String content = Files.readString(file);
            if (content.contains(apiKey)) {
                filesContainingTheRealKey.add(file);
            }
        }

        assertEquals(0, filesContainingTheRealKey.size(),
            "the real API key must never appear in any file under runs/ or the recorded fixtures, but found it in: "
                + filesContainingTheRealKey);
    }
}
