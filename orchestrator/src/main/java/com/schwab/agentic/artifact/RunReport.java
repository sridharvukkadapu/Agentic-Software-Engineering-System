package com.schwab.agentic.artifact;

import com.schwab.agentic.engine.RunMetrics;
import com.schwab.agentic.graph.WorkflowGraph;
import com.schwab.agentic.model.AcceptanceCriterion;
import com.schwab.agentic.model.Evidence;
import com.schwab.agentic.model.WorkflowState;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Renders a run's metrics, workflow graph, and traceability matrix as a single Markdown
 * document, computed entirely from a {@link WorkflowState} (in practice, one restored
 * from a persisted {@code state.json}/{@code audit.json}, never a live run object this
 * class holds onto), so the same report can be produced again later from disk alone and
 * always agree with itself. Every number in the metrics section comes from
 * {@link RunMetrics}, which itself only ever reads {@link WorkflowState#getAuditLog}; this
 * class adds no computation of its own beyond formatting.
 *
 * This is the reduced scope of the full spec 08 report: metrics, the Mermaid graph, and
 * the traceability matrix. Decision lineage, the re-plan section, approvals, policy
 * events, and the cross-run summary are out of scope for this pass.
 */
public final class RunReport {

    private final WorkflowState state;

    public RunReport(WorkflowState state) {
        this.state = state;
    }

    public String render() {
        StringBuilder report = new StringBuilder();
        report.append("# Run report: ").append(state.getRunId()).append("\n\n");
        report.append(renderMetricsSection());
        report.append('\n');
        report.append(renderGraphSection());
        report.append('\n');
        report.append(renderTraceabilitySection());
        return report.toString();
    }

    private String renderMetricsSection() {
        RunMetrics metrics = new RunMetrics(state);
        StringBuilder section = new StringBuilder();
        section.append("## Metrics\n\n");
        section.append("| Metric | Value |\n");
        section.append("|---|---|\n");
        section.append("| Success rate | ").append(formatPercent(metrics.successRate())).append(" |\n");
        section.append("| Total retries | ").append(metrics.totalRetryCount()).append(" |\n");
        section.append("| Retry frequency (per attempted node) | ")
            .append(formatDecimal(metrics.retryFrequency())).append(" |\n");
        section.append("| Rollback count | ").append(metrics.rollbackCount()).append(" |\n");
        section.append("| Rollback frequency (per node) | ")
            .append(formatDecimal(metrics.rollbackFrequency())).append(" |\n");
        section.append("| End-to-end latency | ").append(formatDuration(metrics.endToEndLatency())).append(" |\n");
        Duration mttr = metrics.mttr();
        section.append("| MTTR | ").append(mttr == null ? "null (no node needed more than one attempt)"
            : formatDuration(mttr)).append(" |\n");
        List<String> unrecovered = metrics.unrecoveredNodes();
        if (!unrecovered.isEmpty()) {
            section.append("| Unrecovered nodes (failed, never later completed) | ")
                .append(String.join(", ", unrecovered)).append(" |\n");
        }
        return section.toString();
    }

    /**
     * Delegates to {@link WorkflowGraph#toMermaid(java.util.Map)}, the same renderer spec
     * 09's architecture documentation uses for a structure-only diagram, so the node ids
     * and edges in a run report's graph can never drift from what that renderer produces
     * given the same graph: only the status labels differ, and those come from this run's
     * own real final statuses, not asserted text.
     */
    private String renderGraphSection() {
        WorkflowGraph graph = WorkflowGraph.of(new ArrayList<>(state.getNodes().values()));
        StringBuilder section = new StringBuilder();
        section.append("## Workflow graph\n\n");
        section.append("```mermaid\n");
        section.append(graph.toMermaid(state.getStatuses()));
        section.append("```\n");
        return section.toString();
    }

    private String renderTraceabilitySection() {
        StringBuilder section = new StringBuilder();
        section.append("## Traceability matrix\n\n");
        section.append("| Criterion | Evidence | Origin | Passed | Artifact |\n");
        section.append("|---|---|---|---|---|\n");
        List<Evidence> evidence = state.getEvidence();
        for (AcceptanceCriterion criterion : state.getRequirementSpec().acceptanceCriteria()) {
            List<Evidence> evidenceForCriterion = evidence.stream()
                .filter(item -> item.acceptanceCriterionId().equals(criterion.id()))
                .toList();
            if (evidenceForCriterion.isEmpty()) {
                section.append("| ").append(criterion.id()).append(" | (none) | | | |\n");
                continue;
            }
            for (Evidence item : evidenceForCriterion) {
                section.append("| ").append(criterion.id()).append(" | ")
                    .append(item.description()).append(" | ")
                    .append(item.origin()).append(" | ")
                    .append(item.passed()).append(" | ")
                    .append(item.artifactPath()).append(" |\n");
            }
        }
        return section.toString();
    }

    private String formatPercent(double ratio) {
        return String.format("%.1f%%", ratio * 100.0);
    }

    private String formatDecimal(double value) {
        return String.format("%.2f", value);
    }

    private String formatDuration(Duration duration) {
        long totalSeconds = duration.toSeconds();
        long hours = totalSeconds / 3600;
        long minutes = (totalSeconds % 3600) / 60;
        long seconds = totalSeconds % 60;
        if (hours > 0) {
            return String.format("%dh %dm %ds", hours, minutes, seconds);
        }
        if (minutes > 0) {
            return String.format("%dm %ds", minutes, seconds);
        }
        return String.format("%d.%03ds", seconds, duration.toMillisPart());
    }
}
