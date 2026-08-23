package cn.lineai.mvp.harness;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import cn.lineai.ai.harness.DiffScopeChecker;
import cn.lineai.ai.harness.EvidenceClassifier;
import cn.lineai.ai.harness.EvidenceRecorder;
import cn.lineai.ai.harness.FailurePatternTracker;
import cn.lineai.ai.harness.TaskLessonExtractor;
import cn.lineai.ai.harness.TaskStateMachine;
import cn.lineai.ai.harness.VerdictEngine;
import cn.lineai.ai.harness.VerificationCapabilityMatrix;
import cn.lineai.model.harness.AgentTask;
import cn.lineai.model.harness.AgentTaskStore;
import cn.lineai.model.harness.ExecutionProfile;
import cn.lineai.model.harness.MemoryCandidate;
import cn.lineai.model.harness.TaskBudget;
import cn.lineai.model.harness.TaskCapsule;
import cn.lineai.model.harness.TaskMode;
import cn.lineai.model.harness.TaskStatus;
import cn.lineai.model.harness.TaskVerificationPolicy;
import cn.lineai.model.harness.TaskVerdict;

/**
 * Task lifecycle orchestrator for LCP-Harness v1 (§30, §36–§37).
 *
 * <p><b>Key design decision (D01):</b> TaskController is a <em>thin wrapper</em> that calls
 * the existing {@code GenerationFlowController} — it does NOT create a parallel loop.
 * The actual model/tool execution remains unchanged; TaskController adds lifecycle
 * management, budget enforcement, evidence collection, and verdict computation on top.
 *
 * <p><b>Backward compatibility (D10):</b> Conversations without tasks behave exactly as before.
 * Tasks are only created in AGENT/PLAN/CONTROL modes. CHAT mode = no task.
 *
 * <p><b>Thread-safety (D09):</b> Mutable state is confined to the main thread (post callbacks)
 * or uses synchronized/AtomicInteger per §45.13.
 *
 * <p><b>Single active task per conversation (§45.11, M3).</b>
 */
public final class TaskController {

    private final AgentTaskStore taskStore;
    private final TaskIdGenerator idGenerator;
    private final EvidenceRecorder evidenceRecorder; // nullable in P0-light setups

    /** Current active task (null if no task active). Main-thread confined (§45.13). */
    private AgentTask activeTask;

    /** Evidence collected during current generation cycle. Synchronized (§45.13). */
    private final List<VerdictEngine.EvidenceItem> currentEvidence =
            java.util.Collections.synchronizedList(new ArrayList<>());

    /** Attempt counter for current task. AtomicInteger (§45.13). */
    private final AtomicInteger generationAttempts = new AtomicInteger(0);

    /** Tool calls executed in the current generation cycle (tool-call budget unit, §45.6). */
    private final AtomicInteger cycleToolCalls = new AtomicInteger(0);

    /**
     * Callback interface for task lifecycle events.
     * Implementations: MainCoordinator, ChatUiStateAssembler.
     */
    public interface TaskListener {
        void onTaskCreated(AgentTask task);
        void onTaskStatusChanged(AgentTask task, TaskStatus oldStatus, TaskStatus newStatus);
        void onTaskCompleted(AgentTask task, TaskVerdict verdict);
        void onTaskFailed(AgentTask task, TaskVerdict verdict, String error);
        void onTaskBlocked(AgentTask task, String reason);
        void onTaskCancelled(AgentTask task);
    }

    /**
     * Learning path (§45.14, M6): candidates flow to MainCoordinator which forwards them
     * to the existing MemoryExtractionService confidence gates — harness adds no new store.
     */
    public interface LearningListener {
        void onLearningCandidate(MemoryCandidate candidate);
    }

    private TaskListener listener;
    private LearningListener learningListener;
    private final FailurePatternTracker failureTracker = new FailurePatternTracker();

