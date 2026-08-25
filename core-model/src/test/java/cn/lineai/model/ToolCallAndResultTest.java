package cn.lineai.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import cn.lineai.model.tool.ToolCall;
import cn.lineai.model.tool.ToolResult;

import org.junit.Test;

public final class ToolCallAndResultTest {

    @Test
    public void toolCallDefaultsAndValues() {
        ToolCall call = new ToolCall("call_123", "glob", "{\"pattern\":\"*.kt\"}");
        assertEquals("call_123", call.getId());
        assertEquals("glob", call.getName());
        assertEquals("{\"pattern\":\"*.kt\"}", call.getArguments());

        ToolCall fallback = new ToolCall(null, null, null);
        assertNotNull(fallback.getId());
        assertTrue(fallback.getId().startsWith("tool_call_"));
        assertEquals("", fallback.getName());
        assertEquals("{}", fallback.getArguments());
    }

    @Test
    public void toolResultFactoryMethods() {
        ToolResult okResult = ToolResult.of("call_abc", "glob", "success output", false);
        assertEquals("call_abc", okResult.getToolCallId());
        assertEquals("glob", okResult.getToolName());
        assertEquals("success output", okResult.getContent());
        assertFalse(okResult.isError());

        ToolResult errResult = ToolResult.error("failed to read file");
        assertEquals("failed to read file", errResult.getContent());
        assertTrue(errResult.isError());

        ToolResult succResult = ToolResult.success("simple success");
        assertEquals("simple success", succResult.getContent());
        assertFalse(succResult.isError());
    }

    @Test
    public void toolResultTruncation() {
        String shortContent = "Short content";
        assertEquals(shortContent, ToolResult.truncateContent(shortContent));

        StringBuilder largeBuilder = new StringBuilder();
        for (int i = 0; i < 60000; i++) {
            largeBuilder.append("A");
        }
        String truncated = ToolResult.truncateContent(largeBuilder.toString());
        assertTrue(truncated.contains("chars truncated"));
    }
}
