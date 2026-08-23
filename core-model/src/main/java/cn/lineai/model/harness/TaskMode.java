package cn.lineai.model.harness;

/**
 * Task mode = policy profile for the harness (LCP-Harness v1 §26, D06).
 *
 * <p>One engine, four profiles — not three separate agent systems.
 * CHAT mode does not create a task (D10: backward-compatible).
 */
public enum TaskMode {

    /** Minimal autonomy, answer-first, tools optional. No task created (D10). */
    CHAT,

    /** Analysis, task decomposition, no mutation by default. */
    PLAN,

    /** Full plan → execute → verify → recover loop. */
    AGENT,

    /** Phone control profile — restricted tools, phone permission boundary. */
    CONTROL;

    /** Returns true if this mode creates a task. CHAT does not. */
    public boolean createsTask() {
        return this != CHAT;
    }

    /** Returns true if mutations (write/edit/delete/shell) are allowed in this mode. */
    public boolean allowsMutation() {
        return this == AGENT || this == CONTROL;
    }

    /** Returns true if this mode allows destructive operations. */
    public boolean allowsDestructive() {
        return this == AGENT;
    }

    /** Resolve a mode from a string, case-insensitive. Defaults to {@link #AGENT}. */
    public static TaskMode fromString(String value) {
        if (value == null) return AGENT;
        try {
            return valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return AGENT;
        }
    }
}
