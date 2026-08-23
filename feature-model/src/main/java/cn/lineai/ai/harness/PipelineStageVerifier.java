package cn.lineai.ai.harness;

import java.util.List;
import java.util.Locale;

import cn.lineai.model.harness.EvidenceLevel;

/**
 * Evidence gates between deterministic pipeline stages
 * (LCP-Harness v1 §24: EXPLORE → PLAN → IMPLEMENT → VERIFY).
 *
 * <p>A stage may only advance when its gate evidence exists. This is what makes a
 * pipeline more than three sequential model calls: skipping IMPLEMENT's diff evidence,
 * for instance, is impossible.
 *
 * <p>Gate requirements:
 * <ul>
 *   <li>EXPLORE → PLAN: at least E1 observation gathered (something was actually looked at).</li>
 *   <li>PLAN → IMPLEMENT: plan content non-empty.</li>
 *   <li>IMPLEMENT → VERIFY: at least one diff/mutation evidence item.</li>
 *   <li>VERIFY → done: verdict evidence at E2+, or an honest PARTIALLY_VERIFIED.</li>
 * </ul>
 *
 * <p>Thread-safety: stateless — safe for concurrent use.
 */
public final class PipelineStageVerifier {

    private PipelineStageVerifier() {} // utility class

    /** Canonical pipeline stages in execution order. */
    public enum Stage {
        EXPLORE,
        PLAN,
        IMPLEMENT,
        VERIFY;

        public boolean canFollow(Stage previous) {
            return previous != null && ordinal() == previous.ordinal() + 1;
        }

        public static Stage fromString(String value) {
            if (value == null) return EXPLORE;
            try {
                return valueOf(value.trim().toUpperCase(Locale.US));
            } catch (IllegalArgumentException e) {
                return EXPLORE;
            }
        }
    }

    /**
     * Check whether the pipeline may advance from {@code current} to {@code next}
     * given the accumulated evidence and plan content.
     */
    public static GateResult canAdvance(Stage current, Stage next,
                                        List<VerdictEngine.EvidenceItem> evidence,
                                        String planContent) {
        if (current == null || next == null) {
            return GateResult.fail("stages must not be null");
        }
        if (!next.canFollow(current)) {
            return GateResult.fail("cannot skip stages: " + current + " → " + next);
        }

        switch (next) {
            case PLAN:
                // EXPLORE → PLAN: need at least one real observation (E1+)
                return requireMinLevel(evidence, EvidenceLevel.TOOL_OBSERVATION,
                        "explore produced no tool observation");
            case IMPLEMENT:
                // PLAN → IMPLEMENT: need an actual plan
                if (planContent == null || planContent.trim().isEmpty()) {
                    return GateResult.fail("plan content is empty");
                }
                return GateResult.pass();
            case VERIFY:
                // IMPLEMENT → VERIFY: need mutation/diff evidence
                return requireCategory(evidence,
                        VerdictEngine.EvidenceItem.Category.DIFF_RESULT,
                        "no recorded diff after implement stage");
            default:
                return GateResult.fail("unsupported advance target: " + next);
        }
    }

    /**
     * Final gate: verify-stage closure requires E2+ evidence or an explicitly honest
     * partial verdict — a bare model assertion can never close the pipeline.
     */
    public static GateResult canFinish(List<VerdictEngine.EvidenceItem> evidence,
                                       cn.lineai.model.harness.TaskVerdict finalVerdict) {
        if (finalVerdict == null) return GateResult.fail("no verdict");
        if (finalVerdict == cn.lineai.model.harness.TaskVerdict.PARTIALLY_VERIFIED
                || finalVerdict == cn.lineai.model.harness.TaskVerdict.FAILED) {
            return GateResult.pass(); // honest outcomes may close with lower evidence
        }
        return requireMinLevel(evidence, EvidenceLevel.DETERMINISTIC_CHECK,
                "VERIFIED finish requires at least one E2 deterministic check");
    }

    private static GateResult requireMinLevel(List<VerdictEngine.EvidenceItem> evidence,
                                              EvidenceLevel min, String failReason) {
        if (evidence != null) {
            for (VerdictEngine.EvidenceItem e : evidence) {
                if (!e.failed() && e.level().atLeast(min)) return GateResult.pass();
            }
        }
        return GateResult.fail(failReason);
    }

    private static GateResult requireCategory(List<VerdictEngine.EvidenceItem> evidence,
                                              VerdictEngine.EvidenceItem.Category category,
                                              String failReason) {
        if (evidence != null) {
            for (VerdictEngine.EvidenceItem e : evidence) {
                if (!e.failed() && e.category() == category) return GateResult.pass();
            }
        }
        return GateResult.fail(failReason);
    }

    /** Gate decision value object. */
    public static final class GateResult {
        private final boolean passed;
        private final String reason;

        private GateResult(boolean passed, String reason) {
            this.passed = passed;
            this.reason = reason;
        }

        static GateResult pass() { return new GateResult(true, null); }
        static GateResult fail(String reason) { return new GateResult(false, reason); }

        public boolean passed() { return passed; }
        public String reason() { return reason != null ? reason : ""; }
    }
}
