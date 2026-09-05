package com.schwab.agentic.executor;

import static com.schwab.agentic.Assertions.assertEquals;
import static com.schwab.agentic.Assertions.assertTrue;

import com.schwab.agentic.agent.RecordingClient;
import com.schwab.agentic.engine.NodeExecutor;
import com.schwab.agentic.model.DecisionRecord;
import com.schwab.agentic.model.RiskLevel;
import com.schwab.agentic.model.WorkflowNode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Covers {@link DesignExecutor}: writes all three declared artifacts and records a DecisionRecord naming a rejected alternative. */
public class DesignExecutorTest {

    public void testDesignProducesArtifactsAndRecordsADecisionWithARejectedAlternative() throws IOException {
        Path artifactsDir = Files.createTempDirectory("executor-artifacts");
        Path fixturesDir = Files.createTempDirectory("executor-fixtures-design");

        String agentJson = """
            ```json
            {
              "classStructure": ["PreviewController: handles GET /preview", "PreviewService: fetches and caches previews"],
              "apiContract": "GET /api/v1/urls/{code}/preview -> 200 {title, description} | 404",
              "dataModelChanges": ["No schema changes; preview data is cached in memory, not persisted"],
              "chosenApproach": "Fetch the target page on cache miss with a short timeout, cache the result, evict on TTL",
              "rejectedAlternatives": [
                {"alternative": "Pre-fetch previews at URL creation time", "reason": "Wastes work for links that are never previewed and adds latency to creation"}
              ],
              "affectsCriteria": ["AC-1", "AC-2"]
            }
            ```
            """;
        RecordingClient recordingClient = new RecordingClient(
            FakeAgentClient.alwaysReturningText(agentJson), fixturesDir);

        List<DecisionRecord> recordedDecisions = new ArrayList<>();
        DesignExecutor executor = new DesignExecutor(recordingClient, artifactsDir, recordedDecisions::add);

        WorkflowNode node = new WorkflowNode("DESIGN", "Design", "design", Set.of("IMPACT"),
            "requirement-unambiguous-or-approved", "artifact-written", RiskLevel.MEDIUM, 2, Set.of());

        NodeExecutor.ExecutionOutput output = executor.execute(node, Map.of("normalizedProblem", "Add link previews"));

        assertTrue(output.executorReportedSuccess(), "executor must report success");
        assertTrue(Files.exists(artifactsDir.resolve("design-spec.json")), "design-spec.json must be written");
        assertTrue(Files.exists(artifactsDir.resolve("openapi-fragment.yaml")), "openapi-fragment.yaml must be written");
        assertTrue(Files.exists(artifactsDir.resolve("design.md")), "design.md must be written");

        assertEquals(1, recordedDecisions.size(), "exactly one DecisionRecord must be recorded");
        DecisionRecord decision = recordedDecisions.get(0);
        assertTrue(decision.context().get("rejectedAlternatives").toString().contains("Pre-fetch"),
            "the decision must record the rejected alternative: " + decision.context());
        @SuppressWarnings("unchecked")
        List<String> affectsCriteria = (List<String>) decision.context().get("affectsCriteria");
        assertEquals(List.of("AC-1", "AC-2"), affectsCriteria,
            "affectsCriteria must be recorded so spec 06 can scope a re-plan by it");
    }
}
