package cn.lineai.ai.harness;

import static org.junit.Assert.*;

import org.junit.Before;
import org.junit.Test;

import cn.lineai.model.harness.TaskBudget;

/**
 * Unit tests for {@link SubAgentBudgetLedger} (§23, Invariant 8, P4).
 */
public class SubAgentBudgetLedgerTest {

    private SubAgentBudgetLedger ledger;

    @Before
    public void setUp() {
        ledger = new SubAgentBudgetLedger();
    }

    @Test
    public void acquire_within_per_task_budget_succeeds() {
        TaskBudget budget = TaskBudget.parse("{\"sub_agent\":2}");
        assertNotNull(ledger.tryAcquire("t1", budget));
        assertNotNull(ledger.tryAcquire("t1", budget));
        assertEquals(2, ledger.activeCountForTask("t1"));
    }

    @Test
    public void acquire_beyond_per_task_budget_rejected() {
        TaskBudget budget = TaskBudget.parse("{\"sub_agent\":2}");
        ledger.tryAcquire("t1", budget);
        ledger.tryAcquire("t1", budget);
        assertNull(ledger.tryAcquire("t1", budget)); // per-task cap
    }

    @Test
    public void per_task_caps_are_independent() {
        TaskBudget one = TaskBudget.parse("{\"sub_agent\":1}");
        assertNotNull(ledger.tryAcquire("t1", one));
        assertNull(ledger.tryAcquire("t1", one));
        assertNotNull(ledger.tryAcquire("t2", one)); // other task unaffected
    }

    @Test
    public void session_cap_limits_cross_task_total() {
        SubAgentBudgetLedger small = new SubAgentBudgetLedger(3);
        TaskBudget unlimited = TaskBudget.parse("{}"); // sub_agent 0 = unlimited-ish
        // unlimited per-task → falls back to Integer.MAX_VALUE; session cap 3 binds
        assertNotNull(small.tryAcquire("t1", unlimited));
        assertNotNull(small.tryAcquire("t2", unlimited));
        assertNotNull(small.tryAcquire("t3", unlimited));
        assertNull(small.tryAcquire("t4", unlimited));
        assertEquals(3, small.activeTotal());
    }

    @Test
    public void release_frees_slot_for_new_acquire() {
        TaskBudget budget = TaskBudget.parse("{\"sub_agent\":1}");
        String token = ledger.tryAcquire("t1", budget);
        assertNull(ledger.tryAcquire("t1", budget));
        assertTrue(ledger.release(token));
        assertNotNull(ledger.tryAcquire("t1", budget));
    }

    @Test
    public void release_is_idempotent_and_null_safe() {
        assertFalse(ledger.release(null));
        assertFalse(ledger.release("unknown-token"));
        assertTrue(ledger.activeTotal() == 0);
    }

    @Test
    public void release_of_one_task_does_not_affect_other() {
        TaskBudget b = TaskBudget.parse("{\"sub_agent\":1}");
        String t1Token = ledger.tryAcquire("t1", b);
        ledger.tryAcquire("t2", b);
        ledger.release(t1Token);
        assertEquals(0, ledger.activeCountForTask("t1"));
        assertEquals(1, ledger.activeCountForTask("t2"));
    }

    @Test
    public void total_spawned_counts_cumulative_acquires() {
        TaskBudget b = TaskBudget.parse("{\"sub_agent\":5}");
        for (int i = 0; i < 4; i++) {
            String token = ledger.tryAcquire("t1", b);
            ledger.release(token);
        }
        assertEquals(4, ledger.totalSpawned());
        assertEquals(0, ledger.activeTotal()); // all released
    }

    @Test
    public void hard_session_cap_enforced_regardless_of_request() {
        SubAgentBudgetLedger capped = new SubAgentBudgetLedger(99); // request above hard cap
        TaskBudget b = TaskBudget.unlimited();
        int admitted = 0;
        for (int i = 0; i < 20; i++) {
            if (capped.tryAcquire("task" + i, b) != null) admitted++;
        }
        assertEquals(SubAgentBudgetLedger.HARD_SESSION_CAP, admitted);
    }

    @Test
    public void clear_all_releases_everything() {
        TaskBudget b = TaskBudget.parse("{\"sub_agent\":3}");
        ledger.tryAcquire("t1", b);
        ledger.clearAll();
        assertEquals(0, ledger.activeTotal());
        assertNotNull(ledger.tryAcquire("t1", b));
    }

    @Test
    public void null_arguments_rejected_safely() {
        assertNull(ledger.tryAcquire(null, TaskBudget.defaults()));
        assertNull(ledger.tryAcquire("t1", null));
    }
}
