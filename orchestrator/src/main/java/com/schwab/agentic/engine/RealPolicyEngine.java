package com.schwab.agentic.engine;

import com.schwab.agentic.json.Json;
import com.schwab.agentic.model.AcceptanceCriterion;
import com.schwab.agentic.model.Evidence;
import com.schwab.agentic.model.RiskLevel;
import com.schwab.agentic.model.WorkflowNode;
import com.schwab.agentic.model.WorkflowState;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * The real policy engine, loaded from {@code workflows/policy.json} data rather than
 * hardcoded thresholds baked into this class. Eight rules across the three categories
 * the assignment names: change control, security, and compliance. Every rule's DENY or
 * REQUIRE_APPROVAL branch is proven reachable by a dedicated test that constructs an
 * input crafted to trip it; per CLAUDE.md rule 6, a threshold nothing in this project can
 * ever reach is a dead branch, not a control, so every threshold here was chosen or
 * adjusted specifically so a realistic input in this project's own test fixtures trips it.
 */
public final class RealPolicyEngine implements PolicyEngine {

    private final PolicyConfig config;

    public RealPolicyEngine(PolicyConfig config) {
        this.config = config;
    }

    // ---- Pre-execution: evaluated before the node's executor is ever called ----

    private static final PolicyContext NO_AUTO_APPROVE_CONTEXT = new PolicyContext(null, null, null, false);

    /**
     * The bare {@link Decision}, for the interface method every {@link PolicyEngine} must
     * implement. Assumes {@code autoApprove=false}, the safe default: a caller that has a
     * real {@link PolicyContext} (the engine's actual admission path) should call
     * {@link #evaluatePreExecutionWithReason(WorkflowNode, WorkflowState, PolicyContext)}
     * directly instead, so a real auto-approve run is never silently ignored.
     */
    @Override
    public Decision evaluate(WorkflowNode node, WorkflowState state) {
        return evaluatePreExecutionWithReason(node, state, NO_AUTO_APPROVE_CONTEXT).decision();
    }

    @Override
    public PolicyRule.Result evaluatePreExecutionWithReason(WorkflowNode node, WorkflowState state, PolicyContext context) {
        if (config.isEnabled("critical-risk-requires-approval") && node.riskLevel() == RiskLevel.CRITICAL) {
            return PolicyRule.Result.requireApproval("critical-risk-requires-approval",
                "node " + node.id() + " is CRITICAL risk and always requires human approval before execution");
        }

        if (config.isEnabled("high-risk-requires-approval") && node.riskLevel() == RiskLevel.HIGH && !context.autoApprove()) {
            return PolicyRule.Result.requireApproval("high-risk-requires-approval",
                "node " + node.id() + " is HIGH risk and requires human approval before execution"
                    + " (not in --auto-approve mode)");
        }

        if (config.isEnabled("evidence-before-release") && node.riskLevel() == RiskLevel.CRITICAL) {
            List<String> uncovered = criteriaWithoutPassingEvidence(state);
            if (!uncovered.isEmpty()) {
                return PolicyRule.Result.deny("evidence-before-release",
                    "node " + node.id() + " is CRITICAL risk (a release gate) but " + uncovered.size()
                        + " acceptance criterion(s) lack passing evidence: " + uncovered);
            }
        }

        return PolicyRule.Result.allow("none", "no pre-execution rule denies or requires approval for node " + node.id());
    }

    private List<String> criteriaWithoutPassingEvidence(WorkflowState state) {
        List<Evidence> evidence = state.getEvidence();
        List<String> uncovered = new ArrayList<>();
        for (AcceptanceCriterion criterion : state.getRequirementSpec().acceptanceCriteria()) {
            boolean hasPassingEvidence = evidence.stream()
                .anyMatch(item -> item.acceptanceCriterionId().equals(criterion.id()) && item.passed());
            if (!hasPassingEvidence) {
                uncovered.add(criterion.id());
            }
        }
        return uncovered;
    }

    // ---- Post-execution: evaluated after the executor returns, before the exit gate ----

