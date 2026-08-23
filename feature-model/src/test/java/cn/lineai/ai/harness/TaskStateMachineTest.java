package cn.lineai.ai.harness;

import static org.junit.Assert.*;

import java.util.Set;

import org.junit.Test;

import cn.lineai.model.harness.TaskStatus;

/**
 * Unit tests for {@link TaskStateMachine} (§45.2, §45.21).
 *
 * <p>Tests: all valid transitions, all invalid transitions → exception,
 * terminal states have no transitions, self-transition is idempotent.
 */
public class TaskStateMachineTest {

    // ---- Valid transitions (§45.2) ----

    @Test
    public void created_to_planning_valid() {
        assertEquals(TaskStatus.PLANNING,
                TaskStateMachine.transition(TaskStatus.CREATED, TaskStatus.PLANNING));
    }

    @Test
    public void created_to_cancelled_valid() {
        assertEquals(TaskStatus.CANCELLED,
                TaskStateMachine.transition(TaskStatus.CREATED, TaskStatus.CANCELLED));
    }

    @Test
    public void created_to_failed_valid() {
        assertEquals(TaskStatus.FAILED,
                TaskStateMachine.transition(TaskStatus.CREATED, TaskStatus.FAILED));
    }

    @Test
    public void planning_to_executing_valid() {
        assertEquals(TaskStatus.EXECUTING,
                TaskStateMachine.transition(TaskStatus.PLANNING, TaskStatus.EXECUTING));
    }

    @Test
    public void planning_to_blocked_valid() {
        assertEquals(TaskStatus.BLOCKED,
                TaskStateMachine.transition(TaskStatus.PLANNING, TaskStatus.BLOCKED));
    }

    @Test
    public void executing_to_verifying_valid() {
        assertEquals(TaskStatus.VERIFYING,
                TaskStateMachine.transition(TaskStatus.EXECUTING, TaskStatus.VERIFYING));
    }

    @Test
    public void executing_to_waiting_user_valid() {
        assertEquals(TaskStatus.WAITING_USER,
                TaskStateMachine.transition(TaskStatus.EXECUTING, TaskStatus.WAITING_USER));
    }

    @Test
    public void executing_to_completed_valid() {
        assertEquals(TaskStatus.COMPLETED,
                TaskStateMachine.transition(TaskStatus.EXECUTING, TaskStatus.COMPLETED));
    }

    @Test
    public void verifying_to_completed_valid() {
        assertEquals(TaskStatus.COMPLETED,
                TaskStateMachine.transition(TaskStatus.VERIFYING, TaskStatus.COMPLETED));
    }

    @Test
    public void verifying_to_executing_replan_valid() {
        assertEquals(TaskStatus.EXECUTING,
                TaskStateMachine.transition(TaskStatus.VERIFYING, TaskStatus.EXECUTING));
    }

    @Test
    public void blocked_to_planning_valid() {
        assertEquals(TaskStatus.PLANNING,
                TaskStateMachine.transition(TaskStatus.BLOCKED, TaskStatus.PLANNING));
    }

    @Test
    public void blocked_to_executing_valid() {
        assertEquals(TaskStatus.EXECUTING,
                TaskStateMachine.transition(TaskStatus.BLOCKED, TaskStatus.EXECUTING));
    }

    @Test
    public void waiting_user_to_executing_valid() {
        assertEquals(TaskStatus.EXECUTING,
                TaskStateMachine.transition(TaskStatus.WAITING_USER, TaskStatus.EXECUTING));
    }

    @Test
    public void interrupted_to_failed_valid() {
        assertEquals(TaskStatus.FAILED,
                TaskStateMachine.transition(TaskStatus.INTERRUPTED, TaskStatus.FAILED));
    }

    @Test
    public void interrupted_to_cancelled_valid() {
        assertEquals(TaskStatus.CANCELLED,
                TaskStateMachine.transition(TaskStatus.INTERRUPTED, TaskStatus.CANCELLED));
    }

    // ---- Invalid transitions (negative test cases, §45.21) ----

