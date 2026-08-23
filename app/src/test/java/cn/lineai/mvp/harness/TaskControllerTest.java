package cn.lineai.mvp.harness;

import static org.junit.Assert.*;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.Before;
import org.junit.Test;

import cn.lineai.model.harness.AgentTask;
import cn.lineai.model.harness.ExecutionProfile;
import cn.lineai.model.harness.MemoryCandidate;
import cn.lineai.model.harness.TaskMode;
import cn.lineai.model.harness.TaskStatus;
import cn.lineai.model.harness.TaskVerdict;

/**
 * Integration-style JVM tests for {@link TaskController} lifecycle
 * (§30, §36–§38) using an in-memory store.
 */
public class TaskControllerTest {

    private InMemoryAgentTaskStore store;
    private TaskController controller;
    private List<MemoryCandidate> learningCandidates;

    @Before
    public void setUp() {
        store = new InMemoryAgentTaskStore();
        controller = new TaskController(store,
                conversationId -> conversationId + "_t1");
        learningCandidates = new ArrayList<>();
        controller.setLearningListener(learningCandidates::add);
    }

    private static TaskController.TaskIdGenerator fixedId(String id) {
        return conversationId -> id;
    }

    // ---- Creation ----

    @Test
    public void createTask_enters_planning_and_persists() {
        AgentTask t = controller.createTask("c1", "p1", "Fix build", TaskMode.AGENT,
                ExecutionProfile.LOCAL);
        assertNotNull(t);
        assertEquals(TaskStatus.PLANNING, t.status());
        assertSame(t, controller.getActiveTask());
        assertTrue(controller.hasActiveTask());
        assertEquals(t, store.getById(t.id()));
    }

    @Test
    public void chat_mode_creates_no_task() {
        assertNull(controller.createTask("c1", "p1", "Hello", TaskMode.CHAT,
                ExecutionProfile.LOCAL));
        assertFalse(controller.hasActiveTask());
    }

    @Test
    public void single_active_task_per_conversation() {
        assertNotNull(controller.createTask("c1", "p1", "First", TaskMode.AGENT,
                ExecutionProfile.LOCAL));
        assertNull(controller.createTask("c1", "p1", "Second", TaskMode.AGENT,
                ExecutionProfile.LOCAL));
        // Different conversation unaffected
        assertNotNull(controller.createTask("c2", "p1", "Other conv", TaskMode.AGENT,
                ExecutionProfile.LOCAL));
    }

    @Test
    public void verification_policy_auto_detected_by_mode_and_profile() {
        AgentTask local = controller.createTask("c1", null, "goal", TaskMode.AGENT,
                ExecutionProfile.LOCAL);
        assertEquals(cn.lineai.model.harness.TaskVerificationPolicy.LIGHT,
                local.verificationPolicy()); // LOCAL cannot build

        AgentTask plan = controller.createTask("c3", null, "goal", TaskMode.PLAN,
                ExecutionProfile.SSH);
        assertEquals(cn.lineai.model.harness.TaskVerificationPolicy.NONE,
                plan.verificationPolicy());
    }

    // ---- Generation cycle: happy path to PARTIALLY_VERIFIED (LOCAL/LIGHT) ----

    @Test
    public void happy_path_light_policy_completes_partially_verified() {
        AgentTask t = controller.createTask("c1", null, "Fix login NPE",
                TaskMode.AGENT, ExecutionProfile.LOCAL);

        controller.onGenerationStart("g1");
        assertEquals(TaskStatus.EXECUTING, t.status());

        controller.onToolResult("FileEditTool", true, "edited ok");

        TaskVerdict verdict = controller.onGenerationComplete("g1", true);
        assertEquals(TaskVerdict.PARTIALLY_VERIFIED, verdict);
        assertEquals(TaskVerdict.PARTIALLY_VERIFIED, t.verdict());
        assertTrue(t.isTerminal());
        assertEquals(TaskStatus.COMPLETED, t.status());
        assertFalse(controller.hasActiveTask());
    }

    @Test
    public void model_claim_without_evidence_stays_unverified_and_continues() {
        controller.createTask("c1", null, "Refactor X", TaskMode.AGENT,
                ExecutionProfile.LOCAL);
        controller.onGenerationStart("g1");
        // no tool calls at all — only the model's word
        TaskVerdict verdict = controller.onGenerationComplete("g1", true);
        assertEquals(TaskVerdict.UNVERIFIED, verdict);
        // UNVERIFIED does not complete; task continues in EXECUTING for next iteration
        assertFalse(controller.getActiveTask().isTerminal());
        assertEquals(TaskStatus.EXECUTING, controller.getActiveTask().status());
    }

    // ---- Verified path over SSH emits a WORKFLOW learning candidate ----

