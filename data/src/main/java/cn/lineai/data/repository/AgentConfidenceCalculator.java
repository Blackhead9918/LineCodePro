package cn.lineai.data.repository;

import cn.lineai.model.grounding.AgentConfidenceReport;
import java.util.ArrayList;
import java.util.List;

/**
 * Calculates evidence-grounded confidence scores for agent actions.
 */
public final class AgentConfidenceCalculator {
    private static final AgentConfidenceCalculator INSTANCE = new AgentConfidenceCalculator();

    private AgentConfidenceCalculator() {
    }

    public static AgentConfidenceCalculator getInstance() {
        return INSTANCE;
    }

    /**
     * Evaluates confidence for a proposed action on a given target.
     *
     * @param targetPath Path of file/resource or command to be executed
     * @param isRemote True if target is on remote SSH host
     * @param host Remote host (if applicable)
     * @param port Remote port (if applicable)
     * @param actionType Tool/action name (e.g. file_edit, shell_execute)
     * @return AgentConfidenceReport with itemized breakdown
     */
    public AgentConfidenceReport evaluateConfidence(
            String targetPath,
            boolean isRemote,
            String host,
            int port,
            String actionType
    ) {
        List<String> notes = new ArrayList<>();

        // 1. Grounded Factor (Weight: 0.40)
        double groundedFactor;
        if (targetPath == null || targetPath.trim().isEmpty()) {
            groundedFactor = 0.50;
            notes.add("No specific file target; general context applies.");
        } else {
            boolean isGrounded = isRemote
                    ? GroundedStateManager.getInstance().isRemoteGrounded(host, port, targetPath)
                    : GroundedStateManager.getInstance().isGrounded(targetPath);
            if (isGrounded) {
                groundedFactor = 1.0;
                notes.add("Target is physically grounded with verified hash snapshot.");
            } else if (actionType != null && (actionType.contains("read") || actionType.contains("list") || actionType.contains("outline"))) {
                groundedFactor = 0.90;
                notes.add("Read/discovery operation; grounding will be established upon completion.");
            } else {
                groundedFactor = 0.25;
                notes.add("Target is ungrounded prior to mutation; risk of hallucination or mismatch.");
            }
        }

        // 2. Memory Invariant Alignment Factor (Weight: 0.30)
        double memoryFactor = 0.70;
        List<ScopedMemoryRule> rules = ScopedMemoryRegistry.getInstance().selectEligibleRules(targetPath, null);
        if (rules != null && !rules.isEmpty()) {
            memoryFactor = 0.95;
            notes.add("Aligned with " + rules.size() + " validated workspace memory rule(s).");
        } else {
            notes.add("Default baseline heuristics active (no specific invariant overridden).");
        }

        // 3. Execution History & Success Rate Factor (Weight: 0.30)
        AgentAccuracyLogger.AccuracyStats stats = AgentAccuracyLogger.getInstance().getStats();
        double historyFactor = stats.getTotalActions() > 0 ? stats.getSuccessRate() : 0.85;
        notes.add(String.format("Recent tool execution success rate: %.1f%% (%d/%d)",
                historyFactor * 100, stats.getSuccessfulActions(), stats.getTotalActions()));

        // 4. Retry / Repeated Error Penalty
        int recentFailures = AgentAccuracyLogger.getInstance().getRecentFailureCount(targetPath);
        double retryPenalty = Math.min(0.40, recentFailures * 0.15);
        if (retryPenalty > 0) {
            notes.add(String.format("Applied retry penalty of -%.2f due to %d recent failure(s) on target.",
                    retryPenalty, recentFailures));
        }

        // Aggregate Score
        double rawScore = (groundedFactor * 0.40) + (memoryFactor * 0.30) + (historyFactor * 0.30) - retryPenalty;
        double totalScore = Math.max(0.05, Math.min(1.0, rawScore));

        return new AgentConfidenceReport(
                totalScore,
                groundedFactor,
                memoryFactor,
                historyFactor,
                retryPenalty,
                notes
        );
    }
}
