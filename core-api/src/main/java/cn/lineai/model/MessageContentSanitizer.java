package cn.lineai.model;

import org.json.JSONObject;

public final class MessageContentSanitizer {
    private MessageContentSanitizer() {
    }

    public static String forModel(ChatMessage message) {
        if (message == null) {
            return "";
        }
        if (message.getRole() == ChatMessage.Role.TOOL) {
            return toolContentForModel(message);
        }
        return stripInlineDataImages(message.getContent());
    }

    public static String toolContentForModel(ChatMessage message) {
        if (message == null) {
            return "";
        }
        String content = message.getContent();
        if (content == null || content.trim().length() == 0) {
            return "";
        }
        if (cn.lineai.tool.ToolNames.IMAGE_GENERATION.equals(message.getToolName()) && !message.isError()) {
            try {
                JSONObject object = new JSONObject(content);
                if (object.optBoolean("linecode_image_generation")) {
                    String modelContent = object.optString("model_content");
                    return modelContent.trim().length() > 0 ? modelContent : "Image generated and displayed in conversation.";
                }
            } catch (Exception ignored) {
                // Fail-soft: content is not the expected generation JSON; show the generic fallback.
                return "Image generated and displayed in conversation.";
            }
        }
        try {
            JSONObject object = new JSONObject(content);
            if (object.optBoolean("linecode_agent_ref")) {
                return stripInlineDataImages(content);
            }
            if (!object.optBoolean("linecode_agent_progress")) {
                return stripInlineDataImages(content);
            }
            String modelContent = object.optString("model_content");
            if (modelContent.trim().length() > 0) {
                return modelContent;
            }
            String output = object.optString("output");
            return output.trim().length() > 0 ? output : "Agent is still running, final result not yet generated.";
        } catch (Exception ignored) {
            // Best-effort: non-JSON tool content passes through with inline images stripped.
            return stripInlineDataImages(content);
        }
    }

    public static String imageGenerationDisplayMarkdown(String content) {
        try {
            JSONObject object = new JSONObject(content == null ? "" : content);
            if (object.optBoolean("linecode_image_generation")) {
                return object.optString("display_markdown").trim();
            }
        } catch (Exception ignored) {
            // Best-effort: display-only lookup; non-JSON content yields no markdown.
        }
        return "";
    }

    public static String stripInlineDataImages(String content) {
        String text = content == null ? "" : content;
        StringBuilder builder = new StringBuilder(text.length());
        int cursor = 0;
        while (cursor < text.length()) {
            int start = text.indexOf("data:image/", cursor);
            if (start < 0) {
                builder.append(text.substring(cursor));
                break;
            }
            builder.append(text, cursor, start);
            int end = endOfDataUrl(text, start);
            builder.append("linecode-inline-image");
            cursor = end;
        }
        return builder.toString();
    }

    private static int endOfDataUrl(String text, int start) {
        int end = start;
        while (end < text.length()) {
            char c = text.charAt(end);
            if (c == ')' || c == '"' || c == '\'' || Character.isWhitespace(c)) {
                break;
            }
            end++;
        }
        return end;
    }
}
