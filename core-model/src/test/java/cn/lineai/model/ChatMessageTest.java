package cn.lineai.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import cn.lineai.model.tool.ToolCall;
import cn.lineai.model.tool.ToolResult;

import org.junit.Test;

import java.util.Collections;
import java.util.List;

public final class ChatMessageTest {

    @Test
    public void defaultValuesAndNullSafety() {
        ChatMessage message = new ChatMessage("msg_1", null, null, false);
        assertEquals("msg_1", message.getId());
        assertEquals(ChatMessage.Role.USER, message.getRole());
        assertEquals("", message.getContent());
        assertEquals("", message.getReasoningContent());
        assertFalse(message.isStreaming());
        assertFalse(message.isHidden());
        assertFalse(message.isExcludeFromContext());
        assertFalse(message.hasToolCalls());
        assertNotNull(message.getToolCalls());
        assertNotNull(message.getToolResults());
    }

    @Test
    public void immutableTransformationsWithContentAndToolCalls() {
        ChatMessage original = new ChatMessage("msg_2", ChatMessage.Role.ASSISTANT, "Hello", "thinking", false);
        assertEquals("Hello", original.getContent());
        assertEquals("thinking", original.getReasoningContent());

        ChatMessage updated = original.withContent("Hello World", "updated thinking", true);
        assertEquals("Hello World", updated.getContent());
        assertEquals("updated thinking", updated.getReasoningContent());
        assertTrue(updated.isStreaming());
        // Original remains unchanged
        assertEquals("Hello", original.getContent());
        assertFalse(original.isStreaming());

        ToolCall toolCall = new ToolCall("tc_1", "file_read", "{\"file_path\":\"test.txt\"}");
        ChatMessage withTool = original.withToolCalls(Collections.singletonList(toolCall), true);
        assertTrue(withTool.hasToolCalls());
        assertEquals(1, withTool.getToolCalls().size());
        assertEquals("file_read", withTool.getToolCalls().get(0).getName());
        assertTrue(withTool.isHidden());
    }

    @Test
    public void toolResultFactoryAndLookup() {
        ToolResult result = ToolResult.of("res_1", "file_read", "file content", false);
        ChatMessage toolMessage = ChatMessage.toolResult("msg_tool", "file content", "call_1", "file_read", false);
        assertEquals(ChatMessage.Role.TOOL, toolMessage.getRole());
        assertEquals("call_1", toolMessage.getToolCallId());
        assertEquals("file_read", toolMessage.getToolName());
        assertFalse(toolMessage.isError());

        ChatMessage messageWithResults = new ChatMessage(
                "msg_3", ChatMessage.Role.ASSISTANT, "", "", false, false, false,
                Collections.emptyList(), Collections.singletonList(result), "", "", false
        );
        assertEquals(result, messageWithResults.getToolResult("res_1"));
        assertNull(messageWithResults.getToolResult("non_existent"));
        assertNull(messageWithResults.getToolResult(null));
    }

    @Test
    public void compactProgressAndModelSwitchFactory() {
        ChatMessage progress = ChatMessage.compactProgress("prog_1", ChatMessage.COMPACT_STATUS_RUNNING);
        assertTrue(progress.isStreaming());
        assertTrue(progress.isCompactBlock());
        assertEquals(ChatMessage.COMPACT_STATUS_RUNNING, progress.getCompactStatus());

        ChatMessage switchNotice = ChatMessage.modelSwitchNotice("notice_1", "gpt-4o", "gemini-1.5-pro");
        assertTrue(switchNotice.isModelSwitchNotification());
        assertTrue(switchNotice.getModelSwitchNotification().contains("gpt-4o"));
        assertTrue(switchNotice.getModelSwitchNotification().contains("gemini-1.5-pro"));
        assertTrue(switchNotice.isExcludeFromContext());
    }
}
