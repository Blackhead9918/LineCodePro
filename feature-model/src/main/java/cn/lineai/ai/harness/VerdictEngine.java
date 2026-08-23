package cn.lineai.ai.harness;

import java.util.List;

import cn.lineai.model.harness.AgentTask;
import cn.lineai.model.harness.EvidenceLevel;
import cn.lineai.model.harness.ExecutionProfile;
import cn.lineai.model.harness.TaskCapsule;
import cn.lineai.model.harness.TaskStatus;
import cn.lineai.model.harness.TaskVerificationPolicy;
import cn.lineai.model.harness.TaskVerdict;

/**
 * Computes verification verdict from evidence (LCP-Harness v1 §45.3, §15–§17).
 *
 * <p>Two-layer evaluation:
 * <ol>
 *   <li><b>Model-judged (E0):</b> model reads completion_condition and decides — today's behavior.</li>
 *   <li><b>Deterministic guards (E2):</b> VerdictEngine evaluates evidence post-generation.</li>
 * </ol>
 *
 * <p>The verdict is never higher than what the execution profile can actually support
 * (§13, D01/D12): LOCAL profile cannot claim BUILD_AND_TEST → downgrade to PARTIALLY_VERIFIED.
 *
 * <p>Thread-safety: stateless, immutable — safe for concurrent use.
 */
public final class VerdictEngine {

    private VerdictEngine() {} // utility class

    /**
     * Evaluate evidence and produce a verdict.
     *
     * @param task the active task (for verification policy, execution profile, status)
     * @param evidenceList evidence collected during this generation cycle
     * @return the computed verdict
     */
    public static TaskVerdict evaluate(AgentTask task, List<EvidenceItem> evidenceList) {
        if (task == null) return TaskVerdict.UNVERIFIED;
        if (evidenceList == null || evidenceList.isEmpty()) return TaskVerdict.UNVERIFIED;

        TaskVerificationPolicy policy = task.verificationPolicy();
        ExecutionProfile profile = task.executionProfile();

        // Downgrade policy to what the profile can actually support
        TaskVerificationPolicy effectivePolicy = policy.downgradeForProfile(profile);

        // Collect strongest evidence by category
        EvidenceLevel strongestTool = EvidenceLevel.MODEL_ASSERTION;
        EvidenceLevel strongestBuild = null;
        EvidenceLevel strongestTest = null;
        EvidenceLevel strongestDiff = null;
        boolean hasToolError = false;
        boolean hasDiff = false;
        boolean hasAnyMutation = false;

        for (EvidenceItem e : evidenceList) {
            // A failure in any executed step (tool/build/test/diff) counts as a tool error;
            // user confirmation is a verdict override, not an execution step.
            if (e.failed() && e.category() != EvidenceItem.Category.USER_CONFIRMATION) {
                hasToolError = true;
            }
            switch (e.category()) {
                case TOOL_RESULT:
                    if (e.level().atLeast(strongestTool)) {
                        strongestTool = e.level();
                    }
                    hasAnyMutation = true;
                    break;
                case BUILD_RESULT:
                    strongestBuild = e.level();
                    hasAnyMutation = true;
                    break;
                case TEST_RESULT:
                    strongestTest = e.level();
                    break;
                case DIFF_RESULT:
                    hasDiff = true;
                    strongestDiff = e.level();
                    hasAnyMutation = true; // a recorded diff implies a file mutation happened
                    break;
                case USER_CONFIRMATION:
                    // E4 always overrides
                    return TaskVerdict.VERIFIED;
                default:
                    break;
            }
        }

        // --- Failure conditions ---

        // Tool error → FAILED
        if (hasToolError) {
            return TaskVerdict.FAILED;
        }

        // Task is in failed/blocked state → FAILED
        if (task.status() == TaskStatus.FAILED) {
            return TaskVerdict.FAILED;
        }

        // --- Completion conditions based on effective policy ---

        switch (effectivePolicy) {
            case NONE:
                // No verification — model assertion only
                return TaskVerdict.UNVERIFIED;

            case LIGHT:
                // Light: need at least E1 tool observation
                if (hasAnyMutation && !hasToolError) {
                    return TaskVerdict.PARTIALLY_VERIFIED;
                }
                return TaskVerdict.UNVERIFIED;

            case BUILD:
                // Need build success (E2+)
                if (strongestBuild != null && strongestBuild.atLeast(EvidenceLevel.DETERMINISTIC_CHECK)) {
                    if (hasDiff) {
                        return TaskVerdict.VERIFIED;
                    }
                    return TaskVerdict.PARTIALLY_VERIFIED; // build passes but no diff check
                }
                if (hasAnyMutation && !hasToolError) {
                    return TaskVerdict.PARTIALLY_VERIFIED; // cannot build, fallback
                }
                return TaskVerdict.UNVERIFIED;

            case BUILD_AND_TEST:
                // Need build + test success
                if (strongestBuild != null && strongestBuild.atLeast(EvidenceLevel.DETERMINISTIC_CHECK)
                        && strongestTest != null && strongestTest.atLeast(EvidenceLevel.INDEPENDENT_VERIFICATION)) {
                    return TaskVerdict.VERIFIED;
                }
                if (strongestBuild != null && strongestBuild.atLeast(EvidenceLevel.DETERMINISTIC_CHECK)) {
                    return TaskVerdict.PARTIALLY_VERIFIED; // build ok, test missing/fail
                }
                if (hasAnyMutation) {
                    return TaskVerdict.PARTIALLY_VERIFIED;
                }
                return TaskVerdict.UNVERIFIED;

            case FULL:
                // Full: build + test + runtime check
                if (strongestBuild != null && strongestBuild.atLeast(EvidenceLevel.DETERMINISTIC_CHECK)
                        && strongestTest != null && strongestTest.atLeast(EvidenceLevel.INDEPENDENT_VERIFICATION)) {
                    return TaskVerdict.VERIFIED;
                }
                if (strongestBuild != null && strongestBuild.atLeast(EvidenceLevel.DETERMINISTIC_CHECK)) {
                    return TaskVerdict.PARTIALLY_VERIFIED;
                }
                return TaskVerdict.UNVERIFIED;

            default:
                return TaskVerdict.UNVERIFIED;
        }
    }

