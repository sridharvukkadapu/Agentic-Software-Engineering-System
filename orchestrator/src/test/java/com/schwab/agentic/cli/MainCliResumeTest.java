package com.schwab.agentic.cli;

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
 * The required cross-process resume test: a run is started in one real JVM process,
 * that process exits, a completely separate JVM process approves the parked node, a
 * third separate JVM process resumes and completes the run, and only then does this
 * test (running in a fourth process, the test runner itself) read the final
 * {@code state.json} back and check it. Every step that matters (writing state, reading
 * state, approving, resuming) crosses a real process boundary via {@code ProcessBuilder},
 * proving persistence actually works rather than only proving that
 * {@code WorkflowState.fromJsonString(state.toJsonString())} round trips in memory,
 * which a test that never left the original process would only ever show.
 *
 * Uses {@code --replay} against the real fixtures recorded in {@code fixtures/cli/},
 * so this test makes no network call and costs nothing to run repeatedly.
 */
public class MainCliResumeTest {

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

    public void testRunApproveAndResumeEachCrossARealProcessBoundaryAndTheRunCompletes() throws IOException, InterruptedException {
        Path repoRoot = findRepoRoot();
        Path runsDirectory = Files.createTempDirectory("cli-resume-test-runs");
        String runId = "CLI-RESUME-TEST-" + System.nanoTime();

        // --- Process 1: start the run. This process's JVM exits completely before the
        // next step begins; nothing about it (no static state, no live object) survives
        // into what comes next. ---
        ProcessResult runResult = runMainAsASeparateProcess(repoRoot, runsDirectory, "run",
            "--workflow", "workflows/approval-demo.json",
            "--requirement", "scenarios/_smoke/requirement.md",
            "--replay", "--run-id", runId, "--fixtures", "fixtures");
        assertEquals(0, runResult.exitCode(), "run must exit 0: " + runResult.combinedOutput());
        assertTrue(runResult.combinedOutput().contains("AWAITING_APPROVAL"),
            "the run must park at AWAITING_APPROVAL: " + runResult.combinedOutput());

        Path statePath = runsDirectory.resolve(runId).resolve("state.json");
        assertTrue(Files.isRegularFile(statePath),
            "state.json must actually be written to disk by the first process: " + statePath);

        Map<String, Object> stateAfterPark = readStateJson(statePath);
        assertEquals("AWAITING_APPROVAL", stateAfterPark.get("workflowStatus"),
            "the persisted state.json must itself record AWAITING_APPROVAL, not just this test's memory of it");
        List<Object> auditLogAfterPark = auditLog(stateAfterPark);
        assertTrue(!auditLogAfterPark.isEmpty(), "the persisted audit log must contain the pre-suspension events");
        boolean noResumeEventYet = auditLogAfterPark.stream()
            .noneMatch(event -> "RUN_RESUMED".equals(((Map<?, ?>) event).get("type")));
        assertTrue(noResumeEventYet, "no RUN_RESUMED event must exist before any resume has happened");

        // --- Process 2: approve, in a fresh JVM that knows nothing about process 1
        // except what it can read from the state.json and approvals.json files on disk. ---
        ProcessResult approveResult = runMainAsASeparateProcess(repoRoot, runsDirectory, "approve",
            "--run-id", runId, "DOCUMENT", "--by", "Sridhar", "--reason", "cross-process resume test approval",
            "--workflow", "workflows/approval-demo.json");
        assertEquals(0, approveResult.exitCode(), "approve must exit 0: " + approveResult.combinedOutput());

        Path approvalsPath = runsDirectory.resolve(runId).resolve("approvals.json");
        assertTrue(Files.isRegularFile(approvalsPath),
            "approvals.json must actually be written to disk by the second, separate process");

        // --- Process 3: resume, a third fresh JVM. ---
        ProcessResult resumeResult = runMainAsASeparateProcess(repoRoot, runsDirectory, "resume",
            "--run-id", runId, "--replay", "--fixtures", "fixtures", "--workflow", "workflows/approval-demo.json");
        assertEquals(0, resumeResult.exitCode(), "resume must exit 0: " + resumeResult.combinedOutput());
        assertTrue(resumeResult.combinedOutput().contains("COMPLETED"),
            "the resumed run must complete: " + resumeResult.combinedOutput());

        // --- Back in this test's own process: read the final state.json a fourth,
        // completely independent process wrote, and check it directly rather than
        // trusting any process's stdout claim. ---
        Map<String, Object> finalState = readStateJson(statePath);
        assertEquals("COMPLETED", finalState.get("workflowStatus"), "the final persisted state must be COMPLETED");

        List<Object> finalNodes = (List<Object>) finalState.get("nodes");
        for (Object nodeObj : finalNodes) {
            Map<?, ?> node = (Map<?, ?>) nodeObj;
            assertEquals("COMPLETED", node.get("status"),
                "every node must be COMPLETED in the final state: " + node.get("id"));
        }

        assertRealArtifactsExist(runsDirectory, runId);
        assertAuditLogIsGapFreeAndContainsPreSuspensionEventsPlusRunResumed(finalState, auditLogAfterPark);
    }

