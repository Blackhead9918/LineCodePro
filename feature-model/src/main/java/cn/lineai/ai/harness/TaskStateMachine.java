package cn.lineai.ai.harness;

import java.util.Map;
import java.util.Set;

import cn.lineai.model.harness.TaskStatus;

/**
 * Pure-Java state machine for task lifecycle transitions (LCP-Harness v1 §45.2).
 *
 * <p>Valid transitions are defined in {@link TaskStatus#validTransitions()}.
 * This class enforces them and throws {@link InvalidTransitionException} on illegal moves.
 *
 * <p>Thread-safety: this class is stateless and immutable — safe for concurrent use.
 */
public final class TaskStateMachine {

    private static final Map<TaskStatus, Set<TaskStatus>> TRANSITIONS = TaskStatus.validTransitions();

    private TaskStateMachine() {} // utility class

    /**
     * Attempt a state transition. Returns the new status on success.
     *
     * @param current current task status
     * @param next desired next status
     * @return the validated next status
     * @throws InvalidTransitionException if the transition is not allowed
     */
    public static TaskStatus transition(TaskStatus current, TaskStatus next) {
        if (current == null || next == null) {
            throw new InvalidTransitionException(current, next, "status must not be null");
        }
        if (current == next) {
            // self-transition is a no-op, not an error (idempotent)
            return current;
        }
        Set<TaskStatus> allowed = TRANSITIONS.get(current);
        if (allowed == null) {
            throw new InvalidTransitionException(current, next,
                    "unknown source state: " + current);
        }
        if (allowed.isEmpty()) {
            throw new InvalidTransitionException(current, next,
                    current + " is a terminal state with no outgoing transitions");
        }
        if (!allowed.contains(next)) {
            throw new InvalidTransitionException(current, next,
                    "transition " + current + " → " + next + " is not valid; allowed: " + allowed);
        }
        return next;
    }

    /**
     * Check if a transition is valid without throwing.
     */
    public static boolean canTransition(TaskStatus current, TaskStatus next) {
        if (current == null || next == null) return false;
        if (current == next) return true;
        Set<TaskStatus> allowed = TRANSITIONS.get(current);
        return allowed != null && allowed.contains(next);
    }

    /**
     * Returns the set of valid target states for the given source state.
     * Returns an empty set for terminal states.
     */
    public static Set<TaskStatus> validTargets(TaskStatus current) {
        Set<TaskStatus> allowed = TRANSITIONS.get(current);
        return allowed != null ? allowed : java.util.Collections.emptySet();
    }

    /**
     * Exception thrown on invalid state transitions.
     */
    public static final class InvalidTransitionException extends RuntimeException {
        private final TaskStatus from;
        private final TaskStatus to;

        public InvalidTransitionException(TaskStatus from, TaskStatus to, String message) {
            super(message);
            this.from = from;
            this.to = to;
        }

        public TaskStatus from() { return from; }
        public TaskStatus to() { return to; }
    }
}
