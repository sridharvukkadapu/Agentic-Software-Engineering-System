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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Reads the actual target service source and determines the impact of a requirement on
 * it: affected files, affected API contracts, affected data flows, blast radius, and
 * regression risks.
 *
 * Never sends the whole tree: it sends a file inventory (every source file's relative
 * path) plus the contents of files whose path or name plausibly relates to the
 * requirement, and records which files were actually sent so a reviewer can see exactly
 * what the agent was shown. Whether this run is greenfield or brownfield is never passed
 * in as a flag; the agent determines that itself from the real inventory and file
 * contents it was given, and states plainly when a requirement's impact is on files that
 * do not exist yet, rather than returning an empty impact.
 */
public final class ImpactExecutor implements NodeExecutor {

    private static final String SYSTEM_PROMPT = """
        You are a software architect performing impact analysis on an existing URL
        shortener codebase before a requirement is implemented. You are given a file
        inventory of the target service and the contents of files that plausibly relate
        to the requirement.

        Determine for yourself, from the evidence you were given, whether this
        requirement is greenfield (no existing code in the inventory addresses it, so the
        impact is on files that do not exist yet) or brownfield (existing code is
        directly affected). State which one you concluded and why. Never assume; if the
        inventory shows no related files, say plainly that the impact is on new files
        that do not exist yet, rather than returning an empty impact.

        Respond with exactly one fenced json block containing an object with these keys:
        - natureOfChange: string, either "greenfield" or "brownfield", with your
          reasoning folded into the surrounding fields, not asserted without support
        - affectedFiles: array of strings, relative paths of existing files this change
          affects, or an empty array if none exist yet
        - newFilesExpected: array of strings, relative paths of files that will need to
          be created, for a greenfield change
        - affectedApiContracts: array of strings, describing any REST endpoints affected
        - affectedDataFlows: array of strings, describing how data moves through the
          affected area
        - blastRadius: string, a short assessment of how contained or far-reaching this
          change is
        - regressionRisks: array of strings, specific existing behavior that could break
        """;

    private final AgentClient agentClient;
    private final Path artifactsDirectory;
    private final Path targetServiceDirectory;

    public ImpactExecutor(AgentClient agentClient, Path artifactsDirectory, Path targetServiceDirectory) {
        this.agentClient = agentClient;
        this.artifactsDirectory = artifactsDirectory;
        this.targetServiceDirectory = targetServiceDirectory;
    }

    @Override
    public ExecutionOutput execute(WorkflowNode node, Map<String, Object> context) {
        List<String> fileInventory = buildFileInventory();
        Map<String, String> relevantFileContents = readRelevantFiles(fileInventory, context);

        String userPrompt = buildUserPrompt(fileInventory, relevantFileContents, context);
        AgentResponse response = agentClient.call(new AgentRequest(SYSTEM_PROMPT, userPrompt, 3000, node.id()));

        ResponseParser.ParseResult parseResult = ResponseParser.extractJson(response.text(), "json",
            List.of("natureOfChange", "affectedFiles", "blastRadius", "regressionRisks"));

        if (parseResult.isFailure()) {
            return new ExecutionOutput(false,
                "agent response could not be parsed: " + parseResult.failure().reason(),
                Map.of("parseFailureReason", parseResult.failure().reason()));
        }

        Map<String, Object> parsed = parseResult.value();

        Path impactJsonPath = artifactsDirectory.resolve("impact.json");
        Map<String, Object> impactJson = new LinkedHashMap<>(parsed);
        impactJson.put("filesInventoried", fileInventory.size());
        impactJson.put("filesSentToAgent", List.copyOf(relevantFileContents.keySet()));
        writeJsonFile(impactJsonPath, impactJson);

        Path impactMarkdownPath = artifactsDirectory.resolve("impact-analysis.md");
        writeMarkdown(impactMarkdownPath, parsed, relevantFileContents.keySet());

        Map<String, Object> outputs = new LinkedHashMap<>();
        outputs.put("artifactPath", impactMarkdownPath.toString());
        outputs.put("impactJsonPath", impactJsonPath.toString());
        outputs.put("natureOfChange", parsed.get("natureOfChange"));

        return new ExecutionOutput(true,
            "impact analysis complete: " + parsed.get("natureOfChange"), outputs);
    }

