package cn.lineai.model.harness;

/**
 * Verification verdict for a task (LCP-Harness v1 §17, §45.3).
 *
 * <p>Produced by {@link cn.lineai.ai.harness.VerdictEngine} after evaluating evidence.
 * A verdict is never higher than the weakest evidence level available.
 */
public enum TaskVerdict {

    /** All completion conditions verified with strong evidence (E2+). */
    VERIFIED,

    /** Some conditions verified, others unverifiable in current profile (e.g., no Gradle on LOCAL). */
    PARTIALLY_VERIFIED,

    /** Only model assertion (E0) or weak tool observation (E1); cannot confirm claim. */
    UNVERIFIED,

    /** Evidence contradicts the claim or a deterministic check failed. */
    FAILED,

    /** Task cannot be verified (blocked by policy, missing tools, etc.). */
    BLOCKED;

    /** Returns true if this verdict allows the task to transition to COMPLETED. */
    public boolean allowsCompletion() {
        return this == VERIFIED || this == PARTIALLY_VERIFIED;
    }

    /** Returns true if recovery should be attempted. */
    public boolean needsRecovery() {
        return this == FAILED;
    }

    /** Resolve a verdict from a string, case-insensitive. Returns {@code null} if not recognized. */
    public static TaskVerdict fromString(String value) {
        if (value == null) return null;
        try {
            return valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