    /**
     * Required check: the resumed run's audit log contains the pre-suspension events
     * plus a RUN_RESUMED event, in sequence order with no gaps.
     */
    @SuppressWarnings("unchecked")
    private void assertAuditLogIsGapFreeAndContainsPreSuspensionEventsPlusRunResumed(
        Map<String, Object> finalState, List<Object> auditLogBeforeResume) {
        List<Object> finalAuditLog = auditLog(finalState);

        assertTrue(finalAuditLog.size() > auditLogBeforeResume.size(),
            "the final audit log must contain more events than existed before resume");

        for (int i = 0; i < auditLogBeforeResume.size(); i++) {
            Map<String, Object> before = (Map<String, Object>) auditLogBeforeResume.get(i);
            Map<String, Object> after = (Map<String, Object>) finalAuditLog.get(i);
            assertEquals(before.get("sequence"), after.get("sequence"),
                "every pre-suspension event must survive at the same sequence number after resume");
            assertEquals(before.get("type"), after.get("type"),
                "every pre-suspension event's type must be unchanged after resume");
        }

        boolean hasRunResumedEvent = finalAuditLog.stream()
            .anyMatch(event -> "RUN_RESUMED".equals(((Map<String, Object>) event).get("type")));
        assertTrue(hasRunResumedEvent, "the final audit log must contain a RUN_RESUMED event");

        double previousSequence = 0;
        for (Object eventObj : finalAuditLog) {
            Map<String, Object> event = (Map<String, Object>) eventObj;
            double sequence = (Double) event.get("sequence");
            assertTrue(sequence == previousSequence + 1,
                "sequence numbers must be strictly consecutive with no gaps: expected " + (previousSequence + 1)
                    + " but found " + sequence);
            previousSequence = sequence;
        }
    }

    private void assertRealArtifactsExist(Path runsDirectory, String runId) {
        Path artifactsDirectory = runsDirectory.resolve(runId).resolve("artifacts");
        assertTrue(Files.isRegularFile(artifactsDirectory.resolve("requirement-spec.json")),
            "requirement-spec.json must exist as a real artifact after the resumed run completes");
        assertTrue(Files.isRegularFile(artifactsDirectory.resolve("api-docs.md")),
            "api-docs.md must exist as a real artifact after DOCUMENT completes post-resume");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> readStateJson(Path statePath) throws IOException {
        return (Map<String, Object>) Json.parse(Files.readString(statePath));
    }

    @SuppressWarnings("unchecked")
    private List<Object> auditLog(Map<String, Object> state) {
        return (List<Object>) state.get("auditLog");
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
        boolean finished = process.waitFor(60, TimeUnit.SECONDS);
        if (!finished) {
            process.destroyForcibly();
            throw new IllegalStateException("Subprocess for \"" + command + "\" did not finish within 60 seconds");
        }
        return new ProcessResult(process.exitValue(), output);
    }

    private record ProcessResult(int exitCode, String combinedOutput) {
    }
}
