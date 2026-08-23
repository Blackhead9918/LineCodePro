package cn.lineai.model.harness;

import java.util.Collections;
import java.util.List;

/**
 * Task Capsule — the highest-priority context item during an active task (LCP-Harness v1 §6–§8, D03).
 *
 * <p>The capsule is injected into the system prompt at P0-level priority.
 * It does NOT override the user's latest message (anti goal-drift, D03):
 * user request remains P1, capsule is system-level guidance.
 *
 * <p>When the user changes topic, a new task is created rather than updating the capsule.
 * The capsule survives compaction (§45.15) — re-injected post-compaction like MemoryPromptBuilder.
 *
 * <p>Text length limits (§45.12): goal ≤ 4096, scope ≤ 2048, constraints ≤ 8192,
 * completionCondition ≤ 4096.
 */
public final class TaskCapsule {

    /** Maximum characters for goal field. */
    public static final int MAX_GOAL_CHARS = 4096;

    /** Maximum characters for scope field. */
    public static final int MAX_SCOPE_CHARS = 2048;

    /** Maximum characters for constraints field. */
    public static final int MAX_CONSTRAINTS_CHARS = 8192;

    /** Maximum characters for completion condition field. */
    public static final int MAX_COMPLETION_CHARS = 4096;

    private final String taskId;
    private final String goal;
    private final String scope;
    private final List<String> constraints;
    private final String completionCondition;
    private final String failureCondition;
    private final TaskRiskLevel riskLevel;
    private final ExecutionProfile executionProfile;
    private final TaskVerificationPolicy verificationPolicy;

    private TaskCapsule(Builder b) {
        this.taskId = b.taskId;
        this.goal = truncate(b.goal, MAX_GOAL_CHARS);
        this.scope = truncate(b.scope, MAX_SCOPE_CHARS);
        this.constraints = b.constraints;
        this.completionCondition = truncate(b.completionCondition, MAX_COMPLETION_CHARS);
        this.failureCondition = truncate(b.failureCondition, MAX_COMPLETION_CHARS);
        this.riskLevel = b.riskLevel;
        this.executionProfile = b.executionProfile;
        this.verificationPolicy = b.verificationPolicy;
    }

    public String taskId() { return taskId; }
    public String goal() { return goal; }
    public String scope() { return scope; }
    public List<String> constraints() { return constraints; }
    public String completionCondition() { return completionCondition; }
    public String failureCondition() { return failureCondition; }
    public TaskRiskLevel riskLevel() { return riskLevel; }
    public ExecutionProfile executionProfile() { return executionProfile; }
    public TaskVerificationPolicy verificationPolicy() { return verificationPolicy; }

    /** Render capsule as system-prompt-level guidance text. */
    public String renderForSystemPrompt() {
        StringBuilder sb = new StringBuilder();
        sb.append("## Active Task\n");
        sb.append("Goal: ").append(goal).append('\n');
        if (scope != null && !scope.isEmpty()) {
            sb.append("Scope: ").append(scope).append('\n');
        }
        if (constraints != null && !constraints.isEmpty()) {
            sb.append("Constraints:\n");
            for (String c : constraints) {
                sb.append("- ").append(c).append('\n');
            }
        }
        if (completionCondition != null && !completionCondition.isEmpty()) {
            sb.append("Completion: ").append(completionCondition).append('\n');
        }
        sb.append("Risk: ").append(riskLevel.name()).append('\n');
        sb.append("Profile: ").append(executionProfile.name()).append('\n');
        sb.append("Verification: ").append(verificationPolicy.name()).append('\n');
        return sb.toString();
    }

    private static String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max);
    }

    /** Builder for TaskCapsule. */
    public static final class Builder {
        private final String taskId;
        private final String goal;
        private String scope;
        private List<String> constraints = Collections.emptyList();
        private String completionCondition;
        private String failureCondition;
        private TaskRiskLevel riskLevel = TaskRiskLevel.LOW;
        private ExecutionProfile executionProfile = ExecutionProfile.LOCAL;
        private TaskVerificationPolicy verificationPolicy = TaskVerificationPolicy.LIGHT;

        public Builder(String taskId, String goal) {
            this.taskId = taskId;
            this.goal = goal;
        }

        public Builder scope(String scope) { this.scope = scope; return this; }
        public Builder constraints(List<String> constraints) {
            this.constraints = constraints != null ? constraints : Collections.emptyList();
            return this;
        }
        public Builder completionCondition(String s) { this.completionCondition = s; return this; }
        public Builder failureCondition(String s) { this.failureCondition = s; return this; }
        public Builder riskLevel(TaskRiskLevel r) { this.riskLevel = r; return this; }
        public Builder executionProfile(ExecutionProfile p) { this.executionProfile = p; return this; }
        public Builder verificationPolicy(TaskVerificationPolicy p) { this.verificationPolicy = p; return this; }

        public TaskCapsule build() {
            if (taskId == null || taskId.isEmpty()) throw new IllegalArgumentException("taskId required");
            if (goal == null || goal.isEmpty()) throw new IllegalArgumentException("goal required");
            return new TaskCapsule(this);
        }
    }
}
