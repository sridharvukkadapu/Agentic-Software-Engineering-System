package com.schwab.agentic.tools;

import com.schwab.agentic.engine.CommandRunner;
import com.schwab.agentic.engine.Checkpoint;
import com.schwab.agentic.engine.Gates;
import com.schwab.agentic.engine.NodeExecutor;
import com.schwab.agentic.engine.NodeExecutorRegistry;
import com.schwab.agentic.engine.PolicyConfig;
import com.schwab.agentic.engine.RealPolicyEngine;
import com.schwab.agentic.engine.WorkflowEngine;
import com.schwab.agentic.graph.WorkflowGraph;
import com.schwab.agentic.model.AcceptanceCriterion;
import com.schwab.agentic.model.RequirementSpec;
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
 * A standalone, one-time operational tool, not a unit test: drives one real node through
 * a real {@link WorkflowEngine} and {@link RealPolicyEngine}, with an executor that
 * reports a genuine write outside the node's declared {@code writePaths}, so the
 * resulting {@code POLICY_DENIED} audit event and {@code SAFE_STOPPED} run status in
 * {@code runs/POLICY-DENIAL-DEMO/state.json} are produced by the same policy machinery a
 * live agent-backed run would trip, not asserted or narrated. This needs no Anthropic API
 * credit: the node executor here is a deterministic stand-in for the write it reports,
 * exactly the same shortcut {@link com.schwab.agentic.engine.ControllableExecutor} takes
 * in the engine's own unit tests, never the agent layer's job of producing the diff.
 *
 * Kept under src/test, not src/main/java/.../tools alongside {@link FixtureRecorder}, and
 * registered under an executor name no real workflow JSON references, for the same reason
 * {@code ControllableExecutor} stays test-only: a synthetic executor that never calls the
 * agent layer must not be reachable from a real workflow definition, or a workflow author
 * could point a real node at it and silently fake a stage.
 */
public final class PolicyDenialDemoRunner {

    private PolicyDenialDemoRunner() {
    }

    public static void main(String[] args) throws IOException {
        Path repoRoot = findRepoRoot();
        Path targetServiceDirectory = repoRoot.resolve("target-service");
        Path runsDirectory = repoRoot.resolve("runs");
        String runId = "POLICY-DENIAL-DEMO";

        Path runDirectory = runsDirectory.resolve(runId);
        if (Files.exists(runDirectory)) {
            deleteRecursively(runDirectory);
        }

        String allowedRelativePath = "src/main/java/com/example/allowed/Allowed.java";
        Path allowedAbsolutePath = targetServiceDirectory.resolve(allowedRelativePath);
        Files.createDirectories(allowedAbsolutePath.getParent());
        // A real, pre-existing file inside the node's own declared writePaths, so the
        // checkpoint this node takes actually has genuine prior content to restore.
        // Without this, the checkpoint covers only an empty, not-yet-created directory,
        // and rollback (correctly) restores zero files, which reads as suspicious even
        // though it is not: it would be proving the checkpoint scope is narrow, not that
        // rollback is fake. Writing this file first, outside the engine's own run, gives
        // rollback something real to put back.
        Files.writeString(allowedAbsolutePath,
            "package com.example.allowed;\n\npublic final class Allowed {\n    // original content\n}\n");

        WorkflowNode node = new WorkflowNode(
            "IMPLEMENT_DEMO",
            "Implementation (policy denial demonstration)",
            "policy-denial-demo",
            Set.of(),
            "dependencies-complete",
            "artifact-written",
            RiskLevel.HIGH,
            1,
            Set.of(),
            null,
            Set.of("src/main/java/com/example/allowed"));

        WorkflowGraph graph = WorkflowGraph.of(List.of(node));

        RequirementSpec requirementSpec = new RequirementSpec(
            "POLICY-DENIAL-DEMO",
            1,
            "Demonstrate that RealPolicyEngine denies a node reporting a write outside its "
                + "declared writePaths, with no live agent call involved.",
            "Demonstrate policy enforcement of the write-paths contract.",
            List.of(new AcceptanceCriterion(
                "AC-POLICY-DENIAL-1",
                "A node that writes outside its declared writePaths is denied, not completed.",
                RiskLevel.HIGH)));

        WorkflowState state = new WorkflowState(runId, requirementSpec, List.of(node));

        NodeExecutorRegistry registry = new NodeExecutorRegistry();
        registry.register("policy-denial-demo",
            new OutOfContractWriteExecutor(targetServiceDirectory, allowedAbsolutePath));

        PolicyConfig policyConfig = PolicyConfig.loadFromFile(repoRoot.resolve("workflows/policy.json"));
        RealPolicyEngine policyEngine = new RealPolicyEngine(policyConfig);

        WorkflowEngine engine = new WorkflowEngine(graph, state, registry, new Gates(), policyEngine,
            new Checkpoint(), targetServiceDirectory, runsDirectory, new CommandRunner(),
            "true", "true", true, new com.schwab.agentic.engine.ApprovalStore());

        String beforeContent = Files.readString(allowedAbsolutePath);
        WorkflowStatus finalStatus;
        int restoredFileCount;
        String afterRestoreContent;
        try {
            finalStatus = engine.run();
            restoredFileCount = restoredFileCountFrom(state);
            afterRestoreContent = Files.readString(allowedAbsolutePath);
        } finally {
            // The executor's out-of-contract write lands outside anything the node's own
            // checkpoint covers (that is the whole point of the denial: writePaths is a
            // security boundary the checkpoint cannot be relied on to enforce for a path
            // it was never told to watch), so rollback never touches it; clean it up here
            // so this demo leaves target-service exactly as it found it. The in-contract
            // file is left in place deliberately: its restored, original content is the
            // whole point of this demo, and it is deleted by name below, not swept up by
            // a directory-wide cleanup, so a bug that widened the cleanup could never
            // silently erase the very evidence this run exists to produce.
            Path forbiddenDirectory = targetServiceDirectory.resolve("src/main/java/com/example/forbidden");
            deleteRecursively(forbiddenDirectory);
            Files.deleteIfExists(allowedAbsolutePath);
            Path exampleDirectory = targetServiceDirectory.resolve("src/main/java/com/example");
            Path allowedDirectory = allowedAbsolutePath.getParent();
            if (Files.isDirectory(allowedDirectory) && isEmptyDirectory(allowedDirectory)) {
                Files.delete(allowedDirectory);
            }
            if (Files.isDirectory(exampleDirectory) && isEmptyDirectory(exampleDirectory)) {
                deleteRecursively(exampleDirectory);
            }
        }

        System.out.println("Final workflow status: " + finalStatus);
        System.out.println("Node status: " + state.getStatuses().get("IMPLEMENT_DEMO"));
        System.out.println("Allowed file content before this run's attempt: " + beforeContent.strip());
        System.out.println("Allowed file content the executor wrote during its attempt (now overwritten by rollback): "
            + "package com.example.allowed;\n\npublic final class Allowed {\n    // MODIFIED by a policy-denied attempt\n}");
        System.out.println("Allowed file content after rollback restored it: " + afterRestoreContent.strip());
        System.out.println("Rollback restored " + restoredFileCount + " file(s) (verified by content hash, not just a status label)");
        System.out.println("Audit log:");
        for (var event : state.getAuditLog()) {
            System.out.println("  " + event.type() + " node=" + event.nodeId()
                + " from=" + event.from() + " to=" + event.to() + " reason=" + event.reason());
        }
        System.out.println("Run persisted at: " + runDirectory.resolve("state.json"));
    }

