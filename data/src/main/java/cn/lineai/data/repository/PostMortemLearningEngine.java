package cn.lineai.data.repository;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Automated Post-Mortem Learning Engine.
 * Observes tool execution transitions (Failure -> Success/Fix) to distill
 * grounded, candidate rules and lessons into ScopedMemoryRegistry.
 */
public final class PostMortemLearningEngine {
    private static final PostMortemLearningEngine INSTANCE = new PostMortemLearningEngine();

    public static class FailureEvent {
        public final String workspace;
        public final String toolName;
        public final String inputSummary;
        public final String errorMessage;
        public final long timestamp;

        public FailureEvent(String workspace, String toolName, String inputSummary, String errorMessage, long timestamp) {
            this.workspace = workspace == null ? "" : workspace;
            this.toolName = toolName == null ? "" : toolName;
            this.inputSummary = inputSummary == null ? "" : inputSummary;
            this.errorMessage = errorMessage == null ? "" : errorMessage;
            this.timestamp = timestamp;
        }
    }

    private final Map<String, FailureEvent> pendingFailures = new ConcurrentHashMap<>();

    private PostMortemLearningEngine() {
    }

    public static PostMortemLearningEngine getInstance() {
        return INSTANCE;
    }

    public void recordFailure(String workspace, String toolName, String inputSummary, String errorMessage) {
        if (errorMessage == null || errorMessage.trim().isEmpty()) {
            return;
        }
        String safeWorkspace = workspace == null ? "" : workspace.trim();
        pendingFailures.put(safeWorkspace, new FailureEvent(
                safeWorkspace,
                toolName,
                inputSummary,
                errorMessage,
                System.currentTimeMillis()
        ));
    }

    public void recordSuccess(String workspace, String toolName, String inputSummary, String resultSummary) {
        String safeWorkspace = workspace == null ? "" : workspace.trim();
        FailureEvent lastFailure = pendingFailures.remove(safeWorkspace);
        if (lastFailure == null) {
            return;
        }

        // Only evaluate if the fix happened within 10 minutes
        if (System.currentTimeMillis() - lastFailure.timestamp > 10 * 60 * 1000) {
            return;
        }

        distillLesson(lastFailure, toolName, inputSummary, resultSummary);
    }

    private void distillLesson(FailureEvent failure, String successTool, String successInput, String successResult) {
        try {
            String error = failure.errorMessage;
            ScopedMemoryRule.Hierarchy hierarchy = ScopedMemoryRule.Hierarchy.WORKSPACE;
            ScopedMemoryRule.Category category = ScopedMemoryRule.Category.CONDITIONAL_FAILURE;
            String scopeTarget = failure.workspace.isEmpty() ? "*" : failure.workspace;
            String condition = "When encountering: " + abbreviate(error, 120);
            String recommendedRule = "";

            if (failure.toolName.contains("file_edit") || failure.toolName.contains("file_multi_edit")) {
                category = ScopedMemoryRule.Category.INVARIANT;
                hierarchy = ScopedMemoryRule.Hierarchy.FILE_PATTERN;
                recommendedRule = "Always use file_read before file_edit to ground exact line contents and prevent target mismatch.";
            } else if (failure.toolName.contains("shell") || failure.toolName.contains("command")) {
                recommendedRule = "Verify command syntax and required environment flags before executing scripts in this workspace.";
            } else {
                recommendedRule = "Ensure proper prerequisites are validated when executing " + failure.toolName + ".";
            }

            ScopedMemoryRegistry.getInstance().proposeCandidate(
                    hierarchy,
                    category,
                    scopeTarget,
                    condition,
                    abbreviate(error, 150),
                    recommendedRule
            );
        } catch (Exception ignored) {
        }
    }

    private static String abbreviate(String str, int maxLen) {
        if (str == null) return "";
        String trimmed = str.trim().replaceAll("\\s+", " ");
        if (trimmed.length() <= maxLen) return trimmed;
        return trimmed.substring(0, maxLen - 3) + "...";
    }
}
