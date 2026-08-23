package cn.lineai.ai.harness;

import static org.junit.Assert.*;

import java.util.Arrays;

import org.junit.Test;

import cn.lineai.model.harness.ContextItem;
import cn.lineai.model.harness.ProjectSnapshot;

/**
 * Unit tests for {@link FreshnessEvaluator} (§7).
 */
public class FreshnessEvaluatorTest {

    private final FreshnessEvaluator evaluator = new FreshnessEvaluator();

    private static ContextItem itemUpdatedAgo(long agoMs, long verifiedAt) {
        long now = System.currentTimeMillis();
        return new ContextItem.Builder("f1", "content")
                .source("file")
                .updatedAt(now - agoMs)
                .lastVerifiedAt(verifiedAt > 0 ? now - verifiedAt : 0)
                .build();
    }

    @Test
    public void recent_item_without_verification_is_cache_hit() {
        ContextItem item = itemUpdatedAgo(60_000, 0); // updated 1 min ago, never verified
        assertEquals(FreshnessEvaluator.Freshness.CACHE_HIT, evaluator.evaluate(item));
    }

    @Test
    public void recently_verified_item_is_cache_hit() {
        ContextItem item = itemUpdatedAgo(10 * 60_000, 60_000); // old write but verified 1 min ago
        assertEquals(FreshnessEvaluator.Freshness.CACHE_HIT, evaluator.evaluate(item));
    }

    @Test
    public void old_unverified_item_is_cache_stale() {
        ContextItem item = itemUpdatedAgo(10 * 60_000, 0); // 10 min old, never verified
        assertEquals(FreshnessEvaluator.Freshness.CACHE_STALE, evaluator.evaluate(item));
    }

    @Test
    public void old_verification_is_cache_stale() {
        ContextItem item = itemUpdatedAgo(30 * 60_000, 10 * 60_000);
        assertEquals(FreshnessEvaluator.Freshness.CACHE_STALE, evaluator.evaluate(item));
    }

    @Test
    public void immutable_items_never_go_stale() {
        long now = System.currentTimeMillis();
        ContextItem item = new ContextItem.Builder("sys", "system prompt")
                .immutable(true)
                .updatedAt(now - 24 * 3600_000L)
                .build();
        assertEquals(FreshnessEvaluator.Freshness.CACHE_HIT, evaluator.evaluate(item));
    }

    @Test
    public void null_item_requires_refresh() {
        assertEquals(FreshnessEvaluator.Freshness.REFRESH_REQUIRED, evaluator.evaluate(null));
    }

    // ---- requiresRefresh (§7 triggers) ----

    @Test
    public void force_refresh_always_refreshes() {
        ContextItem item = itemUpdatedAgo(0, 0);
        assertTrue(evaluator.requiresRefresh(item, false, true));
    }

    @Test
    public void own_pipeline_writes_do_not_require_refresh() {
        // Harness wrote the file itself — no external drift assumed
        ContextItem stale = itemUpdatedAgo(60 * 60_000, 0);
        assertFalse(evaluator.requiresRefresh(stale, true, false));
    }

    @Test
    public void fresh_cache_does_not_require_refresh() {
        ContextItem item = itemUpdatedAgo(0, 0);
        assertFalse(evaluator.requiresRefresh(item, false, false));
    }

    @Test
    public void stale_cache_requires_refresh_before_dependent_operation() {
        ContextItem item = itemUpdatedAgo(60 * 60_000, 0);
        assertTrue(evaluator.requiresRefresh(item, false, false));
    }

    // ---- Snapshot freshness ----

    @Test
    public void null_snapshot_requires_refresh() {
        assertEquals(FreshnessEvaluator.Freshness.REFRESH_REQUIRED,
                evaluator.evaluateSnapshot(null, System.currentTimeMillis()));
    }

    @Test
    public void snapshot_after_last_change_is_hit() {
        ProjectSnapshot s = new ProjectSnapshot.Builder("p1")
                .timestamp(System.currentTimeMillis())
                .build();
        assertEquals(FreshnessEvaluator.Freshness.CACHE_HIT,
                evaluator.evaluateSnapshot(s, System.currentTimeMillis() - 1000));
    }

    @Test
    public void snapshot_older_than_last_change_is_stale() {
        ProjectSnapshot s = new ProjectSnapshot.Builder("p1")
                .timestamp(System.currentTimeMillis() - 5000)
                .build();
        assertEquals(FreshnessEvaluator.Freshness.CACHE_STALE,
                evaluator.evaluateSnapshot(s, System.currentTimeMillis()));
    }

    @Test
    public void no_external_change_means_hit_even_for_old_snapshot() {
        ProjectSnapshot s = new ProjectSnapshot.Builder("p1")
                .timestamp(System.currentTimeMillis() - 5000)
                .build();
        assertEquals(FreshnessEvaluator.Freshness.CACHE_HIT,
                evaluator.evaluateSnapshot(s, 0));
    }

    // ---- Custom threshold ----

    @Test
    public void custom_threshold_is_respected() {
        FreshnessEvaluator strict = new FreshnessEvaluator(1000); // 1 second
        ContextItem item = itemUpdatedAgo(2000, 0);
        assertEquals(FreshnessEvaluator.Freshness.CACHE_STALE, strict.evaluate(item));
    }
}