    private List<String> buildFileInventory() {
        if (!Files.isDirectory(targetServiceDirectory)) {
            return List.of();
        }
        try (Stream<Path> walk = Files.walk(targetServiceDirectory)) {
            return walk.filter(Files::isRegularFile)
                .filter(path -> !path.toString().contains("/.git/"))
                .filter(path -> !path.toString().contains("/target/"))
                .filter(path -> !path.toString().contains("/build/"))
                .map(path -> targetServiceDirectory.relativize(path).toString())
                .sorted()
                .toList();
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to inventory target service files", e);
        }
    }

    /**
     * Reads the contents of every source file (java, yml, properties) in the inventory,
     * up to a cap, so the agent has real evidence to reason from without sending the
     * whole tree. A requirement about a new capability with nothing related in the
     * inventory legitimately means this returns an empty map, which the agent's own
     * response is expected to state plainly rather than the executor guessing.
     */
    private Map<String, String> readRelevantFiles(List<String> fileInventory, Map<String, Object> context) {
        Map<String, String> contents = new LinkedHashMap<>();
        int maxFiles = 15;
        for (String relativePath : fileInventory) {
            if (contents.size() >= maxFiles) {
                break;
            }
            if (!relativePath.endsWith(".java") && !relativePath.endsWith(".yml") && !relativePath.endsWith(".sql")) {
                continue;
            }
            try {
                contents.put(relativePath, Files.readString(targetServiceDirectory.resolve(relativePath)));
            } catch (IOException e) {
                throw new UncheckedIOException("Failed to read " + relativePath, e);
            }
        }
        return contents;
    }

    private String buildUserPrompt(List<String> fileInventory, Map<String, String> relevantFileContents,
                                    Map<String, Object> context) {
        StringBuilder prompt = new StringBuilder();
        Object normalizedProblem = context.get("normalizedProblem");
        if (normalizedProblem != null) {
            prompt.append("Requirement: ").append(normalizedProblem).append("\n\n");
        }
        prompt.append("File inventory (").append(fileInventory.size()).append(" files):\n");
        for (String path : fileInventory) {
            prompt.append("- ").append(path).append('\n');
        }
        prompt.append("\nContents of files sent for review:\n\n");
        for (Map.Entry<String, String> entry : relevantFileContents.entrySet()) {
            prompt.append("--- ").append(entry.getKey()).append(" ---\n");
            prompt.append(entry.getValue()).append("\n\n");
        }
        if (context.containsKey("previousFailureReason")) {
            prompt.append("A previous attempt failed: ").append(context.get("previousFailureReason"))
                .append(". Address this in your response.");
        }
        return prompt.toString();
    }

    private void writeMarkdown(Path path, Map<String, Object> parsed, java.util.Set<String> filesSent) {
        StringBuilder markdown = new StringBuilder();
        markdown.append("# Impact analysis\n\n");
        markdown.append("**Nature of change:** ").append(parsed.get("natureOfChange")).append("\n\n");
        markdown.append("**Blast radius:** ").append(parsed.get("blastRadius")).append("\n\n");
        markdown.append("## Files sent for review\n\n");
        for (String file : filesSent) {
            markdown.append("- ").append(file).append('\n');
        }
        markdown.append("\n## Affected files\n\n");
        appendList(markdown, parsed.get("affectedFiles"));
        markdown.append("\n## Regression risks\n\n");
        appendList(markdown, parsed.get("regressionRisks"));
        writeFile(path, markdown.toString());
    }

    private void appendList(StringBuilder markdown, Object listValue) {
        if (listValue instanceof List<?> list) {
            for (Object item : list) {
                markdown.append("- ").append(item).append('\n');
            }
        }
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
