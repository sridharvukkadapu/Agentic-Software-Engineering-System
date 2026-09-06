package com.schwab.agentic.engine;

import static com.schwab.agentic.Assertions.assertEquals;
import static com.schwab.agentic.Assertions.assertNull;
import static com.schwab.agentic.Assertions.assertTrue;

import com.schwab.agentic.model.WorkflowState;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

/**
 * Every test here reads a hand-built {@code state.json} fixture off disk, exactly the
 * shape {@link WorkflowState#toJson} writes and {@link WorkflowState#fromJsonString}
 * reads back, never a live run: {@link RunMetrics} takes only a restored
 * {@link WorkflowState}, so a fixture built by hand and one written by a real run are, to
 * this class, indistinguishable. That is deliberate: metrics must be recomputable from
 * {@code audit.json} alone, long after the process that produced it has exited.
 */
public class RunMetricsTest {

    public void testSuccessRateOnARunWithAMixOfCompletedAndFailed() throws IOException {
        WorkflowState state = loadFixture(threeNodeRunJson(
            List.of(
                statusChange(1, "A", "PENDING", "RUNNING"),
                statusChange(2, "A", "RUNNING", "COMPLETED"),
                statusChange(3, "B", "PENDING", "RUNNING"),
                statusChange(4, "B", "RUNNING", "COMPLETED"),
                statusChange(5, "C", "PENDING", "RUNNING"),
                statusChange(6, "C", "RUNNING", "FAILED"),
                statusChange(7, "C", "FAILED", "ROLLED_BACK")),
            statuses("A", "COMPLETED", "B", "COMPLETED", "C", "ROLLED_BACK")));

        RunMetrics metrics = new RunMetrics(state);

        // 2 of 3 terminal nodes (A, B, C all terminal: COMPLETED, COMPLETED, ROLLED_BACK) are COMPLETED.
        assertEquals(2.0 / 3.0, metrics.successRate(), "success rate must be COMPLETED / terminal nodes");
    }

    public void testMttrNullWhenNoNodeRetried() throws IOException {
        WorkflowState state = loadFixture(threeNodeRunJson(
            List.of(
                statusChange(1, "A", "PENDING", "RUNNING"),
                statusChange(2, "A", "RUNNING", "COMPLETED"),
                statusChange(3, "B", "PENDING", "RUNNING"),
                statusChange(4, "B", "RUNNING", "COMPLETED"),
                statusChange(5, "C", "PENDING", "RUNNING"),
                statusChange(6, "C", "RUNNING", "COMPLETED")),
            statuses("A", "COMPLETED", "B", "COMPLETED", "C", "COMPLETED")));

        RunMetrics metrics = new RunMetrics(state);

        assertNull(metrics.mttr(), "MTTR must be null, not zero, when no node in the run ever failed");
        assertTrue(metrics.unrecoveredNodes().isEmpty(), "no node failed, so none can be unrecovered");
    }

    public void testMttrCorrectOnAHandBuiltLogWithOneNodeFailingThenSucceeding() throws IOException {
        String json = threeNodeRunJson(
            List.of(
                statusChange(1, "A", "PENDING", "RUNNING", "2026-01-01T00:00:00Z"),
                statusChange(2, "A", "RUNNING", "FAILED", "2026-01-01T00:01:00Z"),
                statusChange(3, "A", "FAILED", "PENDING", "2026-01-01T00:01:05Z"),
                statusChange(4, "A", "PENDING", "RUNNING", "2026-01-01T00:01:10Z"),
                statusChange(5, "A", "RUNNING", "COMPLETED", "2026-01-01T00:05:00Z"),
                statusChange(6, "B", "PENDING", "RUNNING", "2026-01-01T00:05:05Z"),
                statusChange(7, "B", "RUNNING", "COMPLETED", "2026-01-01T00:05:10Z")),
            statuses("A", "COMPLETED", "B", "COMPLETED", "C", "PENDING"));
        WorkflowState state = loadFixture(json);

        RunMetrics metrics = new RunMetrics(state);

        // A's first FAILED event is at 00:01:00; its next COMPLETED after that is 00:05:00.
        // That is the one and only recovery episode, so the mean equals that one episode's duration.
        assertEquals(Duration.ofMinutes(4), metrics.mttr(),
            "MTTR must equal the single recorded recovery episode's duration exactly");
        assertTrue(metrics.unrecoveredNodes().isEmpty(),
            "A did recover (it reached COMPLETED after its FAILED event), so it must not be reported unrecovered");
    }

