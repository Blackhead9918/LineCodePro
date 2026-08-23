package cn.lineai.ai.harness;

import cn.lineai.model.harness.EvidenceLevel;

/**
 * Classifies tool observations into evidence levels (LCP-Harness v1 §14).
 *
 * <p>Maps concrete tool/build/test outcomes to {@link EvidenceLevel} and
 * {@link VerdictEngine.EvidenceItem.Category}.
 *
 * <p>Thread-safety: stateless — safe for concurrent use.
 */
public final class EvidenceClassifier {

    private EvidenceClassifier() {} // utility class

    /**
     * Classify a tool execution result.
     *
     * @param toolName name of the tool that was executed
     * @param success whether the tool reported success
     * @param output tool output (for further classification)
     * @return evidence item
     */
    public static VerdictEngine.EvidenceItem classifyToolResult(
            String toolName, boolean success, String output) {

        EvidenceLevel level = success
                ? EvidenceLevel.TOOL_OBSERVATION
                : EvidenceLevel.MODEL_ASSERTION;

        VerdictEngine.EvidenceItem.Category category;
        String summary;

        if (isBuildCommand(toolName, output)) {
            category = VerdictEngine.EvidenceItem.Category.BUILD_RESULT;
            level = success ? EvidenceLevel.DETERMINISTIC_CHECK : EvidenceLevel.TOOL_OBSERVATION;
            summary = "Build: " + (success ? "SUCCESS" : "FAILED");
        } else if (isTestCommand(toolName, output)) {
            category = VerdictEngine.EvidenceItem.Category.TEST_RESULT;
            level = success ? EvidenceLevel.INDEPENDENT_VERIFICATION : EvidenceLevel.DETERMINISTIC_CHECK;
            summary = "Test: " + (success ? "PASS" : "FAIL");
        } else if (isDiffOperation(toolName)) {
            category = VerdictEngine.EvidenceItem.Category.DIFF_RESULT;
            level = success ? EvidenceLevel.TOOL_OBSERVATION : EvidenceLevel.MODEL_ASSERTION;
            summary = "Diff: " + (success ? "recorded" : "no change");
        } else {
            category = VerdictEngine.EvidenceItem.Category.TOOL_RESULT;
            summary = toolName + ": " + (success ? "ok" : "error");
        }

        return new VerdictEngine.EvidenceItem(category, level, !success, summary);
    }

    /**
     * Classify a build command outcome.
     */
    public static VerdictEngine.EvidenceItem classifyBuildResult(boolean success, String output) {
        EvidenceLevel level = success
                ? EvidenceLevel.DETERMINISTIC_CHECK
                : EvidenceLevel.TOOL_OBSERVATION;
        return new VerdictEngine.EvidenceItem(
                VerdictEngine.EvidenceItem.Category.BUILD_RESULT,
                level, !success,
                "Build: " + (success ? "SUCCESS" : "FAILED"));
    }

    /**
     * Classify a test command outcome.
     */
    public static VerdictEngine.EvidenceItem classifyTestResult(boolean success, String output) {
        EvidenceLevel level = success
                ? EvidenceLevel.INDEPENDENT_VERIFICATION
                : EvidenceLevel.DETERMINISTIC_CHECK;
        return new VerdictEngine.EvidenceItem(
                VerdictEngine.EvidenceItem.Category.TEST_RESULT,
                level, !success,
                "Test: " + (success ? "PASS" : "FAIL"));
    }

    /**
     * Tools whose OUTPUT can represent an executed command. Classification into
     * BUILD/TEST categories must be restricted to these — otherwise a FileReadTool
     * reading a file that merely mentions "gradle" or "test pass" would fabricate
     * E2/E3 evidence and inflate verdicts.
     */
    private static boolean isCommandCapableTool(String toolName) {
        if (toolName == null) return false;
        String n = toolName.toLowerCase(java.util.Locale.US);
        return "shellexecutetool".equals(n)
                || "shell_execute".equals(n)
                || n.startsWith("git_")
                || "gittool".equals(n)
                || n.startsWith("mcpx_"); // MCP tools may wrap arbitrary commands
    }

    private static boolean isBuildCommand(String toolName, String output) {
        if (!isCommandCapableTool(toolName) || output == null) return false;
        String lower = output.toLowerCase();
        return lower.contains("gradle") || lower.contains("build") || lower.contains("compile");
    }

    private static boolean isTestCommand(String toolName, String output) {
        if (!isCommandCapableTool(toolName) || output == null) return false;
        String lower = output.toLowerCase();
        return lower.contains("test") && (lower.contains("pass") || lower.contains("fail")
                || lower.contains("assert") || lower.contains("junit"));
    }

    private static boolean isDiffOperation(String toolName) {
        return "FileWriteTool".equals(toolName) || "FileEditTool".equals(toolName)
                || "FileDeleteTool".equals(toolName);
    }
}
