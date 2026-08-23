package cn.lineai.ai.harness;

import static org.junit.Assert.*;

import org.junit.Test;

/**
 * Unit tests for {@link AdaptiveResourceGovernor} (§26, P4).
 */
public class AdaptiveResourceGovernorTest {

    private void fill(AdaptiveResourceGovernor g, int successes, int failures) {
        for (int i = 0; i < successes; i++) g.recordOutcome(true);
        for (int i = 0; i < failures; i++) g.recordOutcome(false);
    }

    @Test
    public void fresh_governor_keeps_base_limits() {
        AdaptiveResourceGovernor g = new AdaptiveResourceGovernor();
        assertEquals(4, g.effectiveParallelLimit(4));
        assertEquals(30, g.effectiveToolBudget(30));
        assertFalse(g.isAdjusted());
        assertEquals(0.0, g.failureRate(), 0.0001);
    }

    @Test
    public void high_failure_rate_halves_parallelism() {
        AdaptiveResourceGovernor g = new AdaptiveResourceGovernor();
        fill(g, 5, 15); // 75% failure over full window (20)
        assertEquals(2, g.effectiveParallelLimit(4)); // halved
        assertEquals(18, g.effectiveToolBudget(30));  // 60%
        assertTrue(g.isAdjusted());
    }

    @Test
    public void moderate_failure_rate_reduces_quarter() {
        AdaptiveResourceGovernor g = new AdaptiveResourceGovernor(20);
        fill(g, 13, 7); // 35% failure → quarter-reduce band (25–50%)
        assertEquals(3, g.effectiveParallelLimit(4));
        assertEquals(30, g.effectiveToolBudget(30)); // tool budget only cut in severe band
    }

    @Test
    public void healthy_rate_restores_base_limits() {
        AdaptiveResourceGovernor g = new AdaptiveResourceGovernor(20);
        fill(g, 19, 1); // 5% failure
        assertEquals(4, g.effectiveParallelLimit(4));
        assertEquals(30, g.effectiveToolBudget(30));
        assertFalse(g.isAdjusted());
    }

    @Test
    public void limits_never_exceed_base_even_with_perfect_record() {
        AdaptiveResourceGovernor g = new AdaptiveResourceGovernor();
        fill(g, 20, 0);
        assertEquals(4, g.effectiveParallelLimit(4)); // never grows above base
        assertEquals(30, g.effectiveToolBudget(30));
    }

    @Test
    public void small_window_requires_min_samples_before_acting() {
        AdaptiveResourceGovernor g = new AdaptiveResourceGovernor(20);
        g.recordOutcome(false); // 100% failure but only 1 sample
        assertEquals(4, g.effectiveParallelLimit(4)); // no adjustment yet
    }

    @Test
    public void sliding_window_drops_old_outcomes() {
        AdaptiveResourceGovernor g = new AdaptiveResourceGovernor(10);
        fill(g, 0, 8);           // window of failures
        fill(g, 10, 0);          // push failures out; now all success
        double rate = g.failureRate();
        assertTrue(rate < 0.25);
        assertEquals(4, g.effectiveParallelLimit(4));
    }

    @Test
    public void parallelism_floor_is_one_not_zero() {
        AdaptiveResourceGovernor g = new AdaptiveResourceGovernor();
        fill(g, 0, 20);
        // severe band halves 4 → 2; floor of 1 only binds when base itself is 1
        assertEquals(2, g.effectiveParallelLimit(4));
        assertEquals(1, g.effectiveParallelLimit(1)); // floor: never drops below 1
        assertEquals(18, g.effectiveToolBudget(30)); // 60% of base in severe band
    }

    @Test
    public void zero_base_limit_passes_through() {
        AdaptiveResourceGovernor g = new AdaptiveResourceGovernor();
        fill(g, 0, 20);
        assertEquals(0, g.effectiveParallelLimit(0)); // unlimited semantics untouched
    }

    @Test
    public void reset_clears_statistics() {
        AdaptiveResourceGovernor g = new AdaptiveResourceGovernor();
        fill(g, 0, 20);
        g.reset();
        assertEquals(0.0, g.failureRate(), 0.0001);
        assertFalse(g.isAdjusted());
        assertEquals(4, g.effectiveParallelLimit(4));
    }
}