    private static int restoredFileCountFrom(WorkflowState state) {
        return state.getAuditLog().stream()
            .filter(event -> event.type() == com.schwab.agentic.model.AuditEvent.EventType.ARTIFACT_WRITTEN)
            .filter(event -> event.details().containsKey("restoredFileCount"))
            .mapToInt(event -> ((Number) event.details().get("restoredFileCount")).intValue())
            .max()
            .orElse(0);
    }

    private static boolean isEmptyDirectory(Path directory) throws IOException {
        try (var stream = Files.list(directory)) {
            return stream.findAny().isEmpty();
        }
    }

    /**
     * Modifies the real, pre-existing in-contract file (so rollback has genuine prior
     * content to restore and prove with a non-zero count), then really writes a second
     * file under {@code src/main/java/com/example/forbidden}, deliberately outside the
     * node's declared {@code writePaths} of {@code src/main/java/com/example/allowed},
     * and reports both writes, so {@link RealPolicyEngine#evaluateWritePathsContract} has
     * a genuine mismatch to deny and {@link WorkflowEngine}'s rollback has a real,
     * modified file to actually restore, per CLAUDE.md's rule that rollback must actually
     * restore, not just claim to.
     */
    private static final class OutOfContractWriteExecutor implements NodeExecutor {
        private final Path targetServiceDirectory;
        private final Path allowedAbsolutePath;

        OutOfContractWriteExecutor(Path targetServiceDirectory, Path allowedAbsolutePath) {
            this.targetServiceDirectory = targetServiceDirectory;
            this.allowedAbsolutePath = allowedAbsolutePath;
        }

        @Override
        public ExecutionOutput execute(WorkflowNode node, Map<String, Object> context) {
            String forbiddenRelativePath = "src/main/java/com/example/forbidden/Sneaky.java";
            Path forbiddenAbsolutePath = targetServiceDirectory.resolve(forbiddenRelativePath);
            try {
                Files.writeString(allowedAbsolutePath,
                    "package com.example.allowed;\n\npublic final class Allowed {\n"
                        + "    // MODIFIED by a policy-denied attempt\n}\n");
                Files.createDirectories(forbiddenAbsolutePath.getParent());
                Files.writeString(forbiddenAbsolutePath,
                    "package com.example.forbidden;\n\npublic final class Sneaky {\n}\n");
            } catch (IOException e) {
                throw new java.io.UncheckedIOException(e);
            }
            return new ExecutionOutput(true,
                "modified a file inside its declared writePaths and wrote a second file outside them,"
                    + " to demonstrate write-paths-contract",
                Map.of(
                    "artifactPath", "runs/POLICY-DENIAL-DEMO/artifacts/implementation-note.txt",
                    "filesWritten", List.of("src/main/java/com/example/allowed/Allowed.java", forbiddenRelativePath)));
        }
    }

    private static Path findRepoRoot() {
        Path current = Path.of("").toAbsolutePath();
        while (current != null) {
            if (current.resolve("scenarios").toFile().isDirectory()
                && current.resolve("orchestrator").toFile().isDirectory()) {
                return current;
            }
            current = current.getParent();
        }
        throw new IllegalStateException("Could not find repo root by walking up from " + Path.of("").toAbsolutePath());
    }

    private static void deleteRecursively(Path path) throws IOException {
        if (!Files.exists(path)) {
            return;
        }
        try (var stream = Files.walk(path)) {
            stream.sorted((a, b) -> b.getNameCount() - a.getNameCount())
                .forEach(p -> {
                    try {
                        Files.delete(p);
                    } catch (IOException e) {
                        throw new java.io.UncheckedIOException(e);
                    }
                });
        }
    }
}
