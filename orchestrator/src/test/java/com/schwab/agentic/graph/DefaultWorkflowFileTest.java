package com.schwab.agentic.graph;

import static com.schwab.agentic.Assertions.assertEquals;

import java.nio.file.Path;
import java.util.Set;

/**
 * Loads the actual committed workflows/sdlc-default.json, not a hand-built equivalent,
 * so a change to that file that breaks loading or validation is caught here instead of
 * only surfacing the first time the execution engine tries to run it.
 */
public class DefaultWorkflowFileTest {

    public void testDefaultWorkflowFileLoadsAndValidates() {
        Path path = findWorkflowFile();
        WorkflowGraph graph = WorkflowGraph.loadFromFile(path);

        assertEquals(
            Set.of("REQUIREMENT", "IMPACT", "DESIGN", "IMPLEMENT", "TEST", "DOCUMENT", "VALIDATE", "RELEASE"),
            graph.getNodeIds(),
            "the default workflow file must declare exactly the eight specified nodes");
    }

    public void testDefaultWorkflowFileDownstreamOfDesignMatchesTheSpec() {
        WorkflowGraph graph = WorkflowGraph.loadFromFile(findWorkflowFile());

        Set<String> downstream = graph.downstreamOf("DESIGN");

        assertEquals(Set.of("IMPLEMENT", "TEST", "DOCUMENT", "VALIDATE", "RELEASE"), downstream,
            "downstreamOf(DESIGN) on the file-loaded default graph");
    }

    /**
     * Walks up from the working directory to find workflows/sdlc-default.json, since
     * test.sh may invoke TestRunner from either the repo root or the orchestrator
     * directory depending on how it is called.
     */
    private static Path findWorkflowFile() {
        Path current = Path.of("").toAbsolutePath();
        while (current != null) {
            Path candidate = current.resolve("workflows/sdlc-default.json");
            if (candidate.toFile().isFile()) {
                return candidate;
            }
            current = current.getParent();
        }
        throw new IllegalStateException(
            "Could not find workflows/sdlc-default.json by walking up from " + Path.of("").toAbsolutePath());
    }
}