    public TaskController(AgentTaskStore taskStore, TaskIdGenerator idGenerator) {
        this(taskStore, idGenerator,
                new EvidenceRecorder(taskStore));
    }

    public TaskController(AgentTaskStore taskStore, TaskIdGenerator idGenerator,
                          EvidenceRecorder evidenceRecorder) {
        this.taskStore = taskStore;
        this.idGenerator = idGenerator;
        this.evidenceRecorder = evidenceRecorder;
    }

    public void setListener(TaskListener listener) {
        this.listener = listener;
    }

    public void setLearningListener(LearningListener learningListener) {
        this.learningListener = learningListener;
    }

    // ---- Task Lifecycle (§36) ----

    /**
     * Create a new task for the given conversation. Called when user sends a message
     * in AGENT/PLAN/CONTROL mode (§45.7, H2).
     *
     * @param conversationId current conversation ID
     * @param projectId current project ID (nullable)
     * @param goal user's goal/request
     * @param mode task mode
     * @param executionProfile execution environment
     * @return the created task, or null if a task is already active (§45.11)
     */
    public AgentTask createTask(String conversationId, String projectId,
                                String goal, TaskMode mode,
                                ExecutionProfile executionProfile) {
        if (!mode.createsTask()) return null;

        // §45.11: one active task per conversation
        AgentTask existing = taskStore.getActiveForConversation(conversationId);
        if (existing != null && existing.isActive()) {
            return null; // task already active
        }

        // §45.5: auto-detect verification policy from mode + profile
        cn.lineai.model.harness.TaskVerificationPolicy vPolicy = autoDetectVerificationPolicy(mode, executionProfile);

        String taskId = idGenerator.generate(conversationId);
        AgentTask task = new AgentTask.Builder(taskId, conversationId, goal)
                .projectId(projectId)
                .mode(mode)
                .executionProfile(executionProfile)
                .verificationPolicy(vPolicy)
                .budget(TaskBudget.defaults())
                .build();

        // Transition: CREATED → PLANNING
        TaskStateMachine.transition(task.status(), TaskStatus.PLANNING);
        task.setStatus(TaskStatus.PLANNING);

        taskStore.put(task);
        taskStore.insertEvent(UUID.randomUUID().toString(), taskId,
                "TASK_CREATED", "TaskController", null);

        this.activeTask = task;
        if (listener != null) listener.onTaskCreated(task);

        return task;
    }

    /**
     * Start execution phase. Called when generation begins.
     */
    public void onGenerationStart(String generationId) {
        if (activeTask == null) return;
        try {
            TaskStateMachine.transition(activeTask.status(), TaskStatus.EXECUTING);
            activeTask.setStatus(TaskStatus.EXECUTING);
            taskStore.update(activeTask);
            currentEvidence.clear();
            generationAttempts.incrementAndGet();
            cycleToolCalls.set(0);
            taskStore.insertEvent(UUID.randomUUID().toString(), activeTask.id(),
                    "TASK_STARTED", "TaskController", "{\"gen\":\"" + generationId + "\"}");
        } catch (TaskStateMachine.InvalidTransitionException e) {
            android.util.Log.w("TaskController", "Invalid transition on generation start: " + e.getMessage());
        }
    }

    /**
     * Record a model response observation.
     */
    public void onModelResponse(String responsePreview) {
        // Model response = E0 observation (will be evaluated with other evidence)
        // No direct state change — evidence collected at completion
    }

