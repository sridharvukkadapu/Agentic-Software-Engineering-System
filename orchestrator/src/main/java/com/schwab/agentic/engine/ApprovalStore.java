package com.schwab.agentic.engine;

import com.schwab.agentic.json.Json;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Persists every approval decision for a run to {@code runs/<runId>/approvals.json}, and
 * answers the one question everything else in spec 05 depends on: is there a currently
 * valid approval for this node at this exact requirement revision?
 *
 * Approvals are appended, never overwritten or deleted: a node approved at revision 1,
 * denied at revision 1 after a re-plan, then approved again at revision 2 keeps all three
 * records, since the full history of who decided what and when is itself part of the
 * audit trail this project exists to produce. {@link #hasValidApproval} always answers
 * from the most recent record for a given (nodeId, revision) pair, so a later decision
 * correctly supersedes an earlier one at the same revision without deleting the earlier
 * one's evidence that it happened.
 */
public final class ApprovalStore {

    private final List<ApprovalRecord> records = new ArrayList<>();

    /** Records a new approval or denial, appending it to this store's in-memory list. */
    public void record(ApprovalRecord record) {
        if (record == null) {
            throw new IllegalArgumentException("ApprovalStore.record record must not be null");
        }
        records.add(record);
    }

    /** Every approval record this store holds, in the order they were recorded. */
    public List<ApprovalRecord> getRecords() {
        return List.copyOf(records);
    }

    /**
     * Whether the most recent decision for {@code nodeId} at exactly
     * {@code requirementRevision} is {@link ApprovalRecord.Decision#APPROVED}. An
     * approval recorded against any other revision, earlier or later, never satisfies
     * this check: a stale approval from before a re-plan amended the requirement is not
     * silently reinterpreted as covering the new revision, and a future-dated approval
     * (which should never happen in practice, but is not this method's place to assume
     * cannot) is equally not treated as covering an earlier revision it was never
     * actually granted against.
     */
    public boolean hasValidApproval(String nodeId, int requirementRevision) {
        ApprovalRecord.Decision latest = null;
        for (ApprovalRecord record : records) {
            if (record.nodeId().equals(nodeId) && record.requirementRevision() == requirementRevision) {
                latest = record.decision();
            }
        }
        return latest == ApprovalRecord.Decision.APPROVED;
    }

    /** Writes this store's records to {@code runs/<runId>/approvals.json}, replacing any existing file. */
    public void saveToFile(Path runsDirectory, String runId) {
        Path approvalsPath = runsDirectory.resolve(runId).resolve("approvals.json");
        try {
            Files.createDirectories(approvalsPath.getParent());
            Files.writeString(approvalsPath, Json.write(toJson()));
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to write " + approvalsPath, e);
        }
    }

    /** Loads a store from {@code runs/<runId>/approvals.json}, or an empty store if the file does not exist yet. */
    public static ApprovalStore loadFromFile(Path runsDirectory, String runId) {
        Path approvalsPath = runsDirectory.resolve(runId).resolve("approvals.json");
        if (!Files.isRegularFile(approvalsPath)) {
            return new ApprovalStore();
        }
        String json;
        try {
            json = Files.readString(approvalsPath);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read " + approvalsPath, e);
        }
        return fromJsonString(json);
    }

    @SuppressWarnings("unchecked")
    public static ApprovalStore fromJsonString(String json) {
        ApprovalStore store = new ApprovalStore();
        if (json.isBlank()) {
            return store;
        }
        Map<String, Object> root = (Map<String, Object>) Json.parse(json);
        for (Object recordObj : (List<Object>) root.getOrDefault("approvals", List.of())) {
            Map<String, Object> recordJson = (Map<String, Object>) recordObj;
            store.records.add(new ApprovalRecord(
                (String) recordJson.get("nodeId"),
                ((Double) recordJson.get("requirementRevision")).intValue(),
                ApprovalRecord.Decision.valueOf((String) recordJson.get("decision")),
                (String) recordJson.get("approver"),
                (String) recordJson.get("reason"),
                Instant.parse((String) recordJson.get("decidedAt"))));
        }
        return store;
    }

    private Map<String, Object> toJson() {
        List<Object> approvalsJson = new ArrayList<>();
        for (ApprovalRecord record : records) {
            Map<String, Object> recordJson = new LinkedHashMap<>();
            recordJson.put("nodeId", record.nodeId());
            recordJson.put("requirementRevision", (double) record.requirementRevision());
            recordJson.put("decision", record.decision().name());
            recordJson.put("approver", record.approver());
            recordJson.put("reason", record.reason());
            recordJson.put("decidedAt", record.decidedAt().toString());
            approvalsJson.add(recordJson);
        }
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("approvals", approvalsJson);
        return root;
    }
}
