package cn.lineai.model.grounding;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Quantifies agent confidence based on empirical evidence:
 * Grounded state, memory invariant match, recent success history, and retry penalty.
 */
public final class AgentConfidenceReport {
    public enum Level {
        HIGH,
        MEDIUM,
        LOW
    }

    private final double totalScore; // 0.0 - 1.0
    private final Level level;
    private final double groundedFactor;
    private final double memoryFactor;
    private final double historyFactor;
    private final double retryPenalty;
    private final List<String> reasoningNotes;

    public AgentConfidenceReport(
            double totalScore,
            double groundedFactor,
            double memoryFactor,
            double historyFactor,
            double retryPenalty,
            List<String> reasoningNotes
    ) {
        this.totalScore = Math.max(0.0, Math.min(1.0, totalScore));
        if (this.totalScore >= 0.85) {
            this.level = Level.HIGH;
        } else if (this.totalScore >= 0.60) {
            this.level = Level.MEDIUM;
        } else {
            this.level = Level.LOW;
        }
        this.groundedFactor = groundedFactor;
        this.memoryFactor = memoryFactor;
        this.historyFactor = historyFactor;
        this.retryPenalty = retryPenalty;
        this.reasoningNotes = reasoningNotes == null ? Collections.emptyList() : new ArrayList<>(reasoningNotes);
    }

    public double getTotalScore() {
        return totalScore;
    }

    public Level getLevel() {
        return level;
    }

    public double getGroundedFactor() {
        return groundedFactor;
    }

    public double getMemoryFactor() {
        return memoryFactor;
    }

    public double getHistoryFactor() {
        return historyFactor;
    }

    public double getRetryPenalty() {
        return retryPenalty;
    }

    public List<String> getReasoningNotes() {
        return Collections.unmodifiableList(reasoningNotes);
    }

    @Override
    public String toString() {
        return String.format("AgentConfidenceReport[score=%.2f, level=%s, grounded=%.2f, memory=%.2f, history=%.2f, penalty=%.2f]",
                totalScore, level, groundedFactor, memoryFactor, historyFactor, retryPenalty);
    }
}