    public void testMttrExcludesNodesThatFailedAndNeverRecovered() throws IOException {
        String json = threeNodeRunJson(
            List.of(
                statusChange(1, "A", "PENDING", "RUNNING", "2026-01-01T00:00:00Z"),
                statusChange(2, "A", "RUNNING", "FAILED", "2026-01-01T00:01:00Z"),
                statusChange(3, "A", "FAILED", "ROLLED_BACK", "2026-01-01T00:01:05Z"),
                statusChange(4, "B", "PENDING", "RUNNING", "2026-01-01T00:02:00Z"),
                statusChange(5, "B", "RUNNING", "FAILED", "2026-01-01T00:03:00Z"),
                statusChange(6, "B", "FAILED", "PENDING", "2026-01-01T00:03:05Z"),
                statusChange(7, "B", "PENDING", "RUNNING", "2026-01-01T00:03:10Z"),
                statusChange(8, "B", "RUNNING", "COMPLETED", "2026-01-01T00:03:40Z")),
            statuses("A", "ROLLED_BACK", "B", "COMPLETED", "C", "PENDING"));
        WorkflowState state = loadFixture(json);

        RunMetrics metrics = new RunMetrics(state);

        assertEquals(List.of("A"), metrics.unrecoveredNodes(),
            "A failed and rolled back with no later COMPLETED; it must be reported as unrecovered, not folded into MTTR");
        // B fails at 00:03:00 and next reaches COMPLETED at 00:03:40: a real 40-second episode.
        assertEquals(Duration.ofSeconds(40), metrics.mttr(),
            "MTTR must be computed only from B's real recovery episode, excluding A's unrecovered failure entirely");
    }

    public void testRollbackFrequencyCountsRolledBackNodes() throws IOException {
        WorkflowState state = loadFixture(threeNodeRunJson(
            List.of(
                statusChange(1, "A", "PENDING", "RUNNING"),
                statusChange(2, "A", "RUNNING", "COMPLETED"),
                statusChange(3, "B", "PENDING", "RUNNING"),
                statusChange(4, "B", "RUNNING", "FAILED"),
                statusChange(5, "B", "FAILED", "ROLLED_BACK"),
                statusChange(6, "C", "PENDING", "RUNNING"),
                statusChange(7, "C", "RUNNING", "FAILED"),
                statusChange(8, "C", "FAILED", "ROLLED_BACK")),
            statuses("A", "COMPLETED", "B", "ROLLED_BACK", "C", "ROLLED_BACK")));

        RunMetrics metrics = new RunMetrics(state);

        assertEquals(2, metrics.rollbackCount(), "exactly two nodes (B, C) rolled back");
        assertEquals(List.of("B", "C"), metrics.rolledBackNodes(), "rolledBackNodes must name exactly B and C");
        assertEquals(2.0 / 3.0, metrics.rollbackFrequency(), "rollback frequency must be rollbacks / total nodes");
    }

    public void testEndToEndLatencyIsFirstToLastAuditEvent() throws IOException {
        String json = threeNodeRunJson(
            List.of(
                statusChange(1, "A", "PENDING", "RUNNING", "2026-01-01T00:00:00Z"),
                statusChange(2, "A", "RUNNING", "COMPLETED", "2026-01-01T00:10:00Z"),
                statusChange(3, "B", "PENDING", "RUNNING", "2026-01-01T00:10:05Z"),
                statusChange(4, "B", "RUNNING", "COMPLETED", "2026-01-01T00:20:00Z")),
            statuses("A", "COMPLETED", "B", "COMPLETED", "C", "PENDING"));
        WorkflowState state = loadFixture(json);

        RunMetrics metrics = new RunMetrics(state);

        assertEquals(Duration.ofMinutes(20), metrics.endToEndLatency(),
            "end-to-end latency must be the span from the first to the last audit event's timestamp");
    }

