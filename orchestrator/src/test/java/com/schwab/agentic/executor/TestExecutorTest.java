package com.schwab.agentic.executor;

import static com.schwab.agentic.Assertions.assertEquals;
import static com.schwab.agentic.Assertions.assertTrue;

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
 * Covers {@link TestExecutor} against the real throwaway Gradle project: AC-04-5
 * (generated test names carry the acceptance criterion id and the mapping is parsed,
 * not hardcoded, proven by renaming a criterion and watching the mapping follow) and
 * AC-04-6 (evidence produced has {@link Evidence.Origin#EXECUTED}).
 */
public class TestExecutorTest {

    private static WorkflowState newState() {
        WorkflowNode node = TestExecutorFixtures.testGenNode();
        RequirementSpec requirementSpec = new RequirementSpec(
            "REQ-1", 1, "req", "req normalized",
            List.of(new AcceptanceCriterion("AC-1", "criterion", RiskLevel.LOW)));
        return new WorkflowState("RUN-1", requirementSpec, List.of(node));
    }

    private static String agentResponseNamingCriterion(String criterionIdentifierForm) {
        return """
            ```java
            // FILE: src/test/java/com/example/GreetingTest.java
            package com.example;

            import org.junit.jupiter.api.Test;
            import static org.junit.jupiter.api.Assertions.assertTrue;

            class GreetingTest {
                @Test
                void test%s_ProvesGreetingWorks() {
                    assertTrue(true);
                }
            }
            ```
            """.formatted(criterionIdentifierForm);
    }

    public void testGeneratedTestNameCarriesTheCriterionIdAndEvidenceIsExecuted() throws IOException {
        Path projectDir = ThrowawayCompileProject.copyFresh();
        Path artifactsDir = Files.createTempDirectory("executor-artifacts-test");
        Path fixturesDir = Files.createTempDirectory("executor-fixtures-test");
        Path runsDir = Files.createTempDirectory("executor-runs-test");

        FakeAgentClient fakeClient = FakeAgentClient.alwaysReturningText(agentResponseNamingCriterion("AC_1"));
        com.schwab.agentic.agent.RecordingClient recordingClient =
            new com.schwab.agentic.agent.RecordingClient(fakeClient, fixturesDir);

        List<Evidence> recordedEvidence = new ArrayList<>();
        WorkflowState state = newState();
        TestExecutor executor = new TestExecutor(recordingClient, new CommandRunner(), projectDir, artifactsDir,
            runsDir, "RUN-1", "./gradlew test", recordedEvidence::add, state);

        Map<String, Object> context = Map.of("acceptanceCriteria",
            List.of(Map.of("id", "AC-1", "description", "criterion")));

        NodeExecutor.ExecutionOutput output = executor.execute(TestExecutorFixtures.testGenNode(), context);

        assertTrue(output.executorReportedSuccess(),
            "executor must report success when the named criterion's test actually passed: " + output.summary());
        assertEquals(1, recordedEvidence.size(), "expected exactly one piece of evidence, for AC-1");
        Evidence evidence = recordedEvidence.get(0);
        assertEquals("AC-1", evidence.acceptanceCriterionId(), "evidence must be recorded against the real criterion id");
        assertTrue(evidence.passed(), "the test named after AC-1 passed, so evidence must record passed=true");
        assertEquals(Evidence.Origin.EXECUTED, evidence.origin(),
            "evidence derived from a real test command run must be EXECUTED, never ASSERTED");

        boolean commandExecutedEventExists = state.getAuditLog().stream()
            .anyMatch(event -> event.type() == com.schwab.agentic.model.AuditEvent.EventType.COMMAND_EXECUTED);
        assertTrue(commandExecutedEventExists,
            "running the real test command through runAndRecord must leave a COMMAND_EXECUTED audit event");
    }

    /**
     * AC-04-5's actual required proof: rename the criterion the agent is asked to prove
     * (as it would be renamed in a real requirement revision) and confirm the evidence
     * mapping follows automatically, because nothing in TestExecutor hardcodes AC-1 to
     * any test name; it only ever reads the name out of the source it just wrote.
     */
    /**
     * The real proof AC-04-5 asks for: a test file whose method name still carries the
     * criterion's OLD id (as if the file were written before a rename and never
     * regenerated) run against a context where the requirement now declares the NEW,
     * renamed id. A mapping that is genuinely derived from the test source, rather than
     * asserted or hardcoded anywhere, must fail to find the new id (nothing in the test
     * source says it) while correctly finding the old id if it happens to still be
     * declared. This is the case the deliberate-breakage check in this class's inline
     * review caught: a broken version that assumes every declared criterion was proven
     * regardless of what the source says would incorrectly report the renamed criterion
     * as covered.
     */
    public void testRenamingTheCriterionCausesTheEvidenceMappingToFollowWithNoCodeChange() throws IOException {
        Path projectDir = ThrowawayCompileProject.copyFresh();
        Path artifactsDir = Files.createTempDirectory("executor-artifacts-rename");
        Path fixturesDir = Files.createTempDirectory("executor-fixtures-rename");
        Path runsDir = Files.createTempDirectory("executor-runs-rename");

        // The agent's test still names the OLD id; the requirement has already moved on
        // to the NEW id. A derived mapping must not credit the new id with evidence.
        FakeAgentClient fakeClient = FakeAgentClient.alwaysReturningText(agentResponseNamingCriterion("AC_1"));
        com.schwab.agentic.agent.RecordingClient recordingClient =
            new com.schwab.agentic.agent.RecordingClient(fakeClient, fixturesDir);

        List<Evidence> recordedEvidence = new ArrayList<>();
        WorkflowState state = newState();
        TestExecutor executor = new TestExecutor(recordingClient, new CommandRunner(), projectDir, artifactsDir,
            runsDir, "RUN-1", "./gradlew test", recordedEvidence::add, state);

        Map<String, Object> context = Map.of("acceptanceCriteria",
            List.of(Map.of("id", "AC-1-RENAMED", "description", "the same criterion, renamed")));

        executor.execute(TestExecutorFixtures.testGenNode(), context);

        assertEquals(0, recordedEvidence.size(),
            "the test source still names the pre-rename id; a mapping genuinely derived from that source must"
                + " find no match for the renamed id rather than crediting it anyway");
    }

    /**
     * The positive half of the same proof: once the test is regenerated to name the new
     * id (exactly what TestExecutor's own retry loop would do on the next attempt), the
     * mapping follows with no change to TestExecutor's code at all.
     */
    public void testAfterRegeneratingTheTestWithTheNewNameTheMappingFollows() throws IOException {
        Path projectDir = ThrowawayCompileProject.copyFresh();
        Path artifactsDir = Files.createTempDirectory("executor-artifacts-rename2");
        Path fixturesDir = Files.createTempDirectory("executor-fixtures-rename2");
        Path runsDir = Files.createTempDirectory("executor-runs-rename2");

        FakeAgentClient fakeClient = FakeAgentClient.alwaysReturningText(
            agentResponseNamingCriterion("AC_1_RENAMED"));
        com.schwab.agentic.agent.RecordingClient recordingClient =
            new com.schwab.agentic.agent.RecordingClient(fakeClient, fixturesDir);

        List<Evidence> recordedEvidence = new ArrayList<>();
        WorkflowState state = newState();
        TestExecutor executor = new TestExecutor(recordingClient, new CommandRunner(), projectDir, artifactsDir,
            runsDir, "RUN-1", "./gradlew test", recordedEvidence::add, state);

        Map<String, Object> context = Map.of("acceptanceCriteria",
            List.of(Map.of("id", "AC-1-RENAMED", "description", "the same criterion, renamed")));

        executor.execute(TestExecutorFixtures.testGenNode(), context);

        assertEquals(1, recordedEvidence.size(), "expected exactly one piece of evidence, for the renamed criterion");
        assertEquals("AC-1-RENAMED", recordedEvidence.get(0).acceptanceCriterionId(),
            "evidence must follow the rename automatically since the mapping is derived from the real test name"
                + " at run time, not hardcoded to the original criterion id anywhere in TestExecutor");
    }

    public void testACriterionWithNoMatchingTestMethodProducesNoEvidenceAndReportsFailure() throws IOException {
        Path projectDir = ThrowawayCompileProject.copyFresh();
        Path artifactsDir = Files.createTempDirectory("executor-artifacts-missing");
        Path fixturesDir = Files.createTempDirectory("executor-fixtures-missing");
        Path runsDir = Files.createTempDirectory("executor-runs-missing");

        // Agent proves AC-1 only, but the requirement declares both AC-1 and AC-2.
        FakeAgentClient fakeClient = FakeAgentClient.alwaysReturningText(agentResponseNamingCriterion("AC_1"));
        com.schwab.agentic.agent.RecordingClient recordingClient =
            new com.schwab.agentic.agent.RecordingClient(fakeClient, fixturesDir);

        List<Evidence> recordedEvidence = new ArrayList<>();
        WorkflowState state = newState();
        TestExecutor executor = new TestExecutor(recordingClient, new CommandRunner(), projectDir, artifactsDir,
            runsDir, "RUN-1", "./gradlew test", recordedEvidence::add, state);

        Map<String, Object> context = Map.of("acceptanceCriteria",
            List.of(Map.of("id", "AC-1", "description", "criterion one"),
                Map.of("id", "AC-2", "description", "criterion two, never proven")));

        NodeExecutor.ExecutionOutput output = executor.execute(TestExecutorFixtures.testGenNode(), context);

        assertTrue(!output.executorReportedSuccess(),
            "a declared criterion with no matching test method must not be silently treated as covered");
        boolean ac2HasEvidence = recordedEvidence.stream()
            .anyMatch(evidence -> evidence.acceptanceCriterionId().equals("AC-2"));
        assertTrue(!ac2HasEvidence, "AC-2 was never named in any test, so no evidence should exist for it");
    }
}
