package com.schwab.agentic.engine;

import static com.schwab.agentic.Assertions.assertEquals;
import static com.schwab.agentic.Assertions.assertTrue;

import com.schwab.agentic.graph.WorkflowGraph;
import com.schwab.agentic.model.AuditEvent;
import com.schwab.agentic.model.NodeStatus;
import com.schwab.agentic.model.RiskLevel;
import com.schwab.agentic.model.WorkflowNode;
import com.schwab.agentic.model.WorkflowState;
import com.schwab.agentic.model.WorkflowStatus;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Required test: a node writing outside its declared writePaths is denied by the real
 * engine, with the offending path named in the real audit event, not merely in a
 * {@link PolicyRule.Result} object a unit test constructed by hand.
 * {@link PolicyEngineTest#testWritePathsContractDeniesAWriteOutsideTheNodesDeclaredPaths}
 * proves the rule itself fires; this test proves the whole path from a real executor's
 * reported output through {@link WorkflowEngine}'s post-execution policy check to a real
 * {@link AuditEvent} sitting in {@link WorkflowState#getAuditLog}.
 */
public class WriteOutsidePathsAuditIntegrationTest {

    private static final String POLICY_JSON = """
        {
          "rules": [
            {"name": "critical-risk-requires-approval", "category": "change-control", "enabled": true},
            {"name": "high-risk-requires-approval", "category": "change-control", "enabled": true},
            {"name": "write-paths-contract", "category": "security", "enabled": true}
          ]
        }
        """;

    /** Reports a fixed filesWritten list on execution, standing in for a real executor's output. */
    private static final class ReportsFilesWrittenExecutor implements NodeExecutor {
        private final List<String> filesWritten;

        ReportsFilesWrittenExecutor(List<String> filesWritten) {
            this.filesWritten = filesWritten;
        }

        @Override
        public ExecutionOutput execute(WorkflowNode node, Map<String, Object> context) {
            return new ExecutionOutput(true, "wrote files", Map.of("filesWritten", filesWritten,
                "artifactPath", "irrelevant-for-this-test.txt"));
        }
    }

    public void testANodeWritingOutsideItsDeclaredWritePathsIsDeniedWithTheOffendingPathInTheRealAuditEvent()
        throws IOException {
        Path targetServiceDirectory = Files.createTempDirectory("write-outside-paths-target-service");
        Path runsDirectory = Files.createTempDirectory("write-outside-paths-runs");

        WorkflowNode node = new WorkflowNode("IMPLEMENT", "Implementation", "reports-files-written", Set.of(),
            "dependencies-complete", "artifact-written", RiskLevel.LOW, 1, Set.of(), null, Set.of("src/main"));
        WorkflowGraph graph = WorkflowGraph.of(List.of(node));
        WorkflowState state = new WorkflowState("RUN-1", TestEngineFixtures.requirementSpec(), graph.getAllNodes());

        NodeExecutorRegistry registry = new NodeExecutorRegistry().register("reports-files-written",
            new ReportsFilesWrittenExecutor(List.of("src/other/File.java")));

        RealPolicyEngine policyEngine = new RealPolicyEngine(PolicyConfig.loadFromJson(POLICY_JSON));

        WorkflowEngine engine = new WorkflowEngine(graph, state, registry, new Gates(), policyEngine,
            new Checkpoint(), targetServiceDirectory, runsDirectory, new CommandRunner(), null, null);

        WorkflowStatus outcome = engine.run();

        assertEquals(WorkflowStatus.SAFE_STOPPED, outcome,
            "a real write-paths-contract violation must safe-stop the run, not complete it");
        assertEquals(NodeStatus.ROLLED_BACK, state.getStatus("IMPLEMENT"),
            "the node must be rolled back after the real post-execution policy denial");

        List<AuditEvent> policyDenials = state.getAuditLog().stream()
            .filter(event -> event.type() == AuditEvent.EventType.POLICY_DENIED)
            .toList();
        assertEquals(1, policyDenials.size(), "exactly one real POLICY_DENIED event must exist");

        AuditEvent denial = policyDenials.get(0);
        assertEquals("IMPLEMENT", denial.nodeId(), "the real audit event must be scoped to the offending node");
        assertTrue(denial.reason().contains("src/other/File.java"),
            "the real audit event's reason must name the actual offending path: " + denial.reason());
        assertTrue(denial.reason().contains("src/main"),
            "the real audit event's reason must name the declared writePaths it violated: " + denial.reason());
        assertEquals("write-paths-contract", denial.details().get("rule"),
            "the real audit event's details must name which rule fired");
    }
}
