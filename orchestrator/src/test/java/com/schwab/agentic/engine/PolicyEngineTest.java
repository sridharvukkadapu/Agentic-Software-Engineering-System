package com.schwab.agentic.engine;

import static com.schwab.agentic.Assertions.assertEquals;
import static com.schwab.agentic.Assertions.assertTrue;

import com.schwab.agentic.model.AcceptanceCriterion;
import com.schwab.agentic.model.Evidence;
import com.schwab.agentic.model.RequirementSpec;
import com.schwab.agentic.model.RiskLevel;
import com.schwab.agentic.model.WorkflowNode;
import com.schwab.agentic.model.WorkflowState;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Covers all eight of {@link RealPolicyEngine}'s rules (AC-05-4): one test per rule that
 * constructs an input specifically crafted to trip its DENY or REQUIRE_APPROVAL branch.
 * A rule with no such test is assumed dead under CLAUDE.md rule 6; every test here proves
 * the opposite for its rule by actually reaching the restrictive branch, not merely by
 * exercising the rule's ALLOW path.
 */
public class PolicyEngineTest {

    private static final String POLICY_JSON = """
        {
          "rules": [
            {"name": "critical-risk-requires-approval", "category": "change-control", "enabled": true},
            {"name": "high-risk-requires-approval", "category": "change-control", "enabled": true},
            {"name": "change-budget", "category": "change-control", "enabled": true, "maxFilesChanged": 3, "maxLinesAdded": 60},
            {"name": "protected-paths-global", "category": "security", "enabled": true, "allowedRoots": ["target-service", "runs"]},
            {"name": "write-paths-contract", "category": "security", "enabled": true},
            {"name": "no-secrets-in-diff", "category": "security", "enabled": true, "patterns": ["sk-ant-api[0-9]{2}-[A-Za-z0-9_-]{20,}", "AKIA[0-9A-Z]{16}"]},
            {"name": "no-dependency-additions", "category": "security", "enabled": true, "dependencyManifestFilenames": ["pom.xml", "build.gradle", "build.gradle.kts"]},
            {"name": "evidence-before-release", "category": "compliance", "enabled": true}
          ]
        }
        """;

    private static RealPolicyEngine newEngine() {
        return new RealPolicyEngine(PolicyConfig.loadFromJson(POLICY_JSON));
    }

    private static WorkflowNode nodeWithRisk(String id, RiskLevel riskLevel) {
        return new WorkflowNode(id, id, "noop", Set.of(), null, null, riskLevel, 1, Set.of());
    }

    private static WorkflowNode nodeWithWritePaths(String id, RiskLevel riskLevel, Set<String> writePaths) {
        return new WorkflowNode(id, id, "noop", Set.of(), null, null, riskLevel, 1, Set.of(), null, writePaths);
    }

    private static WorkflowState newState(WorkflowNode node, List<AcceptanceCriterion> criteria) {
        RequirementSpec spec = new RequirementSpec("REQ-1", 1, "req", "req normalized", criteria);
        return new WorkflowState("RUN-1", spec, List.of(node));
    }

    /**
     * A real {@code target-service/} and {@code runs/} sibling layout under one temp
     * repository root, matching what {@code protected-paths-global} actually checks
     * against (allowed roots resolved relative to {@code targetServiceDirectory}'s
     * parent). Tests for other rules that are not themselves testing the global
     * boundary still need a layout this rule's ALLOW path accepts, since
     * {@code evaluatePostExecution} checks it first and would otherwise mask whatever
     * rule the test actually means to exercise.
     */
    private record RepoLayout(Path repositoryRoot, Path targetServiceDirectory, Path runsDirectory) {
        static RepoLayout create(String prefix) throws IOException {
            Path repositoryRoot = Files.createTempDirectory(prefix);
            Path targetServiceDirectory = repositoryRoot.resolve("target-service");
            Path runsDirectory = repositoryRoot.resolve("runs");
            Files.createDirectories(targetServiceDirectory);
            Files.createDirectories(runsDirectory);
            return new RepoLayout(repositoryRoot, targetServiceDirectory, runsDirectory);
        }
    }

    // ---- 1. critical-risk-requires-approval ----

