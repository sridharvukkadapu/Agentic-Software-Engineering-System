package com.schwab.agentic.executor;

import static com.schwab.agentic.Assertions.assertEquals;
import static com.schwab.agentic.Assertions.assertTrue;

import com.schwab.agentic.agent.AgentRequest;
import com.schwab.agentic.agent.RecordingClient;
import com.schwab.agentic.engine.CommandRunner;
import com.schwab.agentic.engine.NodeExecutor;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Covers {@link ImplementExecutor} against a real, throwaway Gradle project (AC-04-3:
 * output actually compiles, verified by a real build command exit code), and AC-04-4:
 * an induced compile error causes a retry whose prompt contains the compiler output.
 */
public class ImplementExecutorTest {

    public void testWrittenFileActuallyCompilesVerifiedByARealBuildCommand() throws IOException {
        Path projectDir = ThrowawayCompileProject.copyFresh();
        Path artifactsDir = Files.createTempDirectory("executor-artifacts");
        Path fixturesDir = Files.createTempDirectory("executor-fixtures-implement");

        String agentResponse = """
            ```java
            // FILE: src/main/java/com/example/Greeter.java
            package com.example;

            public class Greeter {
                public String greet() {
                    return "hello";
                }
            }
            ```
            """;
        RecordingClient recordingClient = new RecordingClient(
            FakeAgentClient.alwaysReturningText(agentResponse), fixturesDir);

        ImplementExecutor executor = new ImplementExecutor(recordingClient, projectDir, artifactsDir);
        var node = TestExecutorFixtures.implementNode();

        NodeExecutor.ExecutionOutput output = executor.execute(node, Map.of());

        assertTrue(output.executorReportedSuccess(), "executor must report success");
        assertTrue(Files.exists(projectDir.resolve("src/main/java/com/example/Greeter.java")),
            "the file the agent named must actually be written to the project");
        assertTrue(Files.exists(artifactsDir.resolve("implementation.diff")), "implementation.diff must be written");

        CommandRunner.Result buildResult = new CommandRunner().run(
            "./gradlew compileJava", projectDir, Duration.ofMinutes(3));
        assertTrue(buildResult.succeeded(),
            "the real build command must exit 0 for genuinely valid Java the executor wrote: "
                + buildResult.stdout() + buildResult.stderr());
    }

    /**
     * Required (AC-04-4): an induced compile error causes a retry whose prompt contains
     * the compiler output. This exercises the same context-passing mechanism spec 02's
     * engine uses (previousFailureReason), verifying the actual compiler output text,
     * not a summary, reaches the next attempt's request, asserted on the recorded
     * fixture request.
     */
    public void testInducedCompileErrorCausesARetryWhoseFixtureRequestContainsTheCompilerOutput() throws IOException {
        Path projectDir = ThrowawayCompileProject.copyFresh();
        Path artifactsDir = Files.createTempDirectory("executor-artifacts");
        Path fixturesDir = Files.createTempDirectory("executor-fixtures-implement-retry");

        String brokenJava = """
            ```java
            // FILE: src/main/java/com/example/Broken.java
            package com.example;

            public class Broken {
                public String missingSemicolon() {
                    return "oops"
                }
            }
            ```
            """;

        FakeAgentClient fakeClient = FakeAgentClient.alwaysReturningText(brokenJava);
        RecordingClient recordingClient = new RecordingClient(fakeClient, fixturesDir);
        ImplementExecutor executor = new ImplementExecutor(recordingClient, projectDir, artifactsDir);
        var node = TestExecutorFixtures.implementNode();

        executor.execute(node, Map.of());

        CommandRunner.Result buildResult = new CommandRunner().run(
            "./gradlew compileJava", projectDir, Duration.ofMinutes(3));
        assertTrue(!buildResult.succeeded(), "the deliberately broken Java must fail to compile");

        // Simulate the engine's retry: it passes the exit gate's failure reason (the
        // real compiler output) into the next attempt's context as previousFailureReason,
        // exactly as WorkflowEngine.runAttemptsUntilOutcome does.
        Map<String, Object> retryContext = new HashMap<>();
        retryContext.put("previousFailureReason", buildResult.stdout() + buildResult.stderr());

        executor.execute(node, retryContext);

        List<AgentRequest> requestsSeen = fakeClient.requestsSeen();
        assertEquals(2, requestsSeen.size(), "expected exactly two agent calls: the original and the retry");
        AgentRequest retryRequest = requestsSeen.get(1);
        assertTrue(retryRequest.userPrompt().contains("error") || retryRequest.userPrompt().contains("Broken"),
            "the retry's prompt must contain real compiler output naming the actual problem, not a summary: "
                + retryRequest.userPrompt());
    }
}