    /**
     * Record a tool result observation and persist it as evidence.
     */
    public void onToolResult(String toolName, boolean success, String output) {
        VerdictEngine.EvidenceItem item = EvidenceClassifier.classifyToolResult(toolName, success, output);
        synchronized (currentEvidence) {
            currentEvidence.add(item);
        }

        // Learning path: repeated independent failures → FAILURE_PATTERN candidate (§18)
        if (!success) {
            emitIfPresent(failureTracker.recordFailure("tool:" + toolName, output));
        } else {
            failureTracker.recordSuccess("tool:" + toolName);
        }
        if (evidenceRecorder != null && activeTask != null) {
            try {
                evidenceRecorder.recordToolResult(activeTask.id(), item, "tool_results", toolName);
            } catch (RuntimeException e) {
                android.util.Log.w("TaskController", "Evidence persistence failed: " + e.getMessage());
            }
        }

        // Budget check (§45.6): tool-call budget counts TOOL CALLS, not generation attempts.
        int calls = cycleToolCalls.incrementAndGet();
        if (activeTask != null && !activeTask.budget().isToolCallAllowed(calls)) {
            cn.lineai.ai.harness.HarnessTelemetry.increment(cn.lineai.ai.harness.HarnessTelemetry.TOOL_BUDGET_EXHAUSTED);
            android.util.Log.w("TaskController", "Tool call budget exhausted at " + calls);
        }
    }

    /**
     * True when the current generation cycle has consumed the task's tool-call budget.
     * Used by {@code TaskAwareGenerationHost} to stop the loop.
     */
    public boolean isToolCallBudgetExhausted() {
        AgentTask task = activeTask;
        return task != null && !task.budget().isToolCallAllowed(cycleToolCalls.get());
    }

    /**
     * Called when generation completes. Evaluates verdict and decides next step.
     *
     * @param generationId the generation that just completed
     * @param modelClaimsCompletion true if model's output indicates task is done
     * @return the computed verdict
     */
    public TaskVerdict onGenerationComplete(String generationId, boolean modelClaimsCompletion) {
        if (activeTask == null) return TaskVerdict.UNVERIFIED;

        try {
            // Transition to VERIFYING
            if (activeTask.status() == TaskStatus.EXECUTING) {
                TaskStateMachine.transition(activeTask.status(), TaskStatus.VERIFYING);
                activeTask.setStatus(TaskStatus.VERIFYING);
            }

            // Resolve effective verification policy via capability matrix (§13, D01/D12)
            VerificationCapabilityMatrix.Capability capability =
                    VerificationCapabilityMatrix.resolve(
                            activeTask.verificationPolicy(), activeTask.executionProfile());

            // Evaluate verdict
            List<VerdictEngine.EvidenceItem> evidence;
            synchronized (currentEvidence) {
                evidence = new ArrayList<>(currentEvidence);
            }

            TaskVerdict verdict;
            if (modelClaimsCompletion) {
                verdict = VerdictEngine.evaluateCompletionClaim(activeTask, evidence);
            } else {
                verdict = VerdictEngine.evaluate(activeTask, evidence);
            }

            // Downgraded tasks can never claim full VERIFIED without E4 (§13 rule 2)
            verdict = capability.cap(verdict);

            activeTask.setVerdict(verdict);

            taskStore.insertEvent(UUID.randomUUID().toString(), activeTask.id(),
                    "EVIDENCE_CREATED", "TaskController",
                    "{\"verdict\":\"" + verdict + "\",\"evidenceCount\":" + evidence.size() + "}");

            // Decide next step
            if (verdict.allowsCompletion()) {
                completeTask(verdict);
            } else if (verdict.needsRecovery()) {
                handleRecovery(verdict);
            } else if (activeTask.isBudgetExhausted()) {
                failTask(verdict, "Budget exhausted");
            } else {
                // Continue — stay in EXECUTING for next iteration
                TaskStateMachine.transition(activeTask.status(), TaskStatus.EXECUTING);
                activeTask.setStatus(TaskStatus.EXECUTING);
                taskStore.update(activeTask);
            }

            return verdict;

        } catch (TaskStateMachine.InvalidTransitionException e) {
            android.util.Log.e("TaskController", "State error: " + e.getMessage());
            return TaskVerdict.FAILED;
        }
    }

