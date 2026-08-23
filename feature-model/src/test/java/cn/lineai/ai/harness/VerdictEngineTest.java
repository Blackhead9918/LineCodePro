package cn.lineai.ai.harness;

import static org.junit.Assert.*;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.junit.Test;

import cn.lineai.model.harness.AgentTask;
import cn.lineai.model.harness.EvidenceLevel;
import cn.lineai.model.harness.ExecutionProfile;
import cn.lineai.model.harness.TaskStatus;
import cn.lineai.model.harness.TaskVerificationPolicy;
import cn.lineai.model.harness.TaskVerdict;

/**
 * Unit tests for {@link VerdictEngine} (§45.3, §45.21).
 *
 * <p>Tests: verdict computation, profile downgrade, conflict/empty evidence,
 * self-evaluation invariant, completion claim acceptance.
 */
public class VerdictEngineTest {

    // ---- Basic verdict computation ----

    @Test
    public void no_evidence_returns_unverified() {
        AgentTask task = buildTask(ExecutionProfile.LOCAL, TaskVerificationPolicy.LIGHT);
        TaskVerdict v = VerdictEngine.evaluate(task, Collections.emptyList());
        assertEquals(TaskVerdict.UNVERIFIED, v);
    }

    @Test
    public void null_evidence_returns_unverified() {
        AgentTask task = buildTask(ExecutionProfile.LOCAL, TaskVerificationPolicy.LIGHT);
        TaskVerdict v = VerdictEngine.evaluate(task, null);
        assertEquals(TaskVerdict.UNVERIFIED, v);
    }

    @Test
    public void tool_error_returns_failed() {
        AgentTask task = buildTask(ExecutionProfile.LOCAL, TaskVerificationPolicy.LIGHT);
        List<VerdictEngine.EvidenceItem> evidence = Arrays.asList(
                new VerdictEngine.EvidenceItem(
                        VerdictEngine.EvidenceItem.Category.TOOL_RESULT,
                        EvidenceLevel.TOOL_OBSERVATION, true, "FileWriteTool: error"));
        TaskVerdict v = VerdictEngine.evaluate(task, evidence);
        assertEquals(TaskVerdict.FAILED, v);
    }

    @Test
    public void light_policy_with_tool_success_returns_partially_verified() {
        AgentTask task = buildTask(ExecutionProfile.LOCAL, TaskVerificationPolicy.LIGHT);
        List<VerdictEngine.EvidenceItem> evidence = Arrays.asList(
                new VerdictEngine.EvidenceItem(
                        VerdictEngine.EvidenceItem.Category.TOOL_RESULT,
                        EvidenceLevel.TOOL_OBSERVATION, false, "FileWriteTool: ok"));
        TaskVerdict v = VerdictEngine.evaluate(task, evidence);
        assertEquals(TaskVerdict.PARTIALLY_VERIFIED, v);
    }

    @Test
    public void build_policy_with_build_success_returns_verified() {
        AgentTask task = buildTask(ExecutionProfile.SSH, TaskVerificationPolicy.BUILD);
        List<VerdictEngine.EvidenceItem> evidence = Arrays.asList(
                new VerdictEngine.EvidenceItem(
                        VerdictEngine.EvidenceItem.Category.DIFF_RESULT,
                        EvidenceLevel.TOOL_OBSERVATION, false, "Diff recorded"),
                new VerdictEngine.EvidenceItem(
                        VerdictEngine.EvidenceItem.Category.BUILD_RESULT,
                        EvidenceLevel.DETERMINISTIC_CHECK, false, "Build: SUCCESS"));
        TaskVerdict v = VerdictEngine.evaluate(task, evidence);
        assertEquals(TaskVerdict.VERIFIED, v);
    }

    @Test
    public void build_and_test_with_both_pass_returns_verified() {
        AgentTask task = buildTask(ExecutionProfile.IPC_TERMINAL, TaskVerificationPolicy.BUILD_AND_TEST);
        List<VerdictEngine.EvidenceItem> evidence = Arrays.asList(
                new VerdictEngine.EvidenceItem(
                        VerdictEngine.EvidenceItem.Category.DIFF_RESULT,
                        EvidenceLevel.TOOL_OBSERVATION, false, "Diff recorded"),
                new VerdictEngine.EvidenceItem(
                        VerdictEngine.EvidenceItem.Category.BUILD_RESULT,
                        EvidenceLevel.DETERMINISTIC_CHECK, false, "Build: SUCCESS"),
                new VerdictEngine.EvidenceItem(
                        VerdictEngine.EvidenceItem.Category.TEST_RESULT,
                        EvidenceLevel.INDEPENDENT_VERIFICATION, false, "Test: PASS"));
        TaskVerdict v = VerdictEngine.evaluate(task, evidence);
        assertEquals(TaskVerdict.VERIFIED, v);
    }

    // ---- Profile downgrade (§13, D01/D12) ----

