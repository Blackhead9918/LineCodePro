package cn.lineai.model.harness;

/**
 * Risk classification for a task (LCP-Harness v1 §5).
 *
 * <p>Risk level influences confirmation requirements and policy gate strictness.
 */
public enum TaskRiskLevel {

    /** Read-only analysis, no side effects. */
    LOW,

    /** Mutating operations (file write/edit). */
    MEDIUM,

    /** Destructive operations (file delete, git push, phone control). */
    HIGH,

    /** Operations with irreversible external consequences. */
    CRITICAL;

    /** Returns true if this risk level requires explicit user confirmation. */
    public boolean requiresConfirmation() {
        return this == HIGH || this == CRITICAL;
    }

    /** Resolve from string, case-insensitive. Defaults to {@link #LOW}. */
    public static TaskRiskLevel fromString(String value) {
        if (value == null) return LOW;
        try {
            return valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return LOW;
        }
    }
}
