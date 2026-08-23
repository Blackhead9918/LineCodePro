package cn.lineai.model.harness;

import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * Lifecycle states for an agent task (LCP-Harness v1 §45.2).
 *
 * <p>State transitions are defined in {@link cn.lineai.ai.harness.TaskStateMachine}.
 * Terminal states ({@link #COMPLETED}, {@link #FAILED}, {@link #CANCELLED}) have no outgoing transitions.
 * {@link #INTERRUPTED} is set by crash-reconciliation (§30.4) and must transition to {@link #FAILED}
 * or {@link #CANCELLED} before a new task can start.
 */
public enum TaskStatus {

    /** Task created but not yet started. */
    CREATED,

    /** Task is being planned (analysis, decomposition). */
    PLANNING,

    /** Task is actively executing tool calls / generation. */
    EXECUTING,

    /** Task results are being verified. */
    VERIFYING,

    /** Task is blocked (dependency, policy, resource). */
    BLOCKED,

    /** Task is waiting for user input / confirmation. */
    WAITING_USER,

    /** Task completed successfully (terminal). */
    COMPLETED,

    /** Task failed (terminal). */
    FAILED,

    /** Task was cancelled by user or system (terminal). */
    CANCELLED,

    /** Task was interrupted by process death — reconciled on startup (§30.4, D04). */
    INTERRUPTED;

    /** Returns true if this is a terminal state (no outgoing transitions). */
    public boolean isTerminal() {
        return this == COMPLETED || this == FAILED || this == CANCELLED;
    }

    /** Returns true if this state represents an active (non-terminal, non-interrupted) task. */
    public boolean isActive() {
        return !isTerminal() && this != INTERRUPTED;
    }

    /**
     * Returns an unmodifiable set of valid target states for each source state.
     * Used by {@link cn.lineai.ai.harness.TaskStateMachine}.
     */
    public static Map<TaskStatus, Set<TaskStatus>> validTransitions() {
        Map<TaskStatus, Set<TaskStatus>> m = new EnumMap<>(TaskStatus.class);

        m.put(CREATED,       EnumSet.of(PLANNING, CANCELLED, FAILED));
        m.put(PLANNING,      EnumSet.of(EXECUTING, CANCELLED, FAILED, BLOCKED));
        m.put(EXECUTING,     EnumSet.of(VERIFYING, BLOCKED, WAITING_USER, COMPLETED, FAILED, CANCELLED));
        m.put(VERIFYING,     EnumSet.of(COMPLETED, FAILED, EXECUTING, BLOCKED));
        m.put(BLOCKED,       EnumSet.of(PLANNING, EXECUTING, CANCELLED, FAILED));
        m.put(WAITING_USER,  EnumSet.of(EXECUTING, CANCELLED, FAILED));
        m.put(COMPLETED,     EnumSet.noneOf(TaskStatus.class));
        m.put(FAILED,        EnumSet.noneOf(TaskStatus.class));
        m.put(CANCELLED,     EnumSet.noneOf(TaskStatus.class));
        m.put(INTERRUPTED,   EnumSet.of(FAILED, CANCELLED));

        return Collections.unmodifiableMap(m);
    }

    /** Resolve a status from a string, case-insensitive. Returns {@code null} if not recognized. */
    public static TaskStatus fromString(String value) {
        if (value == null) return null;
        try {
            return valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
