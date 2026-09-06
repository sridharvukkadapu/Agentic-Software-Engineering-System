package com.schwab.agentic.executor;

import static com.schwab.agentic.Assertions.assertEquals;
import static com.schwab.agentic.Assertions.assertTrue;

import com.schwab.agentic.json.Json;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * The real, required proof that the full eight-node {@code sdlc-default.json} pipeline
 * runs end to end through the actual CLI, invoked as a genuine subprocess exactly as
 * {@code MainCliResumeTest} invokes {@code approval-demo.json}, not by calling executors
 * directly the way {@link GreenfieldEndToEndTest} does. This is the difference between
 * "the plumbing exists" and "a reviewer running {@code ./scripts/run.sh greenfield} sees
 * this for real": every node here is registered in {@code Main.buildEngine}'s real
 * registry, every cross-node context key is threaded from a real artifact file on disk
 * (never a direct in-memory value), and the real {@code compiles}/{@code tests-pass} exit
 * gates run real {@code ./gradlew} commands against a real, fresh copy of
 * {@code target-service}.
 *
 * Runs against a fresh {@link TargetServiceCompileProject} copy, not the tracked
 * {@code target-service/} working tree, so this test can never leave behind real, changed
 * source in a developer's own checkout, matching the same reasoning
 * {@code GreenfieldEndToEndTest} already documents for why it never targets the real
 * directory directly.
 */
public class MainCliFullPipelineTest {

    private static final Path CLASSPATH = findCompiledMainClasspath();
    private static final String JAVA_BINARY = System.getProperty("java.home") + "/bin/java";

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
            + Path.of("").toAbsolutePath() + ". Run ./scripts/build.sh or ./scripts/test.sh first.");
    }

    private static Path findRepoRoot() {
        Path current = Path.of("").toAbsolutePath();
        while (current != null) {
            if (current.resolve("workflows").toFile().isDirectory()
                && current.resolve("fixtures").toFile().isDirectory()) {
                return current;
            }
            current = current.getParent();
        }
        throw new IllegalStateException("Could not find repo root by walking up from " + Path.of("").toAbsolutePath());
    }

    public void testRunReachesCompletedWithAllEightNodesCompletedAcrossARealCliSubprocess()
        throws IOException, InterruptedException {
        Path repoRoot = findRepoRoot();
        Path runsDirectory = Files.createTempDirectory("cli-full-pipeline-runs");
        Path targetServiceCopy = TargetServiceCompileProject.copyFresh();
        String runId = "CLI-FULL-PIPELINE-" + System.nanoTime();

        ProcessResult runResult = runMainAsASeparateProcess(repoRoot, runsDirectory, "run",
            "--workflow", "workflows/sdlc-default.json",
            "--requirement", "scenarios/greenfield/requirement.md",
            "--target-service", targetServiceCopy.toString(),
            "--replay", "--auto-approve", "--run-id", runId, "--fixtures", "fixtures");
        assertEquals(0, runResult.exitCode(), "run must exit 0: " + runResult.combinedOutput());
        assertTrue(runResult.combinedOutput().contains("COMPLETED"),
            "the run must reach COMPLETED: " + runResult.combinedOutput());

        Path statePath = runsDirectory.resolve(runId).resolve("state.json");
        assertTrue(Files.isRegularFile(statePath), "state.json must actually be written to disk: " + statePath);

        Map<String, Object> finalState = readStateJson(statePath);
        assertEquals("COMPLETED", finalState.get("workflowStatus"), "the final persisted state must be COMPLETED");

        List<Object> finalNodes = (List<Object>) finalState.get("nodes");
        assertEquals(8, finalNodes.size(), "the full sdlc-default.json graph declares eight nodes");
        for (Object nodeObj : finalNodes) {
            Map<?, ?> node = (Map<?, ?>) nodeObj;
            assertEquals("COMPLETED", node.get("status"),
                "every node must be COMPLETED in the final state: " + node.get("id"));
        }

        assertRealArtifactsExist(runsDirectory, runId);
    }

    private void assertRealArtifactsExist(Path runsDirectory, String runId) {
        Path artifactsDirectory = runsDirectory.resolve(runId).resolve("artifacts");
        assertTrue(Files.isRegularFile(artifactsDirectory.resolve("requirement-spec.json")),
            "requirement-spec.json must exist as a real artifact");
        assertTrue(Files.isRegularFile(artifactsDirectory.resolve("impact.json")),
            "impact.json must exist as a real artifact");
        assertTrue(Files.isRegularFile(artifactsDirectory.resolve("design-spec.json")),
            "design-spec.json must exist as a real artifact");
        assertTrue(Files.isRegularFile(artifactsDirectory.resolve("implementation.diff")),
            "implementation.diff must exist as a real artifact");
        assertTrue(Files.isRegularFile(artifactsDirectory.resolve("api-docs.md")),
            "api-docs.md must exist as a real artifact");
        assertTrue(Files.isRegularFile(artifactsDirectory.resolve("validation-report.md")),
            "validation-report.md must exist as a real artifact");
        assertTrue(Files.isRegularFile(artifactsDirectory.resolve("release-readiness.md")),
            "release-readiness.md must exist as a real artifact");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> readStateJson(Path statePath) throws IOException {
        return (Map<String, Object>) Json.parse(Files.readString(statePath));
    }

    private ProcessResult runMainAsASeparateProcess(Path repoRoot, Path runsDirectory, String command, String... args)
        throws IOException, InterruptedException {
        List<String> fullCommand = new java.util.ArrayList<>();
        fullCommand.add(JAVA_BINARY);
        fullCommand.add("-cp");
        fullCommand.add(CLASSPATH.toString());
        fullCommand.add("com.schwab.agentic.cli.Main");
        fullCommand.add(command);
        for (String arg : args) {
            fullCommand.add(arg);
        }
        fullCommand.add("--runs");
        fullCommand.add(runsDirectory.toString());

        ProcessBuilder builder = new ProcessBuilder(fullCommand);
        builder.directory(repoRoot.toFile());
        builder.redirectErrorStream(true);

        Process process = builder.start();
        String output = new String(process.getInputStream().readAllBytes());
        boolean finished = process.waitFor(300, TimeUnit.SECONDS);
        if (!finished) {
            process.destroyForcibly();
            throw new IllegalStateException("Subprocess for \"" + command + "\" did not finish within 300 seconds");
        }
        return new ProcessResult(process.exitValue(), output);
    }

    private record ProcessResult(int exitCode, String combinedOutput) {
    }
}
