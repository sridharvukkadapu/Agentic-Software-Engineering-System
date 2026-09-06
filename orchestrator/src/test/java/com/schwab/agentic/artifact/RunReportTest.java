package com.schwab.agentic.artifact;

import static com.schwab.agentic.Assertions.assertTrue;

import com.schwab.agentic.model.WorkflowState;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Covers {@link RunReport}'s Mermaid graph and traceability matrix sections, built from a
 * hand-written {@code state.json} fixture on disk, not a live run, matching
 * {@link com.schwab.agentic.engine.RunMetricsTest}'s own fixture style.
 */
public class RunReportTest {

    public void testMermaidOutputNamesEveryNodeAndEdge() throws IOException {
        WorkflowState state = loadFixture(twoNodeChainJson(null));

        String report = new RunReport(state).render();

        assertTrue(report.contains("```mermaid"), "the graph section must be fenced as a mermaid code block");
        assertTrue(report.contains("flowchart TD"), "mermaid output must declare a flowchart");
        assertTrue(report.contains("REQUIREMENT"), "mermaid output must name the REQUIREMENT node");
        assertTrue(report.contains("DOCUMENT"), "mermaid output must name the DOCUMENT node");
        assertTrue(report.contains("REQUIREMENT --> DOCUMENT"), "mermaid output must contain the real REQUIREMENT -> DOCUMENT edge");
    }

    public void testTraceabilityMatrixResolvesEveryArtifactPathToARealFile() throws IOException {
        Path realArtifact = Files.createTempFile("run-report-real-artifact", ".log");
        Files.writeString(realArtifact, "real test output, proving this artifact path resolves to a real file");

        WorkflowState state = loadFixture(twoNodeChainJson(realArtifact.toString()));

        String report = new RunReport(state).render();

        assertTrue(report.contains("## Traceability matrix"), "report must contain a traceability matrix section");
        assertTrue(report.contains("AC-1"), "traceability matrix must have a row for AC-1");
        assertTrue(report.contains(realArtifact.toString()),
            "traceability matrix must name the real evidence artifact path: " + report);
        assertTrue(Files.isRegularFile(Path.of(realArtifact.toString())),
            "the artifact path named in the matrix must actually resolve to a real file on disk, not an asserted string");
    }

    private WorkflowState loadFixture(String json) throws IOException {
        Path fixtureFile = Files.createTempFile("run-report-fixture", ".json");
        Files.writeString(fixtureFile, json);
        return WorkflowState.fromJsonString(Files.readString(fixtureFile));
    }

    /**
     * A minimal, hand-built {@code state.json} for a real two-node chain
     * (REQUIREMENT -> DOCUMENT, the same shape {@code workflows/approval-demo.json}
     * declares), with one piece of evidence for AC-1 pointing at {@code artifactPath} if
     * given, or {@code null} evidence entirely if not (for the Mermaid-only test, which
     * does not need any evidence to exist).
     */
    private String twoNodeChainJson(String artifactPath) {
        String evidence = artifactPath == null ? "[]" : """
            [{"origin":"EXECUTED","acceptanceCriterionId":"AC-1","passed":true,
              "description":"a real test ran","source":"./gradlew test","producedByNode":"DOCUMENT",
              "artifactPath":"%s","capturedAt":"2026-01-01T00:00:00Z"}]
            """.formatted(artifactPath);

        return """
            {"runId":"FIXTURE-RUN","startedAt":"2026-01-01T00:00:00Z","workflowStatus":"COMPLETED",
             "rollbackCount":0,"replanCount":0,"sequence":2,
             "requirementSpec":{"id":"REQ-1","revision":1,"rawText":"req","normalizedProblem":"req normalized",
               "acceptanceCriteria":[{"id":"AC-1","description":"It works","riskLevel":"LOW"}]},
             "nodes":[
               {"id":"REQUIREMENT","name":"REQUIREMENT","executor":"requirement","dependsOn":[],
                "entryGate":"dependencies-complete","exitGate":"requirement-complete","riskLevel":"LOW",
                "maxAttempts":2,"producesEvidenceFor":[],"fallbackExecutor":null,"writePaths":[],
                "status":"COMPLETED"},
               {"id":"DOCUMENT","name":"DOCUMENT","executor":"document","dependsOn":["REQUIREMENT"],
                "entryGate":"dependencies-complete","exitGate":"artifact-written","riskLevel":"HIGH",
                "maxAttempts":2,"producesEvidenceFor":[],"fallbackExecutor":null,"writePaths":[],
                "status":"COMPLETED"}
             ],
             "retryCounts":[],
             "auditLog":[
               {"sequence":1,"runId":"FIXTURE-RUN","nodeId":"REQUIREMENT","type":"STATUS_CHANGE",
                "from":"PENDING","to":"COMPLETED","actor":"engine","reason":"fixture event",
                "details":{},"timestamp":"2026-01-01T00:00:01Z"},
               {"sequence":2,"runId":"FIXTURE-RUN","nodeId":"DOCUMENT","type":"STATUS_CHANGE",
                "from":"PENDING","to":"COMPLETED","actor":"engine","reason":"fixture event",
                "details":{},"timestamp":"2026-01-01T00:00:02Z"}
             ],
             "evidence":%s,
             "decisions":[]}
            """.formatted(evidence);
    }
}
