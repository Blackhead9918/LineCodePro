package cn.lineai.model.tool;

import cn.lineai.model.Strings;

public final class ToolResult {
    private final String toolCallId;
    private final String toolName;
    private final String content;
    private final boolean error;
    private final String diffId;
    private final String reviewState;
    private final String reviewMessage;

    /** 工具结果内容最大字符数（50KB），超过此限制时执行中间截断。 */
    public static final int MAX_TOOL_RESULT_CHARS = 50 * 1024;

    /** 中间截断时保留的首/尾各半字符数。 */
    private static final int TRUNCATION_HALF = MAX_TOOL_RESULT_CHARS / 2;

    /** 截断标记核心短语；用于识别已截断内容，避免二次截断破坏标记本身。 */
    private static final String TRUNCATION_MARKER_PHRASE = " chars truncated";

    /** 截断标记文本的长度上限，用于幂等判断。 */
    private static final int TRUNCATION_MARKER_ALLOWANCE = 512;

    public ToolResult(
            String toolCallId,
            String toolName,
            String content,
            boolean error,
            String diffId,
            String reviewState,
            String reviewMessage
    ) {
        this.toolCallId = Strings.nullToEmpty(toolCallId);
        this.toolName = Strings.nullToEmpty(toolName);
        this.content = Strings.nullToEmpty(content);
        this.error = error;
        this.diffId = Strings.nullToEmpty(diffId);
        this.reviewState = Strings.nullToEmpty(reviewState);
        this.reviewMessage = Strings.nullToEmpty(reviewMessage);
    }

    public static ToolResult of(String toolCallId, String toolName, String content, boolean error) {
        return new ToolResult(toolCallId, toolName, content, error, "", "", "");
    }

    public static ToolResult success(String output) {
        return new ToolResult("", "", output, false, "", "", "");
    }

    public static ToolResult error(String error) {
        return new ToolResult("", "", error, true, "", "", "");
    }

    public static ToolResult withReview(String output, String toolCallId, String toolName,
                                         String diffId, String reviewState, String reviewMessage) {
        return new ToolResult(toolCallId, toolName, output, false, diffId, reviewState, reviewMessage);
    }

    public static ToolResult withReview(String toolCallId, String toolName, String content,
                                         boolean error, String diffId, String reviewState, String reviewMessage) {
        return new ToolResult(toolCallId, toolName, content, error, diffId, reviewState, reviewMessage);
    }

    public String getToolCallId() {
        return toolCallId;
    }

    public String getToolName() {
        return toolName;
    }

    public String getContent() {
        return content;
    }

    public boolean isError() {
        return error;
    }

    public String getDiffId() {
        return diffId;
    }

    public String getReviewState() {
        return reviewState;
    }

    public String getReviewMessage() {
        return reviewMessage;
    }

    public ToolResult withCall(String nextToolCallId, String nextToolName) {
        return new ToolResult(nextToolCallId, nextToolName, content, error, diffId, reviewState, reviewMessage);
    }

    public ToolResult withDiffId(String nextDiffId) {
        return new ToolResult(toolCallId, toolName, content, error, nextDiffId, reviewState, reviewMessage);
    }

    public ToolResult withReview(String nextReviewState, String nextReviewMessage) {
        return new ToolResult(toolCallId, toolName, content, error, diffId, nextReviewState, nextReviewMessage);
    }

    /**
     * 对内容执行中间截断：当内容超过 {@link #MAX_TOOL_RESULT_CHARS} 时，
     * 保留首 TRUNCATION_HALF 字符 + 截断标记 + 尾 TRUNCATION_HALF 字符。
     * 不超过限制时原样返回；已经截断过的内容原样返回，避免二次截断把标记截坏。
     *
     * <p>标记明确告知模型：被省略的内容从未被读取，必须用更窄的读取方式重新获取，
     * 不得凭猜测补全（防止模型把未读到的中间段当作已知内容）。
     *
     * @param content 原始内容
     * @return 截断后的内容，或原始内容（如未超限/已截断）
     */
    public static String truncateContent(String content) {
        if (content == null || content.length() <= MAX_TOOL_RESULT_CHARS) {
            return content;
        }
        if (content.length() <= MAX_TOOL_RESULT_CHARS + TRUNCATION_MARKER_ALLOWANCE
                && content.contains(TRUNCATION_MARKER_PHRASE)) {
            return content;
        }
        int truncated = content.length() - MAX_TOOL_RESULT_CHARS;
        return content.substring(0, TRUNCATION_HALF)
                + "\n... (" + truncated + " chars truncated - the omitted middle was NOT read; "
                + "do not guess it. Re-read the exact range (file_read start_kb/end_kb) or re-run "
                + "the command with a narrower filter: grep / sed -n / head / tail) ...\n"
                + content.substring(content.length() - TRUNCATION_HALF);
    }
}
