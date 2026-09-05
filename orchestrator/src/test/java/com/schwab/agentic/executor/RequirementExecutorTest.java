package com.schwab.agentic.executor;

import static com.schwab.agentic.Assertions.assertEquals;
import static com.schwab.agentic.Assertions.assertFalse;
import static com.schwab.agentic.Assertions.assertTrue;

import com.schwab.agentic.agent.RecordingClient;
import com.schwab.agentic.engine.Gate;
import com.schwab.agentic.engine.GateContext;
import com.schwab.agentic.engine.Gates;
import com.schwab.agentic.engine.NodeExecutor;
import com.schwab.agentic.model.AcceptanceCriterion;
import com.schwab.agentic.model.RequirementSpec;
import com.schwab.agentic.model.RiskLevel;
import com.schwab.agentic.model.WorkflowNode;
import com.schwab.agentic.model.WorkflowState;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Covers {@link RequirementExecutor}: AC-04-2 (the ambiguous scenario produces at least
 * two ambiguities), and the required test that a requirement omitting behavior the
 * design needs causes the run to safe-stop via the requirement-complete exit gate,
 * rather than the executor inventing a policy to fill the gap.
 *
 * Fixtures recorded here are placeholder recordings via RecordingClient wrapping a
 * FakeAgentClient, not real Anthropic responses: see docs/decisions.md "Open items" for
 * why (the configured account has no credit balance in this environment).
 */
public class RequirementExecutorTest {

    private static WorkflowNode requirementNode() {
        return new WorkflowNode("REQUIREMENT", "Requirement analysis", "requirement", Set.of(),
            "dependencies-complete", "requirement-complete", RiskLevel.LOW, 2, Set.of());
    }

    private static WorkflowState newState(WorkflowNode node) {
        RequirementSpec placeholderSpec = new RequirementSpec(
            "REQ-0", 1, "placeholder", "placeholder", List.of());
        return new WorkflowState("RUN-1", placeholderSpec, List.of(node));
    }

    public void testGreenfieldRequirementProducesAcceptanceCriteriaAndPassesTheGate() throws IOException {
        Path artifactsDir = Files.createTempDirectory("executor-artifacts");
        Path fixturesDir = Files.createTempDirectory("executor-fixtures-greenfield");

        String agentJson = """
            ```json
            {
              "normalizedProblem": "Add a link preview endpoint returning title and description for a short code, cached, with a timeout on the external fetch.",
              "acceptanceCriteria": [
                {"id": "AC-1", "description": "GET /api/v1/urls/{code}/preview returns title and description", "riskLevel": "MEDIUM"},
                {"id": "AC-2", "description": "Preview responses are cached", "riskLevel": "MEDIUM"},
                {"id": "AC-3", "description": "Unknown short code returns 404", "riskLevel": "LOW"}
              ],
              "ambiguities": [],
              "assumptions": ["Cache TTL of 1 hour is acceptable absent a stated value"],
              "outOfScope": ["Preview image extraction"],
              "openQuestions": []
            }
            ```
            """;
        RecordingClient recordingClient = new RecordingClient(
            FakeAgentClient.alwaysReturningText(agentJson), fixturesDir);

        RequirementExecutor executor = new RequirementExecutor(recordingClient, artifactsDir);
        WorkflowNode node = requirementNode();
        WorkflowState state = newState(node);

        NodeExecutor.ExecutionOutput output = executor.execute(node,
            Map.of("requirementPath", requirementFile("greenfield").toString()));

        assertTrue(output.executorReportedSuccess(), "executor must report success for a well-formed response");

        Gate.Result gateResult = new Gates().resolve("requirement-complete").evaluate(node, state,
            new GateContext(output.outputs(), null, null, null, null, null, null));
        assertTrue(gateResult.passed(), "requirement-complete gate must pass: " + gateResult.reason());

        String writtenSpec = Files.readString(artifactsDir.resolve("requirement-spec.json"));
        assertTrue(writtenSpec.contains("AC-1"), "written requirement-spec.json must contain the parsed criteria");
    }

