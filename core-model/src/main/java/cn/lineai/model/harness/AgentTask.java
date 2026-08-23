package cn.lineai.model.harness;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * Persistent DTO for an agent task (LCP-Harness v1 §5, §35.1).
 *
 * <p>Mapped to the {@code agent_tasks} table. Created by {@code AgentTaskRepository},
 * consumed by {@code TaskController}. Thread-safety: mutable fields are guarded
 * per §45.13 — use synchronized access or confine to single thread.
 */
public final class AgentTask {

    /** Maximum characters for goal field (§45.12). */
    public static final int MAX_GOAL_CHARS = 4096;
    /** Maximum characters for scope field (§45.12). */
    public static final int MAX_SCOPE_CHARS = 2048;
    /** Maximum characters for constraints field (§45.12). */
    public static final int MAX_CONSTRAINTS_CHARS = 8192;
    /** Maximum characters for completion_condition field (§45.12). */
    public static final int MAX_COMPLETION_CHARS = 4096;
    /** Maximum characters for budget_json field (§45.12). */
    public static final int MAX_BUDGET_JSON_CHARS = 1024;

    private final String id;                          // {conversationId}_t{counter}
    private final String conversationId;
    private final String projectId;
    private final String parentTaskId;                // sub-agent: parent task

    private TaskMode mode;
    private final String goal;
    private final String scope;
    private final String constraints;                 // JSON array string
    private volatile TaskStatus status;
    private TaskRiskLevel riskLevel;
    private final ExecutionProfile executionProfile;
    private final TaskVerificationPolicy verificationPolicy;
    private final String completionCondition;
    private final String failureCondition;
    private final TaskBudget budget;
    private volatile TaskVerdict verdict;
    private volatile int attemptCount;                // AtomicInteger in concurrent use (§45.13)
    private final int maxAttempts;
    private final long createdAt;
    private volatile long updatedAt;
    private volatile long completedAt;

    private AgentTask(Builder b) {
        this.id = b.id;
        this.conversationId = b.conversationId;
        this.projectId = b.projectId;
        this.parentTaskId = b.parentTaskId;
        this.mode = b.mode;
        this.goal = truncate(b.goal, MAX_GOAL_CHARS);
        this.scope = truncate(b.scope, MAX_SCOPE_CHARS);
        this.constraints = truncate(b.constraints, MAX_CONSTRAINTS_CHARS);
        this.status = b.status;
        this.riskLevel = b.riskLevel;
        this.executionProfile = b.executionProfile;
        this.verificationPolicy = b.verificationPolicy;
        this.completionCondition = truncate(b.completionCondition, MAX_COMPLETION_CHARS);
        this.failureCondition = truncate(b.failureCondition, MAX_COMPLETION_CHARS);
        this.budget = b.budget;
        this.verdict = b.verdict;
        this.attemptCount = b.attemptCount;
        this.maxAttempts = b.maxAttempts;
        this.createdAt = b.createdAt;
        this.updatedAt = b.updatedAt;
        this.completedAt = b.completedAt;
    }

    // --- Getters ---

    public String id() { return id; }
    public String conversationId() { return conversationId; }
    public String projectId() { return projectId; }
    public String parentTaskId() { return parentTaskId; }
    public TaskMode mode() { return mode; }
    public String goal() { return goal; }
    public String scope() { return scope; }
    public String constraints() { return constraints; }
    public synchronized TaskStatus status() { return status; }
    public TaskRiskLevel riskLevel() { return riskLevel; }
    public ExecutionProfile executionProfile() { return executionProfile; }
    public TaskVerificationPolicy verificationPolicy() { return verificationPolicy; }
    public String completionCondition() { return completionCondition; }
    public String failureCondition() { return failureCondition; }
    public TaskBudget budget() { return budget; }
    public synchronized TaskVerdict verdict() { return verdict; }
    public int attemptCount() { return attemptCount; }
    public int maxAttempts() { return maxAttempts; }
    public long createdAt() { return createdAt; }
    public long updatedAt() { return updatedAt; }
    public long completedAt() { return completedAt; }

    // --- Mutable accessors (synchronized per §45.13) ---

    public synchronized void setStatus(TaskStatus newStatus) {
        this.status = newStatus;
        this.updatedAt = System.currentTimeMillis();
    }

    public synchronized void setVerdict(TaskVerdict verdict) {
        this.verdict = verdict;
        this.updatedAt = System.currentTimeMillis();
    }

    public synchronized void setMode(TaskMode mode) {
        this.mode = mode;
        this.updatedAt = System.currentTimeMillis();
    }

    public synchronized void markCompleted(TaskVerdict finalVerdict) {
        this.status = TaskStatus.COMPLETED;
        this.verdict = finalVerdict;
        this.completedAt = System.currentTimeMillis();
        this.updatedAt = this.completedAt;
    }

    public synchronized void markFailed(TaskVerdict finalVerdict) {
        this.status = TaskStatus.FAILED;
        this.verdict = finalVerdict;
        this.completedAt = System.currentTimeMillis();
        this.updatedAt = this.completedAt;
    }

