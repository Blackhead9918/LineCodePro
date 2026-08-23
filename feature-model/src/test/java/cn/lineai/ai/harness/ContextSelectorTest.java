package cn.lineai.ai.harness;

import static org.junit.Assert.*;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.junit.Test;

import cn.lineai.model.harness.ContextItem;
import cn.lineai.model.harness.ContextPriority;

/**
 * Unit tests for {@link ContextSelector} (§6, §6.1).
 */
public class ContextSelectorTest {

    private static ContextItem item(String id, ContextPriority p, int tokens) {
        return new ContextItem.Builder(id, repeat('x', tokens * ContextItem.CHARS_PER_TOKEN))
                .priority(p)
                .updatedAt(System.currentTimeMillis())
                .build();
    }

    private static String repeat(char c, int n) {
        char[] arr = new char[n];
        java.util.Arrays.fill(arr, c);
        return new String(arr);
    }

    // ---- SELECT ----

    @Test
    public void select_keeps_highest_priority_first() {
        List<ContextItem> candidates = Arrays.asList(
                item("history", ContextPriority.P7_CONVERSATION_HISTORY, 100),
                item("tool", ContextPriority.P2_TOOL_RESULT, 100),
                item("user", ContextPriority.P1_USER_REQUEST, 100));
        // Budget only fits 200 tokens → user + tool in, history pruned
        ContextSelector.Selection s = ContextSelector.select(candidates, 220);
        assertEquals(2, s.included().size());
        assertEquals("user", s.included().get(0).id());
        assertEquals("tool", s.included().get(1).id());
        assertEquals(1, s.pruned().size());
        assertEquals("history", s.pruned().get(0).id());
    }

    @Test
    public void select_protected_items_always_included_even_over_budget() {
        List<ContextItem> candidates = Arrays.asList(
                item("capsule", ContextPriority.P0_SYSTEM_AND_CAPSULE, 500),
                item("user", ContextPriority.P1_USER_REQUEST, 500),
                item("memory", ContextPriority.P6_PERSISTENT_MEMORY, 50));
        ContextSelector.Selection s = ContextSelector.select(candidates, 400);
        assertTrue(s.included().stream().anyMatch(i -> i.id().equals("capsule")));
        assertTrue(s.included().stream().anyMatch(i -> i.id().equals("user")));
        assertFalse(s.included().stream().anyMatch(i -> i.id().equals("memory")));
        assertTrue(s.protectedOverflow());
    }

    @Test
    public void select_recency_wins_within_same_priority() {
        long now = System.currentTimeMillis();
        ContextItem oldTool = new ContextItem.Builder("old", "aaaaaaaaaaaaaaaa") // ~4 tokens
                .priority(ContextPriority.P2_TOOL_RESULT)
                .updatedAt(now - 10_000)
                .build();
        ContextItem newTool = new ContextItem.Builder("new", "bbbbbbbbbbbbbbbb") // ~4 tokens
                .priority(ContextPriority.P2_TOOL_RESULT)
                .updatedAt(now)
                .build();
        ContextSelector.Selection s = ContextSelector.select(Arrays.asList(oldTool, newTool), 4);
        assertEquals("new", s.included().get(0).id());
        assertEquals("old", s.pruned().get(0).id());
    }

    @Test
    public void select_empty_and_null_inputs_are_safe() {
        assertTrue(ContextSelector.select(Collections.emptyList(), 100).included().isEmpty());
        assertTrue(ContextSelector.select(null, 100).included().isEmpty());
    }

    @Test
    public void selection_reports_used_tokens() {
        List<ContextItem> candidates = Collections.singletonList(
                item("a", ContextPriority.P3_CODE_CONTEXT, 25));
        ContextSelector.Selection s = ContextSelector.select(candidates, 100);
        assertEquals(25, s.usedTokens());
        assertFalse(s.protectedOverflow());
    }

    // ---- PRUNE ----

    @Test
    public void prune_drops_lowest_priority_first() {
        List<ContextItem> items = new ArrayList<>(Arrays.asList(
                item("meta", ContextPriority.P8_METADATA, 50),
                item("code", ContextPriority.P3_CODE_CONTEXT, 50),
                item("capsule", ContextPriority.P0_SYSTEM_AND_CAPSULE, 50)));
        List<ContextItem> kept = ContextSelector.prune(items, 120);
        assertEquals(2, kept.size());
        assertTrue(kept.stream().noneMatch(i -> i.id().equals("meta")));   // P8 dropped first
        assertTrue(kept.stream().anyMatch(i -> i.id().equals("code")));
        assertTrue(kept.stream().anyMatch(i -> i.id().equals("capsule"))); // protected survives
    }

    @Test
    public void prune_never_drops_protected_while_others_remain() {
        List<ContextItem> items = new ArrayList<>(Arrays.asList(
                item("user", ContextPriority.P1_USER_REQUEST, 100),
                item("hist", ContextPriority.P7_CONVERSATION_HISTORY, 30)));
        List<ContextItem> kept = ContextSelector.prune(items, 100);
        assertTrue(kept.stream().anyMatch(i -> i.id().equals("user")));
        assertFalse(kept.stream().anyMatch(i -> i.id().equals("hist")));
    }

    @Test
    public void prune_noop_when_under_budget() {
        List<ContextItem> items = Arrays.asList(
                item("a", ContextPriority.P3_CODE_CONTEXT, 10),
                item("b", ContextPriority.P7_CONVERSATION_HISTORY, 10));
        List<ContextItem> kept = ContextSelector.prune(items, 100);
        assertEquals(2, kept.size());
    }

    @Test
    public void prune_null_returns_empty() {
        assertTrue(ContextSelector.prune(null, 100).isEmpty());
    }
}