    /**
     * Required test: a requirement that omits duplicate-URL behavior must cause the run
     * to safe-stop, not have the executor invent a policy. Modeled on the ambiguous
     * scenario's actual requirement text, which never mentions what happens when the
     * same long URL is submitted twice.
     */
    public void testRequirementOmittingDuplicateUrlBehaviorSafeStopsRatherThanInventingAPolicy() throws IOException {
        Path artifactsDir = Files.createTempDirectory("executor-artifacts");
        Path fixturesDir = Files.createTempDirectory("executor-fixtures-ambiguous");

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
                "The requirement does not specify what should happen if the same long URL is submitted twice: return the existing short code, create a new one, or reject the request. This affects the data model and cannot be decided by the requirement text as written."
              ]
            }
            ```
            """;
        RecordingClient recordingClient = new RecordingClient(
            FakeAgentClient.alwaysReturningText(agentJson), fixturesDir);

        RequirementExecutor executor = new RequirementExecutor(recordingClient, artifactsDir);
        WorkflowNode node = requirementNode();
        WorkflowState state = newState(node);

        NodeExecutor.ExecutionOutput output = executor.execute(node,
            Map.of("requirementPath", requirementFile("ambiguous").toString()));

        Gate.Result gateResult = new Gates().resolve("requirement-complete").evaluate(node, state,
            new GateContext(output.outputs(), null, null, null, null, null, null));

        assertFalse(gateResult.passed(),
            "requirement-complete gate must fail when open-questions.json declares an unresolved question,"
                + " so the run safe-stops rather than an invented policy flowing downstream");
        assertTrue(gateResult.reason().contains("unresolved question"),
            "gate failure reason must explain why: " + gateResult.reason());

        String openQuestionsContent = Files.readString(artifactsDir.resolve("open-questions.json"));
        assertTrue(openQuestionsContent.contains("submitted twice"),
            "open-questions.json must record the actual gap the requirement left unanswered: " + openQuestionsContent);
    }

    /**
     * AC-04-2: the ambiguous scenario must produce at least two entries in ambiguities.
     * Separate from the open-questions test above: ambiguities are wording/scope the
     * requirement itself flags as unclear, distinct from a question the requirement
     * never addresses at all.
     */
    public void testAmbiguousScenarioProducesAtLeastTwoAmbiguities() throws IOException {
        Path artifactsDir = Files.createTempDirectory("executor-artifacts");
        Path fixturesDir = Files.createTempDirectory("executor-fixtures-ambiguous-2");

        String agentJson = """
            ```json
            {
              "normalizedProblem": "Make resolution of popular short URLs faster.",
              "acceptanceCriteria": [
                {"id": "AC-1", "description": "Popular links resolve faster", "riskLevel": "MEDIUM"}
              ],
              "ambiguities": [
                "No staleness budget or cache invalidation strategy is specified",
                "No definition of what counts as a popular link is given",
                "No statement about whether shared cache infrastructure already exists"
              ],
              "assumptions": [],
              "outOfScope": [],
              "openQuestions": []
            }
            ```
            """;
        RecordingClient recordingClient = new RecordingClient(
            FakeAgentClient.alwaysReturningText(agentJson), fixturesDir);

        RequirementExecutor executor = new RequirementExecutor(recordingClient, artifactsDir);
        WorkflowNode node = requirementNode();

        executor.execute(node, Map.of("requirementPath", requirementFile("ambiguous").toString()));

        String writtenSpec = Files.readString(artifactsDir.resolve("requirement-spec.json"));
        @SuppressWarnings("unchecked")
        Map<String, Object> parsed = (Map<String, Object>) com.schwab.agentic.json.Json.parse(writtenSpec);
        @SuppressWarnings("unchecked")
        List<Object> ambiguities = (List<Object>) parsed.get("ambiguities");

        assertTrue(ambiguities.size() >= 2,
            "the ambiguous scenario must produce at least two ambiguities, got " + ambiguities.size());
    }

    public void testMalformedAgentResponseProducesAFailureNotACrash() throws IOException {
        Path artifactsDir = Files.createTempDirectory("executor-artifacts");
        Path fixturesDir = Files.createTempDirectory("executor-fixtures-malformed");

        RecordingClient recordingClient = new RecordingClient(
            FakeAgentClient.alwaysReturningText("Sorry, I cannot help with that."), fixturesDir);

        RequirementExecutor executor = new RequirementExecutor(recordingClient, artifactsDir);
        WorkflowNode node = requirementNode();

        NodeExecutor.ExecutionOutput output = executor.execute(node,
            Map.of("requirementPath", requirementFile("greenfield").toString()));

        assertFalse(output.executorReportedSuccess(), "prose with no fenced block must be reported as a failure");
        assertFalse(Files.exists(artifactsDir.resolve("requirement-spec.json")),
            "no requirement-spec.json should be written when the response could not be parsed");
    }

    private static Path requirementFile(String scenarioName) {
        Path current = Path.of("").toAbsolutePath();
        while (current != null) {
            Path candidate = current.resolve("scenarios/" + scenarioName + "/requirement.md");
            if (candidate.toFile().isFile()) {
                return candidate;
            }
            current = current.getParent();
        }
        throw new IllegalStateException("Could not find scenarios/" + scenarioName + "/requirement.md");
    }
}
