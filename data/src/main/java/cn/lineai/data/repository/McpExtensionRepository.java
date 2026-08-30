package cn.lineai.data.repository;

import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import cn.lineai.data.db.LineCodeDatabase;
import cn.lineai.model.ExtensionMcpConfig;
import cn.lineai.model.McpRequestHeader;
import cn.lineai.model.McpToolSummary;
import cn.lineai.security.SimpleHttpClient;
import cn.lineai.security.UrlPolicy;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * MCP 扩展 CRUD 与 HTTP 工具发现仓库，负责 extension_mcps 表的读写及远程 tools/list 调用。
 */
public final class McpExtensionRepository extends BaseRepository {

    public McpExtensionRepository(LineCodeDatabase database) {
        super(database);
    }

    public synchronized List<ExtensionMcpConfig> getMcpExtensions() {
        ArrayList<ExtensionMcpConfig> mcps = new ArrayList<>();
        Cursor cursor = database.getReadableDatabase().rawQuery(
                "SELECT id, enabled, name, url, request_headers_json, tools_json, created_at, updated_at "
                        + "FROM extension_mcps ORDER BY updated_at DESC",
                new String[0]
        );
        try {
            while (cursor.moveToNext()) {
                mcps.add(new ExtensionMcpConfig(
                        value(cursor, "id"),
                        intValue(cursor, "enabled") != 0,
                        value(cursor, "name"),
                        value(cursor, "url"),
                        headersFromJson(value(cursor, "request_headers_json")),
                        toolsFromJson(value(cursor, "tools_json")),
                        longValue(cursor, "created_at"),
                        longValue(cursor, "updated_at")
                ));
            }
        } finally {
            cursor.close();
        }
        return mcps;
    }

    public synchronized ExtensionMcpConfig saveMcpExtension(ExtensionMcpConfig input) {
        long now = System.currentTimeMillis();
        String id = input == null || input.getId().length() == 0 ? nextId("mcp") : input.getId();
        long createdAt = input == null || input.getCreatedAt() <= 0 ? now : input.getCreatedAt();
        ExtensionMcpConfig saved = new ExtensionMcpConfig(
                id,
                input == null || input.isEnabled(),
                input == null ? "" : input.getName(),
                normalizeHttpUrl(input == null ? "" : input.getUrl()),
                input == null ? Collections.emptyList() : input.getRequestHeaders(),
                input == null ? Collections.emptyList() : input.getTools(),
                createdAt,
                now
        );
        ContentValues values = new ContentValues();
        values.put("id", saved.getId());
        values.put("enabled", saved.isEnabled() ? 1 : 0);
        values.put("name", saved.getName());
        values.put("url", saved.getUrl());
        values.put("request_headers_json", headersToJson(saved.getRequestHeaders()).toString());
        values.put("tools_json", toolsToJson(saved.getTools()).toString());
        values.put("created_at", saved.getCreatedAt());
        values.put("updated_at", saved.getUpdatedAt());
        values.put("raw_json", "");
        database.getWritableDatabase().insertWithOnConflict("extension_mcps", null, values, SQLiteDatabase.CONFLICT_REPLACE);
        return saved;
    }

    public synchronized void setMcpEnabled(String id, boolean enabled) {
        updateEnabled("extension_mcps", id, enabled);
    }

    public synchronized void deleteMcp(String id) {
        database.getWritableDatabase().delete("extension_mcps", "id = ?", new String[] {safe(id)});
    }

    public List<McpToolSummary> queryMcpTools(String url, List<McpRequestHeader> headers) throws Exception {
        String endpoint = normalizeHttpUrl(url);
        JSONObject body = new JSONObject()
                .put("jsonrpc", "2.0")
                .put("id", "linecode_" + System.currentTimeMillis())
                .put("method", "tools/list")
                .put("params", new JSONObject());
        Map<String, String> httpHeaders = new LinkedHashMap<>();
        httpHeaders.put("Accept", "application/json, text/event-stream");
        httpHeaders.put("Content-Type", "application/json");
        for (McpRequestHeader header : headers == null ? Collections.<McpRequestHeader>emptyList() : headers) {
            if (header.getName().length() > 0) {
                httpHeaders.put(header.getName(), header.getValue());
            }
        }
        SimpleHttpClient.Request request = new SimpleHttpClient.Request(endpoint, "POST", body.toString());
        request.connectTimeoutMs = 15000;
        request.readTimeoutMs = 30000;
        request.headers.putAll(httpHeaders);
        SimpleHttpClient.Response response = SimpleHttpClient.execute(request);
        if (response.code < 200 || response.code >= 300) {
            throw new IllegalStateException(response.code + ": " + response.body);
        }
        List<McpToolSummary> tools = parseMcpToolResponse(response.body);
        if (tools.isEmpty()) {
            throw new IllegalStateException("No tools list found in MCP response.");
        }
        return tools;
    }

    private String normalizeHttpUrl(String raw) {
        String value = UrlPolicy.requireHttpOrLocalCleartextUrl(raw, "MCP URL");
        while (value.endsWith("/") && value.length() > "https://x".length()) {
            value = value.substring(0, value.length() - 1);
        }
        return value;
    }

