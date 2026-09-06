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
import com.schwab.agentic.executor.DesignExecutor;
import com.schwab.agentic.executor.DocumentExecutor;
import com.schwab.agentic.executor.ImpactExecutor;
import com.schwab.agentic.executor.ImplementExecutor;
import com.schwab.agentic.executor.ImplementationSourceReader;
import com.schwab.agentic.executor.ReleaseExecutor;
import com.schwab.agentic.executor.RequirementExecutor;
import com.schwab.agentic.executor.TestExecutor;
import com.schwab.agentic.executor.ValidateExecutor;
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
 * The orchestrator's command-line entry point: {@code run}, {@code resume},
 * {@code approve}, and {@code amend} are real, wired to the real executor registry (all
 * eight spec-04 executors, not a subset) and a real {@link WorkflowEngine}. {@code report}
 * (spec 08's reporting) is declared as a named subcommand so the CLI surface exists, but
 * prints that it is not yet implemented rather than faking a result.
 *
 * {@code buildEngine} registers every node id {@code sdlc-default.json} declares:
 * requirement, impact, design, implement, test, document, validate, release. A workflow
 * naming an executor this registry never registered fails at
 * {@link WorkflowEngine}'s own startup validation, before any node runs, naming the
 * offending node and executor.
 *
 * Cross-node context threading (D6's long-standing open item) is real here: each of
 * IMPACT, DESIGN, TEST, and DOCUMENT reads its upstream input from the real artifact file
 * its dependency actually wrote under {@code runs/<runId>/artifacts/}, via
 * {@link WorkflowEngine#withInitialContext}'s lazy supplier, which is only invoked at the
 * moment that node's own first attempt starts (by which point the dependency has
 * genuinely completed). Disk is the contract: no executor output is ever handed to
 * another executor by direct in-memory reference. VALIDATE and RELEASE read the run's
 * live evidence, requirement, and audit log directly off the {@link WorkflowState}
 * instance the engine itself holds, since both need to see facts (TEST's real evidence,
 * VALIDATE's real outcome) that do not exist yet when the registry is built, before any
 * node has executed.
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
              run --workflow <path> --requirement <path> [--live | --replay] [--auto-approve] [--fixtures <dir>] [--runs <dir>] [--run-id <id>] [--target-service <path>] [--build-command <cmd>] [--test-command <cmd>]
              resume --run-id <id> [--workflow <path>] [--live | --replay] [--fixtures <dir>] [--runs <dir>] [--target-service <path>] [--build-command <cmd>] [--test-command <cmd>]
              approve --run-id <id> <nodeId> --by "<name>" --reason "<text>" [--runs <dir>]
              amend --run-id <id> --requirement <file> [--workflow <path>] [--target-service <path>] [--runs <dir>] [--fixtures <dir>]
              report: not yet implemented (spec 08)
            """);
        System.exit(2);
    }

    private static final String DEFAULT_WORKFLOW = "workflows/sdlc-default.json";
    private static final String DEFAULT_BUILD_COMMAND = "./gradlew compileJava";
    private static final String DEFAULT_TEST_COMMAND = "./gradlew test";

    // ---- run ----

    private static void runCommand(String[] args) {
        CliArgs cliArgs = CliArgs.parse(args);
        Path workflowPath = cliArgs.pathOrDefault("--workflow", Path.of(DEFAULT_WORKFLOW));
        Path requirementPath = cliArgs.requirePath("--requirement");
        Path runsDirectory = cliArgs.pathOrDefault("--runs", Path.of("runs"));
        Path fixturesDirectory = cliArgs.pathOrDefault("--fixtures", Path.of("fixtures"));
        Path targetServiceDirectory = cliArgs.pathOrDefault("--target-service", Path.of("target-service"));
        String buildCommand = cliArgs.valueOrDefault("--build-command", DEFAULT_BUILD_COMMAND);
        String testCommand = cliArgs.valueOrDefault("--test-command", DEFAULT_TEST_COMMAND);
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

        WorkflowEngine engine = buildEngine(graph, state, runsDirectory, fixturesDirectory, targetServiceDirectory,
            buildCommand, testCommand, live, autoApprove, new ApprovalStore());
        engine.withInitialContext("REQUIREMENT", Map.of("requirementPath", requirementPath.toString()));
        seedCrossNodeContext(engine, runsDirectory, runId, targetServiceDirectory);

        System.out.println("Starting run " + runId + " (" + (live ? "LIVE" : "REPLAY")
            + (autoApprove ? ", auto-approve" : "") + ")");
        WorkflowStatus outcome = engine.run();
        reportOutcome(runId, outcome, state);
    }

    /**
     * Wires every real dependency-to-dependent context key threading spec 04's own
     * executors read from {@code context}, each a lazy supplier read from the real
     * artifact its upstream dependency wrote to {@code runs/<runId>/artifacts/}. Called
     * for both {@code run} and {@code resume}: a resumed run re-enters the same
     * scheduling loop, so a node whose dependency has not run yet in this process still
     * needs its supplier registered before the engine's first pass.
     */
    private static void seedCrossNodeContext(WorkflowEngine engine, Path runsDirectory, String runId,
                                              Path targetServiceDirectory) {
        Path artifactsDirectory = runsDirectory.resolve(runId).resolve("artifacts");

        engine.withInitialContext("IMPACT", () -> {
            String normalizedProblem = readJsonField(artifactsDirectory.resolve("requirement-spec.json"),
                "normalizedProblem");
            return normalizedProblem == null ? Map.of() : Map.of("normalizedProblem", normalizedProblem);
        });

        engine.withInitialContext("DESIGN", () -> {
            Map<String, Object> context = new java.util.LinkedHashMap<>();
            String normalizedProblem = readJsonField(artifactsDirectory.resolve("requirement-spec.json"),
                "normalizedProblem");
            if (normalizedProblem != null) {
                context.put("normalizedProblem", normalizedProblem);
            }
            Path impactMarkdownPath = artifactsDirectory.resolve("impact-analysis.md");
            if (Files.isRegularFile(impactMarkdownPath)) {
                context.put("impactSummary", readFileQuietly(impactMarkdownPath));
            }
            return context;
        });

        engine.withInitialContext("IMPLEMENT", () -> {
            Map<String, Object> context = new java.util.LinkedHashMap<>();
            Path designSpecPath = artifactsDirectory.resolve("design-spec.json");
            if (Files.isRegularFile(designSpecPath)) {
                context.put("designSpec", readFileQuietly(designSpecPath));
            }
            String existingCodeContext = readAffectedFilesContent(
                artifactsDirectory.resolve("impact.json"), targetServiceDirectory);
            if (existingCodeContext != null) {
                context.put("existingCodeContext", existingCodeContext);
            }
            return context;
        });

        engine.withInitialContext("TEST", () -> {
            Map<String, Object> context = new java.util.LinkedHashMap<>();
            Path designSpecPath = artifactsDirectory.resolve("design-spec.json");
            if (Files.isRegularFile(designSpecPath)) {
                context.put("designSpec", readFileQuietly(designSpecPath));
            }
            Path implementationDiffPath = artifactsDirectory.resolve("implementation.diff");
            context.put("implementationSource",
                ImplementationSourceReader.readFromDiff(targetServiceDirectory, implementationDiffPath));
            List<Object> criteria = readAcceptanceCriteriaAsContextList(
                artifactsDirectory.resolve("requirement-spec.json"));
            if (!criteria.isEmpty()) {
                context.put("acceptanceCriteria", criteria);
            }
            return context;
        });

        engine.withInitialContext("DOCUMENT", () -> {
            Map<String, Object> context = new java.util.LinkedHashMap<>();
            Path designSpecPath = artifactsDirectory.resolve("design-spec.json");
            Path requirementSpecPath = artifactsDirectory.resolve("requirement-spec.json");
            if (Files.isRegularFile(designSpecPath)) {
                context.put("designSpec", readFileQuietly(designSpecPath));
            } else if (Files.isRegularFile(requirementSpecPath)) {
                // The two-node approval-demo.json workflow has no DESIGN node at all:
                // DOCUMENT depends directly on REQUIREMENT, so there is no real
                // design-spec.json for it to read. Falling back to REQUIREMENT's own
                // real requirement-spec.json is what lets DocumentExecutor document
                // something real rather than an empty context a real model correctly
                // refuses to fabricate documentation from (found by running this CLI
                // live against approval-demo.json during spec 05).
                context.put("designSpec", readFileQuietly(requirementSpecPath));
            }
            Path implementationDiffPath = artifactsDirectory.resolve("implementation.diff");
            if (Files.isRegularFile(implementationDiffPath)) {
                context.put("implementationDiff", readFileQuietly(implementationDiffPath));
            }
            return context;
        });
    }

    @SuppressWarnings("unchecked")
    private static String readJsonField(Path jsonPath, String field) {
        if (!Files.isRegularFile(jsonPath)) {
            return null;
        }
        Map<String, Object> parsed = (Map<String, Object>) Json.parse(readFileQuietly(jsonPath));
        Object value = parsed.get(field);
        return value == null ? null : String.valueOf(value);
    }

    @SuppressWarnings("unchecked")
    private static List<Object> readAcceptanceCriteriaAsContextList(Path requirementSpecPath) {
        if (!Files.isRegularFile(requirementSpecPath)) {
            return List.of();
        }
        Map<String, Object> parsed = (Map<String, Object>) Json.parse(readFileQuietly(requirementSpecPath));
        Object criteria = parsed.get("acceptanceCriteria");
        return criteria instanceof List<?> list ? new ArrayList<>(list) : List.of();
    }

    /**
     * The real content of every file {@code impact.json} named under {@code affectedFiles}
     * (existing target-service classes the impact analysis found this change depends on),
     * so IMPLEMENT can be told the real package names, class names, and method signatures
     * of code it must reference, rather than guessing at them the way an implementation
     * that only ever sees an abstract design spec is forced to.
     */
    @SuppressWarnings("unchecked")
    private static String readAffectedFilesContent(Path impactJsonPath, Path targetServiceDirectory) {
        if (!Files.isRegularFile(impactJsonPath)) {
            return null;
        }
        Map<String, Object> parsed = (Map<String, Object>) Json.parse(readFileQuietly(impactJsonPath));
        Object affectedFiles = parsed.get("affectedFiles");
        if (!(affectedFiles instanceof List<?> list) || list.isEmpty()) {
            return null;
        }
        StringBuilder combined = new StringBuilder();
        for (Object item : list) {
            String relativePath = String.valueOf(item);
            Path absolute = targetServiceDirectory.resolve(relativePath);
            if (!Files.isRegularFile(absolute)) {
                continue;
            }
            combined.append("// FILE: ").append(relativePath).append('\n');
            combined.append(readFileQuietly(absolute)).append("\n\n");
        }
        return combined.length() == 0 ? null : combined.toString();
    }

    private static String readFileQuietly(Path path) {
        try {
            return Files.readString(path);
        } catch (IOException e) {
            throw new java.io.UncheckedIOException("Failed to read " + path, e);
        }
    }

    // ---- resume ----

    private static void resumeCommand(String[] args) {
        CliArgs cliArgs = CliArgs.parse(args);
        String runId = cliArgs.requireValue("--run-id");
        Path runsDirectory = cliArgs.pathOrDefault("--runs", Path.of("runs"));
        Path fixturesDirectory = cliArgs.pathOrDefault("--fixtures", Path.of("fixtures"));
        Path targetServiceDirectory = cliArgs.pathOrDefault("--target-service", Path.of("target-service"));
        String buildCommand = cliArgs.valueOrDefault("--build-command", DEFAULT_BUILD_COMMAND);
        String testCommand = cliArgs.valueOrDefault("--test-command", DEFAULT_TEST_COMMAND);
        boolean live = cliArgs.hasFlag("--live");
        Path workflowPath = cliArgs.pathOrDefault("--workflow", Path.of(DEFAULT_WORKFLOW));

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

        WorkflowEngine engine = buildEngine(graph, state, runsDirectory, fixturesDirectory, targetServiceDirectory,
            buildCommand, testCommand, live, false, approvalStore);
        seedCrossNodeContext(engine, runsDirectory, runId, targetServiceDirectory);

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
        Path workflowPath = cliArgs.pathOrDefault("--workflow", Path.of(DEFAULT_WORKFLOW));

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
        WorkflowEngine engine = buildEngine(graph, state, runsDirectory, Path.of("fixtures"),
            Path.of("target-service"), DEFAULT_BUILD_COMMAND, DEFAULT_TEST_COMMAND, false, false, approvalStore);

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
        Path workflowPath = cliArgs.pathOrDefault("--workflow", Path.of(DEFAULT_WORKFLOW));
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

    /**
     * Registers all eight spec-04 executors under the executor names
     * {@code sdlc-default.json} declares, so this registry can run the real, full SDLC
     * pipeline, not only the two-node demo workflow spec 05 introduced. VALIDATE and
     * RELEASE are given the live {@code state} instance itself rather than pre-computed
     * arguments, since both are registered here, before any node has run, and each needs
     * to read facts (evidence, the VALIDATE node's real outcome) that only exist once
     * earlier nodes have actually executed.
     */
    private static WorkflowEngine buildEngine(WorkflowGraph graph, WorkflowState state, Path runsDirectory,
                                               Path fixturesDirectory, Path targetServiceDirectory,
                                               String buildCommand, String testCommand, boolean live,
                                               boolean autoApprove, ApprovalStore approvalStore) {
        NodeExecutorRegistry registry = new NodeExecutorRegistry();
        Path artifactsDirectory = runsDirectory.resolve(state.getRunId()).resolve("artifacts");
        String runId = state.getRunId();

        // cli/requirement and greenfield/requirement were both recorded live against the
        // exact same real request (same requirement.md text, same maxTokens), so they
        // share a request hash, but a real model's output is not deterministic across
        // two separate live calls: their real recorded responses differ. cli/requirement
        // is used here since it is the one MainCliResumeTest (approval-demo.json's own
        // test) depends on; it is equally valid content for the same real request.
        AgentClient requirementClient = agentClientFor(live, fixturesDirectory.resolve("cli").resolve("requirement"), state);
        registry.register("requirement", new RequirementExecutor(requirementClient, artifactsDirectory));

        AgentClient impactClient = agentClientFor(live, fixturesDirectory.resolve("greenfield").resolve("impact"), state);
        registry.register("impact", new ImpactExecutor(impactClient, artifactsDirectory, targetServiceDirectory));

        AgentClient designClient = agentClientFor(live, fixturesDirectory.resolve("greenfield").resolve("design"), state);
        registry.register("design", new DesignExecutor(designClient, artifactsDirectory, state::addDecision));

        AgentClient implementClient = agentClientFor(live, fixturesDirectory.resolve("greenfield").resolve("implement"), state);
        registry.register("implement", new ImplementExecutor(implementClient, targetServiceDirectory, artifactsDirectory));

        AgentClient testClient = agentClientFor(live, fixturesDirectory.resolve("greenfield").resolve("test"), state);
        registry.register("test", new TestExecutor(testClient, new CommandRunner(), targetServiceDirectory,
            artifactsDirectory, runsDirectory, runId, testCommand, state::addEvidence, state));

        // approval-demo.json's DOCUMENT depends directly on REQUIREMENT (no DESIGN node
        // exists in that graph at all) and gets REQUIREMENT's own spec as a designSpec
        // stand-in (see seedCrossNodeContext's DOCUMENT fallback); its recorded fixture
        // lives under cli/document, and this registry is what MainCliResumeTest depends
        // on end to end. sdlc-default.json's DOCUMENT instead reads a real DESIGN spec
        // and a real implementation diff, recorded separately under greenfield/document;
        // running the real eight-node pipeline through this same registry needs that
        // fixture wired in here instead, which is real, known, follow-up work (tracked
        // alongside the rest of the eight-node pipeline's live fixture re-recording),
        // not something this constructor does today.
        AgentClient documentClient = agentClientFor(live, fixturesDirectory.resolve("cli").resolve("document"), state);
        registry.register("document", new DocumentExecutor(documentClient, artifactsDirectory));

        registry.register("validate", new ValidateExecutor(artifactsDirectory, state,
            artifactsDirectory.resolve("impact.json"), artifactsDirectory.resolve("implementation.diff")));

        registry.register("release", new ReleaseExecutor(artifactsDirectory, state));

        PolicyConfig policyConfig = PolicyConfig.loadFromFile(Path.of("workflows/policy.json"));
        RealPolicyEngine policyEngine = new RealPolicyEngine(policyConfig);

        return new WorkflowEngine(graph, state, registry, new Gates(), policyEngine, new Checkpoint(),
            targetServiceDirectory, runsDirectory, new CommandRunner(), buildCommand, testCommand,
            autoApprove, approvalStore);
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