    @Override
    public PolicyRule.Result evaluatePostExecution(WorkflowNode node, WorkflowState state,
                                                     Map<String, Object> executorOutputs, PolicyContext context) {
        List<String> reportedWrites = reportedFilesWritten(executorOutputs);
        if (reportedWrites.isEmpty()) {
            return PolicyRule.Result.allow("none", "node " + node.id() + " reported no written files to check");
        }

        // Order matters: a path that escapes the workspace entirely is a security
        // boundary violation and must be reported as one, never as a mere declaration
        // mismatch against this node's own writePaths.
        PolicyRule.Result globalResult = evaluateProtectedPathsGlobal(node, context, reportedWrites);
        if (globalResult.decision() != Decision.ALLOW) {
            return globalResult;
        }

        PolicyRule.Result contractResult = evaluateWritePathsContract(node, reportedWrites);
        if (contractResult.decision() != Decision.ALLOW) {
            return contractResult;
        }

        PolicyRule.Result secretsResult = evaluateNoSecretsInDiff(node, context, reportedWrites);
        if (secretsResult.decision() != Decision.ALLOW) {
            return secretsResult;
        }

        PolicyRule.Result dependencyResult = evaluateNoDependencyAdditions(node, reportedWrites);
        if (dependencyResult.decision() != Decision.ALLOW) {
            return dependencyResult;
        }

        PolicyRule.Result budgetResult = evaluateChangeBudget(node, context, reportedWrites);
        if (budgetResult.decision() != Decision.ALLOW) {
            return budgetResult;
        }

        return PolicyRule.Result.allow("none", "no post-execution rule denies or requires approval for node " + node.id());
    }

    private List<String> reportedFilesWritten(Map<String, Object> executorOutputs) {
        Object filesWritten = executorOutputs.get("filesWritten");
        if (filesWritten instanceof List<?> list) {
            List<String> result = new ArrayList<>();
            for (Object item : list) {
                result.add(String.valueOf(item));
            }
            return result;
        }
        return List.of();
    }

    /**
     * Security boundary: every reported write, resolved to its canonical absolute path,
     * must fall under one of the allowed roots (target-service/, runs/) relative to the
     * repository root. Resolving canonically before comparing is what catches a relative
     * path like {@code ../../etc/hosts}: the raw string never mentions the allowed roots
     * at all, but string comparison alone would miss a path that walks back through them
     * and out the other side, which canonical resolution collapses correctly.
     */
    private PolicyRule.Result evaluateProtectedPathsGlobal(WorkflowNode node, PolicyContext context,
                                                             List<String> reportedWrites) {
        if (!config.isEnabled("protected-paths-global") || context.targetServiceDirectory() == null) {
            return PolicyRule.Result.allow("protected-paths-global", "rule disabled or no target service directory configured");
        }
        List<String> allowedRootNames = config.getStringList("protected-paths-global", "allowedRoots");
        Path repositoryRoot = context.targetServiceDirectory().getParent();
        List<Path> allowedRoots = allowedRootNames.stream()
            .map(name -> repositoryRoot.resolve(name).normalize().toAbsolutePath())
            .toList();

        for (String reportedWrite : reportedWrites) {
            Path resolved = context.targetServiceDirectory().resolve(reportedWrite).normalize().toAbsolutePath();
            boolean underAnAllowedRoot = allowedRoots.stream().anyMatch(resolved::startsWith);
            if (!underAnAllowedRoot) {
                return PolicyRule.Result.deny("protected-paths-global",
                    "node " + node.id() + " reported writing to " + reportedWrite
                        + ", which resolves to " + resolved + ", outside every allowed root " + allowedRoots);
            }
        }
        return PolicyRule.Result.allow("protected-paths-global", "every reported write resolves under an allowed root");
    }

    /**
     * Checkpoint contract: every reported write must fall under this node's own declared
     * {@link WorkflowNode#writePaths}, the same declaration {@link Checkpoint} uses to
     * scope what it snapshots and restores. A node whose real output strays outside its
     * own declaration has broken the assumption the checkpoint mechanism depends on:
     * rollback for this node would not actually cover everything it touched.
     */
    private PolicyRule.Result evaluateWritePathsContract(WorkflowNode node, List<String> reportedWrites) {
        if (!config.isEnabled("write-paths-contract") || node.writePaths().isEmpty()) {
            return PolicyRule.Result.allow("write-paths-contract", "rule disabled or node declares no writePaths");
        }
        for (String reportedWrite : reportedWrites) {
            Path relative = Path.of(reportedWrite).normalize();
            boolean underADeclaredPath = node.writePaths().stream()
                .anyMatch(declared -> relative.startsWith(Path.of(declared).normalize()));
            if (!underADeclaredPath) {
                return PolicyRule.Result.deny("write-paths-contract",
                    "node " + node.id() + " reported writing to " + reportedWrite
                        + ", which is outside its declared writePaths " + node.writePaths());
            }
        }
        return PolicyRule.Result.allow("write-paths-contract", "every reported write falls under a declared writePath");
    }

