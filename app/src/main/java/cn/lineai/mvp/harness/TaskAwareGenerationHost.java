package cn.lineai.mvp.harness;

import cn.lineai.model.harness.AgentTask;
import cn.lineai.model.harness.TaskStatus;

/**
 * Decorator around {@code GenerationFlowController.Host} that hooks into TaskController
 * lifecycle (LCP-Harness v1 §45.4, D01).
 *
 * <p><b>Pattern:</b> Decorator + delegation. The delegate is the original Host
 * (typically MainCoordinator or a test stub). TaskController callbacks fire
 * <em>before</em> the delegate, so the harness can intercept/block if needed.
 *
 * <p><b>Cancellation:</b> Uses {@code cancellationToken.cancel()} pattern from
 * {@code ToolExecutionScheduler.cancelRemainingFutures}.
 *
 * <p>This class does NOT modify the model/tool execution flow — it observes and
 * records. The only control it exerts is:
 * <ol>
 *   <li>Budget enforcement (tool call count, attempt count)</li>
 *   <li>State transitions via TaskStateMachine</li>
 *   <li>Evidence collection for VerdictEngine</li>
 * </ol>
 *
 * <p>Thread-safety: all callbacks are expected on the generation thread.
 * State is confined to that thread or synchronized per §45.13.
 */
public final class TaskAwareGenerationHost {

    private final TaskController taskController;
    private final Host delegate;
    private final CancellationToken cancellationToken;

    /**
     * The original Host interface that MainCoordinator implements.
     * Kept as a plain interface reference — we don't redefine it.
     */
    public interface Host {
        void onGenerationStart(String generationId);
        void onModelResponse(String responsePreview);
        void onToolResult(String toolName, boolean success, String output);
        void onGenerationComplete(String generationId);
        void onError(String error);
    }

    /**
     * Cancellation token for the current generation loop.
     * Mirrors ToolExecutionScheduler's cancellation pattern.
     */
    public static final class CancellationToken {
        private volatile boolean cancelled = false;

        public void cancel() { this.cancelled = true; }
        public boolean isCancelled() { return cancelled; }
        public void reset() { this.cancelled = false; }
    }

    public TaskAwareGenerationHost(TaskController taskController, Host delegate) {
        this.taskController = taskController;
        this.delegate = delegate;
        this.cancellationToken = new CancellationToken();
    }

    public CancellationToken cancellationToken() { return cancellationToken; }

    // ---- Wrapped callbacks (§45.4) ----

    /**
     * Called when generation starts. Records attempt and transitions task state.
     */
    public void onGenerationStart(String generationId) {
        AgentTask task = taskController.getActiveTask();
        if (task != null && task.isActive()) {
            taskController.onGenerationStart(generationId);

            // Check budget before starting
            if (task.isBudgetExhausted()) {
                cancellationToken.cancel();
                return;
            }
        }
        delegate.onGenerationStart(generationId);
    }

    /**
     * Called on model response. Records observation.
     */
    public void onModelResponse(String responsePreview) {
        AgentTask task = taskController.getActiveTask();
        if (task != null) {
            taskController.onModelResponse(responsePreview);
        }
        delegate.onModelResponse(responsePreview);
    }

    /**
     * Called on tool result. Records evidence and checks budget.
     */
    public void onToolResult(String toolName, boolean success, String output) {
        AgentTask task = taskController.getActiveTask();
        if (task != null) {
            taskController.onToolResult(toolName, success, output);

            // Budget check: actual per-cycle tool-call count (§45.6)
            if (taskController.isToolCallBudgetExhausted()) {
                cancellationToken.cancel();
            }
        }
        delegate.onToolResult(toolName, success, output);
    }

    /**
     * Called when generation completes. Evaluates verdict and decides next step.
     * Returns true if the generation loop should continue, false to stop.
     */
    public boolean onGenerationComplete(String generationId) {
        AgentTask task = taskController.getActiveTask();
        if (task == null || !task.isActive()) {
            delegate.onGenerationComplete(generationId);
            return false; // no active task, stop
        }

        // Determine if model claims completion
        // This is a simplified heuristic — in production, parse the actual output
        boolean modelClaimsCompletion = false; // will be set by output parser

        cn.lineai.model.harness.TaskVerdict verdict =
                taskController.onGenerationComplete(generationId, modelClaimsCompletion);

        delegate.onGenerationComplete(generationId);

        // Return true if we should continue the generation loop
        AgentTask updated = taskController.getActiveTask();
        if (updated == null) return false; // task completed or failed
        if (updated.status() == TaskStatus.EXECUTING) return true; // replanning
        return false; // terminal or waiting
    }

    /**
     * Called on error. Records failure.
     */
    public void onError(String error) {
        taskController.onError(error);
        delegate.onError(error);
    }

    /**
     * Check if this host is in an active task cycle.
     */
    public boolean isActive() {
        return taskController.hasActiveTask();
    }

    /**
     * Get the current task's capsule for system prompt injection (§45.15, M7).
     */
    public cn.lineai.model.harness.TaskCapsule getCapsule() {
        return taskController.getActiveCapsule();
    }
}
