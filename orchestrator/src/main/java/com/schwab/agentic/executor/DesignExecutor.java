package com.schwab.agentic.executor;

import com.schwab.agentic.agent.AgentClient;
import com.schwab.agentic.agent.AgentRequest;
import com.schwab.agentic.agent.AgentResponse;
import com.schwab.agentic.agent.ResponseParser;
import com.schwab.agentic.engine.NodeExecutor;
import com.schwab.agentic.json.Json;
import com.schwab.agentic.model.DecisionRecord;
import com.schwab.agentic.model.WorkflowNode;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Produces a structured design spec from the requirement and impact analysis: class
 * structure, an API contract fragment, data model changes, and the chosen approach with
 * rejected alternatives.
 *
 * The decision to record ({@code affectsCriteria}, the acceptance criteria this design
 * choice touches) is what spec 06's re-planning uses to scope a re-plan correctly: when
 * a requirement amendment changes something this design decided, re-planning needs to
 * know which criteria, and therefore which downstream evidence, that decision affects.
 */
public final class DesignExecutor implements NodeExecutor {

    private static final String SYSTEM_PROMPT = """
        You are a software architect producing a design specification for a change to a
        URL shortener service, based on a normalized requirement and an impact analysis.

        Propose a specific technical approach. Name at least one alternative you
        considered and rejected, and say why you rejected it: a design with no
        considered alternative is not defensible.

        Respond with exactly one fenced json block containing an object with these keys:
        - classStructure: array of strings, one per class/component to add or change,
          each naming the class and its responsibility
        - apiContract: string, an OpenAPI-fragment-style description of any new or
          changed endpoints (path, method, request/response shape)
        - dataModelChanges: array of strings, schema or entity changes required
        - chosenApproach: string, the approach you are recommending
        - rejectedAlternatives: array of objects, each with "alternative" (string) and
          "reason" (string) for why it was not chosen
        - affectsCriteria: array of strings, the acceptance criterion ids (like "AC-1")
          this design decision affects
        """;

    private final AgentClient agentClient;
    private final Path artifactsDirectory;
    private final DecisionSink decisionSink;

    public DesignExecutor(AgentClient agentClient, Path artifactsDirectory, DecisionSink decisionSink) {
        this.agentClient = agentClient;
        this.artifactsDirectory = artifactsDirectory;
        this.decisionSink = decisionSink;
    }

    /**
     * Where this executor records the DecisionRecord it produces. A plain functional
     * interface rather than a direct WorkflowState dependency, so this executor stays
     * testable without constructing a full run's worth of state just to check what
     * decision it recorded.
     */
    public interface DecisionSink {
        void record(DecisionRecord decision);
    }

    @Override
    public ExecutionOutput execute(WorkflowNode node, Map<String, Object> context) {
        String userPrompt = buildUserPrompt(context);
        AgentResponse response = agentClient.call(new AgentRequest(SYSTEM_PROMPT, userPrompt, 3000, node.id()));

        ResponseParser.ParseResult parseResult = ResponseParser.extractJson(response.text(), "json",
            List.of("classStructure", "apiContract", "dataModelChanges", "chosenApproach",
                "rejectedAlternatives", "affectsCriteria"));

        if (parseResult.isFailure()) {
            return new ExecutionOutput(false,
                "agent response could not be parsed: " + parseResult.failure().reason(),
                Map.of("parseFailureReason", parseResult.failure().reason()));
        }

        Map<String, Object> parsed = parseResult.value();

        Path designSpecPath = artifactsDirectory.resolve("design-spec.json");
        writeJsonFile(designSpecPath, parsed);

        Path openApiPath = artifactsDirectory.resolve("openapi-fragment.yaml");
        writeFile(openApiPath, String.valueOf(parsed.get("apiContract")));

        Path designMarkdownPath = artifactsDirectory.resolve("design.md");
        writeMarkdown(designMarkdownPath, parsed);

        @SuppressWarnings("unchecked")
        List<String> affectsCriteria = ((List<Object>) parsed.getOrDefault("affectsCriteria", List.of()))
            .stream().map(String::valueOf).toList();

        decisionSink.record(new DecisionRecord(
            "DECISION-" + UUID.randomUUID(),
            String.valueOf(parsed.get("chosenApproach")),
            "agent:" + node.id(),
            Instant.now(),
            Map.of("affectsCriteria", affectsCriteria, "rejectedAlternatives",
                parsed.getOrDefault("rejectedAlternatives", List.of()))));

        Map<String, Object> outputs = new LinkedHashMap<>();
        outputs.put("artifactPath", designMarkdownPath.toString());
        outputs.put("designSpecPath", designSpecPath.toString());
        outputs.put("openApiPath", openApiPath.toString());

        return new ExecutionOutput(true, "design produced: " + parsed.get("chosenApproach"), outputs);
    }

    private String buildUserPrompt(Map<String, Object> context) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("Produce a design specification for the change described below.\n\n");
        if (context.containsKey("normalizedProblem")) {
            prompt.append("Requirement: ").append(context.get("normalizedProblem")).append("\n\n");
        }
        if (context.containsKey("impactSummary")) {
            prompt.append("Impact analysis: ").append(context.get("impactSummary")).append("\n\n");
        }
        if (context.containsKey("previousFailureReason")) {
            prompt.append("A previous attempt failed: ").append(context.get("previousFailureReason"))
                .append(". Address this in your response.");
        }
        return prompt.toString();
    }

    private void writeMarkdown(Path path, Map<String, Object> parsed) {
        StringBuilder markdown = new StringBuilder();
        markdown.append("# Design\n\n");
        markdown.append("## Chosen approach\n\n").append(parsed.get("chosenApproach")).append("\n\n");
        markdown.append("## Rejected alternatives\n\n");
        if (parsed.get("rejectedAlternatives") instanceof List<?> alternatives) {
            for (Object altObj : alternatives) {
                if (altObj instanceof Map<?, ?> alt) {
                    markdown.append("- **").append(alt.get("alternative")).append("**: ")
                        .append(alt.get("reason")).append('\n');
                }
            }
        }
        markdown.append("\n## Class structure\n\n");
        if (parsed.get("classStructure") instanceof List<?> classes) {
            for (Object c : classes) {
                markdown.append("- ").append(c).append('\n');
            }
        }
        writeFile(path, markdown.toString());
    }

    private void writeJsonFile(Path path, Object content) {
        writeFile(path, Json.write(content));
    }

    private void writeFile(Path path, String content) {
        try {
            Files.createDirectories(path.getParent());
            Files.writeString(path, content);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to write " + path, e);
        }
    }
}