    public void testCriticalRiskNodeAlwaysRequiresApproval() {
        RealPolicyEngine engine = newEngine();
        WorkflowNode node = nodeWithRisk("RELEASE", RiskLevel.CRITICAL);
        WorkflowState state = newState(node, List.of());

        PolicyRule.Result result = engine.evaluatePreExecutionWithReason(node, state,
            new PolicyContext(null, null, "RUN-1", false));

        assertEquals(PolicyEngine.Decision.REQUIRE_APPROVAL, result.decision(), "CRITICAL risk must require approval");
        assertEquals("critical-risk-requires-approval", result.ruleName(), "the firing rule must be named");
    }

    // ---- 2. high-risk-requires-approval ----

    public void testHighRiskNodeRequiresApprovalUnlessAutoApprove() {
        RealPolicyEngine engine = newEngine();
        WorkflowNode node = nodeWithRisk("IMPLEMENT", RiskLevel.HIGH);
        WorkflowState state = newState(node, List.of());

        PolicyRule.Result blocked = engine.evaluatePreExecutionWithReason(node, state,
            new PolicyContext(null, null, "RUN-1", false));
        assertEquals(PolicyEngine.Decision.REQUIRE_APPROVAL, blocked.decision(), "HIGH risk must require approval by default");
        assertEquals("high-risk-requires-approval", blocked.ruleName(), "the firing rule must be named");

        PolicyRule.Result autoApproved = engine.evaluatePreExecutionWithReason(node, state,
            new PolicyContext(null, null, "RUN-1", true));
        assertEquals(PolicyEngine.Decision.ALLOW, autoApproved.decision(),
            "the same HIGH risk node must be allowed when autoApprove is true, proving the rule actually reads the flag");
    }

    // ---- 3. change-budget ----

    public void testChangeBudgetDeniesABrownfieldDiffExceedingTheFileCountThreshold() throws IOException {
        RealPolicyEngine engine = newEngine();
        RepoLayout layout = RepoLayout.create("policy-target-service");
        Path artifactsDirectory = layout.runsDirectory().resolve("RUN-1").resolve("artifacts");
        Files.createDirectories(artifactsDirectory);
        Files.writeString(artifactsDirectory.resolve("impact.json"), "{\"natureOfChange\": \"brownfield\"}");

        // Four files exceeds the configured maxFilesChanged of 3.
        List<String> filesWritten = List.of(
            "src/main/A.java", "src/main/B.java", "src/main/C.java", "src/main/D.java");
        for (String relativePath : filesWritten) {
            Path absolute = layout.targetServiceDirectory().resolve(relativePath);
            Files.createDirectories(absolute.getParent());
            Files.writeString(absolute, "one line");
        }

        WorkflowNode node = nodeWithWritePaths("IMPLEMENT", RiskLevel.HIGH, Set.of("src/main"));
        WorkflowState state = newState(node, List.of());
        PolicyContext context = new PolicyContext(layout.targetServiceDirectory(), layout.runsDirectory(), "RUN-1", false);

        PolicyRule.Result result = engine.evaluatePostExecution(node, state, Map.of("filesWritten", filesWritten), context);

        assertEquals(PolicyEngine.Decision.DENY, result.decision(),
            "a brownfield diff touching 4 files must be denied against a budget of 3: " + result.reason());
        assertEquals("change-budget", result.ruleName(), "the firing rule must be named");
    }

    public void testChangeBudgetAllowsTheSameSizedDiffOnAGreenfieldRun() throws IOException {
        RealPolicyEngine engine = newEngine();
        RepoLayout layout = RepoLayout.create("policy-target-service-green");
        Path artifactsDirectory = layout.runsDirectory().resolve("RUN-1").resolve("artifacts");
        Files.createDirectories(artifactsDirectory);
        Files.writeString(artifactsDirectory.resolve("impact.json"), "{\"natureOfChange\": \"greenfield\"}");

        List<String> filesWritten = List.of(
            "src/main/A.java", "src/main/B.java", "src/main/C.java", "src/main/D.java");
        for (String relativePath : filesWritten) {
            Path absolute = layout.targetServiceDirectory().resolve(relativePath);
            Files.createDirectories(absolute.getParent());
            Files.writeString(absolute, "one line");
        }

        WorkflowNode node = nodeWithWritePaths("IMPLEMENT", RiskLevel.HIGH, Set.of("src/main"));
        WorkflowState state = newState(node, List.of());
        PolicyContext context = new PolicyContext(layout.targetServiceDirectory(), layout.runsDirectory(), "RUN-1", false);

        PolicyRule.Result result = engine.evaluatePostExecution(node, state, Map.of("filesWritten", filesWritten), context);

        assertEquals(PolicyEngine.Decision.ALLOW, result.decision(),
            "the same-sized diff must be allowed on a greenfield run, since the budget is brownfield-only: "
                + result.reason());
    }

