package cn.lineai.ai.harness;

import java.util.List;
import java.util.Locale;
import java.util.UUID;

import cn.lineai.model.harness.AgentEvidence;
import cn.lineai.model.harness.AgentTask;
import cn.lineai.model.harness.EvidenceLevel;
import cn.lineai.model.harness.ExecutionProfile;
import cn.lineai.model.harness.MemoryCandidate;
import cn.lineai.model.harness.TaskVerificationPolicy;
import cn.lineai.model.harness.TaskVerdict;

/**
 * Produces evidence-backed memory candidates from finished tasks
 * (LCP-Harness v1 §18, P3; wiring path per §45.14 / M6).
 *
 * <p>Rules:
 * <ul>
 *   <li><b>VERIFIED</b> task with E2+ evidence → WORKFLOW candidate ("what worked"),
 *       confidence scaled by the strongest evidence level.</li>
 *   <li><b>PARTIALLY_VERIFIED</b> in LOCAL profile → lower-confidence PROJECT_FACT
 *       candidate only when diff scope check passed.</li>
 *   <li><b>FAILED</b> → no direct lesson here (failure patterns are owned by
 *       {@link FailurePatternTracker}); returns null to avoid double-counting.</li>
 *   <li><b>UNVERIFIED / BLOCKED</b> → null (Invariant 5: claims without evidence are not lessons).</li>
 * </ul>
 *
 * <p>Thread-safety: stateless — safe for concurrent use.
 */
public final class TaskLessonExtractor {

    /** Confidence floor for candidates emitted from VERIFIED tasks (aligns RULE_CONFIDENCE). */
    public static final double MIN_VERIFIED_CONFIDENCE = 0.88;

    private TaskLessonExtractor() {} // utility class

    /**
     * Extract a candidate lesson from a finished task, or null when the result does not
     * deserve persistence.
     */
    public static MemoryCandidate extract(AgentTask task, List<AgentEvidence> evidence) {
        if (task == null) return null;
        TaskVerdict verdict = task.verdict();
        if (verdict == null) verdict = TaskVerdict.UNVERIFIED;

        EvidenceLevel strongest = strongestLevel(evidence);

        switch (verdict) {
            case VERIFIED:
                return verifiedLesson(task, strongest);
            case PARTIALLY_VERIFIED:
                return partialLesson(task, evidence);
            default:
                // FAILED handled by FailurePatternTracker; UNVERIFIED/BLOCKED carry no lesson
                return null;
        }
    }

    private static MemoryCandidate verifiedLesson(AgentTask task, EvidenceLevel strongest) {
        double confidence = confidenceFor(strongest);
        if (confidence <= 0) return null; // E0-only "verified" is impossible via VerdictEngine, but guard anyway

        String content = "Workflow that worked for \"" + preview(task.goal(), 80) + "\": "
                + approachSummary(task);
        return new MemoryCandidate(
                UUID.randomUUID().toString(),
                MemoryCandidate.MemoryClass.WORKFLOW,
                MemoryCandidate.Source.VERIFIED_TASK_RESULT,
                content,
                scopeHint(task),
                Math.max(confidence, MIN_VERIFIED_CONFIDENCE),
                "verdict=VERIFIED, strongest=E" + strongest.level(),
                System.currentTimeMillis());
    }

    private static MemoryCandidate partialLesson(AgentTask task, List<AgentEvidence> evidence) {
        // LOCAL downgrades are expected; only persist something when the deterministic
        // diff scope check actually passed (E2 available on-device).
        boolean scopeCheckPassed = hasPassingScopeCheck(evidence);
        if (!scopeCheckPassed) return null;

        String profileNote = task.executionProfile() == ExecutionProfile.LOCAL
                ? " (on-device verification only)"
                : "";
        String content = "Partial success for \"" + preview(task.goal(), 80) + "\": "
                + approachSummary(task)
                + " Changes stayed within declared scope" + profileNote + ".";
        return new MemoryCandidate(
                UUID.randomUUID().toString(),
                MemoryCandidate.MemoryClass.PROJECT_FACT,
                MemoryCandidate.Source.VERIFIED_TASK_RESULT,
                content,
                scopeHint(task),
                0.78, // aligns MIN_KEEP_CONFIDENCE
                "verdict=PARTIALLY_VERIFIED, diff-scope-check=PASS",
                System.currentTimeMillis());
    }

    private static String approachSummary(AgentTask task) {
        StringBuilder sb = new StringBuilder();
        sb.append("profile=").append(task.executionProfile().name().toLowerCase(Locale.US));
        sb.append(", policy=").append(task.verificationPolicy().name().toLowerCase(Locale.US));
        if (task.scope() != null && !task.scope().isEmpty()) {
            sb.append(", scope=").append(preview(task.scope(), 60));
        }
        return sb.toString();
    }

    private static boolean hasPassingScopeCheck(List<AgentEvidence> evidence) {
        if (evidence == null) return false;
        for (AgentEvidence e : evidence) {
            if ("diff".equals(e.source())
                    && e.level().atLeast(EvidenceLevel.DETERMINISTIC_CHECK)
                    && e.summary() != null
                    && e.summary().contains("PASS")) {
                return true;
            }
        }
        return false;
    }

    private static EvidenceLevel strongestLevel(List<AgentEvidence> evidence) {
        EvidenceLevel strongest = EvidenceLevel.MODEL_ASSERTION;
        if (evidence != null) {
            for (AgentEvidence e : evidence) {
                if (e.level() != null && e.level().level() > strongest.level()) {
                    strongest = e.level();
                }
            }
        }
        return strongest;
    }

    private static double confidenceFor(EvidenceLevel level) {
        switch (level) {
            case DETERMINISTIC_CHECK:      return 0.88;
            case INDEPENDENT_VERIFICATION: return 0.95;
            case USER_CONFIRMATION:        return 1.0;
            default:                       return 0; // E0/E1 alone cannot verify a workflow
        }
    }

    private static String scopeHint(AgentTask task) {
        return task.projectId() != null ? "project:" + task.projectId() : "";
    }

    private static String preview(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }
}
