package com.schwab.agentic.executor;

import static com.schwab.agentic.Assertions.assertTrue;

import com.schwab.agentic.engine.NodeExecutor;
import com.schwab.agentic.model.AcceptanceCriterion;
import com.schwab.agentic.model.AuditEvent;
import com.schwab.agentic.model.NodeStatus;
import com.schwab.agentic.model.RequirementSpec;
import com.schwab.agentic.model.RiskLevel;
import com.schwab.agentic.model.WorkflowNode;
import com.schwab.agentic.model.WorkflowState;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Covers {@link ReleaseExecutor}, which makes no agent call and reads readiness purely
 * from the audit log: validation passed, no POLICY_DENIED events, and every node that
 * actually entered WAITING_APPROVAL during this run also has a matching
 * APPROVAL_GRANTED event, derived rather than declared by any static configuration.
 */
public class ReleaseExecutorTest {

    /**
     * {@code validationPassed} controls whether VALIDATE is driven to COMPLETED (a real
     * transition through RUNNING first, since PENDING -> COMPLETED is not a legal edge)
     * or left at FAILED, standing in for what a real VALIDATE node's own exit gate would
     * have decided before RELEASE ever runs. {@link ReleaseExecutor} reads this back via
     * {@code workflowState.getStatus("VALIDATE")}, never a constructor argument, exactly
     * as the real CLI's registry-registered instance would.
     */
    private static WorkflowState newState(boolean validationPassed) {
        WorkflowNode approvalNode = new WorkflowNode("IMPLEMENT", "Implementation", "implement", Set.of(),
            null, null, RiskLevel.HIGH, 1, Set.of());
        RequirementSpec requirementSpec = new RequirementSpec(
            "REQ-1", 1, "req", "req normalized",
            List.of(new AcceptanceCriterion("AC-1", "criterion", RiskLevel.LOW)));
        WorkflowState state = new WorkflowState("RUN-1", requirementSpec,
            List.of(approvalNode, TestExecutorFixtures.validateNode()));
        state.transition("VALIDATE", NodeStatus.RUNNING, "test", "starting VALIDATE");
        state.transition("VALIDATE", validationPassed ? NodeStatus.COMPLETED : NodeStatus.FAILED, "test",
            validationPassed ? "validation passed" : "validation failed");
        return state;
    }

    public void testValidationPassedNoDenialsNoApprovalsNeededIsReleaseReady() throws IOException {
        Path artifactsDir = Files.createTempDirectory("executor-artifacts-release-1");
        WorkflowState state = newState(true);

        ReleaseExecutor executor = new ReleaseExecutor(artifactsDir, state);
        NodeExecutor.ExecutionOutput output = executor.execute(TestExecutorFixtures.releaseNode(), Map.of());

        assertTrue(output.executorReportedSuccess(), "with no denials and no pending approvals, release must be ready");
    }

    public void testValidationFailedBlocksRelease() throws IOException {
        Path artifactsDir = Files.createTempDirectory("executor-artifacts-release-2");
        WorkflowState state = newState(false);

        ReleaseExecutor executor = new ReleaseExecutor(artifactsDir, state);
        NodeExecutor.ExecutionOutput output = executor.execute(TestExecutorFixtures.releaseNode(), Map.of());

        assertTrue(!output.executorReportedSuccess(), "release must not be ready when validation failed");
        String report = Files.readString(artifactsDir.resolve("release-readiness.md"));
        assertTrue(report.contains("validation did not pass"), "the report must state why release is blocked");
    }

    public void testPolicyDenialInAuditLogBlocksRelease() throws IOException {
        Path artifactsDir = Files.createTempDirectory("executor-artifacts-release-3");
        WorkflowState state = newState(true);
        state.record(AuditEvent.EventType.POLICY_DENIED, "IMPLEMENT", "policy", "risk too high for auto-approval",
            Map.of());

        ReleaseExecutor executor = new ReleaseExecutor(artifactsDir, state);
        NodeExecutor.ExecutionOutput output = executor.execute(TestExecutorFixtures.releaseNode(), Map.of());

        assertTrue(!output.executorReportedSuccess(), "a recorded policy denial must block release");
        String report = Files.readString(artifactsDir.resolve("release-readiness.md"));
        assertTrue(report.contains("policy denial"), "the report must mention the policy denial");
        assertTrue(report.contains("IMPLEMENT"), "the report must name the node the denial applied to");
    }

    public void testNodeThatEnteredWaitingApprovalWithNoGrantedEventBlocksRelease() throws IOException {
        Path artifactsDir = Files.createTempDirectory("executor-artifacts-release-4");
        WorkflowState state = newState(true);
        state.transition("IMPLEMENT", NodeStatus.WAITING_APPROVAL, "policy", "high risk node requires approval");

        ReleaseExecutor executor = new ReleaseExecutor(artifactsDir, state);
        NodeExecutor.ExecutionOutput output = executor.execute(TestExecutorFixtures.releaseNode(), Map.of());

        assertTrue(!output.executorReportedSuccess(),
            "a node that entered WAITING_APPROVAL but was never actually granted approval must block release");
        String report = Files.readString(artifactsDir.resolve("release-readiness.md"));
        assertTrue(report.contains("IMPLEMENT"), "the report must name the node still awaiting approval");
    }

    /**
     * The important negative-adjacent case: a node entering WAITING_APPROVAL and later
     * receiving a real APPROVAL_GRANTED event for that same node id must NOT block
     * release, proving this executor checks for the actual grant rather than simply
     * refusing to release any run that ever paused for approval.
     */
    public void testNodeThatEnteredWaitingApprovalAndWasGrantedApprovalDoesNotBlockRelease() throws IOException {
        Path artifactsDir = Files.createTempDirectory("executor-artifacts-release-5");
        WorkflowState state = newState(true);
        state.transition("IMPLEMENT", NodeStatus.WAITING_APPROVAL, "policy", "high risk node requires approval");
        state.record(AuditEvent.EventType.APPROVAL_GRANTED, "IMPLEMENT", "human:reviewer",
            "approved after manual review", Map.of());

        ReleaseExecutor executor = new ReleaseExecutor(artifactsDir, state);
        NodeExecutor.ExecutionOutput output = executor.execute(TestExecutorFixtures.releaseNode(), Map.of());

        assertTrue(output.executorReportedSuccess(),
            "a node that received a real APPROVAL_GRANTED event for its own id must not block release: "
                + output.summary());
    }
}
