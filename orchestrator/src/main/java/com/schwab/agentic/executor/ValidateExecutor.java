package com.schwab.agentic.executor;

import com.schwab.agentic.engine.NodeExecutor;
import com.schwab.agentic.json.Json;
import com.schwab.agentic.model.AcceptanceCriterion;
import com.schwab.agentic.model.Evidence;
import com.schwab.agentic.model.RiskLevel;
import com.schwab.agentic.model.WorkflowNode;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Makes no agent call. Every finding this executor writes comes from reading real
 * artifacts other executors left on disk and comparing them with deterministic code,
 * never from asking an agent whether things look right.
 *
 * Checks, per the spec: every acceptance criterion has passing evidence; every HIGH or
 * CRITICAL criterion has EXECUTED (not merely ASSERTED) evidence; and the implementation
 * diff touched only files the impact analysis predicted, reporting any unpredicted file
 * by name rather than silently accepting it.
 */
public final class ValidateExecutor implements NodeExecutor {

    private static final Pattern DIFF_FILE_HEADER = Pattern.compile("^--- a/(.+)$", Pattern.MULTILINE);

    private final Path artifactsDirectory;
    private final List<AcceptanceCriterion> acceptanceCriteria;
    private final List<Evidence> evidence;
    private final Path impactJsonPath;
    private final Path implementationDiffPath;

    public ValidateExecutor(Path artifactsDirectory, List<AcceptanceCriterion> acceptanceCriteria,
                             List<Evidence> evidence, Path impactJsonPath, Path implementationDiffPath) {
        this.artifactsDirectory = artifactsDirectory;
        this.acceptanceCriteria = acceptanceCriteria;
        this.evidence = evidence;
        this.impactJsonPath = impactJsonPath;
        this.implementationDiffPath = implementationDiffPath;
    }

    @Override
    public ExecutionOutput execute(WorkflowNode node, Map<String, Object> context) {
        List<String> findings = new ArrayList<>();

        List<AcceptanceCriterion> criteriaWithoutPassingEvidence = acceptanceCriteria.stream()
            .filter(criterion -> evidence.stream().noneMatch(
                item -> item.acceptanceCriterionId().equals(criterion.id()) && item.passed()))
            .toList();
        for (AcceptanceCriterion criterion : criteriaWithoutPassingEvidence) {
            findings.add("criterion " + criterion.id() + " has no passing evidence at all");
        }

        List<AcceptanceCriterion> highRiskCriteriaMissingExecutedEvidence = acceptanceCriteria.stream()
            .filter(criterion -> criterion.riskLevel() == RiskLevel.HIGH || criterion.riskLevel() == RiskLevel.CRITICAL)
            .filter(criterion -> evidence.stream().noneMatch(
                item -> item.acceptanceCriterionId().equals(criterion.id())
                    && item.passed()
                    && item.origin() == Evidence.Origin.EXECUTED))
            .toList();
        for (AcceptanceCriterion criterion : highRiskCriteriaMissingExecutedEvidence) {
            boolean hasOnlyAssertedEvidence = evidence.stream().anyMatch(
                item -> item.acceptanceCriterionId().equals(criterion.id()) && item.origin() == Evidence.Origin.ASSERTED);
            findings.add("criterion " + criterion.id() + " is " + criterion.riskLevel()
                + " risk but has no passing EXECUTED evidence"
                + (hasOnlyAssertedEvidence ? " (only ASSERTED evidence exists, which cannot satisfy this risk level)" : ""));
        }

        Set<String> predictedFiles = readPredictedFiles();
        Set<String> actuallyChangedFiles = readActuallyChangedFiles();
        Set<String> unpredictedFiles = new LinkedHashSet<>(actuallyChangedFiles);
        unpredictedFiles.removeAll(predictedFiles);
        boolean impactWasRecorded = Files.isRegularFile(impactJsonPath);
        if (impactWasRecorded) {
            for (String unpredictedFile : unpredictedFiles) {
                findings.add("file " + unpredictedFile + " was changed but was not predicted by the impact analysis");
            }
        }

        boolean allEvidenceComplete = criteriaWithoutPassingEvidence.isEmpty();
        boolean allHighRiskExecuted = highRiskCriteriaMissingExecutedEvidence.isEmpty();
        boolean noUnpredictedFiles = !impactWasRecorded || unpredictedFiles.isEmpty();
        boolean validationPassed = allEvidenceComplete && allHighRiskExecuted && noUnpredictedFiles;

        Path reportPath = artifactsDirectory.resolve("validation-report.md");
        writeFile(reportPath, buildValidationReport(validationPassed, findings));

        Path matrixPath = artifactsDirectory.resolve("traceability-matrix.md");
        writeFile(matrixPath, buildTraceabilityMatrix());

        Map<String, Object> outputs = new java.util.LinkedHashMap<>();
        outputs.put("artifactPath", reportPath.toString());
        outputs.put("traceabilityMatrixPath", matrixPath.toString());
        outputs.put("validationPassed", validationPassed);
        outputs.put("findings", findings);

        return new ExecutionOutput(validationPassed,
            validationPassed ? "validation passed" : "validation failed: " + findings.size() + " finding(s)",
            outputs);
    }

