package com.schwab.agentic.executor;

import static com.schwab.agentic.Assertions.assertTrue;

import com.schwab.agentic.agent.RecordingClient;
import com.schwab.agentic.engine.CommandRunner;
import com.schwab.agentic.engine.NodeExecutor;
import com.schwab.agentic.model.AcceptanceCriterion;
import com.schwab.agentic.model.Evidence;
import com.schwab.agentic.model.RequirementSpec;
import com.schwab.agentic.model.RiskLevel;
import com.schwab.agentic.model.WorkflowNode;
import com.schwab.agentic.model.WorkflowState;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * AC-04-1: a full greenfield run through all eight stages writes all eight artifact
 * groups, each non-empty. Each executor is wired to its own fixture-backed
 * {@link RecordingClient} (the placeholder-fixture approach documented in
 * docs/decisions.md, since the configured Anthropic account has no credit balance), and
 * outputs from one stage are threaded into the next stage's context by hand, exactly as
 * spec 05's engine will do once it exists. This test also produces the last of the
 * fixtures spec 04 asked to be recorded, one per executor, committed to
 * fixtures/greenfield/.
 */
public class GreenfieldEndToEndTest {

    private static final Path GREENFIELD_REQUIREMENT = findRepoRoot().resolve("scenarios/greenfield/requirement.md");
    private static final Path FIXTURES_ROOT = findRepoRoot().resolve("fixtures/greenfield");

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

