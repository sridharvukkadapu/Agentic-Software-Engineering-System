package com.schwab.agentic.executor;

import static com.schwab.agentic.Assertions.assertTrue;

import com.schwab.agentic.agent.RecordingClient;
import com.schwab.agentic.engine.NodeExecutor;
import com.schwab.agentic.model.RiskLevel;
import com.schwab.agentic.model.WorkflowNode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;

/**
 * Records real (placeholder, see docs/decisions.md) fixtures into the committed
 * fixtures/ tree, one per scenario where the prompt content genuinely differs: a
 * requirement fixture for each of greenfield, ambiguous, and brownfield (the raw
 * requirement text sent to the agent differs across all three), and an impact fixture
 * for brownfield specifically, since ImpactExecutor's greenfield-vs-brownfield
 * determination is the other place a scenario materially changes what the agent is
 * asked to reason about. Every other executor's prompt shape is driven by the content
 * handed to it (a design spec, a diff), not by which named scenario produced that
 * content, so it needs no separate per-scenario fixture beyond what
 * {@link GreenfieldEndToEndTest} already records.
 */
public class ScenarioFixtureRecordingTest {

    private static final Path FIXTURES_ROOT = findRepoRoot().resolve("fixtures");

    private static Path findRepoRoot() {
        Path current = Path.of("").toAbsolutePath();
        while (current != null) {
            if (current.resolve("scenarios").toFile().isDirectory()
                && current.resolve("orchestrator").toFile().isDirectory()) {
                return current;
            }
            current = current.getParent();
        }
        throw new IllegalStateException("Could not find repo root by walking up from " + Path.of("").toAbsolutePath());
    }

    private static Path requirementFile(String scenarioName) {
        return findRepoRoot().resolve("scenarios/" + scenarioName + "/requirement.md");
    }

    private static WorkflowNode requirementNode() {
        return new WorkflowNode("REQUIREMENT", "Requirement analysis", "requirement", Set.of(),
            "dependencies-complete", "requirement-complete", RiskLevel.LOW, 2, Set.of());
    }

    public void testRecordAmbiguousRequirementFixture() throws IOException {
        Path artifactsDir = Files.createTempDirectory("fixture-recording-artifacts-ambiguous");
        Path fixturesDir = FIXTURES_ROOT.resolve("ambiguous/requirement");

        String agentJson = """
            ```json
            {
              "normalizedProblem": "Make resolution of popular short URLs faster without hammering the database on every redirect.",
              "acceptanceCriteria": [
                {"id": "AC-1", "description": "Popular links resolve without a database hit on every request", "riskLevel": "MEDIUM"}
              ],
              "ambiguities": [
                "No staleness budget or cache invalidation strategy is specified",
                "No definition of what counts as a popular link is given"
              ],
              "assumptions": [],
              "outOfScope": [],
              "openQuestions": [
                "The requirement does not specify what should happen if the same long URL is submitted twice."
              ]
            }
            ```
            """;
        RecordingClient recordingClient = new RecordingClient(
            FakeAgentClient.alwaysReturningText(agentJson), fixturesDir);
        RequirementExecutor executor = new RequirementExecutor(recordingClient, artifactsDir);

        NodeExecutor.ExecutionOutput output = executor.execute(requirementNode(),
            Map.of("requirementPath", requirementFile("ambiguous").toString()));

        assertTrue(output.executorReportedSuccess(), "ambiguous requirement fixture recording must succeed");
        assertFixtureWasWritten(fixturesDir);
    }

    public void testRecordBrownfieldRequirementFixture() throws IOException {
        Path artifactsDir = Files.createTempDirectory("fixture-recording-artifacts-brownfield-req");
        Path fixturesDir = FIXTURES_ROOT.resolve("brownfield/requirement");

        String agentJson = """
            ```json
            {
              "normalizedProblem": "An expired link's resolution attempt is incorrectly counted as a click even though no redirect occurred; click counting must check expiry before incrementing.",
              "acceptanceCriteria": [
                {"id": "AC-1", "description": "Resolving an expired link does not increment its click count", "riskLevel": "HIGH"},
                {"id": "AC-2", "description": "Resolving a non-expired link still increments its click count", "riskLevel": "MEDIUM"}
              ],
              "ambiguities": [],
              "assumptions": ["Existing click counting happens in the redirect path and can be moved after the expiry check"],
              "outOfScope": ["Backfilling or correcting historical click counts already recorded incorrectly"],
              "openQuestions": []
            }
            ```
            """;
        RecordingClient recordingClient = new RecordingClient(
            FakeAgentClient.alwaysReturningText(agentJson), fixturesDir);
        RequirementExecutor executor = new RequirementExecutor(recordingClient, artifactsDir);

        NodeExecutor.ExecutionOutput output = executor.execute(requirementNode(),
            Map.of("requirementPath", requirementFile("brownfield").toString()));

        assertTrue(output.executorReportedSuccess(), "brownfield requirement fixture recording must succeed");
        assertFixtureWasWritten(fixturesDir);
    }

    public void testRecordBrownfieldImpactFixture() throws IOException {
        Path artifactsDir = Files.createTempDirectory("fixture-recording-artifacts-brownfield-impact");
        Path fixturesDir = FIXTURES_ROOT.resolve("brownfield/impact");
        Path targetServiceDir = findRepoRoot().resolve("target-service");

        String agentJson = """
            ```json
            {
              "natureOfChange": "brownfield",
              "affectedFiles": [
                "src/main/java/com/example/urlshortener/url/RedirectController.java",
                "src/main/java/com/example/urlshortener/url/UrlService.java"
              ],
              "newFilesExpected": [],
              "affectedApiContracts": ["GET /{code} redirect endpoint's click-counting side effect changes"],
              "affectedDataFlows": ["Click count increment moves to after the expiry check in the redirect path"],
              "blastRadius": "Contained to the redirect path; no schema change required",
              "regressionRisks": ["A non-expired link must still have its click counted; this must not silently stop counting all clicks"]
            }
            ```
            """;
        RecordingClient recordingClient = new RecordingClient(
            FakeAgentClient.alwaysReturningText(agentJson), fixturesDir);
        ImpactExecutor executor = new ImpactExecutor(recordingClient, artifactsDir, targetServiceDir);

        NodeExecutor.ExecutionOutput output = executor.execute(requirementNode(),
            Map.of("normalizedProblem", "Expired links should not count as clicks"));

        assertTrue(output.executorReportedSuccess(), "brownfield impact fixture recording must succeed");
        assertTrue("brownfield".equals(output.outputs().get("natureOfChange")),
            "the brownfield scenario's impact fixture must have the agent conclude brownfield, not greenfield");
        assertFixtureWasWritten(fixturesDir);
    }

    private void assertFixtureWasWritten(Path fixturesDir) throws IOException {
        assertTrue(Files.isDirectory(fixturesDir), "fixture directory must exist: " + fixturesDir);
        try (var files = Files.list(fixturesDir)) {
            assertTrue(files.findAny().isPresent(), "at least one fixture file must have been written to " + fixturesDir);
        }
    }
}
