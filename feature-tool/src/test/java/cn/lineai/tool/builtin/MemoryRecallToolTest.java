package cn.lineai.tool.builtin;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import cn.lineai.data.repository.ConversationRecord;
import cn.lineai.data.repository.LearningContextStore;
import cn.lineai.data.repository.MemoryRanker;
import cn.lineai.model.tool.ToolResult;
import cn.lineai.tool.ToolContext;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.json.JSONObject;
import org.junit.Test;

public final class MemoryRecallToolTest {

    @Test
    public void missingParametersIsError() {
        MemoryRecallTool tool = new MemoryRecallTool();
        ToolResult result = tool.execute(null, context(new FakeStore()));
        assertTrue(result.isError());
        assertEquals("NO_PARAMS", result.getContent());
    }

    @Test
    public void emptyQueryIsError() {
        MemoryRecallTool tool = new MemoryRecallTool();
        ToolResult result = tool.execute(new JSONObject().put("query", "   "), context(new FakeStore()));
        assertTrue(result.isError());
        assertEquals("QUERY_EMPTY", result.getContent());
    }

    @Test
    public void missingStoreIsError() {
        MemoryRecallTool tool = new MemoryRecallTool();
        ToolContext context = ToolContext.builder()
                .homePath("/workspace")
                .stringResolver(new Strings())
                .build();
        ToolResult result = tool.execute(new JSONObject().put("query", "deadline"), context);
        assertTrue(result.isError());
        assertEquals("NO_STORE", result.getContent());
    }

    @Test
    public void noMatchReportsHonestlyInsteadOfGuessing() throws Exception {
        FakeStore store = new FakeStore();
        MemoryRecallTool tool = new MemoryRecallTool();
        ToolResult result = tool.execute(new JSONObject().put("query", "kubernetes"), context(store));

        assertFalse(result.isError());
        assertEquals("EMPTY(kubernetes|all)", result.getContent());
        assertEquals("kubernetes", store.lastQuery);
        assertEquals("/workspace", store.lastProjectId);
        assertEquals("all", store.lastScope);
    }

    @Test
    public void matchesAreListedWithHeaderAndClampedLimit() throws Exception {
        FakeStore store = new FakeStore();
        store.results.add(new MemoryRanker.Candidate(
                "m1", "deadline project", 1L, "- [project/deadline] sprint ends Friday"));
        store.results.add(new MemoryRanker.Candidate(
                "m2", "deadline user", 2L, "- [user/deadline] prefer mornings"));
        MemoryRecallTool tool = new MemoryRecallTool();
        ToolResult result = tool.execute(
                new JSONObject().put("query", "deadline").put("scope", "PROJECT").put("limit", 999),
                context(store));

        assertFalse(result.isError());
        assertTrue(result.getContent().startsWith("HEADER(deadline|project|2)"));
        assertTrue(result.getContent().contains("- [project/deadline] sprint ends Friday"));
        assertTrue(result.getContent().contains("- [user/deadline] prefer mornings"));
        assertEquals("project", store.lastScope);
        assertEquals(10, store.lastLimit);
    }

    @Test
    public void unknownScopeFallsBackToAllAndLimitFloorIsOne() throws Exception {
        FakeStore store = new FakeStore();
        MemoryRecallTool tool = new MemoryRecallTool();
        tool.execute(new JSONObject().put("query", "prefs").put("scope", "bogus").put("limit", 0),
                context(store));

        assertEquals("all", store.lastScope);
        assertEquals(1, store.lastLimit);
    }

    private static ToolContext context(FakeStore store) {
        return ToolContext.builder()
                .homePath("/workspace")
                .learningContextStore(store)
                .stringResolver(new Strings())
                .build();
    }

    private static final class FakeStore implements LearningContextStore {
        private final List<MemoryRanker.Candidate> results = new ArrayList<>();
        private String lastProjectId;
        private String lastQuery;
        private String lastScope;
        private int lastLimit;

        @Override
        public void saveMemory(String id, String scope, String projectId, String content) {
        }

        @Override
        public void saveExtractedMemory(String scope, String projectId, String content, double confidence) {
        }

        @Override
        public void deleteMemory(String id) {
        }

        @Override
        public void deleteMemories(java.util.List<String> ids) {
        }

        @Override
        public void indexConversation(String projectId, ConversationRecord conversation) {
        }

        @Override
        public List<MemoryRanker.Candidate> searchMemories(String projectId, String query, String scope, int limit) {
            lastProjectId = projectId;
            lastQuery = query;
            lastScope = scope;
            lastLimit = limit;
            return results;
        }
    }

    private static final class Strings implements ToolContext.StringResolver {
        @Override
        public String getString(int resId) {
            if (resId == cn.lineai.tool.R.string.tool_memory_params_empty) return "NO_PARAMS";
            if (resId == cn.lineai.tool.R.string.tool_memory_store_not_init) return "NO_STORE";
            if (resId == cn.lineai.tool.R.string.tool_memory_recall_query_empty) return "QUERY_EMPTY";
            if (resId == cn.lineai.tool.R.string.tool_memory_recall_empty) return "EMPTY(%1$s|%2$s)";
            if (resId == cn.lineai.tool.R.string.tool_memory_recall_header) return "HEADER(%1$s|%2$s|%3$d)";
            if (resId == cn.lineai.tool.R.string.tool_memory_recall_failed) return "FAILED(%1$s)";
            return "";
        }

        @Override
        public String getString(int resId, Object... formatArgs) {
            if (formatArgs == null || formatArgs.length == 0) {
                return getString(resId);
            }
            return String.format(Locale.ROOT, getString(resId), formatArgs);
        }
    }
}
