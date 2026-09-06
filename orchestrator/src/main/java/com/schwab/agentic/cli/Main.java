package com.schwab.agentic.cli;

import com.schwab.agentic.agent.AgentClient;
import com.schwab.agentic.agent.AgentClientFactory;
import com.schwab.agentic.engine.ApprovalStore;
import com.schwab.agentic.engine.CommandRunner;
import com.schwab.agentic.engine.Checkpoint;
import com.schwab.agentic.engine.Gates;
import com.schwab.agentic.engine.NodeExecutorRegistry;
import com.schwab.agentic.engine.PolicyConfig;
import com.schwab.agentic.engine.RealPolicyEngine;
import com.schwab.agentic.engine.Replanner;
import com.schwab.agentic.engine.WorkflowEngine;
import com.schwab.agentic.executor.DocumentExecutor;
import com.schwab.agentic.executor.RequirementExecutor;
import com.schwab.agentic.graph.WorkflowGraph;
import com.schwab.agentic.json.Json;
import com.schwab.agentic.model.AcceptanceCriterion;
import com.schwab.agentic.model.RequirementSpec;
import com.schwab.agentic.model.RiskLevel;
import com.schwab.agentic.model.WorkflowState;
import com.schwab.agentic.model.WorkflowStatus;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The orchestrator's command-line entry point: {@code run}, {@code resume}, and
 * {@code approve} are real, wired to the real executor registry and a real
 * {@link WorkflowEngine}. {@code amend} (spec 06's re-planning) and {@code report}
 * (spec 08's reporting) are declared here as named subcommands so the CLI surface spec 05
 * describes exists, but each prints that it is not yet implemented rather than faking a
 * result, since building either out for real belongs to its own later spec.
 *
 * This class deliberately runs against a small demo workflow
 * ({@code workflows/approval-demo.json}), not the full eight-node
 * {@code sdlc-default.json}: the full pipeline needs each stage's real output threaded
 * into the next stage's context (a real design spec into ImplementExecutor, a real diff
 * into DocumentExecutor), which today only happens by hand in the fixture-recording tool,
 * not inside {@link WorkflowEngine} itself. Building that cross-node context-threading
 * layer is a materially different, larger piece of work than spec 05 asks for; this CLI
 * proves the real thing spec 05 is actually about, persistence and resume across a real
 * process boundary, against two real executors (RequirementExecutor and DocumentExecutor,
 * both spec 04's real implementations, not a stand-in), rather than against the full
 * pipeline before the engine has anywhere to put a later stage's real input.
 */
public final class Main {

    private Main() {
    }

    public static void main(String[] args) {
        if (args.length == 0) {
            printUsageAndExit();
        }

        String command = args[0];
        try {
            switch (command) {
                case "run" -> runCommand(args);
                case "resume" -> resumeCommand(args);
                case "approve" -> approveCommand(args);
                case "amend" -> amendCommand(args);
                case "report" -> System.out.println("report is spec 08's reporting; not yet implemented.");
                default -> {
                    System.err.println("Unknown command: " + command);
                    printUsageAndExit();
                }
            }
        } catch (IllegalArgumentException | IllegalStateException e) {
            System.err.println("error: " + e.getMessage());
            System.exit(1);
        }
    }

    private static void printUsageAndExit() {
        System.err.println("""
            Usage:
              run --workflow <path> --requirement <path> [--live | --replay] [--auto-approve] [--fixtures <dir>] [--runs <dir>] [--run-id <id>]
              resume --run-id <id> [--workflow <path>] [--live | --replay] [--fixtures <dir>] [--runs <dir>]
              approve --run-id <id> <nodeId> --by "<name>" --reason "<text>" [--runs <dir>]
              amend --run-id <id> --requirement <file> [--workflow <path>] [--target-service <path>] [--runs <dir>] [--fixtures <dir>]
              report: not yet implemented (spec 08)
            """);
        System.exit(2);
    }

    // ---- run ----

    private static void runCommand(String[] args) {
        CliArgs cliArgs = CliArgs.parse(args);
        Path workflowPath = cliArgs.requirePath("--workflow");
        Path requirementPath = cliArgs.requirePath("--requirement");
        Path runsDirectory = cliArgs.pathOrDefault("--runs", Path.of("runs"));
        Path fixturesDirectory = cliArgs.pathOrDefault("--fixtures", Path.of("fixtures"));
        boolean live = cliArgs.hasFlag("--live");
        boolean autoApprove = cliArgs.hasFlag("--auto-approve");
        String runId = cliArgs.valueOrDefault("--run-id", "RUN-" + System.currentTimeMillis());

        if (autoApprove && live) {
            throw new IllegalArgumentException(
                "--auto-approve is only permitted with --replay, per spec 05. A live run always requires real approval.");
        }

        WorkflowGraph graph = WorkflowGraph.loadFromFile(workflowPath);
        RequirementSpec placeholderSpec = new RequirementSpec(
            "REQ-" + runId, 1, "placeholder pending RequirementExecutor", "placeholder",
            List.<AcceptanceCriterion>of());
        WorkflowState state = new WorkflowState(runId, placeholderSpec, graph.getAllNodes());

        WorkflowEngine engine = buildEngine(graph, state, runsDirectory, fixturesDirectory, live, autoApprove,
            new ApprovalStore());
        engine.withInitialContext("REQUIREMENT", Map.of("requirementPath", requirementPath.toString()));
        seedDocumentContextFromRealRequirementArtifact(engine, runsDirectory, runId);

        System.out.println("Starting run " + runId + " (" + (live ? "LIVE" : "REPLAY")
            + (autoApprove ? ", auto-approve" : "") + ")");
        WorkflowStatus outcome = engine.run();
        reportOutcome(runId, outcome, state);
    }

    /**
     * DOCUMENT has no upstream design or implementation stage in this small demo
     * workflow (see this class's own javadoc on why). Seeded lazily: the supplier is
     * only called at the moment DOCUMENT's own first attempt actually starts, by which
     * point REQUIREMENT (its declared dependency) has genuinely completed and written
     * its real requirement-spec.json, giving DocumentExecutor real content to document
     * instead of an empty context a real model correctly refuses to fabricate
     * documentation from (found by running this CLI live: the real model's response to
     * an empty context was to ask for the missing design spec and diff, exactly as it
     * should).
     */
    private static void seedDocumentContextFromRealRequirementArtifact(WorkflowEngine engine, Path runsDirectory,
                                                                        String runId) {
        Path requirementSpecPath = runsDirectory.resolve(runId).resolve("artifacts").resolve("requirement-spec.json");
        engine.withInitialContext("DOCUMENT", () -> {
            if (!Files.isRegularFile(requirementSpecPath)) {
                return Map.of();
            }
            try {
                return Map.of("designSpec", Files.readString(requirementSpecPath));
            } catch (IOException e) {
                throw new IllegalStateException("Failed to read " + requirementSpecPath, e);
            }
        });
    }

    // ---- resume ----

    private static void resumeCommand(String[] args) {
        CliArgs cliArgs = CliArgs.parse(args);
        String runId = cliArgs.requireValue("--run-id");
        Path runsDirectory = cliArgs.pathOrDefault("--runs", Path.of("runs"));
        Path fixturesDirectory = cliArgs.pathOrDefault("--fixtures", Path.of("fixtures"));
        boolean live = cliArgs.hasFlag("--live");
        Path workflowPath = cliArgs.pathOrDefault("--workflow", Path.of("workflows/approval-demo.json"));

        Path statePath = runsDirectory.resolve(runId).resolve("state.json");
        if (!Files.isRegularFile(statePath)) {
            throw new IllegalArgumentException("No state.json found for run " + runId + " at " + statePath);
        }
        String stateJson;
        try {
            stateJson = Files.readString(statePath);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read " + statePath, e);
        }
        WorkflowState state = WorkflowState.fromJsonString(stateJson);
        WorkflowStatus statusBeforeResume = state.getWorkflowStatus();

        WorkflowGraph graph = WorkflowGraph.loadFromFile(workflowPath);
        ApprovalStore approvalStore = ApprovalStore.loadFromFile(runsDirectory, runId);

        java.time.Instant pauseStartedAt = lastEventTimestamp(state);
        java.time.Duration pauseDuration = java.time.Duration.between(pauseStartedAt, java.time.Instant.now());
        state.record(com.schwab.agentic.model.AuditEvent.EventType.RUN_RESUMED, "system",
            "run resumed after " + statusBeforeResume + ", pause duration " + pauseDuration,
            Map.of("previousStatus", statusBeforeResume.name(), "pauseDurationMillis", (double) pauseDuration.toMillis()));

        WorkflowEngine engine = buildEngine(graph, state, runsDirectory, fixturesDirectory, live, false, approvalStore);
        seedDocumentContextFromRealRequirementArtifact(engine, runsDirectory, runId);

        System.out.println("Resuming run " + runId + " (was " + statusBeforeResume + ")");
        WorkflowStatus outcome = engine.run();
        reportOutcome(runId, outcome, state);
    }

    private static java.time.Instant lastEventTimestamp(WorkflowState state) {
        List<com.schwab.agentic.model.AuditEvent> auditLog = state.getAuditLog();
        if (auditLog.isEmpty()) {
            return state.getStartedAt();
        }
        return auditLog.get(auditLog.size() - 1).timestamp();
    }

    // ---- approve ----

    private static void approveCommand(String[] args) {
        CliArgs cliArgs = CliArgs.parse(args);
        String runId = cliArgs.requireValue("--run-id");
        String nodeId = cliArgs.requirePositional();
        String approver = cliArgs.requireValue("--by");
        String reason = cliArgs.requireValue("--reason");
        Path runsDirectory = cliArgs.pathOrDefault("--runs", Path.of("runs"));
        Path workflowPath = cliArgs.pathOrDefault("--workflow", Path.of("workflows/approval-demo.json"));

        Path statePath = runsDirectory.resolve(runId).resolve("state.json");
        if (!Files.isRegularFile(statePath)) {
            throw new IllegalArgumentException("No state.json found for run " + runId + " at " + statePath);
        }
        WorkflowState state;
        try {
            state = WorkflowState.fromJsonString(Files.readString(statePath));
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read " + statePath, e);
        }

        WorkflowGraph graph = WorkflowGraph.loadFromFile(workflowPath);
        ApprovalStore approvalStore = ApprovalStore.loadFromFile(runsDirectory, runId);
        WorkflowEngine engine = buildEngine(graph, state, runsDirectory, Path.of("fixtures"), false, false, approvalStore);

        engine.approve(nodeId, approver, reason);
        System.out.println("Approved " + nodeId + " for run " + runId + " by " + approver);
    }

    // ---- amend ----

    /**
     * Amends a paused or completed run's requirement from a fresh file, re-plans from
     * REQUIREMENT (the amended node), and persists the result: {@code state.json} reflects
     * every invalidated node back at PENDING, {@code approvals.json} is unchanged on disk
     * (spec 05's own revision-keyed {@code hasValidApproval} check is what makes a prior
     * approval stop counting, not a rewrite of the approval file), and the run is left
     * ready for {@code resume} to re-execute exactly the nodes the re-plan invalidated.
     * REQUIREMENT is always the changed node for this entry point, since amending the
     * requirement is definitionally a change to what REQUIREMENT itself produced; a
     * scenario that declares {@code amendAfterNode} for a different node is spec 06's
     * other entry point (a mid-run amendment during a single demo command), which this
     * CLI subcommand does not need to serve.
     */
    private static void amendCommand(String[] args) {
        CliArgs cliArgs = CliArgs.parse(args);
        String runId = cliArgs.requireValue("--run-id");
        Path amendedRequirementPath = cliArgs.requirePath("--requirement");
        Path runsDirectory = cliArgs.pathOrDefault("--runs", Path.of("runs"));
        Path fixturesDirectory = cliArgs.pathOrDefault("--fixtures", Path.of("fixtures"));
        Path workflowPath = cliArgs.pathOrDefault("--workflow", Path.of("workflows/approval-demo.json"));
        Path targetServiceDirectory = cliArgs.pathOrDefault("--target-service", null);
        boolean live = cliArgs.hasFlag("--live");

        Path statePath = runsDirectory.resolve(runId).resolve("state.json");
        if (!Files.isRegularFile(statePath)) {
            throw new IllegalArgumentException("No state.json found for run " + runId + " at " + statePath);
        }
        WorkflowState state;
        try {
            state = WorkflowState.fromJsonString(Files.readString(statePath));
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read " + statePath, e);
        }

        WorkflowGraph graph = WorkflowGraph.loadFromFile(workflowPath);

        Path amendArtifactsDirectory = runsDirectory.resolve(runId).resolve("artifacts");
        AgentClient requirementClient = agentClientFor(live, fixturesDirectory.resolve("cli").resolve("requirement"),
            state);
        RequirementExecutor requirementExecutor = new RequirementExecutor(requirementClient, amendArtifactsDirectory);
        var reparsed = requirementExecutor.execute(graph.getNode("REQUIREMENT"),
            Map.of("requirementPath", amendedRequirementPath.toString()));
        if (!reparsed.executorReportedSuccess()) {
            throw new IllegalStateException("Amended requirement could not be parsed: " + reparsed.summary());
        }

        RequirementSpec amended = buildAmendedRequirementSpec(state.getRequirementSpec(), amendArtifactsDirectory);

        Replanner replanner = new Replanner(graph, new Checkpoint(), targetServiceDirectory, runsDirectory);
        java.util.Set<String> invalidated = replanner.replan(state, "REQUIREMENT", amended);

        ApprovalStore approvalStore = ApprovalStore.loadFromFile(runsDirectory, runId);
        state.setWorkflowStatus(WorkflowStatus.RUNNING);
        Path outStatePath = runsDirectory.resolve(runId).resolve("state.json");
        try {
            Files.createDirectories(outStatePath.getParent());
            Files.writeString(outStatePath, state.toJsonString());
        } catch (IOException e) {
            throw new java.io.UncheckedIOException("Failed to write " + outStatePath, e);
        }
        approvalStore.saveToFile(runsDirectory, runId);

        System.out.println("Amended run " + runId + " to requirement revision " + amended.revision());
        System.out.println("Invalidated " + invalidated.size() + " node(s): "
            + invalidated.stream().sorted().toList());
        System.out.println("Run './scripts/resume.sh " + runId + "' to re-execute the invalidated nodes.");
    }

    /**
     * Rebuilds a {@link RequirementSpec} at the next revision from the requirement-spec.json
     * {@link RequirementExecutor} just wrote for the amended text, reusing
     * {@link RequirementSpec#withNextRevision} so the revision counter is always exactly
     * one greater than whatever the run was at before, never a value re-typed by hand.
     */
    @SuppressWarnings("unchecked")
    private static RequirementSpec buildAmendedRequirementSpec(RequirementSpec previous, Path artifactsDirectory) {
        Path requirementSpecPath = artifactsDirectory.resolve("requirement-spec.json");
        Map<String, Object> parsed;
        try {
            parsed = (Map<String, Object>) Json.parse(Files.readString(requirementSpecPath));
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read " + requirementSpecPath, e);
        }
        List<AcceptanceCriterion> criteria = new ArrayList<>();
        for (Object criterionObj : (List<Object>) parsed.get("acceptanceCriteria")) {
            Map<String, Object> criterionJson = (Map<String, Object>) criterionObj;
            criteria.add(new AcceptanceCriterion(
                (String) criterionJson.get("id"),
                (String) criterionJson.get("description"),
                RiskLevel.valueOf((String) criterionJson.get("riskLevel"))));
        }
        return previous.withNextRevision((String) parsed.get("rawText"), (String) parsed.get("normalizedProblem"),
            criteria);
    }

    // ---- shared wiring ----

    private static WorkflowEngine buildEngine(WorkflowGraph graph, WorkflowState state, Path runsDirectory,
                                               Path fixturesDirectory, boolean live, boolean autoApprove,
                                               ApprovalStore approvalStore) {
        NodeExecutorRegistry registry = new NodeExecutorRegistry();
        Path artifactsDirectory = runsDirectory.resolve(state.getRunId()).resolve("artifacts");

        AgentClient requirementClient = agentClientFor(live, fixturesDirectory.resolve("cli").resolve("requirement"), state);
        registry.register("requirement", new RequirementExecutor(requirementClient, artifactsDirectory));

        AgentClient documentClient = agentClientFor(live, fixturesDirectory.resolve("cli").resolve("document"), state);
        registry.register("document", new DocumentExecutor(documentClient, artifactsDirectory));

        PolicyConfig policyConfig = PolicyConfig.loadFromFile(Path.of("workflows/policy.json"));
        RealPolicyEngine policyEngine = new RealPolicyEngine(policyConfig);

        return new WorkflowEngine(graph, state, registry, new Gates(), policyEngine, new Checkpoint(),
            null, runsDirectory, new CommandRunner(), null, null, autoApprove, approvalStore);
    }

    private static AgentClient agentClientFor(boolean live, Path fixturesDirectory, WorkflowState state) {
        if (live) {
            String apiKey = System.getenv("ANTHROPIC_API_KEY");
            return AgentClientFactory.createLive(apiKey, fixturesDirectory, state);
        }
        return AgentClientFactory.createReplay(fixturesDirectory, state);
    }

    private static void reportOutcome(String runId, WorkflowStatus outcome, WorkflowState state) {
        System.out.println("Run " + runId + " ended: " + outcome);
        for (String nodeId : state.getNodes().keySet().stream().sorted().toList()) {
            System.out.println("  " + nodeId + ": " + state.getStatus(nodeId));
        }
    }
}
