package cn.lineai.model.harness;

import static org.junit.Assert.*;

import java.util.Map;
import java.util.Set;

import org.junit.Test;

/**
 * Unit tests for {@link TaskStatus} enum and its transition map.
 */
public class TaskStatusTest {

    @Test
    public void terminal_states_are_terminal() {
        assertTrue(TaskStatus.COMPLETED.isTerminal());
        assertTrue(TaskStatus.FAILED.isTerminal());
        assertTrue(TaskStatus.CANCELLED.isTerminal());
    }

    @Test
    public void active_states_are_not_terminal() {
        assertFalse(TaskStatus.CREATED.isTerminal());
        assertFalse(TaskStatus.PLANNING.isTerminal());
        assertFalse(TaskStatus.EXECUTING.isTerminal());
        assertFalse(TaskStatus.VERIFYING.isTerminal());
        assertFalse(TaskStatus.BLOCKED.isTerminal());
        assertFalse(TaskStatus.WAITING_USER.isTerminal());
    }

    @Test
    public void interrupted_is_not_terminal() {
        // INTERRUPTED can transition to FAILED or CANCELLED
        assertFalse(TaskStatus.INTERRUPTED.isTerminal());
    }

    @Test
    public void isActive_true_for_non_terminal_non_interrupted() {
        assertTrue(TaskStatus.CREATED.isActive());
        assertTrue(TaskStatus.EXECUTING.isActive());
        assertFalse(TaskStatus.INTERRUPTED.isActive());
        assertFalse(TaskStatus.COMPLETED.isActive());
    }

    @Test
    public void fromString_case_insensitive() {
        assertEquals(TaskStatus.CREATED, TaskStatus.fromString("created"));
        assertEquals(TaskStatus.CREATED, TaskStatus.fromString("CREATED"));
        assertEquals(TaskStatus.CREATED, TaskStatus.fromString("Created"));
    }

    @Test
    public void fromString_invalid_returns_null() {
        assertNull(TaskStatus.fromString("INVALID"));
        assertNull(TaskStatus.fromString(""));
        assertNull(TaskStatus.fromString(null));
    }

    @Test
    public void validTransitions_map_has_all_states() {
        Map<TaskStatus, Set<TaskStatus>> transitions = TaskStatus.validTransitions();
        assertEquals(10, transitions.size());
        for (TaskStatus s : TaskStatus.values()) {
            assertTrue("Missing transition map for " + s, transitions.containsKey(s));
        }
    }

    @Test
    public void terminal_states_have_empty_transition_sets() {
        Map<TaskStatus, Set<TaskStatus>> transitions = TaskStatus.validTransitions();
        assertTrue(transitions.get(TaskStatus.COMPLETED).isEmpty());
        assertTrue(transitions.get(TaskStatus.FAILED).isEmpty());
        assertTrue(transitions.get(TaskStatus.CANCELLED).isEmpty());
    }

    @Test
    public void interrupted_transitions_to_failed_or_cancelled() {
        Map<TaskStatus, Set<TaskStatus>> transitions = TaskStatus.validTransitions();
        Set<TaskStatus> targets = transitions.get(TaskStatus.INTERRUPTED);
        assertEquals(2, targets.size());
        assertTrue(targets.contains(TaskStatus.FAILED));
        assertTrue(targets.contains(TaskStatus.CANCELLED));
    }
}