    public synchronized void markCancelled() {
        this.status = TaskStatus.CANCELLED;
        this.completedAt = System.currentTimeMillis();
        this.updatedAt = this.completedAt;
    }

    public synchronized void markInterrupted() {
        this.status = TaskStatus.INTERRUPTED;
        this.updatedAt = System.currentTimeMillis();
    }

    /** Increment attempt count and return the new value. */
    public synchronized int incrementAttempt() {
        attemptCount++;
        updatedAt = System.currentTimeMillis();
        return attemptCount;
    }

    /** Returns true if budget is exhausted. */
    public boolean isBudgetExhausted() {
        return maxAttempts > 0 && attemptCount >= maxAttempts;
    }

    /** Returns true if the task is in a terminal state. */
    public boolean isTerminal() {
        return status().isTerminal();
    }

    /** Returns true if the task is actively running. */
    public boolean isActive() {
        return status().isActive();
    }

    /** Build a TaskCapsule from this task. */
    public TaskCapsule toCapsule() {
        return new TaskCapsule.Builder(id, goal)
                .scope(scope)
                .constraints(parseConstraints())
                .completionCondition(completionCondition)
                .failureCondition(failureCondition)
                .riskLevel(riskLevel)
                .executionProfile(executionProfile)
                .verificationPolicy(verificationPolicy)
                .build();
    }

    private java.util.List<String> parseConstraints() {
        if (constraints == null || constraints.isEmpty()) {
            return java.util.Collections.emptyList();
        }
        try {
            org.json.JSONArray arr = new org.json.JSONArray(constraints);
            java.util.List<String> list = new java.util.ArrayList<>(arr.length());
            for (int i = 0; i < arr.length(); i++) {
                list.add(arr.getString(i));
            }
            return list;
        } catch (JSONException e) {
            return java.util.Collections.singletonList(constraints);
        }
    }

    private static String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max);
    }

    @Override
    public String toString() {
        return "AgentTask{id='" + id + "', status=" + status + ", goal='" +
                (goal.length() > 50 ? goal.substring(0, 50) + "..." : goal) +
                "', attempts=" + attemptCount + "/" + maxAttempts + "}";
    }

    /** Builder for AgentTask. */
    public static final class Builder {
        private final String id;
        private final String conversationId;
        private final String goal;
        private String projectId;
        private String parentTaskId;
        private TaskMode mode = TaskMode.AGENT;
        private String scope;
        private String constraints;
        private TaskStatus status = TaskStatus.CREATED;
        private TaskRiskLevel riskLevel = TaskRiskLevel.LOW;
        private ExecutionProfile executionProfile = ExecutionProfile.LOCAL;
        private TaskVerificationPolicy verificationPolicy = TaskVerificationPolicy.LIGHT;
        private String completionCondition;
        private String failureCondition;
        private TaskBudget budget = TaskBudget.defaults();
        private TaskVerdict verdict;
        private int attemptCount = 0;
        private int maxAttempts = 3;
        private long createdAt = System.currentTimeMillis();
        private long updatedAt = System.currentTimeMillis();
        private long completedAt = 0;

        public Builder(String id, String conversationId, String goal) {
            this.id = id;
            this.conversationId = conversationId;
            this.goal = goal;
        }

        public Builder projectId(String s) { this.projectId = s; return this; }
        public Builder parentTaskId(String s) { this.parentTaskId = s; return this; }
        public Builder mode(TaskMode m) { this.mode = m; return this; }
        public Builder scope(String s) { this.scope = s; return this; }
        public Builder constraints(String s) { this.constraints = s; return this; }
        public Builder status(TaskStatus s) { this.status = s; return this; }
        public Builder riskLevel(TaskRiskLevel r) { this.riskLevel = r; return this; }
        public Builder executionProfile(ExecutionProfile p) { this.executionProfile = p; return this; }
        public Builder verificationPolicy(TaskVerificationPolicy p) { this.verificationPolicy = p; return this; }
        public Builder completionCondition(String s) { this.completionCondition = s; return this; }
        public Builder failureCondition(String s) { this.failureCondition = s; return this; }
        public Builder budget(TaskBudget b) { this.budget = b; return this; }
        public Builder verdict(TaskVerdict v) { this.verdict = v; return this; }
        public Builder attemptCount(int c) { this.attemptCount = c; return this; }
        public Builder maxAttempts(int m) { this.maxAttempts = m; return this; }
        public Builder createdAt(long t) { this.createdAt = t; return this; }
        public Builder updatedAt(long t) { this.updatedAt = t; return this; }
        public Builder completedAt(long t) { this.completedAt = t; return this; }

        public AgentTask build() {
            if (id == null || id.isEmpty()) throw new IllegalArgumentException("id required");
            if (conversationId == null || conversationId.isEmpty()) throw new IllegalArgumentException("conversationId required");
            if (goal == null || goal.isEmpty()) throw new IllegalArgumentException("goal required");
            return new AgentTask(this);
        }
    }
}