    // ---- 4. protected-paths-global ----

    public void testProtectedPathsGlobalDeniesAWriteThatEscapesTheWorkspaceViaTraversal() throws IOException {
        RealPolicyEngine engine = newEngine();
        RepoLayout layout = RepoLayout.create("policy-repo");

        WorkflowNode node = nodeWithWritePaths("IMPLEMENT", RiskLevel.HIGH, Set.of("src/main"));
        WorkflowState state = newState(node, List.of());
        PolicyContext context = new PolicyContext(layout.targetServiceDirectory(), layout.runsDirectory(), "RUN-1", false);

        List<String> filesWritten = List.of("../../etc/hosts");
        PolicyRule.Result result = engine.evaluatePostExecution(node, state, Map.of("filesWritten", filesWritten), context);

        assertEquals(PolicyEngine.Decision.DENY, result.decision(),
            "a write escaping the workspace via ../ traversal must be denied: " + result.reason());
        assertEquals("protected-paths-global", result.ruleName(), "the firing rule must be named");
        assertTrue(result.reason().contains("etc") && result.reason().contains("hosts"),
            "the audit reason must name the actual resolved path that escaped: " + result.reason());
    }

    // ---- 5. write-paths-contract ----

    public void testWritePathsContractDeniesAWriteOutsideTheNodesDeclaredPaths() {
        RealPolicyEngine engine = newEngine();
        WorkflowNode node = nodeWithWritePaths("IMPLEMENT", RiskLevel.HIGH, Set.of("target-service/src/main"));
        WorkflowState state = newState(node, List.of());
        PolicyContext context = new PolicyContext(null, null, "RUN-1", false);

        List<String> filesWritten = List.of("target-service/src/other/File.java");
        PolicyRule.Result result = engine.evaluatePostExecution(node, state, Map.of("filesWritten", filesWritten), context);

        assertEquals(PolicyEngine.Decision.DENY, result.decision(),
            "a write outside the node's declared writePaths must be denied: " + result.reason());
        assertEquals("write-paths-contract", result.ruleName(), "the firing rule must be named");
        assertTrue(result.reason().contains("target-service/src/other/File.java"),
            "the audit reason must name the offending path: " + result.reason());
        assertTrue(result.reason().contains("target-service/src/main"),
            "the audit reason must name the declared writePaths it was checked against: " + result.reason());
    }

    // ---- 6. no-secrets-in-diff ----

    public void testNoSecretsInDiffDeniesAFileContainingAnApiKeyShapedString() throws IOException {
        RealPolicyEngine engine = newEngine();
        RepoLayout layout = RepoLayout.create("policy-target-service-secret");
        Path filePath = layout.targetServiceDirectory().resolve("src/main/Config.java");
        Files.createDirectories(filePath.getParent());
        Files.writeString(filePath, "String key = \"sk-ant-api03-abcdefghijklmnopqrstuvwxyz0123456789\";");

        WorkflowNode node = nodeWithWritePaths("IMPLEMENT", RiskLevel.HIGH, Set.of("src/main"));
        WorkflowState state = newState(node, List.of());
        PolicyContext context = new PolicyContext(layout.targetServiceDirectory(), layout.runsDirectory(), "RUN-1", false);

        List<String> filesWritten = List.of("src/main/Config.java");
        PolicyRule.Result result = engine.evaluatePostExecution(node, state, Map.of("filesWritten", filesWritten), context);

        assertEquals(PolicyEngine.Decision.DENY, result.decision(),
            "a file containing an API-key-shaped string must be denied: " + result.reason());
        assertEquals("no-secrets-in-diff", result.ruleName(), "the firing rule must be named");
    }

    // ---- 7. no-dependency-additions ----