    @Test(expected = TaskStateMachine.InvalidTransitionException.class)
    public void completed_to_any_invalid() {
        TaskStateMachine.transition(TaskStatus.COMPLETED, TaskStatus.EXECUTING);
    }

    @Test(expected = TaskStateMachine.InvalidTransitionException.class)
    public void failed_to_any_invalid() {
        TaskStateMachine.transition(TaskStatus.FAILED, TaskStatus.PLANNING);
    }

    @Test(expected = TaskStateMachine.InvalidTransitionException.class)
    public void cancelled_to_any_invalid() {
        TaskStateMachine.transition(TaskStatus.CANCELLED, TaskStatus.EXECUTING);
    }

    @Test(expected = TaskStateMachine.InvalidTransitionException.class)
    public void created_to_executing_invalid() {
        TaskStateMachine.transition(TaskStatus.CREATED, TaskStatus.EXECUTING);
    }

    @Test(expected = TaskStateMachine.InvalidTransitionException.class)
    public void created_to_completed_invalid() {
        TaskStateMachine.transition(TaskStatus.CREATED, TaskStatus.COMPLETED);
    }

    @Test(expected = TaskStateMachine.InvalidTransitionException.class)
    public void planning_to_verifying_invalid() {
        TaskStateMachine.transition(TaskStatus.PLANNING, TaskStatus.VERIFYING);
    }

    @Test(expected = TaskStateMachine.InvalidTransitionException.class)
    public void verifying_to_planning_invalid() {
        TaskStateMachine.transition(TaskStatus.VERIFYING, TaskStatus.PLANNING);
    }

    @Test(expected = TaskStateMachine.InvalidTransitionException.class)
    public void null_status_throws() {
        TaskStateMachine.transition(null, TaskStatus.EXECUTING);
    }

    // ---- Self-transition (idempotent) ----

    @Test
    public void self_transition_returns_same_status() {
        assertEquals(TaskStatus.EXECUTING,
                TaskStateMachine.transition(TaskStatus.EXECUTING, TaskStatus.EXECUTING));
    }

    // ---- Terminal states ----

    @Test
    public void terminal_states_have_no_transitions() {
        Set<TaskStatus> completedTargets = TaskStateMachine.validTargets(TaskStatus.COMPLETED);
        assertTrue("COMPLETED should have no outgoing transitions", completedTargets.isEmpty());

        Set<TaskStatus> failedTargets = TaskStateMachine.validTargets(TaskStatus.FAILED);
        assertTrue("FAILED should have no outgoing transitions", failedTargets.isEmpty());

        Set<TaskStatus> cancelledTargets = TaskStateMachine.validTargets(TaskStatus.CANCELLED);
        assertTrue("CANCELLED should have no outgoing transitions", cancelledTargets.isEmpty());
    }

    // ---- canTransition ----

    @Test
    public void canTransition_returns_true_for_valid() {
        assertTrue(TaskStateMachine.canTransition(TaskStatus.CREATED, TaskStatus.PLANNING));
    }

    @Test
    public void canTransition_returns_false_for_invalid() {
        assertFalse(TaskStateMachine.canTransition(TaskStatus.CREATED, TaskStatus.EXECUTING));
    }

    @Test
    public void canTransition_null_returns_false() {
        assertFalse(TaskStateMachine.canTransition(null, TaskStatus.EXECUTING));
    }

    // ---- validTargets ----

    @Test
    public void executing_has_correct_targets() {
        Set<TaskStatus> targets = TaskStateMachine.validTargets(TaskStatus.EXECUTING);
        assertEquals(6, targets.size());
        assertTrue(targets.contains(TaskStatus.VERIFYING));
        assertTrue(targets.contains(TaskStatus.BLOCKED));
        assertTrue(targets.contains(TaskStatus.WAITING_USER));
        assertTrue(targets.contains(TaskStatus.COMPLETED));
        assertTrue(targets.contains(TaskStatus.FAILED));
        assertTrue(targets.contains(TaskStatus.CANCELLED));
    }
}
