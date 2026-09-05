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
import java.util.List;
import java.util.Map;

/**
 * Consumes {@code design-spec.json} and writes real Java source into the target
 * directory, plus a unified diff into {@code runs/<runId>/artifacts/implementation.diff}.
 *
 * On a retry, the compiler output from the previous attempt is expected in
 * {@code context.get("previousFailureReason")}, which the {@code compiles} exit gate's
 * failure reason supplies: the exit gate is what actually ran the build and read a real
 * exit code, so this executor never has to run a build itself to know whether its own
 * output compiled, it only has to write real files and let the gate judge them.
 *
 * The agent is asked to return each file as a separate fenced block named by a header
 * comment giving the file's relative path, since a single JSON blob containing large
 * source file bodies is fragile to escape correctly; multiple simple fenced blocks are
 * far more reliable for an LLM to produce and for a parser to extract.
 */
public final class ImplementExecutor implements NodeExecutor {

    private static final String SYSTEM_PROMPT = """
        You are a software engineer implementing a design specification as real Java
        source code for an existing project.

        For each file you write or change, respond with a separate fenced code block.
        The first line inside the fence must be a comment giving the file's path
        relative to the project root, exactly like this:

        ```java
        // FILE: src/main/java/com/example/Thing.java
        package com.example;

        public class Thing {
        }
        ```

        Write complete, compilable file contents, not a diff or a snippet: whatever you
        put in the fenced block replaces the entire file at that path. If a previous
        attempt's compiler output is provided, it names exactly what is wrong; fix that
        specific problem rather than rewriting unrelated parts of the file.
        """;

    private final AgentClient agentClient;
    private final Path targetDirectory;
    private final Path artifactsDirectory;

    public ImplementExecutor(AgentClient agentClient, Path targetDirectory, Path artifactsDirectory) {
        this.agentClient = agentClient;
        this.targetDirectory = targetDirectory;
        this.artifactsDirectory = artifactsDirectory;
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

        StringBuilder diff = new StringBuilder();
        for (FileWrite fileWrite : fileWrites) {
            Path absolutePath = targetDirectory.resolve(fileWrite.relativePath());
            String before = Files.exists(absolutePath) ? readQuietly(absolutePath) : "";
            writeFile(absolutePath, fileWrite.content());
            diff.append(unifiedDiffHeader(fileWrite.relativePath(), before, fileWrite.content()));
        }

        Path diffPath = artifactsDirectory.resolve("implementation.diff");
        writeFile(diffPath, diff.toString());

        Map<String, Object> outputs = new LinkedHashMap<>();
        outputs.put("artifactPath", diffPath.toString());
        outputs.put("filesWritten", fileWrites.stream().map(FileWrite::relativePath).toList());
        outputs.put("exitCode", 0.0);

        return new ExecutionOutput(true,
            "wrote " + fileWrites.size() + " file(s), diff recorded at " + diffPath, outputs);
    }

    private List<FileWrite> extractFileWrites(List<ResponseParser.CodeBlock> blocks) {
        List<FileWrite> writes = new java.util.ArrayList<>();
        for (ResponseParser.CodeBlock block : blocks) {
            String content = block.content();
            String firstLine = content.lines().findFirst().orElse("");
            java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile("//\\s*FILE:\\s*(\\S+)").matcher(firstLine);
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
        prompt.append("Implement the design spec below as real, complete Java source files.\n\n");
        if (context.containsKey("designSpec")) {
            prompt.append("Design spec:\n").append(context.get("designSpec")).append("\n\n");
        }
        if (context.containsKey("previousFailureReason")) {
            prompt.append("The previous attempt failed to compile with this output:\n")
                .append(context.get("previousFailureReason")).append("\n\nFix this specific problem.\n\n");
        }
        return prompt.toString();
    }

    private String readQuietly(Path path) {
        try {
            return Files.readString(path);
        } catch (IOException e) {
            return "";
        }
    }

    private void writeFile(Path path, String content) {
        try {
            Files.createDirectories(path.getParent());
            Files.writeString(path, content);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to write " + path, e);
        }
    }

    /**
     * A minimal unified-diff-style header for the artifact, not a byte-exact unified
     * diff: full line-by-line diffing is not this executor's job, it just needs to
     * record what changed for a reviewer to see, and a real diff tool run over the
     * checkpoint (spec 08's reporting) is a more appropriate place for exact diffs.
     */
    private String unifiedDiffHeader(String relativePath, String before, String after) {
        return "--- a/" + relativePath + "\n+++ b/" + relativePath + "\n"
            + "(" + before.lines().count() + " lines before, " + after.lines().count() + " lines after)\n\n";
    }

    private record FileWrite(String relativePath, String content) {
    }
}
