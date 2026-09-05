package com.schwab.agentic.executor;

import static com.schwab.agentic.Assertions.assertTrue;

import com.schwab.agentic.agent.RecordingClient;
import com.schwab.agentic.engine.NodeExecutor;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/** Covers {@link DocumentExecutor}: it writes non-empty api-docs.md and CHANGELOG-entry.md. */
public class DocumentExecutorTest {

    public void testDocumentExecutorWritesNonEmptyApiDocsAndChangelog() throws IOException {
        Path artifactsDir = Files.createTempDirectory("executor-artifacts-document");
        Path fixturesDir = Files.createTempDirectory("executor-fixtures-document");

        String agentResponse = """
            ```markdown
            # API docs

            ## POST /api/links

            Creates a shortened link.
            ```

            ```markdown
            Added the ability to create shortened links via POST /api/links.
            ```
            """;
        RecordingClient recordingClient = new RecordingClient(
            FakeAgentClient.alwaysReturningText(agentResponse), fixturesDir);

        DocumentExecutor executor = new DocumentExecutor(recordingClient, artifactsDir);
        var node = TestExecutorFixtures.documentNode();

        NodeExecutor.ExecutionOutput output = executor.execute(node,
            Map.of("designSpec", "some design", "implementationDiff", "some diff"));

        assertTrue(output.executorReportedSuccess(), "executor must report success");

        Path apiDocsPath = artifactsDir.resolve("api-docs.md");
        Path changelogPath = artifactsDir.resolve("CHANGELOG-entry.md");
        assertTrue(Files.exists(apiDocsPath), "api-docs.md must be written");
        assertTrue(Files.exists(changelogPath), "CHANGELOG-entry.md must be written");
        assertTrue(Files.size(apiDocsPath) > 0, "api-docs.md must be non-empty");
        assertTrue(Files.size(changelogPath) > 0, "CHANGELOG-entry.md must be non-empty");

        String apiDocsContent = Files.readString(apiDocsPath);
        String changelogContent = Files.readString(changelogPath);
        assertTrue(apiDocsContent.contains("POST /api/links"), "api-docs.md must contain the real agent output");
        assertTrue(changelogContent.contains("shortened link"), "CHANGELOG-entry.md must contain the real agent output");
        assertTrue(!apiDocsContent.equals(changelogContent),
            "api-docs.md and CHANGELOG-entry.md must be distinct content, not the same block written twice");
    }

    public void testEmptyAgentResponseWithNoMarkdownBlocksIsAFailureNotACrash() throws IOException {
        Path artifactsDir = Files.createTempDirectory("executor-artifacts-document-empty");
        Path fixturesDir = Files.createTempDirectory("executor-fixtures-document-empty");

        RecordingClient recordingClient = new RecordingClient(
            FakeAgentClient.alwaysReturningText("I could not think of anything to document."), fixturesDir);

        DocumentExecutor executor = new DocumentExecutor(recordingClient, artifactsDir);
        var node = TestExecutorFixtures.documentNode();

        NodeExecutor.ExecutionOutput output = executor.execute(node, Map.of());

        assertTrue(!output.executorReportedSuccess(),
            "a response with no fenced markdown blocks must be reported as a failure, not silently accepted");
    }
}
