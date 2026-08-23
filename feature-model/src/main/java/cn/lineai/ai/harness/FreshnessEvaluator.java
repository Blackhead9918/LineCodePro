package cn.lineai.ai.harness;

import java.util.Locale;

import cn.lineai.model.harness.ContextItem;
import cn.lineai.model.harness.ProjectSnapshot;

/**
 * Decides whether cached context is still trustworthy (LCP-Harness v1 §7).
 *
 * <p>Code context is never assumed fresh. There is no filesystem watcher —
 * staleness is detected from timestamps and the harness's own knowledge of
 * which files its pipeline changed (on-demand refresh, §32).
 *
 * <p>Thread-safety: stateless — safe for concurrent use.
 */
public final class FreshnessEvaluator {

    /** Default age after which unverified code context is considered stale. */
    public static final long DEFAULT_STALE_MS = 5 * 60 * 1000; // 5 minutes

    private final long staleThresholdMs;

    public FreshnessEvaluator() {
        this(DEFAULT_STALE_MS);
    }

    public FreshnessEvaluator(long staleThresholdMs) {
        this.staleThresholdMs = staleThresholdMs;
    }

    /**
     * Evaluate a single context item.
     */
    public Freshness evaluate(ContextItem item) {
        if (item == null) return Freshness.REFRESH_REQUIRED;
        if (item.isImmutable()) return Freshness.CACHE_HIT;

        long now = System.currentTimeMillis();
        boolean verifiedBefore = item.lastVerifiedAt() > 0;

        if (verifiedBefore && now - item.lastVerifiedAt() <= staleThresholdMs) {
            return Freshness.CACHE_HIT;
        }
        if (!verifiedBefore && now - item.updatedAt() <= staleThresholdMs) {
            // Recently produced by our own pipeline (e.g. tool just read the file)
            return Freshness.CACHE_HIT;
        }
        return Freshness.CACHE_STALE;
    }

    /**
     * Decide if an operation touching {@code targetScope} requires a snapshot refresh
     * before it can proceed (§7 trigger list).
     *
     * @param item           the cached item for the scope (nullable = no cache → refresh)
     * @param pipelineTouched true if the harness itself wrote/edited this scope during
     *                        the current task — own writes are known-fresh
     * @param forceRefresh   explicit user refresh request
     */
    public boolean requiresRefresh(ContextItem item, boolean pipelineTouched, boolean forceRefresh) {
        if (forceRefresh) return true;
        if (pipelineTouched) return false;      // we caused the change; cache reflects post-write state only after re-read, but own action means no external drift
        return evaluate(item) != Freshness.CACHE_HIT;
    }

    /**
     * Check a whole snapshot against the harness's record of recently changed files:
     * any overlap between snapshot's tracked recent changes and externally-observed
     * changes invalidates the structural parts of the cache.
     */
    public Freshness evaluateSnapshot(ProjectSnapshot snapshot, long lastExternalChangeAt) {
        if (snapshot == null) return Freshness.REFRESH_REQUIRED;
        if (lastExternalChangeAt <= 0) return Freshness.CACHE_HIT;
        // Snapshot predates the latest known change → stale
        return snapshot.timestamp() >= lastExternalChangeAt
                ? Freshness.CACHE_HIT
                : Freshness.CACHE_STALE;
    }

    /** Freshness verdicts (§7). */
    public enum Freshness {
        CACHE_HIT,
        CACHE_STALE,
        REFRESH_REQUIRED;

        @Override
        public String toString() {
            return name().toLowerCase(Locale.US);
        }
    }
}
