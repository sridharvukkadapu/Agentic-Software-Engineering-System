package com.schwab.agentic.executor;

import static com.schwab.agentic.Assertions.assertEquals;
import static com.schwab.agentic.Assertions.assertTrue;

import com.schwab.agentic.agent.AgentRequest;
import com.schwab.agentic.agent.RecordingClient;
import com.schwab.agentic.engine.NodeExecutor;
import com.schwab.agentic.model.RiskLevel;
import com.schwab.agentic.model.WorkflowNode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Covers {@link ImpactExecutor} against the real target-service/ directory (reading the
 * actual codebase is exactly what this executor does; the throwaway-project stand-in
 * used elsewhere in this spec is only for the compile/test *execution* path, not for
 * codebase inventory reading).
 */
public class ImpactExecutorTest {

    private static WorkflowNode impactNode() {
        return new WorkflowNode("IMPACT", "Impact analysis", "impact", Set.of("REQUIREMENT"),
            "dependencies-complete", "artifact-written", RiskLevel.MEDIUM, 2, Set.of());
    }

    public void testGreenfieldRequirementSendsAFileInventoryAndAgentStatesGreenfield() throws IOException {
        Path artifactsDir = Files.createTempDirectory("executor-artifacts");
        Path fixturesDir = Files.createTempDirectory("executor-fixtures-impact-greenfield");
        Path targetServiceDir = realTargetServiceDirectory();

        String agentJson = """
            ```json
            {
              "natureOfChange": "greenfield",
              "affectedFiles": [],
              "newFilesExpected": ["controller/PreviewController.java", "service/PreviewService.java"],
              "affectedApiContracts": ["New endpoint: GET /api/v1/urls/{code}/preview"],
              "affectedDataFlows": ["New: fetch target URL, extract title/description, cache result"],
              "blastRadius": "Contained to a new controller and service; no existing endpoints change",
              "regressionRisks": []
            }
            ```
            """;
        RecordingClient recordingClient = new RecordingClient(
            FakeAgentClient.alwaysReturningText(agentJson), fixturesDir);

        ImpactExecutor executor = new ImpactExecutor(recordingClient, artifactsDir, targetServiceDir);
        WorkflowNode node = impactNode();

        NodeExecutor.ExecutionOutput output = executor.execute(node,
            Map.of("normalizedProblem", "Add a link preview endpoint"));

        assertTrue(output.executorReportedSuccess(), "executor must report success");
        assertEquals("greenfield", output.outputs().get("natureOfChange"),
            "executor must not be passed a mode flag; the agent determined greenfield from the real inventory");

        String impactJson = Files.readString(artifactsDir.resolve("impact.json"));
        assertTrue(impactJson.contains("filesInventoried"),
            "impact.json must record how many files were inventoried: " + impactJson);
    }

    public void testFilesActuallySentToTheAgentAreRecorded() throws IOException {
        Path artifactsDir = Files.createTempDirectory("executor-artifacts");
        Path fixturesDir = Files.createTempDirectory("executor-fixtures-impact-files");
        Path targetServiceDir = realTargetServiceDirectory();

        FakeAgentClient fakeClient = FakeAgentClient.alwaysReturningText("""
            ```json
            {"natureOfChange": "brownfield", "affectedFiles": ["src/main/java/com/schwab/urlshortener/url/UrlService.java"],
             "newFilesExpected": [], "affectedApiContracts": [], "affectedDataFlows": [],
             "blastRadius": "small", "regressionRisks": []}
            ```
            """);
        RecordingClient recordingClient = new RecordingClient(fakeClient, fixturesDir);

        ImpactExecutor executor = new ImpactExecutor(recordingClient, artifactsDir, targetServiceDir);
        WorkflowNode node = impactNode();

        executor.execute(node, Map.of());

        List<AgentRequest> requests = fakeClient.requestsSeen();
        assertEquals(1, requests.size(), "expected exactly one agent call");
        assertTrue(requests.get(0).userPrompt().contains("File inventory"),
            "the prompt sent to the agent must include a real file inventory, not a mode flag");
        assertTrue(requests.get(0).userPrompt().contains(".java"),
            "the real target-service inventory must contain at least one .java file");
    }

    private static Path realTargetServiceDirectory() {
        Path current = Path.of("").toAbsolutePath();
        while (current != null) {
            Path candidate = current.resolve("target-service");
            if (candidate.toFile().isDirectory()) {
                return candidate;
            }
            current = current.getParent();
        }
        throw new IllegalStateException("Could not find target-service/ directory");
    }
}
