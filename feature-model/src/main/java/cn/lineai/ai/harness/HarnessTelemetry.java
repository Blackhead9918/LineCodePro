package cn.lineai.ai.harness;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Lightweight in-memory counters for harness events (telemetry, P4 polish).
 *
 * <p>Purpose: make field behavior *measurable* — how often reasoning-only responses
 * are promoted, recovery runs, budgets exhaust, learning candidates fire. Counters are
 * process-local (no persistence, no network) and reset with the process; a debug
 * surface can read {@link #snapshot()} later.
 *
 * <p>Thread-safety: all methods thread-safe.
 */
public final class HarnessTelemetry {

    /** Canonical event names. */
    public static final String REASONING_PROMOTED = "reasoning_promoted";
    public static final String TASK_RECOVERY = "task_recovery";
    public static final String TOOL_BUDGET_EXHAUSTED = "tool_budget_exhausted";
    public static final String LEARNING_CANDIDATE = "learning_candidate";
    public static final String VERDICT_VERIFIED = "verdict_verified";
    public static final String VERDICT_PARTIALLY_VERIFIED = "verdict_partially_verified";
    public static final String VERDICT_FAILED = "verdict_failed";

    private HarnessTelemetry() {} // static registry

    private static final Map<String, AtomicLong> COUNTERS = new LinkedHashMap<>();

    public static void increment(String event) {
        if (event == null || event.isEmpty()) return;
        synchronized (COUNTERS) {
            COUNTERS.computeIfAbsent(event, k -> new AtomicLong()).incrementAndGet();
        }
    }

    /** Current value of one counter (0 when never recorded). */
    public static long count(String event) {
        synchronized (COUNTERS) {
            AtomicLong v = COUNTERS.get(event);
            return v != null ? v.get() : 0;
        }
    }

    /** Copy of all counters, for debug screens or log dumps. */
    public static Map<String, Long> snapshot() {
        synchronized (COUNTERS) {
            Map<String, Long> out = new LinkedHashMap<>();
            for (Map.Entry<String, AtomicLong> e : COUNTERS.entrySet()) {
                out.put(e.getKey(), e.getValue().get());
            }
            return out;
        }
    }

    /** Test/debug only. */
    static void resetForTest() {
        synchronized (COUNTERS) {
            COUNTERS.clear();
        }
    }
}
