package cn.lineai.tool.builtin;

import android.content.Context;
import cn.lineai.data.repository.LearningContextStore;
import cn.lineai.data.repository.MemoryRanker;
import cn.lineai.model.MemoryOverviewState;
import cn.lineai.model.tool.ToolResult;
import cn.lineai.tool.BaseTool;
import cn.lineai.tool.R;
import cn.lineai.tool.ToolCategory;
import cn.lineai.tool.ToolContext;
import cn.lineai.tool.ToolDisplayCategory;
import cn.lineai.tool.ToolNames;
import java.util.List;
import java.util.Locale;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Recalls durable long-term memories that actually match the query.
 * <p>
 * The search is performed against the persisted memories table with keyword
 * relevance ranking (no "recent fallback"), so an empty result is reported
 * honestly instead of returning unrelated memories that could mislead the model.
 */
public final class MemoryRecallTool extends BaseTool {
    public static final String NAME = ToolNames.MEMORY_RECALL;
    private static final int DEFAULT_LIMIT = 5;
    private static final int MAX_LIMIT = 10;

    @Override
    public String getName() {
        return NAME;
    }

    @Override
    public String getDescription() {
        return "Search durable long-term memories (user preferences, project constraints, environment facts, past lessons) "
                + "by keywords. Returns only entries that match the query; when nothing matches, report that no memory was found "
                + "instead of guessing. Prefer this over asking the user to repeat information already stored.";
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
            return error(context.getString(R.string.tool_memory_params_empty));
        }
        String query = input.optString("query", "").trim();
        if (query.length() == 0) {
            return error(context.getString(R.string.tool_memory_recall_query_empty));
        }
        int limit = Math.min(Math.max(input.optInt("limit", DEFAULT_LIMIT), 1), MAX_LIMIT);
        String scope = normalizeScope(input.optString("scope", "all"));

        LearningContextStore store = context.getLearningContextStore();
        if (store == null) {
            return error(context.getString(R.string.tool_memory_store_not_init));
        }
        List<MemoryRanker.Candidate> matches;
        try {
            matches = store.searchMemories(context.getHomePath(), query, scope, limit);
        } catch (Exception e) {
            return error(context.getString(R.string.tool_memory_recall_failed, describe(e)));
        }
        if (matches == null || matches.isEmpty()) {
            return ok(context.getString(R.string.tool_memory_recall_empty, query, scope));
        }
        StringBuilder builder = new StringBuilder();
        builder.append(context.getString(R.string.tool_memory_recall_header, query, scope, matches.size()));
        for (MemoryRanker.Candidate match : matches) {
            if (match == null) {
                continue;
            }
            builder.append('\n').append(match.formatted);
        }
        return ok(builder.toString());
    }

    private static String normalizeScope(String scope) {
        String value = scope == null ? "" : scope.trim().toLowerCase(Locale.ROOT);
        if (MemoryOverviewState.Memory.SCOPE_USER.equals(value)
                || MemoryOverviewState.Memory.SCOPE_PROJECT.equals(value)
                || MemoryOverviewState.Memory.SCOPE_ENVIRONMENT.equals(value)) {
            return value;
        }
        return "all";
    }

    private static String describe(Exception error) {
        if (error == null) {
            return "";
        }
        String message = error.getMessage();
        return message == null ? error.getClass().getSimpleName() : message;
    }
}