    @Test
    public void verified_ssh_task_emits_workflow_candidate() {
        AgentTask t = new AgentTask.Builder("c9_t1", "c9", "Fix CI pipeline")
                .mode(TaskMode.AGENT)
                .executionProfile(ExecutionProfile.SSH)
                .verificationPolicy(cn.lineai.model.harness.TaskVerificationPolicy.BUILD)
                .build();
        t.setStatus(TaskStatus.PLANNING);
        store.put(t);

        // Recreate controller and adopt the pre-seeded active task (conversation resume path)
        TaskController c = new TaskController(store, fixedId("c9_t1"));
        assertNotNull(c.resumeActiveTask("c9"));
        List<MemoryCandidate> candidates = new ArrayList<>();
        c.setLearningListener(candidates::add);

        c.onGenerationStart("g1");
        c.onToolResult("ShellExecuteTool", true, "gradle BUILD SUCCESSFUL");
        c.onDiffRecorded(Arrays.asList("ci/Jenkinsfile"), java.util.Collections.emptyList()); // diff scope pass

        TaskVerdict verdict = c.onGenerationComplete("g1", true);
        assertEquals(TaskVerdict.VERIFIED, verdict);
        assertEquals(1, candidates.size());
        assertEquals(MemoryCandidate.MemoryClass.WORKFLOW,
                candidates.get(0).memoryClass());
        assertEquals(MemoryCandidate.Source.VERIFIED_TASK_RESULT,
                candidates.get(0).source());
        assertTrue(candidates.get(0).confidence() >= 0.88);
        assertFalse(store.getEvidenceForTask("c9_t1").isEmpty());
    }

    // ---- Failure & recovery budget ----

    @Test
    public void tool_error_triggers_recovery_then_budget_exhaustion_fails_task() {
        controller.createTask("c1", null, "Do thing", TaskMode.AGENT,
                ExecutionProfile.LOCAL);

        controller.onGenerationStart("g1");
        controller.onToolResult("ShellExecuteTool", false, "gradle failed");
        TaskVerdict v1 = controller.onGenerationComplete("g1", false);
        assertEquals(TaskVerdict.FAILED, v1);
        // Recovery: VERIFYING → EXECUTING retry per §45.2 transition table, attempt consumed
        AgentTask active = controller.getActiveTask();
        assertNotNull(active);
        assertEquals(TaskStatus.EXECUTING, active.status());
        assertEquals(1, active.attemptCount());

        controller.onGenerationStart("g2");
        controller.onToolResult("ShellExecuteTool", false, "gradle failed again");
        TaskVerdict v2 = controller.onGenerationComplete("g2", false);
        assertEquals(TaskVerdict.FAILED, v2);

        controller.onGenerationStart("g3");
        controller.onToolResult("ShellExecuteTool", false, "and again");
        TaskVerdict v3 = controller.onGenerationComplete("g3", false);
        assertEquals(TaskVerdict.FAILED, v3);
        // Budget exhausted (max_attempts=3) → terminal FAILED
        assertTrue(controller.getActiveTask() == null); // failed task cleared
        AgentTask stored = store.getById("c1_t1");
        assertEquals(TaskStatus.FAILED, stored.status());
    }

    // ---- Failure pattern learning ----

    @Test
    public void repeated_failures_emit_failure_pattern_candidate() {
        List<MemoryCandidate> candidates = new ArrayList<>();
        controller.setLearningListener(candidates::add);
        controller.createTask("c1", null, "Build project", TaskMode.AGENT,
                ExecutionProfile.IPC_TERMINAL);

        for (int i = 0; i < 3; i++) {
            controller.onToolResult("ShellExecuteTool", false, "./gradlew assembleDebug crashed");
        }
        assertEquals(1, candidates.size());
        assertEquals(MemoryCandidate.MemoryClass.FAILURE_PATTERN,
                candidates.get(0).memoryClass());
        assertTrue(candidates.get(0).content().contains("tool:ShellExecuteTool"));

        // 4th failure does not duplicate the candidate
        controller.onToolResult("ShellExecuteTool", false, "again");
        assertEquals(1, candidates.size());
    }

    // ---- Cancellation ----

    @Test
    public void cancel_mid_generation_marks_cancelled_and_clears() {
        controller.createTask("c1", null, "Long task", TaskMode.AGENT,
                ExecutionProfile.LOCAL);
        controller.onGenerationStart("g1");

        controller.cancelTask();

        assertFalse(controller.hasActiveTask());
        assertEquals(TaskStatus.CANCELLED, store.getById("c1_t1").status());
    }

    // ---- Reconciliation on startup ----

    @Test
    public void reconcile_interrupts_stale_active_tasks() {
        AgentTask stale = new AgentTask.Builder("cX_t1", "cX", "old goal")
                .status(TaskStatus.EXECUTING)
                .build();
        store.put(stale);

        assertEquals(1, store.reconcileStaleTasks());
        assertEquals(TaskStatus.INTERRUPTED, stale.status());

        // Interrupted conversation can start a fresh task again
        assertNotNull(controller.createTask("cX", null, "retry goal",
                TaskMode.AGENT, ExecutionProfile.LOCAL));
    }

    // ---- User response while waiting ----

    @Test
    public void user_response_resumes_executing_from_waiting_user() {
        controller.createTask("c1", null, "Needs input", TaskMode.AGENT,
                ExecutionProfile.LOCAL);
        controller.onGenerationStart("g1");
        controller.getActiveTask().setStatus(TaskStatus.WAITING_USER);

        controller.onUserResponse("here is the answer");

        assertEquals(TaskStatus.EXECUTING, controller.getActiveTask().status());
    }
}
