package com.schwab.agentic.executor;

import com.schwab.agentic.agent.AgentClient;
import com.schwab.agentic.agent.AgentRequest;
import com.schwab.agentic.agent.AgentResponse;
import com.schwab.agentic.agent.ResponseParser;
import com.schwab.agentic.engine.NodeExecutor;
import com.schwab.agentic.json.Json;
import com.schwab.agentic.model.WorkflowNode;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Normalizes a raw requirement into structured intent, acceptance criteria, detected
 * ambiguities, proposed assumptions and an out-of-scope list.
 *
 * This is where ambiguity handling actually matters: for a requirement that genuinely
 * leaves something unspecified, this executor is required to say so in
 * {@code ambiguities}, not silently pick an interpretation and move on. A stronger
 * version of the same idea governs {@code open-questions.json}: when the requirement
 * omits behavior the design will need an answer to (not merely something ambiguous in
 * wording, but something the requirement never addresses at all), this executor writes
 * that gap to {@code open-questions.json} rather than inventing a policy to fill it. The
 * {@code requirement-complete} exit gate reads that file from disk and fails
 * deterministically if it is non-empty, so a genuine gap safe-stops the run instead of
 * silently flowing downstream as an assumption nobody approved.
 */
public final class RequirementExecutor implements NodeExecutor {

    private static final String SYSTEM_PROMPT = """
        You are a requirements analyst for a URL shortener service. You read a raw
        requirement (a feature request or a bug report) and normalize it into a
        structured specification an engineering team can act on.

        You must be honest about what the requirement does not say. Do not invent
        behavior the requirement never specifies. If wording is unclear or leaves room
        for more than one reasonable interpretation, record that in "ambiguities".
        If the requirement is entirely silent about something the design will need an
        answer to before implementation can proceed (not just unclear, but never
        addressed at all), record that separately in "openQuestions" and do not guess
        at a policy to fill it.

        Respond with exactly one fenced json block containing an object with these keys:
        - normalizedProblem: string, the requirement restated precisely and concisely
        - acceptanceCriteria: array of objects, each with "id" (string like "AC-1"),
          "description" (string), and "riskLevel" (one of "LOW", "MEDIUM", "HIGH", "CRITICAL")
        - ambiguities: array of strings, wording or scope that is unclear
        - assumptions: array of strings, reasonable assumptions you are proposing to
          resolve an ambiguity (for a human to confirm or reject, not silently adopted)
        - outOfScope: array of strings, explicitly excluded from this requirement
        - openQuestions: array of strings, things the requirement never addresses at all
          that the design will need answered before implementation can proceed. Leave
          this empty if the requirement, even if imperfectly worded, actually covers
          everything the design needs.
        """;

    private final AgentClient agentClient;
    private final Path artifactsDirectory;

    public RequirementExecutor(AgentClient agentClient, Path artifactsDirectory) {
        this.agentClient = agentClient;
        this.artifactsDirectory = artifactsDirectory;
    }

    @Override
    public ExecutionOutput execute(WorkflowNode node, Map<String, Object> context) {
        String requirementText = readRequirementText(context);
        String userPrompt = buildUserPrompt(requirementText, context);

        AgentResponse response = agentClient.call(new AgentRequest(SYSTEM_PROMPT, userPrompt, 4000, node.id()));

        ResponseParser.ParseResult parseResult = ResponseParser.extractJson(response.text(), "json",
            List.of("normalizedProblem", "acceptanceCriteria", "ambiguities", "assumptions", "outOfScope",
                "openQuestions"));

        if (parseResult.isFailure()) {
            return new ExecutionOutput(false,
                "agent response could not be parsed: " + parseResult.failure().reason(),
                Map.of("parseFailureReason", parseResult.failure().reason()));
        }

        Map<String, Object> parsed = parseResult.value();

        Path requirementSpecPath = artifactsDirectory.resolve("requirement-spec.json");
        writeJsonFile(requirementSpecPath, requirementSpecJson(node, parsed));

        @SuppressWarnings("unchecked")
        List<Object> openQuestions = (List<Object>) parsed.getOrDefault("openQuestions", List.of());
        Path openQuestionsPath = artifactsDirectory.resolve("open-questions.json");
        writeJsonFile(openQuestionsPath, openQuestions);

        Map<String, Object> outputs = new LinkedHashMap<>();
        outputs.put("artifactPath", requirementSpecPath.toString());
        outputs.put("openQuestionsPath", openQuestionsPath.toString());
        outputs.put("openQuestionCount", (double) openQuestions.size());

        return new ExecutionOutput(true, "requirement normalized with "
            + ((List<?>) parsed.getOrDefault("acceptanceCriteria", List.of())).size() + " acceptance criteria"
            + (openQuestions.isEmpty() ? "" : " and " + openQuestions.size() + " open question(s)"),
            outputs);
    }

    private String readRequirementText(Map<String, Object> context) {
        Object requirementPathValue = context.get("requirementPath");
        if (!(requirementPathValue instanceof String requirementPathString)) {
            throw new IllegalArgumentException(
                "RequirementExecutor requires context key \"requirementPath\" naming the requirement.md to read");
        }
        try {
            return Files.readString(Path.of(requirementPathString));
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read requirement text: " + requirementPathString, e);
        }
    }

    private String buildUserPrompt(String requirementText, Map<String, Object> context) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("Here is the raw requirement:\n\n").append(requirementText);
        if (context.containsKey("previousFailureReason")) {
            prompt.append("\n\nA previous attempt at this task failed: ")
                .append(context.get("previousFailureReason"))
                .append(". Address this in your response.");
        }
        return prompt.toString();
    }

    private Map<String, Object> requirementSpecJson(WorkflowNode node, Map<String, Object> parsed) {
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("id", "REQ-" + node.id());
        json.put("revision", 1.0);
        json.put("rawText", parsed.get("normalizedProblem"));
        json.put("normalizedProblem", parsed.get("normalizedProblem"));
        json.put("acceptanceCriteria", parsed.get("acceptanceCriteria"));
        json.put("ambiguities", parsed.getOrDefault("ambiguities", List.of()));
        json.put("assumptions", parsed.getOrDefault("assumptions", List.of()));
        json.put("outOfScope", parsed.getOrDefault("outOfScope", List.of()));
        return json;
    }

    private void writeJsonFile(Path path, Object content) {
        try {
            Files.createDirectories(path.getParent());
            Files.writeString(path, Json.write(content));
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to write " + path, e);
        }
    }
}
