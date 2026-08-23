package cn.lineai.ai.harness;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Adjusts effective resource limits from observed execution outcomes
 * (LCP-Harness v1 §26, P4 "adaptive resource governor").
 *
 * <p>Strategy — conservative and explainable:
 * <ul>
 *   <li>Failure rate over the recent window ≥ 50% → halve parallelism, shrink tool budget to 60%.</li>
 *   <li>Failure rate ≥ 25% → reduce parallelism by a quarter.</li>
 *   <li>Failure rate ≤ 10% with a full window → restore base limits.</li>
 * </ul>
 *
 * <p>The governor never raises limits above the user-configured base (no silent budget
 * growth on a device), it only recovers back to base.
 *
 * <p>Thread-safety: all methods synchronized; window is bounded.
 */
public final class AdaptiveResourceGovernor {

    /** Sliding window size for outcome statistics. */
    public static final int DEFAULT_WINDOW = 20;

    private static final double REDUCE_HALF_FAILURE_RATE = 0.50;
    private static final double REDUCE_QUARTER_FAILURE_RATE = 0.25;
    private static final double RECOVER_FAILURE_RATE = 0.10;

    private final int windowSize;
    private final Deque<Boolean> outcomes = new ArrayDeque<>();

    public AdaptiveResourceGovernor() {
        this(DEFAULT_WINDOW);
    }

    public AdaptiveResourceGovernor(int windowSize) {
        this.windowSize = Math.max(4, windowSize);
    }

    /**
     * Record one execution outcome (a tool call or generation attempt).
     *
     * @param success true when the call completed without failure
     */
    public synchronized void recordOutcome(boolean success) {
        outcomes.addLast(success);
        while (outcomes.size() > windowSize) {
            outcomes.removeFirst();
        }
    }

    /** Current failure rate over the filled portion of the window (0..1). */
    public synchronized double failureRate() {
        if (outcomes.isEmpty()) return 0;
        int failures = 0;
        for (Boolean ok : outcomes) {
            if (!ok) failures++;
        }
        return (double) failures / outcomes.size();
    }

    /**
     * Effective parallel tool limit given the configured base
     * ({@code TaskBudget#parallelMax()} or the scheduler's 4-thread pool).
     */
    public synchronized int effectiveParallelLimit(int baseLimit) {
        if (baseLimit <= 0) return baseLimit;
        double rate = failureRate();
        if (outcomes.size() >= minSamplesForAdjustment() && rate >= REDUCE_HALF_FAILURE_RATE) {
            return Math.max(1, baseLimit / 2);
        }
        if (outcomes.size() >= minSamplesForAdjustment() && rate >= REDUCE_QUARTER_FAILURE_RATE) {
            return Math.max(1, (baseLimit * 3) / 4);
        }
        return baseLimit;
    }

    /**
     * Effective remaining tool-call budget multiplier applied to the base budget.
     */
    public synchronized int effectiveToolBudget(int baseBudget) {
        if (baseBudget <= 0) return baseBudget;
        double rate = failureRate();
        if (outcomes.size() >= minSamplesForAdjustment() && rate >= REDUCE_HALF_FAILURE_RATE) {
            return Math.max(1, (int) (baseBudget * 0.6));
        }
        return baseBudget;
    }

    /** True once enough samples exist for the governor to act. */
    public synchronized boolean isAdjusted() {
        return outcomes.size() >= minSamplesForAdjustment()
                && failureRate() >= REDUCE_QUARTER_FAILURE_RATE;
    }

    private int minSamplesForAdjustment() {
        return Math.max(2, windowSize / 2);
    }

    /** Reset statistics on conversation switch (§45.20). */
    public synchronized void reset() {
        outcomes.clear();
    }
}
