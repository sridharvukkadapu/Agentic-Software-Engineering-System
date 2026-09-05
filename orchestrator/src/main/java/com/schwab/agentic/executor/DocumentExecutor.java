package com.schwab.agentic.executor;

import com.schwab.agentic.agent.AgentClient;
import com.schwab.agentic.agent.AgentRequest;
import com.schwab.agentic.agent.AgentResponse;
import com.schwab.agentic.agent.ResponseParser;
import com.schwab.agentic.engine.NodeExecutor;
import com.schwab.agentic.model.WorkflowNode;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Produces API documentation and a changelog entry from the design spec and the
 * implementation diff. No exit gate beyond {@code artifact-written}: unlike the other
 * executors, documentation quality is not something deterministic code can grade, so
 * this stage is gated only on whether it produced a real, non-empty artifact at all.
 */
public final class DocumentExecutor implements NodeExecutor {

    private static final String SYSTEM_PROMPT = """
        You are a technical writer producing documentation for a change to a Java
        service, given its design spec and the diff that implemented it.

        Respond with exactly two fenced blocks, in this order:

        1. A fenced markdown block containing API documentation for any new or changed
           endpoints: method, path, request shape, response shape, and status codes.
        2. A fenced markdown block containing a single changelog entry describing the
           change in one or two sentences, suitable for a CHANGELOG file.

        Write real, complete content, not a placeholder or an outline.
        """;

    private final AgentClient agentClient;
    private final Path artifactsDirectory;

    public DocumentExecutor(AgentClient agentClient, Path artifactsDirectory) {
        this.agentClient = agentClient;
        this.artifactsDirectory = artifactsDirectory;
    }

    @Override
    public ExecutionOutput execute(WorkflowNode node, Map<String, Object> context) {
        String userPrompt = buildUserPrompt(context);
        AgentResponse response = agentClient.call(new AgentRequest(SYSTEM_PROMPT, userPrompt, 3000, node.id()));

        var blocks = ResponseParser.extractFencedBlocks(response.text());
        var markdownBlocks = blocks.stream()
            .filter(block -> block.language().equalsIgnoreCase("markdown") || block.language().isBlank())
            .toList();

        if (markdownBlocks.isEmpty()) {
            return new ExecutionOutput(false,
                "agent response contained no fenced markdown blocks", Map.of());
        }

        String apiDocs = markdownBlocks.get(0).content().strip();
        String changelogEntry = markdownBlocks.size() > 1
            ? markdownBlocks.get(1).content().strip()
            : markdownBlocks.get(0).content().strip();

        Path apiDocsPath = artifactsDirectory.resolve("api-docs.md");
        writeFile(apiDocsPath, apiDocs);

        Path changelogPath = artifactsDirectory.resolve("CHANGELOG-entry.md");
        writeFile(changelogPath, changelogEntry);

        Map<String, Object> outputs = new LinkedHashMap<>();
        outputs.put("artifactPath", apiDocsPath.toString());
        outputs.put("changelogPath", changelogPath.toString());

        return new ExecutionOutput(true,
            "documentation written: " + apiDocsPath + ", " + changelogPath, outputs);
    }

    private String buildUserPrompt(Map<String, Object> context) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("Document the change described below.\n\n");
        if (context.containsKey("designSpec")) {
            prompt.append("Design spec:\n").append(context.get("designSpec")).append("\n\n");
        }
        if (context.containsKey("implementationDiff")) {
            prompt.append("Implementation diff:\n").append(context.get("implementationDiff")).append("\n\n");
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
}