    /**
     * Check if a model's completion claim should be accepted given the evidence.
     *
     * <p>Model claiming "done" without any tool evidence → UNVERIFIED (Invariant 5).
     * Model claiming "done" with E1+ evidence → accept with appropriate verdict.
     */
    public static TaskVerdict evaluateCompletionClaim(AgentTask task, List<EvidenceItem> evidenceList) {
        // Invariant 5 (§38): self-evaluation is never independent evidence.
        // If every collected item is E0 (model assertion), a completion claim stays UNVERIFIED
        // even when the base evaluation would grant PARTIALLY_VERIFIED via LIGHT policy.
        if (hasOnlyModelAssertion(evidenceList)) {
            return TaskVerdict.UNVERIFIED;
        }
        return evaluate(task, evidenceList);
    }

    private static boolean hasOnlyModelAssertion(List<EvidenceItem> evidenceList) {
        if (evidenceList == null || evidenceList.isEmpty()) return true;
        for (EvidenceItem e : evidenceList) {
            if (e.level().level() > EvidenceLevel.MODEL_ASSERTION.level()) {
                return false;
            }
        }
        return true;
    }

    /**
     * A single piece of evidence collected during task execution.
     */
    public static final class EvidenceItem {

        public enum Category {
            TOOL_RESULT,
            BUILD_RESULT,
            TEST_RESULT,
            DIFF_RESULT,
            USER_CONFIRMATION
        }

        private final Category category;
        private final EvidenceLevel level;
        private final boolean failed;
        private final String summary;

        public EvidenceItem(Category category, EvidenceLevel level, boolean failed, String summary) {
            this.category = category;
            this.level = level;
            this.failed = failed;
            this.summary = summary;
        }

        public Category category() { return category; }
        public EvidenceLevel level() { return level; }
        public boolean failed() { return failed; }
        public String summary() { return summary; }

        @Override
        public String toString() {
            return "EvidenceItem{" + category + ", " + level + (failed ? ", FAILED" : "") +
                    ", '" + (summary != null && summary.length() > 40 ? summary.substring(0, 40) + "..." : summary) + "'}";
        }
    }
}