    /**
     * Record a diff for scope checking (§14 diff-as-evidence, §13 rule 3).
     * The only always-available E2 check on-device — backbone of LOCAL verification.
     *
     * @param changedFiles all file paths touched in this generation cycle
     * @param deletedFiles subset that were deletions
     */
    public void onDiffRecorded(java.util.List<String> changedFiles,
                               java.util.List<String> deletedFiles) {
        if (activeTask == null) return;

        DiffScopeChecker.ScopeCheckResult result =
                DiffScopeChecker.check(activeTask.scope(), changedFiles, deletedFiles);

        // Feed into verdict computation as E2 evidence
        VerdictEngine.EvidenceItem item = new VerdictEngine.EvidenceItem(
                VerdictEngine.EvidenceItem.Category.DIFF_RESULT,
                cn.lineai.model.harness.EvidenceLevel.DETERMINISTIC_CHECK,
                !result.passed(),
                result.summary());
        synchronized (currentEvidence) {
            currentEvidence.add(item);
        }

        if (evidenceRecorder != null) {
            try {
                evidenceRecorder.recordDiffScopeCheck(activeTask.id(), result);
            } catch (RuntimeException e) {
                android.util.Log.w("TaskController", "Diff evidence persistence failed: " + e.getMessage());
            }
        }
    }

    /**
     * Called on error during generation.
     */
    public void onError(String error) {
        if (activeTask == null) return;
        failTask(TaskVerdict.FAILED, error);
    }

    /**
     * Cancel the active task. User-initiated.
     */
    public void cancelTask() {
        if (activeTask == null || activeTask.isTerminal()) return;

        TaskStatus oldStatus = activeTask.status();
        activeTask.markCancelled();
        taskStore.update(activeTask);
        taskStore.insertEvent(UUID.randomUUID().toString(), activeTask.id(),
                "TASK_CANCELLED", "TaskController", null);

        AgentTask completed = this.activeTask;
        this.activeTask = null;
        currentEvidence.clear();
        generationAttempts.set(0);

        if (listener != null) listener.onTaskCancelled(completed);
    }

    /**
     * Called when user provides input during WAITING_USER state.
     */
    public void onUserResponse(String response) {
        if (activeTask == null || activeTask.status() != TaskStatus.WAITING_USER) return;
        try {
            TaskStateMachine.transition(activeTask.status(), TaskStatus.EXECUTING);
            activeTask.setStatus(TaskStatus.EXECUTING);
            taskStore.update(activeTask);
        } catch (TaskStateMachine.InvalidTransitionException e) {
            android.util.Log.w("TaskController", "Invalid transition on user response: " + e.getMessage());
        }
    }

    // ---- Internal helpers ----

    private void completeTask(TaskVerdict verdict) {
        TaskStatus oldStatus = activeTask.status();
        activeTask.markCompleted(verdict);
        taskStore.update(activeTask);
        emitIfPresent(extractLesson(activeTask));
        taskStore.insertEvent(UUID.randomUUID().toString(), activeTask.id(),
                "TASK_COMPLETED", "TaskController",
                "{\"verdict\":\"" + verdict + "\"}");

        AgentTask completed = this.activeTask;
        this.activeTask = null;
        currentEvidence.clear();
        generationAttempts.set(0);

        if (listener != null) listener.onTaskCompleted(completed, verdict);
    }

    private void failTask(TaskVerdict verdict, String error) {
        activeTask.markFailed(verdict);
        taskStore.update(activeTask);
        taskStore.insertEvent(UUID.randomUUID().toString(), activeTask.id(),
                "TASK_FAILED", "TaskController",
                "{\"verdict\":\"" + verdict + "\",\"error\":\"" + escapeJson(error) + "\"}");

        AgentTask failed = this.activeTask;
        this.activeTask = null;
        currentEvidence.clear();
        generationAttempts.set(0);

        if (listener != null) listener.onTaskFailed(failed, verdict, error);
    }

