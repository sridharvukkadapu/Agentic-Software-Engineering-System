package com.schwab.agentic.executor;

import static com.schwab.agentic.Assertions.assertTrue;

import com.schwab.agentic.engine.NodeExecutor;
import com.schwab.agentic.model.AcceptanceCriterion;
import com.schwab.agentic.model.Evidence;
import com.schwab.agentic.model.RequirementSpec;
import com.schwab.agentic.model.RiskLevel;
import com.schwab.agentic.model.WorkflowState;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Covers {@link ValidateExecutor}, which makes no agent call and reads only real
 * artifacts from disk: AC-04-7 (a HIGH-risk criterion with only ASSERTED evidence fails
 * validation), AC-04-8 (a file changed outside the impact analysis's predicted set is
 * reported by name), and AC-04-9 (the traceability matrix has one row per criterion with
 * a resolvable artifact path).
 */
public class ValidateExecutorTest {

    public void testHighRiskCriterionWithOnlyAssertedEvidenceFailsValidation() throws IOException {
        Path artifactsDir = Files.createTempDirectory("executor-artifacts-validate-1");
        Path impactJsonPath = artifactsDir.resolve("impact.json");
        Files.writeString(impactJsonPath, "{\"affectedFiles\": [], \"newFilesExpected\": []}");
        Path diffPath = artifactsDir.resolve("implementation.diff");
        Files.writeString(diffPath, "");

        List<AcceptanceCriterion> criteria = List.of(
            new AcceptanceCriterion("AC-1", "a high risk criterion", RiskLevel.HIGH));
        WorkflowState state = stateWithEvidence(criteria,
            new Evidence(Evidence.Origin.ASSERTED, "AC-1", true, "an agent said so", "agent",
                "DESIGN", "design.md", Instant.now()));

        ValidateExecutor executor = new ValidateExecutor(artifactsDir, state, impactJsonPath, diffPath);
        NodeExecutor.ExecutionOutput output = executor.execute(TestExecutorFixtures.validateNode(), Map.of());

        assertTrue(!output.executorReportedSuccess(),
            "a HIGH risk criterion backed only by ASSERTED evidence must fail validation");
        String report = Files.readString(artifactsDir.resolve("validation-report.md"));
        assertTrue(report.contains("AC-1"), "the validation report must name the failing criterion");
    }

    public void testHighRiskCriterionWithExecutedEvidencePassesValidation() throws IOException {
        Path artifactsDir = Files.createTempDirectory("executor-artifacts-validate-2");
        Path impactJsonPath = artifactsDir.resolve("impact.json");
        Files.writeString(impactJsonPath, "{\"affectedFiles\": [], \"newFilesExpected\": []}");
        Path diffPath = artifactsDir.resolve("implementation.diff");
        Files.writeString(diffPath, "");

        List<AcceptanceCriterion> criteria = List.of(
            new AcceptanceCriterion("AC-1", "a high risk criterion", RiskLevel.HIGH));
        WorkflowState state = stateWithEvidence(criteria,
            new Evidence(Evidence.Origin.EXECUTED, "AC-1", true, "a real test ran", "./gradlew test",
                "TEST", "test-results.log", Instant.now()));

        ValidateExecutor executor = new ValidateExecutor(artifactsDir, state, impactJsonPath, diffPath);
        NodeExecutor.ExecutionOutput output = executor.execute(TestExecutorFixtures.validateNode(), Map.of());

        assertTrue(output.executorReportedSuccess(),
            "a HIGH risk criterion backed by passing EXECUTED evidence must pass validation: " + output.summary());
    }

