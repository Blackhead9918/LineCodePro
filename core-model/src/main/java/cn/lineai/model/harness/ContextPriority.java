package cn.lineai.model.harness;

/**
 * Context priority levels sent to the model (LCP-Harness v1 §6.1, final D03).
 *
 * <p>Lower ordinal = higher priority = pruned last.
 * The active task capsule lives at P0 (system guidance region) but must never
 * displace the latest user request (P1) — anti goal-drift invariant 11.
 */
public enum ContextPriority {

    /** System/policy prompt + active task capsule (guidance). */
    P0_SYSTEM_AND_CAPSULE,

    /** Current user request — highest protected content priority. */
    P1_USER_REQUEST,

    /** Current tool result. */
    P2_TOOL_RESULT,

    /** Relevant code / project context. */
    P3_CODE_CONTEXT,

    /** Verification evidence. */
    P4_VERIFICATION_EVIDENCE,

    /** Relevant working memory (transient, expiring). */
    P5_WORKING_MEMORY,

    /** Relevant persistent memory. */
    P6_PERSISTENT_MEMORY,

    /** Historical conversation. */
    P7_CONVERSATION_HISTORY,

    /** Low-priority metadata. */
    P8_METADATA;

    /** Returns true if items at this level are never dropped by the selector unless unavoidable. */
    public boolean isProtected() {
        return this == P0_SYSTEM_AND_CAPSULE || this == P1_USER_REQUEST;
    }

    /** Resolve from a "P3"-style string. Defaults to {@link #P3_CODE_CONTEXT}. */
    public static ContextPriority fromString(String value) {
        if (value == null) return P3_CODE_CONTEXT;
        String v = value.trim().toUpperCase();
        for (ContextPriority p : values()) {
            if (p.name().startsWith(v)) return p;
        }
        return P3_CODE_CONTEXT;
    }
}
