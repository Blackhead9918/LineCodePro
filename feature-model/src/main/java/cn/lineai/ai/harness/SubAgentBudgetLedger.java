package cn.lineai.ai.harness;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import cn.lineai.model.harness.TaskBudget;

/**
 * Cross-task sub-agent budget ledger (LCP-Harness v1 §23, P4; Invariant 8).
 *
 * <p>Enforces two independent caps:
 * <ul>
 *   <li><b>Per-task:</b> from each task's {@link TaskBudget#subAgentMax()}.</li>
 *   <li><b>Session-wide:</b> total live sub-agents across all tasks of the conversation —
 *       prevents a parent + pipeline fan-out from exhausting device resources.</li>
 * </ul>
 *
 * <p>Sub-agent results are never auto-trusted (Invariant 8) — this ledger only accounts
 * resources; callers must still feed sub-agent output back as observation/evidence.
 *
 * <p>Thread-safety: all methods synchronized — spawn/release race from the 4-thread
 * tool pool must not over-commit.
 */
public final class SubAgentBudgetLedger {

    /** Hard cap on live sub-agents per conversation regardless of configuration. */
    public static final int HARD_SESSION_CAP = 16;

    private final int sessionCap;
    private final Map<String, Integer> activePerTask = new HashMap<>();
    private final Map<String, String> tokenToTask = new HashMap<>();
    private int totalSpawned;

    public SubAgentBudgetLedger() {
        this(HARD_SESSION_CAP);
    }

    public SubAgentBudgetLedger(int sessionCap) {
        this.sessionCap = Math.min(sessionCap, HARD_SESSION_CAP);
    }

    /**
     * Try to acquire one sub-agent slot for the task.
     *
     * @param taskId owning parent task
     * @param budget the task's configured budget
     * @return an opaque release token when admitted, or null when either cap is exhausted
     */
    public synchronized String tryAcquire(String taskId, TaskBudget budget) {
        if (taskId == null || budget == null) return null;

        int perTaskCap = budget.subAgentMax() > 0 ? budget.subAgentMax() : Integer.MAX_VALUE;
        int activeForTask = activePerTask.getOrDefault(taskId, 0);
        if (activeForTask >= perTaskCap) return null;
        if (tokenToTask.size() >= sessionCap) return null;

        String token = UUID.randomUUID().toString();
        activePerTask.put(taskId, activeForTask + 1);
        tokenToTask.put(token, taskId);
        totalSpawned++;
        return token;
    }

    /**
     * Release a previously acquired slot. Unknown tokens are ignored (idempotent).
     *
     * @return true if this call actually released a slot
     */
    public synchronized boolean release(String token) {
        if (token == null) return false;
        String taskId = tokenToTask.remove(token);
        if (taskId == null) return false;
        int remaining = activePerTask.getOrDefault(taskId, 1) - 1;
        if (remaining <= 0) activePerTask.remove(taskId);
        else activePerTask.put(taskId, remaining);
        return true;
    }

    /** Live sub-agents spawned by one task. */
    public synchronized int activeCountForTask(String taskId) {
        return activePerTask.getOrDefault(taskId, 0);
    }

    /** Live sub-agents across all tasks. */
    public synchronized int activeTotal() {
        return tokenToTask.size();
    }

    /** Cumulative spawns since creation (diagnostics / learning signal). */
    public synchronized int totalSpawned() {
        return totalSpawned;
    }

    /** Release everything on conversation switch (§45.20 cleanup convention). */
    public synchronized void clearAll() {
        activePerTask.clear();
        tokenToTask.clear();
    }
}
