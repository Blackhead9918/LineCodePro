package cn.lineai.ai.harness;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import cn.lineai.model.harness.MemoryCandidate;

/**
 * Detects repeated failure patterns and emits a learning candidate
 * (LCP-Harness v1 §18: TASK RESULT → EXTRACT LESSON → CHECK EVIDENCE → COMMIT/REJECT).
 *
 * <p>Rule of thumb from the spec: a command/approach that fails N independent times
 * (default 3, each from a distinct generation attempt) becomes a FAILURE_PATTERN
 * candidate — e.g. "Gradle command X always fails on this project; use Y".
 *
 * <p><b>Not thread-safe by design</b>: confine to the generation thread like other
 * harness state (§45.13). Wrap with external synchronization if shared.
 */
public final class FailurePatternTracker {

    /** Distinct failures required before a candidate is emitted. */
    public static final int DEFAULT_THRESHOLD = 3;

    private final int threshold;
    private final Map<String, FailureRecord> records = new LinkedHashMap<>();

    public FailurePatternTracker() {
        this(DEFAULT_THRESHOLD);
    }

    public FailurePatternTracker(int threshold) {
        this.threshold = Math.max(2, threshold);
    }

    /**
     * Record one failure observation.
     *
     * @param signature stable key for the failing thing, e.g. "shell:gradle:assembleDebug"
     * @param detail    short human-readable detail (error message, command)
     * @return candidate if the threshold is newly reached, otherwise null
     */
    public MemoryCandidate recordFailure(String signature, String detail) {
        if (signature == null || signature.isEmpty()) return null;
        long now = System.currentTimeMillis();
        FailureRecord r = records.get(signature);
        if (r == null) {
            r = new FailureRecord();
            records.put(signature, r);
        }
        r.count++;
        r.lastDetail = detail != null ? detail : "";
        r.lastAt = now;

        if (r.count == threshold) { // emit exactly once per crossing
            return new MemoryCandidate(
                    UUID.randomUUID().toString(),
                    MemoryCandidate.MemoryClass.FAILURE_PATTERN,
                    MemoryCandidate.Source.FAILURE_PATTERN,
                    "Avoid '" + signature + "' — failed " + r.count +
                            " independent times. Last error: " + truncate(r.lastDetail, 200) +
                            ". Prefer an alternative approach.",
                    "",
                    0.85,
                    r.count + " independent failures",
                    now);
        }
        return null;
    }

    /**
     * A later success validates or clears the pattern: reset the counter.
     * Returns true if a pending candidate should be considered superseded.
     */
    public boolean recordSuccess(String signature) {
        if (signature == null) return false;
        FailureRecord r = records.get(signature);
        if (r == null) return false;
        boolean hadPendingCandidate = r.count >= threshold;
        records.remove(signature);
        return hadPendingCandidate;
    }

    /** Number of recorded failures for a signature (for tests / diagnostics). */
    public int failureCount(String signature) {
        FailureRecord r = records.get(signature);
        return r != null ? r.count : 0;
    }

    /** Clear all state on conversation switch (§45.20 cleanup convention). */
    public void clear() {
        records.clear();
    }

    private static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }

    private static final class FailureRecord {
        int count;
        String lastDetail = "";
        long lastAt;
    }
}
