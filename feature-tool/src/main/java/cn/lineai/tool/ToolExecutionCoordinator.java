package cn.lineai.tool;
import cn.lineai.model.tool.ToolCall;

import java.util.ArrayList;
import java.util.List;

public final class ToolExecutionCoordinator {
    private final ToolRegistry toolRegistry;

    public ToolExecutionCoordinator() {
        this(null);
    }

    public ToolExecutionCoordinator(ToolRegistry toolRegistry) {
        this.toolRegistry = toolRegistry;
    }

    /**
     * Splits the model's tool calls into a parallel batch and a sequential remainder
     * while preserving the requested execution order.
     *
     * <p>Only a leading run of concurrency-safe calls is executed in parallel; as soon
     * as one non-safe call is seen, every later call is executed sequentially. This is
     * deliberate: running a concurrency-safe call (e.g. {@code file_read}, {@code glob},
     * {@code git_status}) before an earlier write/shell call that precedes it in the
     * model's intent would let the model observe stale state — a read requested after
     * an edit must happen after the edit, not before it.</p>
     */
    public ToolExecutionPlan createPlan(List<ToolCall> toolCalls) {
        ArrayList<ToolCall> concurrentTasks = new ArrayList<>();
        ArrayList<ToolCall> sequentialTasks = new ArrayList<>();
        if (toolCalls == null) {
            return new ToolExecutionPlan(concurrentTasks, sequentialTasks);
        }
        boolean parallelPrefixOpen = true;
        for (ToolCall toolCall : toolCalls) {
            if (toolCall == null) {
                continue;
            }
            if (parallelPrefixOpen && isConcurrencySafe(toolCall)) {
                concurrentTasks.add(toolCall);
                continue;
            }
            parallelPrefixOpen = false;
            sequentialTasks.add(toolCall);
        }
        return new ToolExecutionPlan(concurrentTasks, sequentialTasks);
    }

    private boolean isConcurrencySafe(ToolCall toolCall) {
        if (toolCall == null || toolRegistry == null) {
            return false;
        }
        // An unknown/unregistered tool is never concurrency safe, so it keeps its
        // sequential position and its "unknown tool" error stays in order.
        BaseTool tool = toolRegistry.get(toolCall.getName());
        return tool != null && tool.isConcurrencySafe();
    }

    public static final class ToolExecutionPlan {
        private final List<ToolCall> concurrentTasks;
        private final List<ToolCall> sequentialTasks;

        ToolExecutionPlan(List<ToolCall> concurrentTasks, List<ToolCall> sequentialTasks) {
            this.concurrentTasks = concurrentTasks;
            this.sequentialTasks = sequentialTasks;
        }

        public List<ToolCall> getConcurrentTasks() {
            return concurrentTasks;
        }

        public List<ToolCall> getSequentialTasks() {
            return sequentialTasks;
        }
    }
}
