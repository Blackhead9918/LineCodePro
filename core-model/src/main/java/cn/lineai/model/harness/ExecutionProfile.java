package cn.lineai.model.harness;

/**
 * Execution environment profile (LCP-Harness v1 §12).
 *
 * <p>Each task is bound to one profile. The profile determines available tools,
 * filesystem boundary, network access, permission level, timeout, and resource budget.
 */
public enum ExecutionProfile {

    /** Local on-device execution. No Gradle/compiler on device — verification limited to E1. */
    LOCAL,

    /** Remote execution via SSH (jsch). Full build/test available. */
    SSH,

    /** Execution via IPC terminal provider (Termux). Full build/test available. */
    IPC_TERMINAL,

    /** Phone control via accessibility service. Restricted tool set. */
    PHONE;

    /** Returns true if build/test verification is possible in this profile. */
    public boolean supportsBuildVerification() {
        return this == SSH || this == IPC_TERMINAL;
    }

    /** Returns true if this profile has filesystem access. */
    public boolean hasFilesystemAccess() {
        return this != PHONE;
    }

    /** Returns true if shell execution is available. */
    public boolean hasShellAccess() {
        return this == SSH || this == IPC_TERMINAL || this == LOCAL;
    }

    /** Resolve from string, case-insensitive. Defaults to {@link #LOCAL}. */
    public static ExecutionProfile fromString(String value) {
        if (value == null) return LOCAL;
        try {
            return valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return LOCAL;
        }
    }
}