    private Set<String> readPredictedFiles() {
        if (!Files.isRegularFile(impactJsonPath)) {
            return Set.of();
        }
        Map<String, Object> parsed = readJson(impactJsonPath);
        Set<String> predicted = new LinkedHashSet<>();
        addStringsFromList(predicted, parsed.get("affectedFiles"));
        addStringsFromList(predicted, parsed.get("newFilesExpected"));
        return predicted;
    }

    private Set<String> readActuallyChangedFiles() {
        if (!Files.isRegularFile(implementationDiffPath)) {
            return Set.of();
        }
        String diffContent;
        try {
            diffContent = Files.readString(implementationDiffPath);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read " + implementationDiffPath, e);
        }
        Set<String> changedFiles = new LinkedHashSet<>();
        Matcher matcher = DIFF_FILE_HEADER.matcher(diffContent);
        while (matcher.find()) {
            changedFiles.add(matcher.group(1));
        }
        return changedFiles;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> readJson(Path path) {
        try {
            String content = Files.readString(path);
            return content.isBlank() ? Map.of() : (Map<String, Object>) Json.parse(content);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read " + path, e);
        }
    }

    private void addStringsFromList(Set<String> target, Object listValue) {
        if (listValue instanceof List<?> list) {
            for (Object item : list) {
                if (item != null) {
                    target.add(String.valueOf(item));
                }
            }
        }
    }

    private String buildValidationReport(boolean passed, List<String> findings) {
        StringBuilder report = new StringBuilder();
        report.append("# Validation report\n\n");
        report.append("**Result:** ").append(passed ? "PASSED" : "FAILED").append("\n\n");
        report.append("## Findings\n\n");
        if (findings.isEmpty()) {
            report.append("No findings.\n");
        } else {
            for (String finding : findings) {
                report.append("- ").append(finding).append('\n');
            }
        }
        return report.toString();
    }

    private String buildTraceabilityMatrix() {
        StringBuilder matrix = new StringBuilder();
        matrix.append("# Traceability matrix\n\n");
        matrix.append("| Criterion | Evidence | Origin | Passed | Artifact |\n");
        matrix.append("|---|---|---|---|---|\n");
        for (AcceptanceCriterion criterion : acceptanceCriteria) {
            List<Evidence> evidenceForCriterion = evidence.stream()
                .filter(item -> item.acceptanceCriterionId().equals(criterion.id()))
                .toList();
            if (evidenceForCriterion.isEmpty()) {
                matrix.append("| ").append(criterion.id()).append(" | (none) | | | |\n");
                continue;
            }
            for (Evidence item : evidenceForCriterion) {
                matrix.append("| ").append(criterion.id()).append(" | ")
                    .append(item.description()).append(" | ")
                    .append(item.origin()).append(" | ")
                    .append(item.passed()).append(" | ")
                    .append(item.artifactPath()).append(" |\n");
            }
        }
        return matrix.toString();
    }

    private void writeFile(Path path, String content) {
        try {
            Files.createDirectories(path.getParent());
            Files.writeString(path, content);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to write " + path, e);
        }
    }
}
