package cn.lineai.ai.harness;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import cn.lineai.model.harness.ExecutionProfile;
import cn.lineai.model.harness.TaskVerificationPolicy;

/**
 * Formalizes the verification policy × execution profile capability matrix
 * (LCP-Harness v1 §13, D01/D12).
 *
 * <pre>
 * Policy          | LOCAL              | SSH | IPC_TERMINAL       | PHONE
 * ----------------|--------------------|-----|--------------------|------
 * NONE            | ✅                 | ✅  | ✅                 | ✅
 * LIGHT           | ✅                 | ✅  | ✅                 | n/a
 * BUILD           | ⬇️ downgrade→LIGHT | ✅  | ⚠️ toolchain       | ❌
 * BUILD_AND_TEST  | ⬇️ downgrade→LIGHT | ✅  | ⚠️ toolchain       | ❌
 * FULL            | ⬇️ downgrade→LIGHT | ✅  | ⚠️ toolchain       | ❌
 * </pre>
 *
 * <p><b>Rules:</b>
 * <ol>
 *   <li>Unsupported policy → downgrade to highest supported (never silent failure).</li>
 *   <li>Downgraded result → honest verdict PARTIALLY_VERIFIED at most.</li>
 *   <li>The only always-available on-device E2 is the diff scope check.</li>
 * </ol>
 *
 * <p>Thread-safety: stateless — safe for concurrent use.
 */
public final class VerificationCapabilityMatrix {

    private VerificationCapabilityMatrix() {} // utility class

    /**
     * Resolve the effective policy for a requested policy on a given profile.
     *
     * @param requested the policy the task asked for
     * @param profile   the execution environment
     * @non-null requested non-null, profile non-null
     * @return never-null effective capability
     */
    public static Capability resolve(TaskVerificationPolicy requested, ExecutionProfile profile) {
        if (requested == null) requested = TaskVerificationPolicy.LIGHT;
        if (profile == null) profile = ExecutionProfile.LOCAL;

        TaskVerificationPolicy effective = requested.downgradeForProfile(profile);
        boolean downgraded = effective != requested;
        boolean buildAvailable = profile.supportsBuildVerification()
                && effective.requiresBuild();
        boolean testAvailable = profile.supportsBuildVerification()
                && effective.requiresTest();

        return new Capability(requested, effective, downgraded, buildAvailable, testAvailable);
    }

    /**
     * Returns true if a downgrade would occur for this policy/profile combination.
     * Useful for showing an honest notice before the task starts (§13 rule 2).
     */
    public static boolean requiresDowngrade(TaskVerificationPolicy requested, ExecutionProfile profile) {
        return resolve(requested, profile).downgraded();
    }

    /** The resolved capability for a policy × profile pair. Immutable value object. */
    public static final class Capability {
        private final TaskVerificationPolicy requested;
        private final TaskVerificationPolicy effective;
        private final boolean downgraded;
        private final boolean buildAvailable;
        private final boolean testAvailable;

        Capability(TaskVerificationPolicy requested, TaskVerificationPolicy effective,
                   boolean downgraded, boolean buildAvailable, boolean testAvailable) {
            this.requested = requested;
            this.effective = effective;
            this.downgraded = downgraded;
            this.buildAvailable = buildAvailable;
            this.testAvailable = testAvailable;
        }

        public TaskVerificationPolicy requested() { return requested; }
        public TaskVerificationPolicy effective() { return effective; }

        /** True if the effective policy is weaker than requested (honest-verdict trigger). */
        public boolean downgraded() { return downgraded; }

        /** True if build commands can actually run in this profile. */
        public boolean buildAvailable() { return buildAvailable; }

        /** True if test suites can actually run in this profile. */
        public boolean testAvailable() { return testAvailable; }

        /**
         * Cap a computed verdict based on downgrade status: a downgraded task can
         * never reach full VERIFIED without user confirmation (§13 rule 2).
         */
        public cn.lineai.model.harness.TaskVerdict cap(cn.lineai.model.harness.TaskVerdict verdict) {
            if (!downgraded || verdict == null) return verdict;
            switch (verdict) {
                case VERIFIED:
                    // Downgraded task: strongest honest verdict without independent check
                    // is PARTIALLY_VERIFIED unless user confirms (E4).
                    return cn.lineai.model.harness.TaskVerdict.PARTIALLY_VERIFIED;
                default:
                    return verdict;
            }
        }

        /** Human-readable explanation for UI display / audit log. */
        public String explain() {
            StringBuilder sb = new StringBuilder();
            sb.append("requested=").append(requested.name().toLowerCase(Locale.US));
            sb.append(", effective=").append(effective.name().toLowerCase(Locale.US));
            if (downgraded) {
                sb.append(" [downgraded: profile lacks toolchain]");
            }
            sb.append(", build=").append(buildAvailable ? "available" : "unavailable");
            sb.append(", test=").append(testAvailable ? "available" : "unavailable");
            return sb.toString();
        }

        @Override
        public String toString() {
            return "Capability{" + explain() + "}";
        }
    }

    /** Convenience: list all profiles that fully support the given policy (for UI hints). */
    public static List<ExecutionProfile> supportingProfiles(TaskVerificationPolicy policy) {
        List<ExecutionProfile> result = new ArrayList<>();
        for (ExecutionProfile p : ExecutionProfile.values()) {
            if (!resolve(policy, p).downgraded()) {
                result.add(p);
            }
        }
        return result;
    }
}