    public void testFileChangedOutsidePredictedImpactSetIsReportedByName() throws IOException {
        Path artifactsDir = Files.createTempDirectory("executor-artifacts-validate-3");
        Path impactJsonPath = artifactsDir.resolve("impact.json");
        Files.writeString(impactJsonPath,
            "{\"affectedFiles\": [\"src/main/java/com/example/Expected.java\"], \"newFilesExpected\": []}");
        Path diffPath = artifactsDir.resolve("implementation.diff");
        Files.writeString(diffPath,
            "--- a/src/main/java/com/example/Expected.java\n+++ b/src/main/java/com/example/Expected.java\n"
                + "(1 lines before, 2 lines after)\n\n"
                + "--- a/src/main/java/com/example/Unpredicted.java\n+++ b/src/main/java/com/example/Unpredicted.java\n"
                + "(0 lines before, 5 lines after)\n\n");

        List<AcceptanceCriterion> criteria = List.of(
            new AcceptanceCriterion("AC-1", "a low risk criterion", RiskLevel.LOW));
        WorkflowState state = stateWithEvidence(criteria,
            new Evidence(Evidence.Origin.ASSERTED, "AC-1", true, "fine for LOW risk", "agent",
                "DESIGN", "design.md", Instant.now()));

        ValidateExecutor executor = new ValidateExecutor(artifactsDir, state, impactJsonPath, diffPath);
        NodeExecutor.ExecutionOutput output = executor.execute(TestExecutorFixtures.validateNode(), Map.of());

        assertTrue(!output.executorReportedSuccess(), "an unpredicted file change must fail validation");
        String report = Files.readString(artifactsDir.resolve("validation-report.md"));
        assertTrue(report.contains("Unpredicted.java"),
            "the validation report must name the specific unpredicted file: " + report);
        assertTrue(!report.contains("- file src/main/java/com/example/Expected.java"),
            "a file that WAS predicted must not be reported as a finding");
    }

    public void testTraceabilityMatrixHasOneRowPerCriterionWithAResolvableArtifactPath() throws IOException {
        Path artifactsDir = Files.createTempDirectory("executor-artifacts-validate-4");
        Path impactJsonPath = artifactsDir.resolve("impact.json");
        Files.writeString(impactJsonPath, "{\"affectedFiles\": [], \"newFilesExpected\": []}");
        Path diffPath = artifactsDir.resolve("implementation.diff");
        Files.writeString(diffPath, "");

        Path realArtifact = artifactsDir.resolve("test-results.log");
        Files.writeString(realArtifact, "test output");

        List<AcceptanceCriterion> criteria = List.of(
            new AcceptanceCriterion("AC-1", "first criterion", RiskLevel.LOW),
            new AcceptanceCriterion("AC-2", "second criterion", RiskLevel.MEDIUM));
        WorkflowState state = stateWithEvidence(criteria,
            new Evidence(Evidence.Origin.EXECUTED, "AC-1", true, "a real test ran", "./gradlew test",
                "TEST", realArtifact.toString(), Instant.now()),
            new Evidence(Evidence.Origin.EXECUTED, "AC-2", true, "a real test ran", "./gradlew test",
                "TEST", realArtifact.toString(), Instant.now()));

        ValidateExecutor executor = new ValidateExecutor(artifactsDir, state, impactJsonPath, diffPath);
        executor.execute(TestExecutorFixtures.validateNode(), Map.of());

        String matrix = Files.readString(artifactsDir.resolve("traceability-matrix.md"));
        assertTrue(matrix.contains("AC-1"), "traceability matrix must have a row for AC-1");
        assertTrue(matrix.contains("AC-2"), "traceability matrix must have a row for AC-2");
        assertTrue(matrix.contains(realArtifact.toString()),
            "traceability matrix must contain a resolvable artifact path");
        assertTrue(Files.isRegularFile(Path.of(realArtifact.toString())),
            "the artifact path named in the matrix must actually resolve to a real file on disk");
    }

    /**
     * A real {@link WorkflowState} carrying the given evidence, standing in for the live
     * state a registry-registered {@link ValidateExecutor} reads from in the real CLI:
     * evidence is added to the state, not passed to the executor's constructor, exactly
     * as it would be by TEST's real {@code EvidenceSink} during an actual run.
     */
    private WorkflowState stateWithEvidence(List<AcceptanceCriterion> criteria, Evidence... evidence) {
        RequirementSpec requirementSpec = new RequirementSpec("REQ-1", 1, "req", "req normalized", criteria);
        WorkflowState state = new WorkflowState("VALIDATE-TEST", requirementSpec,
            List.of(TestExecutorFixtures.implementNode(), TestExecutorFixtures.testGenNode(),
                TestExecutorFixtures.documentNode(), TestExecutorFixtures.validateNode()));
        for (Evidence item : evidence) {
            state.addEvidence(item);
        }
        return state;
    }
}