    @Test
    public void local_profile_downgrades_build_policy_to_light() {
        // LOCAL cannot run build → policy downgraded to LIGHT
        AgentTask task = buildTask(ExecutionProfile.LOCAL, TaskVerificationPolicy.BUILD);
        List<VerdictEngine.EvidenceItem> evidence = Arrays.asList(
                new VerdictEngine.EvidenceItem(
                        VerdictEngine.EvidenceItem.Category.TOOL_RESULT,
                        EvidenceLevel.TOOL_OBSERVATION, false, "FileWriteTool: ok"));
        TaskVerdict v = VerdictEngine.evaluate(task, evidence);
        // Should be PARTIALLY_VERIFIED (LIGHT effective), not UNVERIFIED (BUILD without build)
        assertEquals(TaskVerdict.PARTIALLY_VERIFIED, v);
    }

    @Test
    public void local_profile_build_and_test_downgrades_to_light() {
        AgentTask task = buildTask(ExecutionProfile.LOCAL, TaskVerificationPolicy.BUILD_AND_TEST);
        List<VerdictEngine.EvidenceItem> evidence = Arrays.asList(
                new VerdictEngine.EvidenceItem(
                        VerdictEngine.EvidenceItem.Category.TOOL_RESULT,
                        EvidenceLevel.TOOL_OBSERVATION, false, "FileWriteTool: ok"));
        TaskVerdict v = VerdictEngine.evaluate(task, evidence);
        assertEquals(TaskVerdict.PARTIALLY_VERIFIED, v);
    }

    @Test
    public void phone_profile_downgrades_all_to_light() {
        AgentTask task = buildTask(ExecutionProfile.PHONE, TaskVerificationPolicy.FULL);
        List<VerdictEngine.EvidenceItem> evidence = Arrays.asList(
                new VerdictEngine.EvidenceItem(
                        VerdictEngine.EvidenceItem.Category.TOOL_RESULT,
                        EvidenceLevel.TOOL_OBSERVATION, false, "Screenshot: ok"));
        TaskVerdict v = VerdictEngine.evaluate(task, evidence);
        assertEquals(TaskVerdict.PARTIALLY_VERIFIED, v);
    }

    // ---- Self-evaluation invariant (Invariant 5) ----

    @Test
    public void completion_claim_without_evidence_stays_unverified() {
        AgentTask task = buildTask(ExecutionProfile.LOCAL, TaskVerificationPolicy.LIGHT);
        // Only E0 (model assertion) evidence
        List<VerdictEngine.EvidenceItem> evidence = Arrays.asList(
                new VerdictEngine.EvidenceItem(
                        VerdictEngine.EvidenceItem.Category.TOOL_RESULT,
                        EvidenceLevel.MODEL_ASSERTION, false, "model says ok"));
        TaskVerdict v = VerdictEngine.evaluateCompletionClaim(task, evidence);
        assertEquals(TaskVerdict.UNVERIFIED, v);
    }

    @Test
    public void completion_claim_with_e1_evidence_accepted() {
        AgentTask task = buildTask(ExecutionProfile.LOCAL, TaskVerificationPolicy.LIGHT);
        List<VerdictEngine.EvidenceItem> evidence = Arrays.asList(
                new VerdictEngine.EvidenceItem(
                        VerdictEngine.EvidenceItem.Category.TOOL_RESULT,
                        EvidenceLevel.TOOL_OBSERVATION, false, "FileWriteTool: ok"));
        TaskVerdict v = VerdictEngine.evaluateCompletionClaim(task, evidence);
        assertEquals(TaskVerdict.PARTIALLY_VERIFIED, v);
    }

    // ---- User confirmation always wins ----

    @Test
    public void user_confirmation_returns_verified() {
        AgentTask task = buildTask(ExecutionProfile.LOCAL, TaskVerificationPolicy.LIGHT);
        List<VerdictEngine.EvidenceItem> evidence = Arrays.asList(
                new VerdictEngine.EvidenceItem(
                        VerdictEngine.EvidenceItem.Category.USER_CONFIRMATION,
                        EvidenceLevel.USER_CONFIRMATION, false, "User confirmed"));
        TaskVerdict v = VerdictEngine.evaluate(task, evidence);
        assertEquals(TaskVerdict.VERIFIED, v);
    }

    // ---- Null task ----

    @Test
    public void null_task_returns_unverified() {
        TaskVerdict v = VerdictEngine.evaluate(null, Collections.emptyList());
        assertEquals(TaskVerdict.UNVERIFIED, v);
    }

    // ---- Helper ----

    private AgentTask buildTask(ExecutionProfile profile, TaskVerificationPolicy policy) {
        return new AgentTask.Builder("t1", "conv1", "Fix build error")
                .executionProfile(profile)
                .verificationPolicy(policy)
                .status(TaskStatus.EXECUTING)
                .build();
    }
}
