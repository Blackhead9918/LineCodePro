package cn.lineai.model.harness;

import static org.junit.Assert.*;

import org.junit.Test;

/**
 * Unit tests for {@link TaskBudget} (§45.9, §45.21).
 *
 * <p>Tests: parsing, defaults, boundary values, budget exhaustion, hard caps.
 */
public class TaskBudgetTest {

    @Test
    public void defaults_has_standard_limits() {
        TaskBudget b = TaskBudget.defaults();
        assertEquals(30, b.toolCallMax());
        assertEquals(2, b.subAgentMax());
        assertEquals(4, b.parallelMax());
        assertEquals(0, b.tokenMax()); // unlimited
    }

    @Test
    public void unlimited_all_zeros() {
        TaskBudget b = TaskBudget.unlimited();
        assertEquals(0, b.toolCallMax());
        assertEquals(0, b.subAgentMax());
        assertEquals(0, b.tokenMax());
    }

    @Test
    public void parse_valid_json() {
        TaskBudget b = TaskBudget.parse("{\"token\":30000,\"tool_call\":50,\"sub_agent\":5}");
        assertEquals(30000, b.tokenMax());
        assertEquals(50, b.toolCallMax());
        assertEquals(5, b.subAgentMax());
        assertEquals(0, b.timeSecMax()); // not specified
    }

    @Test
    public void parse_null_returns_defaults() {
        TaskBudget b = TaskBudget.parse(null);
        assertEquals(TaskBudget.defaults().toolCallMax(), b.toolCallMax());
    }

    @Test
    public void parse_empty_string_returns_defaults() {
        TaskBudget b = TaskBudget.parse("");
        assertEquals(TaskBudget.defaults().toolCallMax(), b.toolCallMax());
    }

    @Test
    public void parse_invalid_json_returns_defaults() {
        TaskBudget b = TaskBudget.parse("not json");
        assertEquals(TaskBudget.defaults().toolCallMax(), b.toolCallMax());
    }

    @Test
    public void hard_cap_tool_calls() {
        TaskBudget b = TaskBudget.parse("{\"tool_call\":200}");
        assertEquals(TaskBudget.HARD_MAX_TOOL_CALLS, b.toolCallMax());
    }

    @Test
    public void hard_cap_sub_agents() {
        TaskBudget b = TaskBudget.parse("{\"sub_agent\":50}");
        assertEquals(TaskBudget.HARD_MAX_SUB_AGENTS, b.subAgentMax());
    }

    @Test
    public void isToolCallAllowed_within_budget() {
        TaskBudget b = TaskBudget.parse("{\"tool_call\":10}");
        assertTrue(b.isToolCallAllowed(5));
        assertTrue(b.isToolCallAllowed(9));
    }

    @Test
    public void isToolCallAllowed_at_budget() {
        TaskBudget b = TaskBudget.parse("{\"tool_call\":10}");
        assertFalse(b.isToolCallAllowed(10));
    }

    @Test
    public void isToolCallAllowed_unlimited() {
        TaskBudget b = TaskBudget.unlimited();
        assertTrue(b.isToolCallAllowed(1000));
    }

    @Test
    public void isSubAgentAllowed_within_budget() {
        TaskBudget b = TaskBudget.parse("{\"sub_agent\":3}");
        assertTrue(b.isSubAgentAllowed(2));
    }

    @Test
    public void isSubAgentAllowed_at_budget() {
        TaskBudget b = TaskBudget.parse("{\"sub_agent\":3}");
        assertFalse(b.isSubAgentAllowed(3));
    }

    @Test
    public void round_trip_json() {
        TaskBudget original = TaskBudget.parse("{\"token\":5000,\"tool_call\":20,\"parallel\":3}");
        String json = original.toJson();
        TaskBudget restored = TaskBudget.parse(json);
        assertEquals(original.tokenMax(), restored.tokenMax());
        assertEquals(original.toolCallMax(), restored.toolCallMax());
        assertEquals(original.parallelMax(), restored.parallelMax());
    }

    @Test
    public void unlimited_json_round_trip() {
        TaskBudget original = TaskBudget.unlimited();
        String json = original.toJson();
        // unlimited() produces all zeros → toJson() returns null
        assertNull(json);
    }
}