    public void testRetryCountAndFrequency() throws IOException {
        WorkflowState state = loadFixture(threeNodeRunJson(
            List.of(
                statusChange(1, "A", "PENDING", "RUNNING"),
                statusChange(2, "A", "RUNNING", "FAILED"),
                statusChange(3, "A", "FAILED", "PENDING"),
                statusChange(4, "A", "PENDING", "RUNNING"),
                statusChange(5, "A", "RUNNING", "FAILED"),
                statusChange(6, "A", "FAILED", "PENDING"),
                statusChange(7, "A", "PENDING", "RUNNING"),
                statusChange(8, "A", "RUNNING", "COMPLETED"),
                statusChange(9, "B", "PENDING", "RUNNING"),
                statusChange(10, "B", "RUNNING", "COMPLETED")),
            statuses("A", "COMPLETED", "B", "COMPLETED", "C", "PENDING")));

        RunMetrics metrics = new RunMetrics(state);

        assertEquals(2, metrics.retryCountByNode().get("A").intValue(), "A retried exactly twice (two FAILED -> PENDING transitions)");
        assertEquals(2, metrics.totalRetryCount(), "total retries across the run must be 2");
        // Two nodes (A, B) reached RUNNING at least once; C never started.
        assertEquals(1.0, metrics.retryFrequency(), "retry frequency must be total retries / attempted nodes: 2 / 2");
    }

    // ---- fixture construction helpers ----

    private WorkflowState loadFixture(String json) throws IOException {
        Path fixtureFile = Files.createTempFile("run-metrics-fixture", ".json");
        Files.writeString(fixtureFile, json);
        return WorkflowState.fromJsonString(Files.readString(fixtureFile));
    }

    private record StatusChangeEvent(long sequence, String nodeId, String from, String to, String timestamp) {
    }

    private StatusChangeEvent statusChange(long sequence, String nodeId, String from, String to) {
        return statusChange(sequence, nodeId, from, to, "2026-01-01T00:00:" + String.format("%02d", sequence) + "Z");
    }

    private StatusChangeEvent statusChange(long sequence, String nodeId, String from, String to, String timestamp) {
        return new StatusChangeEvent(sequence, nodeId, from, to, timestamp);
    }

    private java.util.Map<String, String> statuses(String... nodeIdAndStatus) {
        java.util.Map<String, String> map = new java.util.LinkedHashMap<>();
        for (int i = 0; i < nodeIdAndStatus.length; i += 2) {
            map.put(nodeIdAndStatus[i], nodeIdAndStatus[i + 1]);
        }
        return map;
    }

    /**
     * A minimal, hand-built {@code state.json} for three nodes A, B, C (A -> B -> C, a
     * straight chain), with the given ordered status-change events and final statuses.
     * This is the exact JSON shape {@link WorkflowState#toJson} produces and
     * {@link WorkflowState#fromJsonString} restores, written out by hand rather than by
     * any live code path, per this spec's own instruction.
     */
    private String threeNodeRunJson(List<StatusChangeEvent> events, java.util.Map<String, String> finalStatuses) {
        StringBuilder nodes = new StringBuilder();
        String[] ids = {"A", "B", "C"};
        String[] dependsOn = {"[]", "[\"A\"]", "[\"B\"]"};
        for (int i = 0; i < ids.length; i++) {
            if (i > 0) {
                nodes.append(",");
            }
            nodes.append("""
                {"id":"%s","name":"%s","executor":"controllable","dependsOn":%s,
                 "entryGate":"dependencies-complete","exitGate":"artifact-written","riskLevel":"LOW",
                 "maxAttempts":3,"producesEvidenceFor":[],"fallbackExecutor":null,"writePaths":[],
                 "status":"%s"}
                """.formatted(ids[i], ids[i], dependsOn[i], finalStatuses.getOrDefault(ids[i], "PENDING")));
        }

        StringBuilder auditLog = new StringBuilder();
        for (int i = 0; i < events.size(); i++) {
            if (i > 0) {
                auditLog.append(",");
            }
            StatusChangeEvent event = events.get(i);
            auditLog.append("""
                {"sequence":%d,"runId":"FIXTURE-RUN","nodeId":"%s","type":"STATUS_CHANGE",
                 "from":"%s","to":"%s","actor":"engine","reason":"fixture event",
                 "details":{},"timestamp":"%s"}
                """.formatted(event.sequence(), event.nodeId(), event.from(), event.to(), event.timestamp()));
        }

        return """
            {"runId":"FIXTURE-RUN","startedAt":"2026-01-01T00:00:00Z","workflowStatus":"COMPLETED",
             "rollbackCount":0,"replanCount":0,"sequence":%d,
             "requirementSpec":{"id":"REQ-1","revision":1,"rawText":"req","normalizedProblem":"req normalized",
               "acceptanceCriteria":[{"id":"AC-1","description":"It works","riskLevel":"LOW"}]},
             "nodes":[%s],
             "retryCounts":[],
             "auditLog":[%s],
             "evidence":[],
             "decisions":[]}
            """.formatted(events.size(), nodes, auditLog);
    }
}