    public void testGreenfieldRunWritesAllEightArtifactGroupsEachNonEmpty() throws IOException {
        Path artifactsDir = Files.createTempDirectory("e2e-artifacts");
        Path targetServiceDir = findRepoRoot().resolve("target-service");
        Path implementTargetDir = Files.createTempDirectory("e2e-implement-target");
        Path testProjectDir = ThrowawayCompileProject.copyFresh();
        Path runsDir = Files.createTempDirectory("e2e-runs");

        List<Evidence> allEvidence = new ArrayList<>();
        List<com.schwab.agentic.model.AuditEvent> allAuditEvents = new ArrayList<>();

        // --- 1. REQUIREMENT ---
        RecordingClient requirementClient = new RecordingClient(
            FakeAgentClient.alwaysReturningText(REQUIREMENT_RESPONSE), fixtureDir("requirement"));
        RequirementExecutor requirementExecutor = new RequirementExecutor(requirementClient, artifactsDir);
        NodeExecutor.ExecutionOutput requirementOutput = requirementExecutor.execute(
            TestExecutorFixtures.implementNode(), Map.of("requirementPath", GREENFIELD_REQUIREMENT.toString()));
        assertTrue(requirementOutput.executorReportedSuccess(), "requirement stage must succeed: " + requirementOutput.summary());
        assertNonEmptyFile(artifactsDir.resolve("requirement-spec.json"), "requirement-spec.json");

        // --- 2. IMPACT ---
        RecordingClient impactClient = new RecordingClient(
            FakeAgentClient.alwaysReturningText(IMPACT_RESPONSE), fixtureDir("impact"));
        ImpactExecutor impactExecutor = new ImpactExecutor(impactClient, artifactsDir, targetServiceDir);
        NodeExecutor.ExecutionOutput impactOutput = impactExecutor.execute(
            TestExecutorFixtures.implementNode(), Map.of("normalizedProblem", "Add a link preview endpoint"));
        assertTrue(impactOutput.executorReportedSuccess(), "impact stage must succeed: " + impactOutput.summary());
        assertNonEmptyFile(artifactsDir.resolve("impact-analysis.md"), "impact-analysis.md");
        assertNonEmptyFile(artifactsDir.resolve("impact.json"), "impact.json");

        // --- 3. DESIGN ---
        RecordingClient designClient = new RecordingClient(
            FakeAgentClient.alwaysReturningText(DESIGN_RESPONSE), fixtureDir("design"));
        List<com.schwab.agentic.model.DecisionRecord> decisions = new ArrayList<>();
        DesignExecutor designExecutor = new DesignExecutor(designClient, artifactsDir, decisions::add);
        NodeExecutor.ExecutionOutput designOutput = designExecutor.execute(
            TestExecutorFixtures.implementNode(), Map.of("normalizedProblem", "Add a link preview endpoint"));
        assertTrue(designOutput.executorReportedSuccess(), "design stage must succeed: " + designOutput.summary());
        assertNonEmptyFile(artifactsDir.resolve("design-spec.json"), "design-spec.json");
        assertNonEmptyFile(artifactsDir.resolve("openapi-fragment.yaml"), "openapi-fragment.yaml");
        assertNonEmptyFile(artifactsDir.resolve("design.md"), "design.md");
        assertTrue(!decisions.isEmpty(), "design stage must record at least one decision");

        // --- 4. IMPLEMENT ---
        RecordingClient implementClient = new RecordingClient(
            FakeAgentClient.alwaysReturningText(IMPLEMENT_RESPONSE), fixtureDir("implement"));
        ImplementExecutor implementExecutor = new ImplementExecutor(implementClient, implementTargetDir, artifactsDir);
        NodeExecutor.ExecutionOutput implementOutput = implementExecutor.execute(
            TestExecutorFixtures.implementNode(), Map.of("designSpec", "link preview design"));
        assertTrue(implementOutput.executorReportedSuccess(), "implement stage must succeed: " + implementOutput.summary());
        assertNonEmptyFile(artifactsDir.resolve("implementation.diff"), "implementation.diff");

        // --- 5. TEST ---
        RequirementSpec requirementSpec = new RequirementSpec("REQ-1", 1, "req", "req normalized",
            List.of(new AcceptanceCriterion("AC-1", "returns a preview", RiskLevel.HIGH)));
        WorkflowNode testNode = TestExecutorFixtures.testGenNode();
        WorkflowState state = new WorkflowState("RUN-E2E", requirementSpec, List.of(testNode));
        RecordingClient testClient = new RecordingClient(
            FakeAgentClient.alwaysReturningText(TEST_RESPONSE), fixtureDir("test"));
        TestExecutor testExecutor = new TestExecutor(testClient, new CommandRunner(), testProjectDir, artifactsDir,
            runsDir, "RUN-E2E", "./gradlew test", allEvidence::add, state);
        NodeExecutor.ExecutionOutput testOutput = testExecutor.execute(testNode,
            Map.of("acceptanceCriteria", List.of(Map.of("id", "AC-1", "description", "returns a preview"))));
        assertTrue(testOutput.executorReportedSuccess(), "test stage must succeed: " + testOutput.summary());
        assertNonEmptyFile(artifactsDir.resolve("test-results.log"), "test-results.log");
        allAuditEvents.addAll(state.getAuditLog());

        // --- 6. DOCUMENT ---
        RecordingClient documentClient = new RecordingClient(
            FakeAgentClient.alwaysReturningText(DOCUMENT_RESPONSE), fixtureDir("document"));
        DocumentExecutor documentExecutor = new DocumentExecutor(documentClient, artifactsDir);
        NodeExecutor.ExecutionOutput documentOutput = documentExecutor.execute(
            TestExecutorFixtures.documentNode(), Map.of("designSpec", "link preview design"));
        assertTrue(documentOutput.executorReportedSuccess(), "document stage must succeed: " + documentOutput.summary());
        assertNonEmptyFile(artifactsDir.resolve("api-docs.md"), "api-docs.md");
        assertNonEmptyFile(artifactsDir.resolve("CHANGELOG-entry.md"), "CHANGELOG-entry.md");

        // --- 7. VALIDATE (no agent call) ---
        ValidateExecutor validateExecutor = new ValidateExecutor(artifactsDir, requirementSpec.acceptanceCriteria(),
            allEvidence, artifactsDir.resolve("impact.json"), artifactsDir.resolve("implementation.diff"));
        NodeExecutor.ExecutionOutput validateOutput = validateExecutor.execute(
            TestExecutorFixtures.validateNode(), Map.of());
        assertNonEmptyFile(artifactsDir.resolve("validation-report.md"), "validation-report.md");
        assertNonEmptyFile(artifactsDir.resolve("traceability-matrix.md"), "traceability-matrix.md");

        // --- 8. RELEASE (no agent call) ---
        ReleaseExecutor releaseExecutor = new ReleaseExecutor(artifactsDir,
            validateOutput.executorReportedSuccess(), allAuditEvents);
        releaseExecutor.execute(TestExecutorFixtures.releaseNode(), Map.of());
        assertNonEmptyFile(artifactsDir.resolve("release-readiness.md"), "release-readiness.md");
    }

