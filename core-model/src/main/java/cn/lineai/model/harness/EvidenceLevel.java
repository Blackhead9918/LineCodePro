package cn.lineai.model.harness;

/**
 * Evidence strength levels (LCP-Harness v1 §14).
 *
 * <p>Confidence must never increase by repeating E0.
 * Higher levels subsume lower levels: E3 evidence is stronger than E1.
 */
public enum EvidenceLevel {

    /** E0: Model assertion — "the code looks correct". */
    MODEL_ASSERTION(0),

    /** E1: Tool observation — FileWrite succeeded, tool returned success. */
    TOOL_OBSERVATION(1),

    /** E2: Deterministic check — compiler/build/test command succeeded. */
    DETERMINISTIC_CHECK(2),

    /** E3: Independent verification — automated test suite passes. */
    INDEPENDENT_VERIFICATION(3),

    /** E4: External/user confirmation — user confirms behavior is correct. */
    USER_CONFIRMATION(4);

    private final int level;

    EvidenceLevel(int level) {
        this.level = level;
    }

    /** Numeric level (0–4). Higher = stronger. */
    public int level() {
        return level;
    }

    /** Returns true if this level is at least as strong as the given threshold. */
    public boolean atLeast(EvidenceLevel threshold) {
        return this.level >= threshold.level;
    }

    /** Resolve from numeric level. Returns {@code null} if out of range. */
    public static EvidenceLevel fromLevel(int level) {
        for (EvidenceLevel e : values()) {
            if (e.level == level) return e;
        }
        return null;
    }

    /** Resolve from string, case-insensitive. Returns {@code null} if not recognized. */
    public static EvidenceLevel fromString(String value) {
        if (value == null) return null;
        try {
            return valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
