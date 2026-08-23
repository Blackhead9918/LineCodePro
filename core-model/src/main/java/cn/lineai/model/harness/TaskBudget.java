package cn.lineai.model.harness;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * Resource budget for an agent task (LCP-Harness v1 §26, §45.9).
 *
 * <p>All fields are optional. Default: unlimited except {@code toolCallMax} = 100 (hard cap).
 * Parsed from {@code budget_json} column in {@code agent_tasks} table.
 *
 * <p>Example JSON:
 * <pre>{"token":30000,"tool_call":30,"sub_agent":2,"parallel":4,"time_sec":600,"output_chars":500000}</pre>
 */
public final class TaskBudget {

    /** Hard maximum for tool calls regardless of user budget. */
    public static final int HARD_MAX_TOOL_CALLS = 100;

    /** Hard maximum for sub-agent depth. */
    public static final int HARD_MAX_SUB_AGENTS = 10;

    /** Default maximum tool calls if not specified. */
    public static final int DEFAULT_MAX_TOOL_CALLS = 30;

    /** Default maximum sub-agents if not specified. */
    public static final int DEFAULT_MAX_SUB_AGENTS = 2;

    /** Default maximum parallel tools if not specified. */
    public static final int DEFAULT_MAX_PARALLEL = 4;

    private final int tokenMax;         // 0 = unlimited
    private final int toolCallMax;      // 0 = unlimited, hard cap 100
    private final int subAgentMax;      // 0 = unlimited, hard cap 10
    private final int parallelMax;      // 0 = unlimited
    private final int timeSecMax;       // 0 = unlimited
    private final int outputCharsMax;   // 0 = unlimited

    private TaskBudget(int tokenMax, int toolCallMax, int subAgentMax,
                       int parallelMax, int timeSecMax, int outputCharsMax) {
        this.tokenMax = tokenMax;
        this.toolCallMax = Math.min(toolCallMax, HARD_MAX_TOOL_CALLS);
        this.subAgentMax = Math.min(subAgentMax, HARD_MAX_SUB_AGENTS);
        this.parallelMax = parallelMax;
        this.timeSecMax = timeSecMax;
        this.outputCharsMax = outputCharsMax;
    }

    /** Default budget with standard limits. */
    public static TaskBudget defaults() {
        return new TaskBudget(0, DEFAULT_MAX_TOOL_CALLS, DEFAULT_MAX_SUB_AGENTS,
                DEFAULT_MAX_PARALLEL, 0, 0);
    }

    /** Unlimited budget (for CHAT mode or testing). */
    public static TaskBudget unlimited() {
        return new TaskBudget(0, 0, 0, 0, 0, 0);
    }

    /**
     * Parse from JSON string. Returns {@link #defaults()} on null/empty/invalid input.
     */
    public static TaskBudget parse(String json) {
        if (json == null || json.trim().isEmpty()) return defaults();
        try {
            JSONObject o = new JSONObject(json);
            return new TaskBudget(
                    optInt(o, "token"),
                    optInt(o, "tool_call"),
                    optInt(o, "sub_agent"),
                    optInt(o, "parallel"),
                    optInt(o, "time_sec"),
                    optInt(o, "output_chars")
            );
        } catch (JSONException e) {
            return defaults();
        }
    }

    private static int optInt(JSONObject o, String key) {
        return o.has(key) ? o.optInt(key, 0) : 0;
    }

    /** Serialize to JSON string for DB storage. */
    public String toJson() {
        try {
            JSONObject o = new JSONObject();
            if (tokenMax > 0) o.put("token", tokenMax);
            if (toolCallMax > 0) o.put("tool_call", toolCallMax);
            if (subAgentMax > 0) o.put("sub_agent", subAgentMax);
            if (parallelMax > 0) o.put("parallel", parallelMax);
            if (timeSecMax > 0) o.put("time_sec", timeSecMax);
            if (outputCharsMax > 0) o.put("output_chars", outputCharsMax);
            return o.length() > 0 ? o.toString() : null;
        } catch (JSONException e) {
            return null;
        }
    }

    public int tokenMax() { return tokenMax; }
    public int toolCallMax() { return toolCallMax; }
    public int subAgentMax() { return subAgentMax; }
    public int parallelMax() { return parallelMax; }
    public int timeSecMax() { return timeSecMax; }
    public int outputCharsMax() { return outputCharsMax; }

    /** Returns true if the given tool call count is within budget. */
    public boolean isToolCallAllowed(int currentCount) {
        return toolCallMax <= 0 || currentCount < toolCallMax;
    }

    /** Returns true if the given sub-agent count is within budget. */
    public boolean isSubAgentAllowed(int currentCount) {
        return subAgentMax <= 0 || currentCount < subAgentMax;
    }

    @Override
    public String toString() {
        return "TaskBudget{token=" + tokenMax +
                ", toolCall=" + toolCallMax +
                ", subAgent=" + subAgentMax +
                ", parallel=" + parallelMax +
                ", timeSec=" + timeSecMax +
                ", outputChars=" + outputCharsMax + "}";
    }
}