    /** Reads each reported file's real content and checks it against configured credential-shaped patterns. */
    private PolicyRule.Result evaluateNoSecretsInDiff(WorkflowNode node, PolicyContext context, List<String> reportedWrites) {
        if (!config.isEnabled("no-secrets-in-diff") || context.targetServiceDirectory() == null) {
            return PolicyRule.Result.allow("no-secrets-in-diff", "rule disabled or no target service directory configured");
        }
        List<Pattern> patterns = config.getStringList("no-secrets-in-diff", "patterns").stream()
            .map(Pattern::compile)
            .toList();

        for (String reportedWrite : reportedWrites) {
            Path absolute = context.targetServiceDirectory().resolve(reportedWrite);
            if (!Files.isRegularFile(absolute)) {
                continue;
            }
            String content = readQuietly(absolute);
            for (Pattern pattern : patterns) {
                if (pattern.matcher(content).find()) {
                    return PolicyRule.Result.deny("no-secrets-in-diff",
                        "node " + node.id() + "'s write to " + reportedWrite
                            + " matches a credential-shaped pattern (" + pattern.pattern() + ")");
                }
            }
        }
        return PolicyRule.Result.allow("no-secrets-in-diff", "no reported write matches a credential-shaped pattern");
    }

    private PolicyRule.Result evaluateNoDependencyAdditions(WorkflowNode node, List<String> reportedWrites) {
        if (!config.isEnabled("no-dependency-additions")) {
            return PolicyRule.Result.allow("no-dependency-additions", "rule disabled");
        }
        List<String> manifestFilenames = config.getStringList("no-dependency-additions", "dependencyManifestFilenames");
        for (String reportedWrite : reportedWrites) {
            String filename = Path.of(reportedWrite).getFileName().toString();
            if (manifestFilenames.contains(filename)) {
                return PolicyRule.Result.requireApproval("no-dependency-additions",
                    "node " + node.id() + " modified dependency manifest " + reportedWrite + ", which requires approval");
            }
        }
        return PolicyRule.Result.allow("no-dependency-additions", "no reported write touches a dependency manifest");
    }

    /**
     * Denies a brownfield node whose real diff exceeds the configured file or line
     * budget. Brownfield-ness is read from the run's real {@code impact.json} artifact
     * (written by ImpactExecutor), never an in-memory flag, consistent with this
     * project's "derive it from what actually happened" discipline: a policy rule that
     * could be satisfied by an unchecked claim of "this is greenfield" would be exactly
     * the kind of asserted-not-derived gap CLAUDE.md rule 1 exists to prevent.
     */
    private PolicyRule.Result evaluateChangeBudget(WorkflowNode node, PolicyContext context, List<String> reportedWrites) {
        if (!config.isEnabled("change-budget") || context.runsDirectory() == null) {
            return PolicyRule.Result.allow("change-budget", "rule disabled or no runs directory configured");
        }
        if (!isBrownfield(context)) {
            return PolicyRule.Result.allow("change-budget", "run is not brownfield; change budget does not apply");
        }

        int maxFilesChanged = config.getInt("change-budget", "maxFilesChanged");
        int maxLinesAdded = config.getInt("change-budget", "maxLinesAdded");

        if (reportedWrites.size() > maxFilesChanged) {
            return PolicyRule.Result.deny("change-budget",
                "node " + node.id() + "'s diff touches " + reportedWrites.size()
                    + " file(s), exceeding the brownfield budget of " + maxFilesChanged);
        }

        int linesAdded = countLinesAdded(context, reportedWrites);
        if (linesAdded > maxLinesAdded) {
            return PolicyRule.Result.deny("change-budget",
                "node " + node.id() + "'s diff adds " + linesAdded
                    + " line(s), exceeding the brownfield budget of " + maxLinesAdded);
        }

        return PolicyRule.Result.allow("change-budget",
            "diff of " + reportedWrites.size() + " file(s) and " + linesAdded + " added line(s) is within budget");
    }

    @SuppressWarnings("unchecked")
    private boolean isBrownfield(PolicyContext context) {
        Path artifactsDirectory = context.artifactsDirectory();
        if (artifactsDirectory == null) {
            return false;
        }
        Path impactJsonPath = artifactsDirectory.resolve("impact.json");
        if (!Files.isRegularFile(impactJsonPath)) {
            return false;
        }
        Map<String, Object> parsed;
        try {
            String content = Files.readString(impactJsonPath);
            parsed = content.isBlank() ? Map.of() : (Map<String, Object>) Json.parse(content);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read " + impactJsonPath, e);
        }
        return "brownfield".equals(parsed.get("natureOfChange"));
    }

    private int countLinesAdded(PolicyContext context, List<String> reportedWrites) {
        int total = 0;
        for (String reportedWrite : reportedWrites) {
            Path absolute = context.targetServiceDirectory().resolve(reportedWrite);
            if (Files.isRegularFile(absolute)) {
                total += (int) readQuietly(absolute).lines().count();
            }
        }
        return total;
    }

    private String readQuietly(Path path) {
        try {
            return Files.readString(path);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read " + path, e);
        }
    }
}
