package com.schwab.agentic.tools;

import com.schwab.agentic.agent.AgentClient;
import com.schwab.agentic.agent.AgentClientFactory;
import com.schwab.agentic.agent.AgentResponse;
import com.schwab.agentic.agent.Mode;
import com.schwab.agentic.engine.CommandRunner;
import com.schwab.agentic.engine.Gate;
import com.schwab.agentic.engine.GateContext;
import com.schwab.agentic.engine.Gates;
import com.schwab.agentic.engine.NodeExecutor;
import com.schwab.agentic.executor.DesignExecutor;
import com.schwab.agentic.executor.DocumentExecutor;
import com.schwab.agentic.executor.ImpactExecutor;
import com.schwab.agentic.executor.ImplementExecutor;
import com.schwab.agentic.executor.RequirementExecutor;
import com.schwab.agentic.executor.TestExecutor;
import com.schwab.agentic.model.AcceptanceCriterion;
import com.schwab.agentic.model.Evidence;
import com.schwab.agentic.model.RequirementSpec;
import com.schwab.agentic.model.RiskLevel;
import com.schwab.agentic.model.WorkflowNode;
import com.schwab.agentic.model.WorkflowState;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A standalone, one-time operational tool, not a unit test: makes real, paid calls to
 * the Anthropic API and writes real fixtures under {@code fixtures/}. Deliberately not
 * wired into {@code ./scripts/test.sh}, since a real model's output is not deterministic
 * and every run of this class costs money; the regular test suite must stay free,
 * repeatable, and network-free by continuing to run against {@code ReplayClient} and the
 * fixtures this tool commits.
 *
 * Every fixture slot is recorded through the exact same {@link AgentClientFactory#createLive}
 * composition the real orchestrator uses in {@code --live} mode: a real
 * {@code AnthropicClient} wrapped in {@code RecordingClient}, so what lands in
 * {@code fixtures/} is indistinguishable from what a real run would have produced.
 *
 * Each slot is checked against the same exit gate the workflow actually uses. A gate
 * failure on real output is not papered over: the failure reason is threaded into a
 * second, real attempt's context exactly as {@code WorkflowEngine} does on retry, and
 * both attempts are reported. If the retry also fails, that failure is left recorded and
 * reported rather than the gate being loosened to make it pass, per instruction: a gate
 * that only passes on text a human wrote is not a gate.
 */
public final class FixtureRecorder {

    private final String apiKey;
    private final Path fixturesRoot;
    private final Path scenariosRoot;
    private final Path targetServiceDirectory;
    private final List<FixtureOutcome> outcomes = new ArrayList<>();
    private Path implementTargetDirForTestStage;
    private Path implementationDiffPathForTestStage;
    private Path greenfieldImpactJsonPath;

    public FixtureRecorder(String apiKey, Path fixturesRoot, Path scenariosRoot, Path targetServiceDirectory) {
        this.apiKey = apiKey;
        this.fixturesRoot = fixturesRoot;
        this.scenariosRoot = scenariosRoot;
        this.targetServiceDirectory = targetServiceDirectory;
    }

    public static void main(String[] args) throws IOException {
        String apiKey = System.getenv("ANTHROPIC_API_KEY");
        if (apiKey == null || apiKey.isBlank()) {
            System.err.println("ANTHROPIC_API_KEY must be set to record live fixtures.");
            System.exit(1);
        }
        Path repoRoot = findRepoRoot();
        FixtureRecorder recorder = new FixtureRecorder(apiKey, repoRoot.resolve("fixtures"),
            repoRoot.resolve("scenarios"), repoRoot.resolve("target-service"));

        String mode = args.length > 0 ? args[0] : "";
        switch (mode) {
            case "--only-test" -> {
                // Re-records only greenfield/test, reusing the already-recorded, still-valid
                // greenfield/design and greenfield/implement fixtures via ReplayClient
                // rather than re-running them live: they are unaffected by the ordering fix
                // that made the old greenfield/test fixture's prompt text stale, so
                // re-recording them again would spend real money for no reason.
                deleteFixturesUnder(recorder.fixturesRoot.resolve("greenfield/test"));
                String realDesignSpec = recorder.replayRecordedDesignSpec();
                recorder.recordTest(realDesignSpec);
            }
            case "--only-greenfield-requirement" -> {
                // Re-records only greenfield/requirement, after scenarios/greenfield/requirement.md
                // was amended to answer its own real open questions. Note: this predates
                // real cross-node context threading; IMPACT and DESIGN now read
                // REQUIREMENT's real normalizedProblem, so a real requirement-text change
                // affecting that field also requires --only-full-chain-from-impact
                // afterward. Kept for the narrower case (a change that does not affect
                // normalizedProblem, such as a wording fix already covered by ambiguities).
                deleteFixturesUnder(recorder.fixturesRoot.resolve("greenfield/requirement"));
                recorder.recordRequirement("greenfield");
            }
            case "--only-implement-and-test" -> {
                // Re-records greenfield/implement and greenfield/test together, chained,
                // now that ImplementExecutor's fixture targets a real copy of
                // target-service/ (a real Spring Boot project) instead of the plain
                // JUnit-only throwaway project: ImplementExecutor's real production
                // target genuinely has Spring/Jackson available, so recording its
                // fixture against a classpath that lacks them was itself the defect, not
                // the model's real, reasonable use of those frameworks. TestExecutor
                // writes directly into the same real target-service copy IMPLEMENT just
                // wrote into, so it sees the exact real classes it is testing, and its
                // real tests-pass gate runs target-service's actual full test suite.
                deleteFixturesUnder(recorder.fixturesRoot.resolve("greenfield/implement"));
                deleteFixturesUnder(recorder.fixturesRoot.resolve("greenfield/test"));
                String realDesignSpec = recorder.replayDesignSpecOnly();
                String realImplementationDiff = recorder.recordImplement(realDesignSpec);
                recorder.recordTest(realDesignSpec);
                System.out.println("real implementation diff length: " + realImplementationDiff.length());
            }
            case "--only-document" -> {
                // Re-records only greenfield/document, after greenfield/implement was
                // re-recorded against target-service (com.example.preview.*): the old
                // greenfield/document fixture's implementationDiff still named the prior
                // recording's file set (com.example.urlshortener.preview.*, including
                // RedisPreviewCache.java), so a real DocumentExecutor call built from the
                // CURRENT implement fixture's real diff can no longer match it by hash.
                // Replays design and implement (both unaffected, still valid) rather than
                // re-running them live.
                deleteFixturesUnder(recorder.fixturesRoot.resolve("greenfield/document"));
                String realDesignSpec = recorder.replayDesignSpecOnly();
                String realImplementationDiff = recorder.replayImplementDiffOnly(realDesignSpec);
                recorder.recordDocument(realDesignSpec, realImplementationDiff);
            }
            case "--only-test-only" -> {
                // Re-records only greenfield/test, replaying the already-recorded and
                // already-correct greenfield/design and greenfield/implement fixtures
                // (both unaffected) rather than re-running them live.
                // implementTargetDirForTestStage and implementationDiffPathForTestStage
                // are populated as a side effect of replayImplementDiffOnly, exactly as
                // recordImplement would populate them during a live recordAll pass, so
                // recordTest can be called afterward unmodified.
                deleteFixturesUnder(recorder.fixturesRoot.resolve("greenfield/test"));
                String realDesignSpec = recorder.replayDesignSpecOnly();
                recorder.replayImplementDiffOnly(realDesignSpec);
                recorder.recordTest(realDesignSpec);
            }
            case "--only-full-chain-from-impact" -> {
                // Re-records greenfield/impact, greenfield/design, greenfield/implement,
                // greenfield/test, and greenfield/document together, chained, now that
                // Main.java's real CLI threads REQUIREMENT's real normalizedProblem into
                // IMPACT and both that and IMPACT's real impact-analysis.md into DESIGN
                // as impactSummary. Every fixture from IMPACT onward was previously
                // recorded against a hand-written stand-in for these values, not
                // REQUIREMENT's or IMPACT's real output, so replaying the real, unaffected
                // greenfield/requirement fixture and re-recording live from there is what
                // makes fixtures/ agree with what the real CLI's real context threading
                // actually sends.
                deleteFixturesUnder(recorder.fixturesRoot.resolve("greenfield/impact"));
                deleteFixturesUnder(recorder.fixturesRoot.resolve("greenfield/design"));
                deleteFixturesUnder(recorder.fixturesRoot.resolve("greenfield/implement"));
                deleteFixturesUnder(recorder.fixturesRoot.resolve("greenfield/test"));
                deleteFixturesUnder(recorder.fixturesRoot.resolve("greenfield/document"));
                String realNormalizedProblem = recorder.replayRequirementNormalizedProblemOnly();
                String realImpactSummary = recorder.recordImpact("greenfield", realNormalizedProblem);
                String realDesignSpec = recorder.recordDesign(realNormalizedProblem, realImpactSummary);
                String realImplementationDiff = recorder.recordImplement(realDesignSpec);
                recorder.recordTest(realDesignSpec);
                recorder.recordDocument(realDesignSpec, realImplementationDiff);
            }
            case "--only-ambiguous-and-brownfield-requirement" -> {
                // RequirementExecutor's maxTokens changed (2000 -> 4000) after the real
                // greenfield fixture's response was found truncated mid-JSON-string at
                // 2000 tokens; this changes every RequirementExecutor fixture's request
                // hash, so ambiguous and brownfield need re-recording too even though
                // their requirement.md files did not change.
                deleteFixturesUnder(recorder.fixturesRoot.resolve("ambiguous/requirement"));
                deleteFixturesUnder(recorder.fixturesRoot.resolve("brownfield/requirement"));
                recorder.recordRequirement("ambiguous");
                recorder.recordRequirement("brownfield");
            }
            default -> {
                recorder.deleteExistingFixtures();
                recorder.recordAll();
            }
        }
        recorder.printReport();
    }

    private static void deleteFixturesUnder(Path directory) throws IOException {
        if (!Files.isDirectory(directory)) {
            return;
        }
        try (var walk = Files.walk(directory)) {
            for (Path file : walk.filter(Files::isRegularFile).toList()) {
                Files.delete(file);
            }
        }
    }

    /**
     * Replays greenfield/requirement's already-recorded fixture and returns its real
     * {@code normalizedProblem} text, via {@link com.schwab.agentic.agent.ReplayClient}
     * rather than the live API, for a mode that only needs to re-record a later stage
     * without re-spending on REQUIREMENT, which did not change.
     */
    private String replayRequirementNormalizedProblemOnly() throws IOException {
        Path requirementArtifacts = Files.createTempDirectory("fixture-recorder-replay-requirement-only");
        var requirementReplay = new com.schwab.agentic.agent.ReplayClient(fixturesRoot.resolve("greenfield/requirement"));
        RequirementExecutor requirementExecutor = new RequirementExecutor(requirementReplay, requirementArtifacts);
        Path requirementPath = scenariosRoot.resolve("greenfield").resolve("requirement.md");
        requirementExecutor.execute(gatedNode("REQUIREMENT", "requirement-complete", RiskLevel.LOW, Set.of()),
            Map.of("requirementPath", requirementPath.toString()));
        return readJsonField(requirementArtifacts.resolve("requirement-spec.json"), "normalizedProblem");
    }

    /**
     * Replays greenfield/requirement's and greenfield/impact's already-recorded fixtures
     * for their real, chained text, then replays greenfield/design's already-recorded
     * fixture against that same real chained context, all via {@code ReplayClient} rather
     * than the live API, for a mode that only needs to re-record a stage downstream of
     * DESIGN without re-spending on stages that are already correctly recorded.
     */
    private String replayDesignSpecOnly() throws IOException {
        String realNormalizedProblem = replayRequirementNormalizedProblemOnly();

        Path impactArtifacts = Files.createTempDirectory("fixture-recorder-replay-impact-only");
        var impactReplay = new com.schwab.agentic.agent.ReplayClient(fixturesRoot.resolve("greenfield/impact"));
        ImpactExecutor impactExecutor = new ImpactExecutor(impactReplay, impactArtifacts, targetServiceDirectory);
        impactExecutor.execute(gatedNode("IMPACT", "artifact-written", RiskLevel.MEDIUM, Set.of()),
            Map.of("normalizedProblem", realNormalizedProblem));
        String realImpactSummary = Files.readString(impactArtifacts.resolve("impact-analysis.md"));
        this.greenfieldImpactJsonPath = impactArtifacts.resolve("impact.json");

        Path designArtifacts = Files.createTempDirectory("fixture-recorder-replay-design-only");
        var designReplay = new com.schwab.agentic.agent.ReplayClient(fixturesRoot.resolve("greenfield/design"));
        DesignExecutor designExecutor = new DesignExecutor(designReplay, designArtifacts, decision -> { });
        designExecutor.execute(gatedNode("DESIGN", "artifact-written", RiskLevel.MEDIUM, Set.of()),
            Map.of("normalizedProblem", realNormalizedProblem, "impactSummary", realImpactSummary));
        return Files.readString(designArtifacts.resolve("design-spec.json"));
    }

    /**
     * Replays the current, already-recorded greenfield/implement fixture (including its
     * retry, if the fixture needed one) via {@link com.schwab.agentic.agent.ReplayClient},
     * writing into a fresh copy of target-service, and returns the real
     * {@code implementation.diff} text this produces: the same real diff DocumentExecutor
     * must be recorded against so its fixture stays consistent with whatever
     * greenfield/implement currently contains. As a side effect, populates
     * {@link #implementTargetDirForTestStage} and {@link #implementationDiffPathForTestStage}
     * exactly as {@link #recordImplement} would, so {@link #recordTest} can be called
     * afterward without needing to know whether IMPLEMENT was replayed or recorded live.
     */
    private String replayImplementDiffOnly(String realDesignSpec) throws IOException {
        Path artifactsDir = Files.createTempDirectory("fixture-recorder-replay-implement-diff");
        Path implementTargetDir = Files.createTempDirectory("fixture-recorder-replay-implement-target");
        copyTargetServiceInto(implementTargetDir);
        Path fixturesDir = fixturesRoot.resolve("greenfield/implement");
        WorkflowNode node = gatedNode("IMPLEMENT", "compiles", RiskLevel.HIGH, Set.of("compiles"));
        CommandRunner commandRunner = new CommandRunner();

        Map<String, Object> context = new LinkedHashMap<>();
        context.put("designSpec", realDesignSpec);
        String existingCodeContext = readAffectedFilesContent(greenfieldImpactJsonPath, targetServiceDirectory);
        if (existingCodeContext != null) {
            context.put("existingCodeContext", existingCodeContext);
        }

        var replayClient = new com.schwab.agentic.agent.ReplayClient(fixturesDir);
        ImplementExecutor executor = new ImplementExecutor(replayClient, implementTargetDir, artifactsDir);
        executor.execute(node, context);

        CommandRunner.Result buildResult = commandRunner.run("./gradlew compileJava", implementTargetDir,
            java.time.Duration.ofMinutes(3));
        if (!buildResult.succeeded()) {
            Path latestFile;
            try (var walk = Files.walk(fixturesDir)) {
                latestFile = walk.filter(Files::isRegularFile)
                    .max(java.util.Comparator.comparingLong(path -> path.toFile().lastModified()))
                    .orElseThrow();
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> fixture = (Map<String, Object>) com.schwab.agentic.json.Json.parse(Files.readString(latestFile));
            @SuppressWarnings("unchecked")
            Map<String, Object> response = (Map<String, Object>) fixture.get("response");
            String retryResponseText = (String) response.get("text");
            com.schwab.agentic.agent.AgentClient fixedResponseClient = request -> new com.schwab.agentic.agent.AgentResponse(
                retryResponseText, 0, 0, 0, com.schwab.agentic.agent.Mode.REPLAY, "fixture-recorder-retry-reconstruction");
            ImplementExecutor retryExecutor = new ImplementExecutor(fixedResponseClient, implementTargetDir, artifactsDir);
            retryExecutor.execute(node, Map.of("designSpec", realDesignSpec,
                "previousFailureReason", buildResult.stdout() + buildResult.stderr()));
        }

        this.implementTargetDirForTestStage = implementTargetDir;
        this.implementationDiffPathForTestStage = artifactsDir.resolve("implementation.diff");
        return Files.readString(this.implementationDiffPathForTestStage);
    }

    private String replayRecordedDesignSpec() throws IOException {
        Path designArtifacts = Files.createTempDirectory("fixture-recorder-replay-design");
        var designReplay = new com.schwab.agentic.agent.ReplayClient(fixturesRoot.resolve("greenfield/design"));
        DesignExecutor designExecutor = new DesignExecutor(designReplay, designArtifacts, decision -> { });
        String normalizedProblem = "Add GET /api/v1/urls/{code}/preview returning a cached title and description"
            + " for the target URL, with a timeout on the external fetch and a 404 for unknown codes.";
        designExecutor.execute(gatedNode("DESIGN", "artifact-written", RiskLevel.MEDIUM, Set.of()),
            Map.of("normalizedProblem", normalizedProblem));
        String realDesignSpec = Files.readString(designArtifacts.resolve("design-spec.json"));

        // The real implement retry's prompt embedded the first attempt's real compiler
        // output, which itself names a fresh, unique temp directory path (a JDK compiler
        // error message, not something this tool controls); that makes the retry
        // request's hash impossible to reproduce byte for byte in a new process, so it
        // cannot be looked up through ReplayClient the normal way. Since only the real,
        // already-compiling implementation on disk is needed here (not a byte-exact
        // replay of the retry call itself), the retry fixture's already-recorded
        // response text is applied directly by feeding it through a fixed-response
        // client instead of a hash-keyed one.
        Path implementTargetDir = Files.createTempDirectory("fixture-recorder-replay-implement-target");
        copyThrowawayProjectInto(implementTargetDir);
        Path implementArtifacts = Files.createTempDirectory("fixture-recorder-replay-implement-artifacts");
        String retryResponseText = latestRecordedResponseText(fixturesRoot.resolve("greenfield/implement"));
        AgentClient fixedResponseClient = request -> new AgentResponse(
            retryResponseText, 0, 0, 0, Mode.REPLAY, "fixture-recorder-only-test-reconstruction");
        ImplementExecutor implementExecutor = new ImplementExecutor(fixedResponseClient, implementTargetDir, implementArtifacts);
        WorkflowNode implementNode = gatedNode("IMPLEMENT", "compiles", RiskLevel.HIGH, Set.of("compiles"));
        implementExecutor.execute(implementNode, Map.of("designSpec", realDesignSpec));

        this.implementTargetDirForTestStage = implementTargetDir;
        return realDesignSpec;
    }

    /** The response text from the most recently written fixture file under {@code directory}, by file modification time. */
    private String latestRecordedResponseText(Path directory) throws IOException {
        Path latestFile;
        try (var walk = Files.walk(directory)) {
            latestFile = walk.filter(Files::isRegularFile)
                .max(java.util.Comparator.comparingLong(path -> path.toFile().lastModified()))
                .orElseThrow(() -> new IllegalStateException("No fixture files found under " + directory));
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> fixture = (Map<String, Object>) com.schwab.agentic.json.Json.parse(Files.readString(latestFile));
        @SuppressWarnings("unchecked")
        Map<String, Object> response = (Map<String, Object>) fixture.get("response");
        return (String) response.get("text");
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

    /**
     * Removes every existing fixture file before recording, so a stale placeholder
     * fixture (from before real credit was available) can never be mistaken for a real
     * recording just because a later run happened not to overwrite it.
     */
    private void deleteExistingFixtures() throws IOException {
        if (!Files.isDirectory(fixturesRoot)) {
            return;
        }
        try (var walk = Files.walk(fixturesRoot)) {
            var files = walk.filter(Files::isRegularFile).toList();
            for (Path file : files) {
                Files.delete(file);
            }
        }
    }

    /**
     * Records the greenfield stages as one real pipeline: each stage's actual, real
     * output (not an invented summary of what it "should" produce) is threaded into the
     * next stage's context, exactly as the real orchestrator would once spec 05's engine
     * exists. Feeding a later stage a hand-written stand-in for an earlier stage's real
     * output was the root cause the first live recording run surfaced: TestExecutor's
     * model independently invented a repository-backed PreviewService shape that
     * disagreed with what ImplementExecutor's model had actually written, because both
     * were given disconnected one-line descriptions instead of each other's real text.
     */
    private void recordAll() throws IOException {
        String realGreenfieldNormalizedProblem = recordRequirement("greenfield");
        recordRequirement("ambiguous");
        recordRequirement("brownfield");
        String realImpactSummary = recordImpact("greenfield", realGreenfieldNormalizedProblem);
        recordImpact("brownfield", "An expired link's resolution attempt is incorrectly counted as a click.");
        String realDesignSpec = recordDesign(realGreenfieldNormalizedProblem, realImpactSummary);
        String realImplementationDiff = recordImplement(realDesignSpec);
        recordTest(realDesignSpec);
        recordDocument(realDesignSpec, realImplementationDiff);
    }

    private WorkflowNode gatedNode(String id, String exitGate, RiskLevel riskLevel, Set<String> producesEvidenceFor) {
        return new WorkflowNode(id, id, id.toLowerCase(), Set.of(), "dependencies-complete", exitGate,
            riskLevel, 2, producesEvidenceFor);
    }

    /**
     * Returns the real {@code normalizedProblem} text this scenario's REQUIREMENT fixture
     * actually produced (from whichever attempt's gate passed, or the retry's if the
     * first attempt's gate never did), so downstream stages (IMPACT, DESIGN) can be
     * recorded against REQUIREMENT's real output rather than a hand-written stand-in,
     * matching what {@code Main.java}'s real cross-node context threading feeds them.
     */
    private String recordRequirement(String scenarioName) throws IOException {
        String slot = scenarioName + "/requirement";
        Path artifactsDir = Files.createTempDirectory("fixture-recorder-artifacts-" + scenarioName + "-req");
        Path fixturesDir = fixturesRoot.resolve(slot);
        Path requirementPath = scenariosRoot.resolve(scenarioName).resolve("requirement.md");

        WorkflowNode node = gatedNode("REQUIREMENT", "requirement-complete", RiskLevel.LOW, Set.of());
        RequirementSpec placeholderSpec = new RequirementSpec("REQ-0", 1, "placeholder", "placeholder", List.of());
        WorkflowState state = new WorkflowState("FIXTURE-RECORDING", placeholderSpec, List.of(node));

        AttemptResult first = attempt(slot, () -> {
            var client = AgentClientFactory.createLive(apiKey, fixturesDir, state);
            RequirementExecutor executor = new RequirementExecutor(client, artifactsDir);
            NodeExecutor.ExecutionOutput output = executor.execute(node,
                Map.of("requirementPath", requirementPath.toString()));
            Gate gate = new Gates().resolve("requirement-complete");
            Gate.Result gateResult = gate.evaluate(node, state,
                new GateContext(output.outputs(), null, null, null, null, null, null));
            return new RawAttempt(output, gateResult);
        });

        if (!first.gatePassed()) {
            AttemptResult retry = attempt(slot, () -> {
                var client = AgentClientFactory.createLive(apiKey, fixturesDir, state);
                RequirementExecutor executor = new RequirementExecutor(client, artifactsDir);
                NodeExecutor.ExecutionOutput output = executor.execute(node,
                    Map.of("requirementPath", requirementPath.toString(),
                        "previousFailureReason", first.reason()));
                Gate gate = new Gates().resolve("requirement-complete");
                Gate.Result gateResult = gate.evaluate(node, state,
                    new GateContext(output.outputs(), null, null, null, null, null, null));
                return new RawAttempt(output, gateResult);
            });
            outcomes.add(new FixtureOutcome(slot, true, true, retry.gatePassed(), first.reason(), retry.reason()));
        } else {
            outcomes.add(new FixtureOutcome(slot, true, false, true, first.reason(), null));
        }

        return readJsonField(artifactsDir.resolve("requirement-spec.json"), "normalizedProblem");
    }

    @SuppressWarnings("unchecked")
    private String readJsonField(Path jsonPath, String field) throws IOException {
        Map<String, Object> parsed = (Map<String, Object>) com.schwab.agentic.json.Json.parse(
            Files.readString(jsonPath));
        Object value = parsed.get(field);
        return value == null ? null : String.valueOf(value);
    }

    /** Returns the real impact-analysis.md content this stage produced, for DESIGN to consume. */
    private String recordImpact(String scenarioName, String normalizedProblem) throws IOException {
        String slot = scenarioName + "/impact";
        Path artifactsDir = Files.createTempDirectory("fixture-recorder-artifacts-" + scenarioName + "-impact");
        Path fixturesDir = fixturesRoot.resolve(slot);

        WorkflowNode node = gatedNode("IMPACT", "artifact-written", RiskLevel.MEDIUM, Set.of());
        RequirementSpec placeholderSpec = new RequirementSpec("REQ-0", 1, "placeholder", "placeholder", List.of());
        WorkflowState state = new WorkflowState("FIXTURE-RECORDING", placeholderSpec, List.of(node));

        AttemptResult first = attempt(slot, () -> {
            var client = AgentClientFactory.createLive(apiKey, fixturesDir, state);
            ImpactExecutor executor = new ImpactExecutor(client, artifactsDir, targetServiceDirectory);
            NodeExecutor.ExecutionOutput output = executor.execute(node, Map.of("normalizedProblem", normalizedProblem));
            Gate gate = new Gates().resolve("artifact-written");
            Gate.Result gateResult = gate.evaluate(node, state,
                new GateContext(output.outputs(), null, null, null, null, null, null));
            return new RawAttempt(output, gateResult);
        });

        recordSimpleOutcome(slot, first, () -> {
            Path retryArtifactsDir = artifactsDir;
            var client = AgentClientFactory.createLive(apiKey, fixturesDir, state);
            ImpactExecutor executor = new ImpactExecutor(client, retryArtifactsDir, targetServiceDirectory);
            NodeExecutor.ExecutionOutput output = executor.execute(node,
                Map.of("normalizedProblem", normalizedProblem, "previousFailureReason", first.reason()));
            Gate gate = new Gates().resolve("artifact-written");
            Gate.Result gateResult = gate.evaluate(node, state,
                new GateContext(output.outputs(), null, null, null, null, null, null));
            return new RawAttempt(output, gateResult);
        });

        if (scenarioName.equals("greenfield")) {
            this.greenfieldImpactJsonPath = artifactsDir.resolve("impact.json");
        }
        return Files.readString(artifactsDir.resolve("impact-analysis.md"));
    }

    /**
     * Returns the real design-spec.json content this stage produced, for downstream
     * stages to consume. {@code normalizedProblem} and {@code impactSummary} are
     * REQUIREMENT's and IMPACT's real outputs from this same recording pass, matching
     * exactly what {@code Main.java}'s real cross-node context threading feeds DESIGN in
     * production, not a hand-written stand-in.
     */
    private String recordDesign(String normalizedProblem, String impactSummary) throws IOException {
        String slot = "greenfield/design";
        Path artifactsDir = Files.createTempDirectory("fixture-recorder-artifacts-design");
        Path fixturesDir = fixturesRoot.resolve(slot);

        WorkflowNode node = gatedNode("DESIGN", "artifact-written", RiskLevel.MEDIUM, Set.of());
        RequirementSpec placeholderSpec = new RequirementSpec("REQ-0", 1, "placeholder", "placeholder", List.of());
        WorkflowState state = new WorkflowState("FIXTURE-RECORDING", placeholderSpec, List.of(node));
        List<com.schwab.agentic.model.DecisionRecord> decisions = new ArrayList<>();

        Map<String, Object> context = Map.of("normalizedProblem", normalizedProblem, "impactSummary", impactSummary);

        AttemptResult first = attempt(slot, () -> {
            var client = AgentClientFactory.createLive(apiKey, fixturesDir, state);
            DesignExecutor executor = new DesignExecutor(client, artifactsDir, decisions::add);
            NodeExecutor.ExecutionOutput output = executor.execute(node, context);
            Gate gate = new Gates().resolve("artifact-written");
            Gate.Result gateResult = gate.evaluate(node, state,
                new GateContext(output.outputs(), null, null, null, null, null, null));
            return new RawAttempt(output, gateResult);
        });

        if (!first.gatePassed()) {
            recordSimpleOutcome(slot, first, () -> {
                Map<String, Object> retryContext = new LinkedHashMap<>(context);
                retryContext.put("previousFailureReason", first.reason());
                var client = AgentClientFactory.createLive(apiKey, fixturesDir, state);
                DesignExecutor executor = new DesignExecutor(client, artifactsDir, decisions::add);
                NodeExecutor.ExecutionOutput output = executor.execute(node, retryContext);
                Gate gate = new Gates().resolve("artifact-written");
                Gate.Result gateResult = gate.evaluate(node, state,
                    new GateContext(output.outputs(), null, null, null, null, null, null));
                return new RawAttempt(output, gateResult);
            });
        } else {
            outcomes.add(new FixtureOutcome(slot, true, false, true, first.reason(), null));
        }

        return Files.readString(artifactsDir.resolve("design-spec.json"));
    }

    /**
     * Consumes the real design spec produced by {@link #recordDesign(String, String)} and writes real
     * source into {@code implementTargetDir}, which is reused as-is (not copied or
     * summarized) as the compile project {@link #recordTest} writes tests into, so the
     * test stage sees the exact same real classes the implementation stage wrote rather
     * than a second, independently-imagined description of them. Returns that directory
     * so the caller can pass it on.
     */
    private String recordImplement(String realDesignSpec) throws IOException {
        String slot = "greenfield/implement";
        Path artifactsDir = Files.createTempDirectory("fixture-recorder-artifacts-implement");
        Path implementTargetDir = Files.createTempDirectory("fixture-recorder-implement-target");
        copyTargetServiceInto(implementTargetDir);
        Path fixturesDir = fixturesRoot.resolve(slot);

        WorkflowNode node = gatedNode("IMPLEMENT", "compiles", RiskLevel.HIGH, Set.of("compiles"));
        RequirementSpec placeholderSpec = new RequirementSpec("REQ-0", 1, "placeholder", "placeholder", List.of());
        WorkflowState state = new WorkflowState("FIXTURE-RECORDING", placeholderSpec, List.of(node));
        CommandRunner commandRunner = new CommandRunner();

        Map<String, Object> context = new LinkedHashMap<>();
        context.put("designSpec", realDesignSpec);
        String existingCodeContext = readAffectedFilesContent(greenfieldImpactJsonPath, targetServiceDirectory);
        if (existingCodeContext != null) {
            context.put("existingCodeContext", existingCodeContext);
        }

        AttemptResult first = attempt(slot, () -> {
            var client = AgentClientFactory.createLive(apiKey, fixturesDir, state);
            ImplementExecutor executor = new ImplementExecutor(client, implementTargetDir, artifactsDir);
            NodeExecutor.ExecutionOutput output = executor.execute(node, context);
            Gate.Result gateResult = evaluateCompiles(node, state, commandRunner, implementTargetDir);
            return new RawAttempt(output, gateResult);
        });

        if (!first.gatePassed()) {
            recordSimpleOutcome(slot, first, () -> {
                Map<String, Object> retryContext = new LinkedHashMap<>(context);
                retryContext.put("previousFailureReason", first.reason());
                var client = AgentClientFactory.createLive(apiKey, fixturesDir, state);
                ImplementExecutor executor = new ImplementExecutor(client, implementTargetDir, artifactsDir);
                NodeExecutor.ExecutionOutput output = executor.execute(node, retryContext);
                Gate.Result gateResult = evaluateCompiles(node, state, commandRunner, implementTargetDir);
                return new RawAttempt(output, gateResult);
            });
        } else {
            outcomes.add(new FixtureOutcome(slot, true, false, true, first.reason(), null));
        }

        this.implementTargetDirForTestStage = implementTargetDir;
        this.implementationDiffPathForTestStage = artifactsDir.resolve("implementation.diff");
        return Files.readString(this.implementationDiffPathForTestStage);
    }

    /**
     * The real content of every file {@code impact.json} named under {@code affectedFiles}
     * (existing target-service classes the impact analysis found this change depends on),
     * so IMPLEMENT can be told the real package names, class names, and method signatures
     * of code it must reference, matching exactly what {@code Main.java}'s real
     * cross-node context threading feeds it in production. Returns {@code null} if
     * {@code impactJsonPath} is {@code null} or names no affected files at all.
     */
    @SuppressWarnings("unchecked")
    private String readAffectedFilesContent(Path impactJsonPath, Path targetServiceDir) throws IOException {
        if (impactJsonPath == null || !Files.isRegularFile(impactJsonPath)) {
            return null;
        }
        Map<String, Object> parsed = (Map<String, Object>) com.schwab.agentic.json.Json.parse(
            Files.readString(impactJsonPath));
        Object affectedFiles = parsed.get("affectedFiles");
        if (!(affectedFiles instanceof List<?> list) || list.isEmpty()) {
            return null;
        }
        StringBuilder combined = new StringBuilder();
        for (Object item : list) {
            String relativePath = String.valueOf(item);
            Path absolute = targetServiceDir.resolve(relativePath);
            if (!Files.isRegularFile(absolute)) {
                continue;
            }
            combined.append("// FILE: ").append(relativePath).append('\n');
            combined.append(Files.readString(absolute)).append("\n\n");
        }
        return combined.length() == 0 ? null : combined.toString();
    }

    private Gate.Result evaluateCompiles(WorkflowNode node, WorkflowState state, CommandRunner commandRunner,
                                          Path implementTargetDir) {
        CommandRunner.Result result = commandRunner.run("./gradlew compileJava", implementTargetDir,
            java.time.Duration.ofMinutes(3));
        return result.succeeded()
            ? Gate.Result.pass("build command exited 0")
            : Gate.Result.fail("build command exited " + result.exitCode() + ": " + result.stdout() + result.stderr());
    }

    /**
     * Consumes the real design spec and writes tests directly into
     * {@link #implementTargetDirForTestStage}: the exact same directory
     * {@link #recordImplement} just wrote real, compiling source into, so the model
     * writing tests here sees the real class it is testing rather than a second,
     * independently-imagined description of it.
     */
    private void recordTest(String realDesignSpec) throws IOException {
        String slot = "greenfield/test";
        Path artifactsDir = Files.createTempDirectory("fixture-recorder-artifacts-test");
        Path testProjectDir = implementTargetDirForTestStage;
        Path fixturesDir = fixturesRoot.resolve(slot);
        Path runsDir = Files.createTempDirectory("fixture-recorder-runs");

        RequirementSpec requirementSpec = new RequirementSpec("REQ-1", 1, "req", "req normalized",
            List.of(new AcceptanceCriterion("AC-1", "returns a preview for a known short code", RiskLevel.HIGH)));
        WorkflowNode node = gatedNode("TEST", "tests-pass", RiskLevel.MEDIUM, Set.of("tests-pass"));
        WorkflowState state = new WorkflowState("FIXTURE-RECORDING-TEST", requirementSpec, List.of(node));
        List<Evidence> evidence = new ArrayList<>();

        Map<String, Object> context = Map.of(
            "designSpec", realDesignSpec,
            "implementationSource", realImplementationSource(),
            "acceptanceCriteria", List.of(Map.of("id", "AC-1", "description", "returns a preview for a known short code")));

        AttemptResult first = attempt(slot, () -> {
            var client = AgentClientFactory.createLive(apiKey, fixturesDir, state);
            TestExecutor executor = new TestExecutor(client, new CommandRunner(), testProjectDir, artifactsDir,
                runsDir, "FIXTURE-RECORDING-TEST", "./gradlew test", evidence::add, state);
            NodeExecutor.ExecutionOutput output = executor.execute(node, context);
            Gate.Result gateResult = output.executorReportedSuccess()
                ? Gate.Result.pass("test command exited 0 and all declared criteria are covered")
                : Gate.Result.fail(output.summary());
            return new RawAttempt(output, gateResult);
        });

        recordSimpleOutcome(slot, first, () -> {
            Map<String, Object> retryContext = new LinkedHashMap<>(context);
            retryContext.put("previousFailureReason", first.reason());
            var client = AgentClientFactory.createLive(apiKey, fixturesDir, state);
            TestExecutor executor = new TestExecutor(client, new CommandRunner(), testProjectDir, artifactsDir,
                runsDir, "FIXTURE-RECORDING-TEST", "./gradlew test", evidence::add, state);
            NodeExecutor.ExecutionOutput output = executor.execute(node, retryContext);
            Gate.Result gateResult = output.executorReportedSuccess()
                ? Gate.Result.pass("test command exited 0 and all declared criteria are covered")
                : Gate.Result.fail(output.summary());
            return new RawAttempt(output, gateResult);
        });
    }

    /**
     * The real content of every file named in the real {@code implementation.diff}
     * IMPLEMENT actually wrote, read via {@link com.schwab.agentic.executor.ImplementationSourceReader},
     * so TestExecutor's model is told the real package and class names it must test
     * rather than inventing its own, which is what caused DESIGN's proposed names,
     * IMPLEMENT's actual names, and TEST's assumed names to disagree the first time this
     * fixture was recorded. Reading the diff's own file list rather than assuming a fixed
     * package root is what keeps this correct regardless of which real package name the
     * model's real output happens to choose from one recording to the next.
     */
    private String realImplementationSource() {
        return com.schwab.agentic.executor.ImplementationSourceReader.readFromDiff(
            implementTargetDirForTestStage, implementationDiffPathForTestStage);
    }

    private void copyThrowawayProjectInto(Path destination) throws IOException {
        Path source = findRepoRoot().resolve("orchestrator/src/test/resources/throwaway-compile-project");
        try (var walk = Files.walk(source)) {
            for (Path path : walk.filter(Files::isRegularFile).toList()) {
                Path relative = source.relativize(path);
                Path target = destination.resolve(relative);
                Files.createDirectories(target.getParent());
                Files.copy(path, target);
                target.toFile().setExecutable(path.toFile().canExecute());
            }
        }
    }

    /**
     * Copies the real {@code target-service/} project (excluding {@code build/} and
     * {@code .gradle/}, regeneratable build caches) into {@code destination}, so
     * ImplementExecutor's fixture is recorded against its actual real-world target, a
     * real Spring Boot project with real Spring, Jackson, and other dependencies
     * genuinely available, rather than the plain-JUnit throwaway project TestExecutor's
     * fixture uses (ImplementExecutor and TestExecutor target different real projects in
     * production: ImplementExecutor writes into target-service/ itself; TestExecutor's
     * own fixture is recorded against the throwaway project purely for fast, hermetic
     * TestExecutor testing, a distinction documented in docs/decisions.md).
     */
    private void copyTargetServiceInto(Path destination) throws IOException {
        Path source = findRepoRoot().resolve("target-service");
        List<String> excludedDirectoryNames = List.of("build", ".gradle", ".git");
        try (var walk = Files.walk(source)) {
            for (Path path : walk.filter(Files::isRegularFile).toList()) {
                Path relative = source.relativize(path);
                boolean excluded = false;
                for (Path part : relative) {
                    if (excludedDirectoryNames.contains(part.toString())) {
                        excluded = true;
                        break;
                    }
                }
                if (excluded) {
                    continue;
                }
                Path target = destination.resolve(relative);
                Files.createDirectories(target.getParent());
                Files.copy(path, target);
                target.toFile().setExecutable(path.toFile().canExecute());
            }
        }
    }

    /** Consumes the real design spec and the real implementation diff from earlier stages. */
    private void recordDocument(String realDesignSpec, String realImplementationDiff) throws IOException {
        String slot = "greenfield/document";
        Path artifactsDir = Files.createTempDirectory("fixture-recorder-artifacts-document");
        Path fixturesDir = fixturesRoot.resolve(slot);

        WorkflowNode node = gatedNode("DOCUMENT", "artifact-written", RiskLevel.LOW, Set.of());
        RequirementSpec placeholderSpec = new RequirementSpec("REQ-0", 1, "placeholder", "placeholder", List.of());
        WorkflowState state = new WorkflowState("FIXTURE-RECORDING", placeholderSpec, List.of(node));

        Map<String, Object> context = Map.of(
            "designSpec", realDesignSpec,
            "implementationDiff", realImplementationDiff);

        AttemptResult first = attempt(slot, () -> {
            var client = AgentClientFactory.createLive(apiKey, fixturesDir, state);
            DocumentExecutor executor = new DocumentExecutor(client, artifactsDir);
            NodeExecutor.ExecutionOutput output = executor.execute(node, context);
            Gate gate = new Gates().resolve("artifact-written");
            Gate.Result gateResult = gate.evaluate(node, state,
                new GateContext(output.outputs(), null, null, null, null, null, null));
            return new RawAttempt(output, gateResult);
        });

        recordSimpleOutcome(slot, first, () -> {
            Map<String, Object> retryContext = new LinkedHashMap<>(context);
            retryContext.put("previousFailureReason", first.reason());
            var client = AgentClientFactory.createLive(apiKey, fixturesDir, state);
            DocumentExecutor executor = new DocumentExecutor(client, artifactsDir);
            NodeExecutor.ExecutionOutput output = executor.execute(node, retryContext);
            Gate gate = new Gates().resolve("artifact-written");
            Gate.Result gateResult = gate.evaluate(node, state,
                new GateContext(output.outputs(), null, null, null, null, null, null));
            return new RawAttempt(output, gateResult);
        });
    }

    private void recordSimpleOutcome(String slot, AttemptResult first, ThrowingSupplier<RawAttempt> retrySupplier)
        throws IOException {
        if (first.gatePassed()) {
            outcomes.add(new FixtureOutcome(slot, true, false, true, first.reason(), null));
            return;
        }
        AttemptResult retry = attempt(slot, retrySupplier);
        outcomes.add(new FixtureOutcome(slot, true, true, retry.gatePassed(), first.reason(), retry.reason()));
    }

    private AttemptResult attempt(String slot, ThrowingSupplier<RawAttempt> action) throws IOException {
        System.out.println("Recording " + slot + " ...");
        try {
            RawAttempt raw = action.get();
            System.out.println("  gate: " + (raw.gateResult().passed() ? "PASSED" : "FAILED: " + raw.gateResult().reason()));
            return new AttemptResult(raw.gateResult().passed(), raw.gateResult().reason());
        } catch (RuntimeException e) {
            System.out.println("  executor threw: " + e.getMessage());
            return new AttemptResult(false, String.valueOf(e.getMessage()));
        }
    }

    private void printReport() {
        System.out.println();
        System.out.println("Fixture recording report:");
        for (FixtureOutcome outcome : outcomes) {
            System.out.println("  " + outcome.slot() + ": recorded=" + (outcome.recorded() ? "yes" : "no")
                + ", retried=" + (outcome.retried() ? "yes" : "no")
                + ", finalGatePassed=" + (outcome.finalGatePassed() ? "yes" : "no"));
            if (outcome.retried()) {
                System.out.println("    first attempt gate reason: " + outcome.firstAttemptReason());
                System.out.println("    retry gate reason: " + outcome.retryReason());
            }
        }
    }

    private interface ThrowingSupplier<T> {
        T get();
    }

    private record RawAttempt(NodeExecutor.ExecutionOutput output, Gate.Result gateResult) {
    }

    private record AttemptResult(boolean gatePassed, String reason) {
    }

    private record FixtureOutcome(String slot, boolean recorded, boolean retried, boolean finalGatePassed,
                                   String firstAttemptReason, String retryReason) {
    }
}
