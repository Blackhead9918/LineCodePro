package cn.lineai.tool;
import cn.lineai.model.tool.ToolCall;
import cn.lineai.model.tool.ToolResult;

import cn.lineai.data.repository.ToolSettingsStore;
import cn.lineai.model.McpSettingsState;
import cn.lineai.model.McpToolConfig;
import cn.lineai.model.WebSearchConfig;
import cn.lineai.tool.ToolInfo;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.json.JSONObject;
import org.junit.Assert;
import org.junit.Test;

public final class ToolExecutorTest {
    @Test
    public void toolExceptionWithoutMessageDoesNotRenderNull() {
        ToolRegistry registry = new ToolRegistry();
        registry.register(new ThrowingTool());
        ToolExecutor executor = new ToolExecutor(registry, new AllowAllToolSettingsStore(), null, null, null, null, null);

        ToolResult result = executor.execute(new ToolCall("call_1", "throwing_tool", "{}"), ToolContext.builder().homePath("").build());

        Assert.assertTrue(result.isError());
        Assert.assertTrue(result.getContent().contains("工具执行失败"));
        Assert.assertTrue(result.getContent().contains("RuntimeException"));
        Assert.assertFalse(result.getContent().contains("null"));
    }

    @Test
    public void injectedDependenciesPreserveProgressListenerAgentResultStoreAndStringResolver() {
        ToolRegistry registry = new ToolRegistry();
        CapturingTool tool = new CapturingTool();
        registry.register(tool);
        ToolExecutor executor = new ToolExecutor(registry, new AllowAllToolSettingsStore(), null, null, null, null, null);

        ToolContext.ProgressListener progressListener = (toolCallId, toolName, content, error) -> {
        };
        ToolContext.AgentResultStore agentResultStore = agentId -> null;
        ToolContext.StringResolver stringResolver = new ToolContext.StringResolver() {
            @Override
            public String getString(int resId) {
                return "resolved-" + resId;
            }

            @Override
            public String getString(int resId, Object... formatArgs) {
                return "resolved-" + resId;
            }
        };
        // Context deliberately lacks toolSettingsStore/modelRepository/modelServiceProvider/learningContextStore
        // so ToolExecutor must run injectDependencies — mirroring GenerationFlowController.toolContext().
        ToolContext context = ToolContext.builder()
                .homePath("/workspace")
                .progressListener(progressListener)
                .agentResultStore(agentResultStore)
                .stringResolver(stringResolver)
                .build();

        ToolResult result = executor.execute(new ToolCall("call_1", "capturing_tool", "{}"), context);

        Assert.assertFalse(result.isError());
        ToolContext seen = tool.lastContext;
        Assert.assertNotNull(seen);
        Assert.assertSame("progressListener must survive injectDependencies", progressListener, seen.getProgressListener());
        Assert.assertSame("agentResultStore must survive injectDependencies", agentResultStore, seen.getAgentResultStore());
        Assert.assertSame("stringResolver must survive injectDependencies", stringResolver, seen.getStringResolver());
        Assert.assertEquals("/workspace", seen.getHomePath());
        Assert.assertEquals("resolved-7", seen.getString(7));
        Assert.assertNotNull("toolSettingsStore must be injected", seen.getToolSettingsStore());
    }

    private static final class CapturingTool extends BaseTool {
        ToolContext lastContext;

        @Override
        public String getName() {
            return "capturing_tool";
        }

        @Override
        public String getDescription() {
            return "Captures the ToolContext it receives.";
        }

        @Override
        public ToolCategory getCategory() {
            return ToolCategory.READ;
        }

        @Override
        public JSONObject getParameters() {
            return new JSONObject();
        }

        @Override
        public ToolResult execute(JSONObject input, ToolContext context) {
            this.lastContext = context;
            return ToolResult.success("captured");
        }
    }

    private static final class ThrowingTool extends BaseTool {
        @Override
        public String getName() {
            return "throwing_tool";
        }

        @Override
        public String getDescription() {
            return "Throws without an exception message.";
        }

        @Override
        public ToolCategory getCategory() {
            return ToolCategory.SYSTEM;
        }

        @Override
        public JSONObject getParameters() {
            return new JSONObject();
        }

        @Override
        public ToolResult execute(JSONObject input, ToolContext context) {
            throw new RuntimeException();
        }
    }

    private static final class AllowAllToolSettingsStore implements ToolSettingsStore {
        @Override
        public String getPermissionMode() {
            return PERMISSION_AUTO;
        }

        @Override
        public void setPermissionMode(String mode) {
        }

        @Override
        public String getExecutionMode() {
            return EXECUTION_LOCAL;
        }

        @Override
        public void setExecutionMode(String mode) {
        }

        @Override
        public List<McpToolConfig> getConfigs() {
            return Collections.emptyList();
        }

        @Override
        public McpSettingsState getMcpSettingsState() {
            return null;
        }

        @Override
        public WebSearchConfig getWebSearchConfig() {
            return null;
        }

        @Override
        public void setWebSearchConfig(WebSearchConfig config) {
        }

        @Override
        public String getImageUnderstandingModelId() {
            return "";
        }

        @Override
        public void setImageUnderstandingModelId(String modelId) {
        }

        @Override
        public String getImageGenerationModelId() {
            return "";
        }

        @Override
        public void setImageGenerationModelId(String modelId) {
        }

        @Override
        public void setMcpEnabled(String id, boolean enabled) {
        }

        @Override
        public Set<String> getEnabledToolNames() {
            return new HashSet<>(Collections.singletonList("throwing_tool"));
        }

        @Override
        public Set<String> getEnabledToolNames(Collection<ToolInfo> implementedTools) {
            return getEnabledToolNames();
        }

        @Override
        public PermissionResult canExecuteTool(String toolName, ToolCategory category) {
            return PermissionResult.allowed();
        }

        @Override
        public boolean needsConfirmation(String toolName) {
            return false;
        }

        @Override
        public String buildToolPrompt(Set<String> implementedToolNames) {
            return "";
        }

        @Override
        public String buildToolPrompt(Set<String> implementedToolNames, boolean nativeToolProtocol) {
            return "";
        }

        @Override
        public String buildToolPrompt(Collection<ToolInfo> implementedTools, boolean nativeToolProtocol) {
            return "";
        }
    }
}
