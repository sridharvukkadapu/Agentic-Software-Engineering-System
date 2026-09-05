package com.schwab.agentic.executor;

import com.schwab.agentic.agent.AgentClient;
import com.schwab.agentic.agent.AgentRequest;
import com.schwab.agentic.agent.AgentResponse;
import com.schwab.agentic.agent.ResponseParser;
import com.schwab.agentic.engine.CommandRunner;
import com.schwab.agentic.engine.NodeExecutor;
import com.schwab.agentic.model.Evidence;
import com.schwab.agentic.model.WorkflowNode;
import com.schwab.agentic.model.WorkflowState;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Consumes the design spec and acceptance criteria, asks the agent for real JUnit test
 * source files, writes them into the target service, runs the real test command, and
 * emits {@link Evidence} with {@link Evidence.Origin#EXECUTED} per acceptance criterion.
 *
 * The criterion a test method proves is never a hardcoded map from test name to
 * criterion id kept somewhere in this class: it is found by checking, at run time,
 * whether a real test method's name contains the identifier form of a criterion id read
 * straight out of {@code context} for this run (see {@link #identifierFormsFoundIn}),
 * which is what AC-04-5 requires ("renaming a criterion and seeing the mapping follow").
 * A hardcoded map would still show the rename in requirement-spec.json but silently keep
 * pointing evidence at the old id, exactly the kind of asserted-not-derived link
 * CLAUDE.md rule 1 forbids.
 */
public final class TestExecutor implements NodeExecutor {

    private static final String SYSTEM_PROMPT = """
        You are a software engineer writing JUnit 5 tests for a Java service, given a
        design spec and a list of acceptance criteria.

        Write one or more test methods per acceptance criterion. Every test method whose
        name proves a given criterion must contain that criterion's id, with any
        non-alphanumeric characters in the id replaced by underscores, somewhere in the
        method name. For example, criterion "AC-7" is proven by a method named
        something like `testAC_7RejectsADuplicateShortCode`.

        For each file you write, respond with a separate fenced code block whose first
        line is a comment giving the file's path relative to the project root, exactly
        like this:

        ```java
        // FILE: src/test/java/com/example/ThingTest.java
        package com.example;

        class ThingTest {
        }
        ```

        Write complete, compilable test file contents, not a diff or a snippet.
        """;

    private static final Pattern JUNIT_TEST_RESULT_LINE = Pattern.compile(
        ">\\s+(?<method>[A-Za-z0-9_$]+)\\(?\\)?\\s+(?<outcome>PASSED|FAILED)", Pattern.CASE_INSENSITIVE);

    private final AgentClient agentClient;
    private final CommandRunner commandRunner;
    private final Path targetDirectory;
    private final Path artifactsDirectory;
    private final Path runsDirectory;
    private final String runId;
    private final String testCommand;
    private final EvidenceSink evidenceSink;
    private final WorkflowState workflowState;

    public TestExecutor(AgentClient agentClient, CommandRunner commandRunner, Path targetDirectory,
                         Path artifactsDirectory, Path runsDirectory, String runId, String testCommand,
                         EvidenceSink evidenceSink, WorkflowState workflowState) {
        this.agentClient = agentClient;
        this.commandRunner = commandRunner;
        this.targetDirectory = targetDirectory;
        this.artifactsDirectory = artifactsDirectory;
        this.runsDirectory = runsDirectory;
        this.runId = runId;
        this.testCommand = testCommand;
        this.evidenceSink = evidenceSink;
        this.workflowState = workflowState;
    }

    /**
     * Where this executor records the {@link Evidence} it derives from real test output.
     * A plain functional interface, mirroring {@link DesignExecutor.DecisionSink}, so
     * this executor stays testable without constructing a full run's worth of state.
     */
    public interface EvidenceSink {
        void record(Evidence evidence);
    }

    @Override
    public ExecutionOutput execute(WorkflowNode node, Map<String, Object> context) {
        String userPrompt = buildUserPrompt(context);
        AgentResponse response = agentClient.call(new AgentRequest(SYSTEM_PROMPT, userPrompt, 4000, node.id()));

        List<ResponseParser.CodeBlock> blocks = ResponseParser.extractFencedBlocks(response.text());
        List<FileWrite> fileWrites = extractFileWrites(blocks);

        if (fileWrites.isEmpty()) {
            return new ExecutionOutput(false,
                "agent response contained no fenced blocks with a recognizable // FILE: header", Map.of());
        }

        Map<String, String> declaredIdsByIdentifierForm = extractDeclaredCriteriaIds(context);
        Set<String> allTestMethodNames = new LinkedHashSet<>();
        for (FileWrite fileWrite : fileWrites) {
            Path absolutePath = targetDirectory.resolve(fileWrite.relativePath());
            writeFile(absolutePath, fileWrite.content());
            allTestMethodNames.addAll(testMethodNamesIn(fileWrite.content()));
        }
        Set<String> identifierFormsNamedInTests = identifierFormsFoundIn(
            allTestMethodNames, declaredIdsByIdentifierForm.keySet());

        CommandRunner.Result result = commandRunner.runAndRecord(
            testCommand, targetDirectory, Duration.ofMinutes(10), runsDirectory, runId, "test", workflowState);

        Map<String, Boolean> outcomeByMethodName = parseTestOutcomes(result.stdout() + result.stderr());
        List<String> criteriaCovered = new ArrayList<>();

        for (String identifierForm : identifierFormsNamedInTests) {
            String originalCriterionId = declaredIdsByIdentifierForm.getOrDefault(identifierForm, identifierForm);
            boolean anyMethodForThisCriterionRan = false;
            boolean allMethodsForThisCriterionPassed = true;
            for (Map.Entry<String, Boolean> entry : outcomeByMethodName.entrySet()) {
                if (methodNameNamesCriterion(entry.getKey(), identifierForm)) {
                    anyMethodForThisCriterionRan = true;
                    allMethodsForThisCriterionPassed &= entry.getValue();
                }
            }
            boolean passed = anyMethodForThisCriterionRan && allMethodsForThisCriterionPassed;
            criteriaCovered.add(originalCriterionId);
            evidenceSink.record(new Evidence(
                Evidence.Origin.EXECUTED,
                originalCriterionId,
                passed,
                anyMethodForThisCriterionRan
                    ? "derived from real test command output (exit code " + result.exitCode() + ")"
                    : "no test method result matched a method name containing this criterion id",
                testCommand,
                node.id(),
                artifactsDirectory.resolve("test-results.log").toString(),
                Instant.now()));
        }

        Path resultsLogPath = artifactsDirectory.resolve("test-results.log");
        writeFile(resultsLogPath, "$ " + testCommand + "\n\n" + result.stdout() + "\n" + result.stderr()
            + "\n--- exit code: " + result.exitCode() + " ---\n");

        Map<String, Object> outputs = new LinkedHashMap<>();
        outputs.put("artifactPath", resultsLogPath.toString());
        outputs.put("filesWritten", fileWrites.stream().map(FileWrite::relativePath).toList());
        outputs.put("criteriaCovered", criteriaCovered);
        outputs.put("exitCode", (double) result.exitCode());

        boolean uncoveredCriterionExists = !declaredIdsByIdentifierForm.isEmpty()
            && !identifierFormsNamedInTests.containsAll(declaredIdsByIdentifierForm.keySet());

        return new ExecutionOutput(result.succeeded() && !uncoveredCriterionExists,
            "wrote " + fileWrites.size() + " test file(s) covering " + criteriaCovered.size()
                + " criteria, test command exited " + result.exitCode(),
            outputs);
    }

    /**
     * Maps each declared criterion's identifier-safe form (non-alphanumeric characters
     * replaced with underscore, matching what the system prompt asks test method names
     * to embed) back to the criterion's real id (e.g. "AC-1"), so a match found in a
     * test method name can be recorded as evidence against the same id the requirement
     * spec and every gate actually use, while the matching itself happens in a form a
     * Java identifier can legally contain.
     */
    private Map<String, String> extractDeclaredCriteriaIds(Map<String, Object> context) {
        Map<String, String> idsByIdentifierForm = new LinkedHashMap<>();
        Object criteria = context.get("acceptanceCriteria");
        if (criteria instanceof List<?> list) {
            for (Object item : list) {
                String originalId = null;
                if (item instanceof Map<?, ?> map && map.get("id") != null) {
                    originalId = String.valueOf(map.get("id"));
                } else if (item != null) {
                    originalId = String.valueOf(item);
                }
                if (originalId != null) {
                    idsByIdentifierForm.put(normalizeToIdentifier(originalId), originalId);
                }
            }
        }
        return idsByIdentifierForm;
    }

    private String normalizeToIdentifier(String criterionId) {
        return criterionId.replaceAll("[^A-Za-z0-9_]", "_");
    }

    /**
     * Every test method name declared in this file's source, parsed directly from the
     * source text (a method returning void whose name starts with "test") rather than
     * from any structure this executor itself produced.
     */
    private Set<String> testMethodNamesIn(String javaSource) {
        Set<String> methodNames = new LinkedHashSet<>();
        Matcher methodMatcher = Pattern.compile("void\\s+(test\\w*)\\s*\\(").matcher(javaSource);
        while (methodMatcher.find()) {
            methodNames.add(methodMatcher.group(1));
        }
        return methodNames;
    }

    /**
     * Which of the declared criteria's identifier forms actually appear inside at least
     * one real test method name. This is a containment check against the criteria the
     * requirement declared, read from {@code context} at run time, never a hardcoded
     * table pairing a specific criterion id to a specific test name kept in this class:
     * renaming a criterion changes the string this method looks for automatically, since
     * the string itself always comes from whatever the requirement currently says.
     */
    private Set<String> identifierFormsFoundIn(Set<String> methodNames, Set<String> declaredIdentifierForms) {
        Set<String> found = new LinkedHashSet<>();
        for (String identifierForm : declaredIdentifierForms) {
            for (String methodName : methodNames) {
                if (methodName.contains(identifierForm)) {
                    found.add(identifierForm);
                    break;
                }
            }
        }
        return found;
    }

    private boolean methodNameNamesCriterion(String methodName, String identifierForm) {
        return methodName.contains(identifierForm);
    }

    /**
     * Parses per-method pass/fail results out of the real test command's combined
     * output. The exact reporting format varies by build tool; this looks for the
     * `methodName(ClassName) PASSED|FAILED` shape Gradle's console test logger and
     * similar tools produce, matched case-insensitively against a per-method-name map
     * so a criterion is only considered proven if its named methods actually ran.
     */
    private Map<String, Boolean> parseTestOutcomes(String combinedOutput) {
        Map<String, Boolean> outcomes = new LinkedHashMap<>();
        Matcher matcher = JUNIT_TEST_RESULT_LINE.matcher(combinedOutput);
        while (matcher.find()) {
            String methodName = matcher.group("method");
            boolean passed = "PASSED".equalsIgnoreCase(matcher.group("outcome"));
            outcomes.merge(methodName, passed, (existing, next) -> existing && next);
        }
        return outcomes;
    }

    private List<FileWrite> extractFileWrites(List<ResponseParser.CodeBlock> blocks) {
        List<FileWrite> writes = new ArrayList<>();
        for (ResponseParser.CodeBlock block : blocks) {
            String content = block.content();
            String firstLine = content.lines().findFirst().orElse("");
            Matcher matcher = Pattern.compile("//\\s*FILE:\\s*(\\S+)").matcher(firstLine);
            if (!matcher.find()) {
                continue;
            }
            String relativePath = matcher.group(1);
            String remainingContent = content.substring(firstLine.length()).stripLeading();
            writes.add(new FileWrite(relativePath, remainingContent));
        }
        return writes;
    }

    private String buildUserPrompt(Map<String, Object> context) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("Write JUnit tests proving the acceptance criteria below against the design spec.\n\n");
        if (context.containsKey("designSpec")) {
            prompt.append("Design spec:\n").append(context.get("designSpec")).append("\n\n");
        }
        if (context.containsKey("acceptanceCriteria")) {
            prompt.append("Acceptance criteria:\n").append(context.get("acceptanceCriteria")).append("\n\n");
        }
        if (context.containsKey("previousFailureReason")) {
            prompt.append("A previous attempt's tests failed with this output:\n")
                .append(context.get("previousFailureReason")).append("\n\nFix this specific problem.\n\n");
        }
        return prompt.toString();
    }

    private void writeFile(Path path, String content) {
        try {
            Files.createDirectories(path.getParent());
            Files.writeString(path, content);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to write " + path, e);
        }
    }

    private record FileWrite(String relativePath, String content) {
    }
}