    public void testNoDependencyAdditionsRequiresApprovalWhenPomXmlIsModified() {
        RealPolicyEngine engine = newEngine();
        // No declared writePaths: write-paths-contract skips entirely (it has nothing
        // to check a node against that declares none), isolating this test to
        // no-dependency-additions specifically.
        WorkflowNode node = nodeWithRisk("IMPLEMENT", RiskLevel.HIGH);
        WorkflowState state = newState(node, List.of());
        PolicyContext context = new PolicyContext(null, null, "RUN-1", false);

        List<String> filesWritten = List.of("pom.xml");
        PolicyRule.Result result = engine.evaluatePostExecution(node, state, Map.of("filesWritten", filesWritten), context);

        assertEquals(PolicyEngine.Decision.REQUIRE_APPROVAL, result.decision(),
            "modifying pom.xml must require approval: " + result.reason());
        assertEquals("no-dependency-additions", result.ruleName(), "the firing rule must be named");
    }

    // ---- 8. evidence-before-release ----

    public void testEvidenceBeforeReleaseDeniesWhenACriterionLacksPassingEvidence() {
        RealPolicyEngine engine = newEngine();
        WorkflowNode releaseNode = nodeWithRisk("RELEASE", RiskLevel.CRITICAL);
        List<AcceptanceCriterion> criteria = List.of(
            new AcceptanceCriterion("AC-1", "covered", RiskLevel.LOW),
            new AcceptanceCriterion("AC-2", "not covered", RiskLevel.LOW));
        WorkflowState state = newState(releaseNode, criteria);
        state.addEvidence(new Evidence(Evidence.Origin.EXECUTED, "AC-1", true, "a real test ran",
            "./gradlew test", "TEST", "test-results.log", Instant.now()));
        // AC-2 has no evidence at all.

        // critical-risk-requires-approval fires first for a CRITICAL node in the normal
        // pre-execution path, so evidence-before-release is tested directly here against
        // a config where that rule is disabled, isolating this rule's own DENY branch.
        RealPolicyEngine engineWithOnlyEvidenceRule = new RealPolicyEngine(PolicyConfig.loadFromJson("""
            {
              "rules": [
                {"name": "critical-risk-requires-approval", "category": "change-control", "enabled": false},
                {"name": "high-risk-requires-approval", "category": "change-control", "enabled": false},
                {"name": "evidence-before-release", "category": "compliance", "enabled": true}
              ]
            }
            """));

        PolicyRule.Result result = engineWithOnlyEvidenceRule.evaluatePreExecutionWithReason(releaseNode, state,
            new PolicyContext(null, null, "RUN-1", false));

        assertEquals(PolicyEngine.Decision.DENY, result.decision(),
            "release must be denied when AC-2 has no passing evidence: " + result.reason());
        assertEquals("evidence-before-release", result.ruleName(), "the firing rule must be named");
        assertTrue(result.reason().contains("AC-2"), "the audit reason must name the uncovered criterion: " + result.reason());
    }

    public void testEvidenceBeforeReleaseAllowsWhenEveryCriterionHasPassingEvidence() {
        WorkflowNode releaseNode = nodeWithRisk("RELEASE", RiskLevel.CRITICAL);
        List<AcceptanceCriterion> criteria = List.of(new AcceptanceCriterion("AC-1", "covered", RiskLevel.LOW));
        WorkflowState state = newState(releaseNode, criteria);
        state.addEvidence(new Evidence(Evidence.Origin.EXECUTED, "AC-1", true, "a real test ran",
            "./gradlew test", "TEST", "test-results.log", Instant.now()));

        RealPolicyEngine engineWithOnlyEvidenceRule = new RealPolicyEngine(PolicyConfig.loadFromJson("""
            {
              "rules": [
                {"name": "critical-risk-requires-approval", "category": "change-control", "enabled": false},
                {"name": "high-risk-requires-approval", "category": "change-control", "enabled": false},
                {"name": "evidence-before-release", "category": "compliance", "enabled": true}
              ]
            }
            """));

        PolicyRule.Result result = engineWithOnlyEvidenceRule.evaluatePreExecutionWithReason(releaseNode, state,
            new PolicyContext(null, null, "RUN-1", false));

        assertEquals(PolicyEngine.Decision.ALLOW, result.decision(),
            "release must be allowed once every criterion has passing evidence: " + result.reason());
    }
}
