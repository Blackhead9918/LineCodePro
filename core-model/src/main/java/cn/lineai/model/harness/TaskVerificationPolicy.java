package cn.lineai.model.harness;

/**
 * Verification policy for a task (LCP-Harness v1 §15).
 *
 * <p>Determines which verification steps are attempted.
 * Combined with {@link ExecutionProfile} to produce a capability matrix (§13, D01/D12):
 * not all policies are achievable in all profiles.
 */
public enum TaskVerificationPolicy {

    /** No verification — model assertion only. */
    NONE,

    /** Light verification — diff scope check, tool success check. */
    LIGHT,

    /** Build verification — compile/build command must succeed. */
    BUILD,

    /** Build + test — both build and test suite must pass. */
    BUILD_AND_TEST,

    /** Full verification — build + test + runtime check + user confirmation. */
    FULL;

    /** Returns true if this policy requires build capability. */
    public boolean requiresBuild() {
        return this == BUILD || this == BUILD_AND_TEST || this == FULL;
    }

    /** Returns true if this policy requires test capability. */
    public boolean requiresTest() {
        return this == BUILD_AND_TEST || this == FULL;
    }

    /**
     * Downgrade this policy to the maximum achievable in the given profile (§13, D01/D12).
     * Returns the highest policy the profile can actually support.
     */
    public TaskVerificationPolicy downgradeForProfile(ExecutionProfile profile) {
        if (!profile.supportsBuildVerification()) {
            // LOCAL and PHONE cannot run build/test
            if (this == NONE || this == LIGHT) return this;
            return LIGHT; // downgrade from BUILD+ to LIGHT
        }
        return this; // SSH/IPC_TERMINAL support all policies
    }

    /** Resolve from string, case-insensitive. Defaults to {@link #LIGHT}. */
    public static TaskVerificationPolicy fromString(String value) {
        if (value == null) return LIGHT;
        try {
            return valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return LIGHT;
        }
    }
}