    private JSONArray headersToJson(List<McpRequestHeader> headers) {
        JSONArray array = new JSONArray();
        if (headers == null) {
            return array;
        }
        for (McpRequestHeader header : headers) {
            if (header.getName().length() == 0) {
                continue;
            }
            try {
                JSONObject item = new JSONObject();
                item.put("name", header.getName());
                item.put("value", header.getValue());
                array.put(item);
            } catch (Exception ignored) {
                // Defensive: name/value are plain strings; put() cannot fail in practice.
            }
        }
        return array;
    }

    private ArrayList<McpRequestHeader> headersFromJson(String raw) {
        ArrayList<McpRequestHeader> headers = new ArrayList<>();
        try {
            JSONArray array = new JSONArray(safe(raw).length() == 0 ? "[]" : raw);
            for (int i = 0; i < array.length(); i++) {
                JSONObject item = array.optJSONObject(i);
                if (item != null && item.optString("name").trim().length() > 0) {
                    headers.add(new McpRequestHeader(item.optString("name"), item.optString("value")));
                }
            }
        } catch (Exception ignored) {
            // Corrupt stored headers JSON must not crash app startup - treated as no headers.
        }
        return headers;
    }

    private JSONArray toolsToJson(List<McpToolSummary> tools) {
        JSONArray array = new JSONArray();
        if (tools == null) {
            return array;
        }
        for (McpToolSummary tool : tools) {
            if (tool.getName().length() == 0) {
                continue;
            }
            try {
                JSONObject item = new JSONObject();
                item.put("name", tool.getName());
                item.put("enabled", tool.isEnabled());
                item.put("description", tool.getDescription());
                if (tool.getInputSchemaJson().length() > 0) {
                    item.put("inputSchema", new JSONObject(tool.getInputSchemaJson()));
                }
                array.put(item);
            } catch (Exception ignored) {
                // A malformed stored inputSchema is skipped; the tool itself is still saved.
            }
        }
        return array;
    }

    private ArrayList<McpToolSummary> toolsFromJson(String raw) {
        try {
            return parseToolList(safe(raw).length() == 0 ? new JSONArray() : new JSONArray(raw));
        } catch (Exception ignored) {
            // Corrupt stored tools JSON must not crash app startup - treated as no tools.
            return new ArrayList<>();
        }
    }

    private List<McpToolSummary> parseMcpToolResponse(String text) throws Exception {
        String jsonText = extractJsonFromEventStream(text);
        JSONObject parsed = new JSONObject(jsonText);
        if (parsed.has("error") && !parsed.isNull("error")) {
            JSONObject err = parsed.optJSONObject("error");
            String errMsg = err != null ? err.optString("message", err.toString()) : parsed.optString("error");
            throw new IllegalStateException("MCP tools/list error: " + errMsg);
        }
        JSONObject result = parsed.optJSONObject("result");
        JSONObject data = parsed.optJSONObject("data");
        Object rawTools = result == null ? null : result.opt("tools");
        if (rawTools == null) {
            rawTools = parsed.opt("tools");
        }
        if (rawTools == null && data != null) {
            rawTools = data.opt("tools");
        }
        if (rawTools == null && result != null) {
            rawTools = result.opt("servers");
        }
        if (rawTools == null) {
            rawTools = parsed.opt("servers");
        }
        return rawTools instanceof JSONArray ? parseToolList((JSONArray) rawTools) : Collections.emptyList();
    }

    private ArrayList<McpToolSummary> parseToolList(JSONArray array) {
        ArrayList<McpToolSummary> tools = new ArrayList<>();
        if (array == null) {
            return tools;
        }
        for (int i = 0; i < array.length(); i++) {
            Object raw = array.opt(i);
            if (raw instanceof String) {
                String toolName = ((String) raw).trim();
                if (toolName.length() > 0) {
                    tools.add(new McpToolSummary(toolName, true, "", ""));
                }
            } else if (raw instanceof JSONObject) {
                JSONObject item = (JSONObject) raw;
                String name = item.optString("name").trim();
                if (name.length() == 0) {
                    name = item.optString("id").trim();
                }
                if (name.length() == 0) {
                    continue;
                }
                JSONObject schema = item.optJSONObject("inputSchema");
                if (schema == null) {
                    schema = item.optJSONObject("input_schema");
                }
                if (schema == null) {
                    schema = item.optJSONObject("schema");
                }
                if (schema == null) {
                    schema = item.optJSONObject("parameters");
                }
                tools.add(new McpToolSummary(
                        name,
                        item.optBoolean("enabled", true),
                        item.optString("description"),
                        schema == null ? "" : schema.toString()
                ));
            }
        }
        return tools;
    }

    private String extractJsonFromEventStream(String text) {
        String safeText = safe(text).trim();
        if (safeText.startsWith("{") && safeText.endsWith("}")) {
            return safeText;
        }
        StringBuilder data = new StringBuilder();
        String[] lines = safeText.split("\\r?\\n");
        for (String line : lines) {
            if (!line.startsWith("data:")) {
                continue;
            }
            String value = line.substring("data:".length()).trim();
            if (value.length() == 0 || "[DONE]".equals(value)) {
                continue;
            }
            data.append(value);
        }
        return data.length() == 0 ? safeText : data.toString();
    }

}