    private Path fixtureDir(String stageName) {
        return FIXTURES_ROOT.resolve(stageName);
    }

    private void assertNonEmptyFile(Path path, String label) throws IOException {
        assertTrue(Files.isRegularFile(path), label + " must exist at " + path);
        assertTrue(Files.size(path) > 0, label + " must be non-empty");
    }

    private static final String REQUIREMENT_RESPONSE = """
        ```json
        {
          "normalizedProblem": "Add GET /api/v1/urls/{code}/preview returning a cached title and description for the target URL, with a timeout on the external fetch and a 404 for unknown codes.",
          "acceptanceCriteria": [
            {"id": "AC-1", "description": "GET /api/v1/urls/{code}/preview returns title and description for a known short code", "riskLevel": "HIGH"},
            {"id": "AC-2", "description": "Unknown short code returns 404", "riskLevel": "MEDIUM"},
            {"id": "AC-3", "description": "Preview results are cached", "riskLevel": "MEDIUM"},
            {"id": "AC-4", "description": "A slow or unreachable target does not hang the request; a timeout applies", "riskLevel": "HIGH"}
          ],
          "ambiguities": [],
          "assumptions": ["Cache TTL of 1 hour is acceptable absent a stated value"],
          "outOfScope": ["Refreshing the cache before expiry", "Previewing non-HTML content"],
          "openQuestions": []
        }
        ```
        """;

    private static final String IMPACT_RESPONSE = """
        ```json
        {
          "natureOfChange": "greenfield",
          "affectedFiles": [],
          "newFilesExpected": [
            "src/main/java/com/example/PreviewController.java",
            "src/main/java/com/example/PreviewService.java"
          ],
          "affectedApiContracts": ["New endpoint GET /api/v1/urls/{code}/preview"],
          "affectedDataFlows": ["New outbound HTTP fetch to the target URL, new cache layer"],
          "blastRadius": "Contained to a new controller and service; no existing endpoint changes",
          "regressionRisks": ["None identified; no existing code touched"]
        }
        ```
        """;

    private static final String DESIGN_RESPONSE = """
        ```json
        {
          "classStructure": ["PreviewController: handles GET /api/v1/urls/{code}/preview", "PreviewService: fetches and caches previews"],
          "apiContract": "GET /api/v1/urls/{code}/preview -> 200 {title, description} | 404",
          "dataModelChanges": ["New cache entry keyed by short code"],
          "chosenApproach": "Add a PreviewService backed by an in-memory cache with a bounded HTTP client timeout",
          "rejectedAlternatives": [
            {"alternative": "Fetch synchronously with no timeout", "reason": "Would let a slow target hang the request indefinitely"}
          ],
          "affectsCriteria": ["AC-1", "AC-2", "AC-3", "AC-4"]
        }
        ```
        """;

    private static final String IMPLEMENT_RESPONSE = """
        ```java
        // FILE: src/main/java/com/example/PreviewService.java
        package com.example;

        public class PreviewService {
            public String fetchPreview(String shortCode) {
                return "preview for " + shortCode;
            }
        }
        ```
        """;

    private static final String TEST_RESPONSE = """
        ```java
        // FILE: src/test/java/com/example/PreviewServiceTest.java
        package com.example;

        import org.junit.jupiter.api.Test;
        import static org.junit.jupiter.api.Assertions.assertTrue;

        class PreviewServiceTest {
            @Test
            void testAC_1ReturnsPreviewForKnownCode() {
                assertTrue(true);
            }
        }
        ```
        """;

    private static final String DOCUMENT_RESPONSE = """
        ```markdown
        # API docs

        ## GET /api/v1/urls/{code}/preview

        Returns a cached preview (title, description) for the target URL, or 404 if the
        short code does not exist.
        ```

        ```markdown
        Added GET /api/v1/urls/{code}/preview to return a cached link preview with a
        bounded fetch timeout.
        ```
        """;
}
