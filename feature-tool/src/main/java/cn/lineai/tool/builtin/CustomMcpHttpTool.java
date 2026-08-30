package cn.lineai.tool.builtin;
import cn.lineai.model.tool.ToolResult;

import cn.lineai.model.ExtensionMcpConfig;
import cn.lineai.model.McpRequestHeader;
import cn.lineai.model.McpToolSummary;
import cn.lineai.security.SimpleHttpClient;
import cn.lineai.tool.BaseTool;
import cn.lineai.tool.R;
import cn.lineai.tool.ToolCategory;
import cn.lineai.tool.ToolContext;
import cn.lineai.tool.ToolDisplayCategory;
import java.util.LinkedHashMap;
import java.util.Map;
import org.json.JSONObject;

public final class CustomMcpHttpTool extends BaseTool {
    private final String name;
    private final ExtensionMcpConfig mcp;
    private final McpToolSummary tool;

    public CustomMcpHttpTool(String name, ExtensionMcpConfig mcp, McpToolSummary tool) {
        this.name = name == null ? "" : name;
        this.mcp = mcp;
        this.tool = tool;
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public String getDescription() {
        StringBuilder builder = new StringBuilder();
        builder.append("Invoke the tool ").append(tool.getName()).append(" of the custom HTTP MCP \"").append(mcp.getName()).append("\".");
        if (tool.getDescription().length() > 0) {
            builder.append('\n').append(tool.getDescription());
        }
        builder.append("\nMCP address: ").append(mcp.getUrl());
        return builder.toString();
    }

    @Override
    public ToolCategory getCategory() {
        return ToolCategory.SYSTEM;
    }

    @Override
    public ToolDisplayCategory getDisplayCategory() {
        return ToolDisplayCategory.GENERIC;
    }

    @Override
    public JSONObject getParameters() throws org.json.JSONException {
        if (tool.getInputSchemaJson().length() > 0) {
            try {
                JSONObject schema = new JSONObject(tool.getInputSchemaJson());
                JSONObject normalized = new JSONObject();
                String type = schema.optString("type", "object");
                normalized.put("type", type.length() == 0 ? "object" : type);
                
                JSONObject properties = schema.optJSONObject("properties");
                if (properties != null) {
                    normalized.put("properties", properties);
                } else {
                    normalized.put("properties", new JSONObject());
                }
                
                org.json.JSONArray required = schema.optJSONArray("required");
                if (required != null) {
                    normalized.put("required", required);
                }
                if (schema.has("additionalProperties")) {
                    normalized.put("additionalProperties", schema.optBoolean("additionalProperties", true));
                }
                if (schema.has("description")) {
                    normalized.put("description", schema.optString("description"));
                }
                return normalized;
            } catch (Exception ignored) {
                // Fall back to default open schema on malformed stored schema
            }
        }
        return new JSONObject()
                .put("type", "object")
                .put("properties", new JSONObject())
                .put("additionalProperties", true);
    }

    @Override
    public ToolResult execute(JSONObject input, ToolContext context) {
        try {
            JSONObject body = new JSONObject()
                    .put("jsonrpc", "2.0")
                    .put("id", "linecode_" + System.currentTimeMillis())
                    .put("method", "tools/call")
                    .put("params", new JSONObject()
                            .put("name", tool.getName())
                            .put("arguments", input == null ? new JSONObject() : input));
            Map<String, String> headers = new LinkedHashMap<>();
            headers.put("Accept", "application/json, text/event-stream");
            headers.put("Content-Type", "application/json");
            for (McpRequestHeader header : mcp.getRequestHeaders()) {
                if (header.getName().length() > 0) {
                    headers.put(header.getName(), header.getValue());
                }
            }
            SimpleHttpClient.Request request = new SimpleHttpClient.Request(mcp.getUrl(), "POST", body.toString());
            request.connectTimeoutMs = 15000;
            request.readTimeoutMs = 60000;
            request.headers.putAll(headers);
            SimpleHttpClient.Response response = SimpleHttpClient.execute(request);
            if (response.code < 200 || response.code >= 300) {
                return error(response.code + ": " + response.body);
            }
            return parseResult(response.body, context);
        } catch (Exception e) {
            return error(context.getString(R.string.tool_mcp_call_failed, e.getMessage()));
        }
    }

    private ToolResult parseResult(String text, ToolContext context) {
        try {
            String jsonPayload = extractEventData(text);
            JSONObject parsed = new JSONObject(jsonPayload);
            if (parsed.has("error") && !parsed.isNull("error")) {
                return error(formatJsonRpcError(parsed.opt("error")));
            }
            JSONObject resultObj = parsed.optJSONObject("result");
            boolean isMcpError = resultObj != null && resultObj.optBoolean("isError", false);
            
            Object result = resultObj != null ? resultObj : (parsed.has("result") ? parsed.opt("result") : parsed);
            String content = summarize(result);
            if (content.length() == 0) {
                content = context.getString(R.string.tool_mcp_completed);
            }
            return isMcpError ? error(content) : ok(content);
        } catch (Exception ignored) {
            return ok(text == null ? "" : text);
        }
    }

    private String formatJsonRpcError(Object error) {
        if (error == null || error == JSONObject.NULL) {
            return "MCP error: unknown error";
        }
        if (error instanceof JSONObject) {
            JSONObject errObj = (JSONObject) error;
            String message = errObj.optString("message", "");
            int code = errObj.optInt("code", 0);
            Object data = errObj.opt("data");
            StringBuilder sb = new StringBuilder();
            if (code != 0) {
                sb.append("[").append(code).append("] ");
            }
            if (message.length() > 0) {
                sb.append(message);
            } else {
                sb.append(errObj.toString());
            }
            if (data != null && data != JSONObject.NULL) {
                sb.append(" - ").append(data.toString());
            }
            return sb.toString();
        }
        return String.valueOf(error);
    }

    private String summarize(Object value) {
        if (value == null || value == JSONObject.NULL) {
            return "";
        }
        if (value instanceof String) {
            return (String) value;
        }
        if (value instanceof org.json.JSONArray) {
            org.json.JSONArray array = (org.json.JSONArray) value;
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < array.length(); i++) {
                Object item = array.opt(i);
                String itemText = summarize(item);
                if (itemText.length() > 0) {
                    if (sb.length() > 0) {
                        sb.append("\n");
                    }
                    sb.append(itemText);
                }
            }
            return sb.toString();
        }
        if (value instanceof JSONObject) {
            JSONObject object = (JSONObject) value;
            
            // Standard MCP content array: result.content = [{type: "text", text: "..."}, {type: "resource", ...}]
            org.json.JSONArray contentArray = object.optJSONArray("content");
            if (contentArray != null) {
                return summarize(contentArray);
            }
            
            String text = object.optString("text");
            if (text.length() > 0) {
                return text;
            }
            String content = object.optString("content");
            if (content.length() > 0) {
                return content;
            }
            String message = object.optString("message");
            if (message.length() > 0) {
                return message;
            }
            JSONObject resource = object.optJSONObject("resource");
            if (resource != null) {
                String resourceText = resource.optString("text");
                if (resourceText.length() > 0) {
                    return resourceText;
                }
                String uri = resource.optString("uri");
                if (uri.length() > 0) {
                    return uri;
                }
            }
        }
        return String.valueOf(value);
    }

    private String extractEventData(String text) {
        if (text == null) {
            return "";
        }
        String trimmed = text.trim();
        if (trimmed.startsWith("{") && trimmed.endsWith("}")) {
            return trimmed;
        }
        StringBuilder builder = new StringBuilder();
        String[] lines = text.split("\\r?\\n");
        for (String line : lines) {
            if (!line.startsWith("data:")) {
                continue;
            }
            String value = line.substring("data:".length()).trim();
            if (value.length() > 0 && !"[DONE]".equals(value)) {
                builder.append(value);
            }
        }
        return builder.length() == 0 ? text : builder.toString();
    }

}
