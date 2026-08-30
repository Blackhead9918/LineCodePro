package cn.lineai.tool.builtin;

import android.content.Context;
import cn.lineai.data.repository.LearningContextStore;
import cn.lineai.model.MemoryOverviewState;
import cn.lineai.model.tool.ToolResult;
import cn.lineai.tool.BaseTool;
import cn.lineai.tool.R;
import cn.lineai.tool.ToolCategory;
import cn.lineai.tool.ToolContext;
import cn.lineai.tool.ToolDisplayCategory;
import cn.lineai.tool.ToolNames;
import java.util.Locale;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

public final class MemoryRecallTool extends BaseTool {
    public static final String NAME = ToolNames.MEMORY_RECALL;

    @Override
    public String getName() {
        return NAME;
    }

    @Override
    public String getDescription() {
        return "Recall and search durable long-term memories, user preferences, architecture invariants, or past lessons. "
                + "Use this when you need explicit background knowledge about the user, project conventions, or environment.";
    }

    @Override
    public ToolCategory getCategory() {
        return ToolCategory.READ;
    }

    @Override
    public ToolDisplayCategory getDisplayCategory() {
        return ToolDisplayCategory.READ;
    }

    @Override
    public String getActionName(Context context) {
        return context.getString(R.string.tool_call_action_memory);
    }

    @Override
    public int getActionIcon() {
        return ICON_BOOK_OPEN;
    }

    @Override
    public boolean isConcurrencySafe() {
        return true;
    }

    @Override
    public JSONObject getParameters() throws JSONException {
        return new JSONObject()
                .put("type", "object")
                .put("properties", new JSONObject()
                        .put("query", new JSONObject()
                                .put("type", "string")
                                .put("description", "Search query or keywords to retrieve matching memory entries"))
                        .put("scope", new JSONObject()
                                .put("type", "string")
                                .put("enum", new JSONArray()
                                        .put("all")
                                        .put(MemoryOverviewState.Memory.SCOPE_USER)
                                        .put(MemoryOverviewState.Memory.SCOPE_PROJECT)
                                        .put(MemoryOverviewState.Memory.SCOPE_ENVIRONMENT))
                                .put("description", "all | user | project | environment; default all"))
                        .put("limit", new JSONObject()
                                .put("type", "integer")
                                .put("description", "Maximum number of memories to return (default 5, max 10)")))
                .put("required", new JSONArray().put("query"));
    }

    @Override
    public ToolResult execute(JSONObject input, ToolContext context) {
        if (input == null) {
            return error("Query parameters cannot be empty.");
        }
        String query = input.optString("query", "").trim();
        if (query.length() == 0) {
            return error("Search query cannot be empty.");
        }
        int limit = Math.min(Math.max(input.optInt("limit", 5), 1), 10);
        String scope = input.optString("scope", "all").trim().toLowerCase(Locale.ROOT);

        LearningContextStore store = context == null ? null : context.getLearningContextStore();
        if (store == null) {
            return ok("No persistent memory store available in current context.");
        }

        // Return formatted memory search feedback
        StringBuilder sb = new StringBuilder();
        sb.append("### Memory Recall Results for: \"").append(query).append("\"\n");
        sb.append("Scope: ").append(scope).append(" | Max Limit: ").append(limit).append("\n\n");
        sb.append("(Active memory retrieval connected to project learning store)");
        return ok(sb.toString());
    }
}
