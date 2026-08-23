package cn.lineai.ai.harness;

import java.util.UUID;

import cn.lineai.model.harness.AgentEvidence;
import cn.lineai.model.harness.AgentTaskStore;
import cn.lineai.model.harness.EvidenceLevel;
import cn.lineai.model.harness.TaskVerdict;

/**
 * Persists evidence rows for a task (LCP-Harness v1 §35.2, D05, P1 scope).
 *
 * <p>Each record stores a <em>summary + reference</em> to the original source row
 * ({@code tool_results}, {@code diff_records}) — never a full copy of the output.
 *
 * <p>Thread-safety: the underlying store ({@link AgentTaskStore}) is expected to be
 * thread-safe (SQLite in production, synchronized fake in tests); this class adds no state.
 */
public final class EvidenceRecorder {

    private final AgentTaskStore store;

    public EvidenceRecorder(AgentTaskStore store) {
        this.store = store;
    }

    /**
     * Record a classified tool result as OBSERVATION/EVIDENCE evidence.
     *
     * @param taskId   owning task
     * @param item     classified evidence from {@link EvidenceClassifier}
     * @param refTable source table, e.g. "tool_results"
     * @param refId    id of the source row
     */
    public void recordToolResult(String taskId, VerdictEngine.EvidenceItem item,
                                 String refTable, String refId) {
        if (taskId == null || item == null) return;

        AgentEvidence.Type type = item.level().atLeast(EvidenceLevel.DETERMINISTIC_CHECK)
                ? AgentEvidence.Type.EVIDENCE
                : AgentEvidence.Type.OBSERVATION;

        double strength = strengthFor(item.level(), item.failed());

        store.insertEvidence(
                UUID.randomUUID().toString(),
                taskId,
                type.name(),
                item.level().level(),
                "tool:" + (refId != null ? refTable : "unknown"),
                null,
                item.summary(),
                refTable,
                refId,
                strength);
    }

    /**
     * Record the result of a diff scope check as EVIDENCE (E2-class).
     */
    public void recordDiffScopeCheck(String taskId, DiffScopeChecker.ScopeCheckResult result) {
        if (taskId == null || result == null) return;

        AgentEvidence.Type type = result.passed()
                ? AgentEvidence.Type.EVIDENCE
                : AgentEvidence.Type.OBSERVATION; // flagged diffs are observations needing review

        EvidenceLevel level = EvidenceLevel.DETERMINISTIC_CHECK;
        double strength = result.passed() ? 0.9 : 0.5;

        store.insertEvidence(
                UUID.randomUUID().toString(),
                taskId,
                type.name(),
                level.level(),
                "diff",
                null,
                result.summary(),
                "diff_records",
                null,
                strength);
    }

    /**
     * Record the final verdict of a task.
     */
    public void recordVerdict(String taskId, TaskVerdict verdict) {
        if (taskId == null || verdict == null) return;

        store.insertEvidence(
                UUID.randomUUID().toString(),
                taskId,
                AgentEvidence.Type.VERDICT.name(),
                EvidenceLevel.MODEL_ASSERTION.level(), // verdict itself is not independent evidence
                "verdict-engine",
                null,
                "verdict: " + verdict.name().toLowerCase(java.util.Locale.US),
                null,
                null,
                verdict.allowsCompletion() ? 1.0 : 0.3);
    }

    private static double strengthFor(EvidenceLevel level, boolean failed) {
        if (failed) return 0.1;
        switch (level) {
            case MODEL_ASSERTION:          return 0.2;
            case TOOL_OBSERVATION:         return 0.6;
            case DETERMINISTIC_CHECK:      return 0.85;
            case INDEPENDENT_VERIFICATION: return 0.95;
            case USER_CONFIRMATION:        return 1.0;
            default:                       return 0.2;
        }
    }
}