    private void handleRecovery(TaskVerdict verdict) {
        activeTask.incrementAttempt();
        if (activeTask.isBudgetExhausted()) {
            failTask(verdict, "Recovery budget exhausted after " + activeTask.attemptCount() + " attempts");
            return;
        }

        try {
            // Recovery: VERIFYING → EXECUTING (replan/retry per §45.2 transition table)
            cn.lineai.ai.harness.HarnessTelemetry.increment(cn.lineai.ai.harness.HarnessTelemetry.TASK_RECOVERY);
            TaskStateMachine.transition(activeTask.status(), TaskStatus.EXECUTING);
            activeTask.setStatus(TaskStatus.EXECUTING);
            taskStore.update(activeTask);
            taskStore.insertEvent(UUID.randomUUID().toString(), activeTask.id(),
                    "TASK_RECOVERY", "TaskController",
                    "{\"attempt\":" + activeTask.attemptCount() + "}");
        } catch (TaskStateMachine.InvalidTransitionException e) {
            failTask(verdict, "Cannot recover: " + e.getMessage());
        }
    }

    private TaskVerificationPolicy autoDetectVerificationPolicy(TaskMode mode, ExecutionProfile profile) {
        switch (mode) {
            case PLAN:
                return TaskVerificationPolicy.NONE;
            case CONTROL:
                return TaskVerificationPolicy.LIGHT;
            case AGENT:
            default:
                return profile.supportsBuildVerification()
                        ? TaskVerificationPolicy.BUILD_AND_TEST
                        : TaskVerificationPolicy.LIGHT;
        }
    }

    /**
     * Adopt an existing non-terminal task from the store into memory
     * (conversation resume / process restart — §45.22 item 1).
     *
     * @return the adopted task, or null when no active task exists for the conversation
     */
    public AgentTask resumeActiveTask(String conversationId) {
        AgentTask existing = taskStore.getActiveForConversation(conversationId);
        if (existing != null && existing.isActive()) {
            this.activeTask = existing;
            this.currentEvidence.clear();
            return existing;
        }
        return null;
    }

    // ---- Query methods ----

    /** Get the current active task (null if none). */
    public AgentTask getActiveTask() { return activeTask; }

    /** Returns true if a task is currently active. */
    public boolean hasActiveTask() { return activeTask != null && activeTask.isActive(); }

    /** Build a TaskCapsule from the active task (for system prompt injection). */
    public TaskCapsule getActiveCapsule() {
        return activeTask != null ? activeTask.toCapsule() : null;
    }

    /**
     * Cleanup on conversation switch (§45.20). Mirrors AgentResultRegistry.clearAll().
     */
    public void cleanup() {
        if (activeTask != null && !activeTask.isTerminal()) {
            cancelTask();
        }
        this.activeTask = null;
        currentEvidence.clear();
        generationAttempts.set(0);
        failureTracker.clear();
    }

    // ---- Learning helpers (§45.14) ----

    private MemoryCandidate extractLesson(AgentTask task) {
        try {
            return TaskLessonExtractor.extract(task, taskStore.getEvidenceForTask(task.id()));
        } catch (RuntimeException e) {
            android.util.Log.w("TaskController", "Lesson extraction failed: " + e.getMessage());
            return null;
        }
    }

    private void emitIfPresent(MemoryCandidate candidate) {
        if (candidate != null) {
            cn.lineai.ai.harness.HarnessTelemetry.increment(cn.lineai.ai.harness.HarnessTelemetry.LEARNING_CANDIDATE);
        }
        if (candidate != null && learningListener != null) {
            try {
                learningListener.onLearningCandidate(candidate);
            } catch (RuntimeException e) {
                android.util.Log.w("TaskController", "Learning listener failed: " + e.getMessage());
            }
        }
    }

    private static String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r");
    }

    /** Strategy interface for generating task IDs (§45.16, M8). */
    public interface TaskIdGenerator {
        String generate(String conversationId);
    }
}
