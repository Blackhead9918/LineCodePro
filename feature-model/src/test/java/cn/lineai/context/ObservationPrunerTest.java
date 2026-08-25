package cn.lineai.context;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import cn.lineai.model.ChatMessage;
import cn.lineai.tool.ToolNames;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

public final class ObservationPrunerTest {

    @Test
    public void keepsRecentTurnObservationsUnpruned() {
        ArrayList<ChatMessage> messages = new ArrayList<>();
        messages.add(new ChatMessage("u1", ChatMessage.Role.USER, "Show me files", false));
        
        StringBuilder longContent = new StringBuilder();
        for (int i = 1; i <= 50; i++) {
            longContent.append("Line ").append(i).append(": some code content\n");
        }
        ChatMessage recentTool = ChatMessage.toolResult("t1", longContent.toString(), "call_1", ToolNames.FILE_READ, false);
        messages.add(recentTool);

        List<ChatMessage> pruned = ObservationPruner.pruneHistoricalObservations(messages);

        assertEquals(2, pruned.size());
        assertEquals(longContent.toString(), pruned.get(1).getContent());
    }

    @Test
    public void prunesHistoricalToolObservations() {
        ArrayList<ChatMessage> messages = new ArrayList<>();
        // Turn 1
        messages.add(new ChatMessage("u1", ChatMessage.Role.USER, "Read old file", false));
        
        StringBuilder fileContent = new StringBuilder();
        fileContent.append("[FILE: src/OldClass.java (Lines 1-100 of 100)]\n");
        for (int i = 1; i <= 100; i++) {
            fileContent.append(i).append("\tclass OldClass { int field").append(i).append("; }\n");
        }
        fileContent.append("[EOF: src/OldClass.java]");
        
        ChatMessage oldTool = ChatMessage.toolResult("t1", fileContent.toString(), "call_1", ToolNames.FILE_READ, false);
        messages.add(oldTool);
        messages.add(new ChatMessage("a1", ChatMessage.Role.ASSISTANT, "I analyzed OldClass.", false));

        // Turn 2 (active turn)
        messages.add(new ChatMessage("u2", ChatMessage.Role.USER, "Now read new file", false));
        ChatMessage recentTool = ChatMessage.toolResult("t2", "short content", "call_2", ToolNames.FILE_READ, false);
        messages.add(recentTool);

        List<ChatMessage> pruned = ObservationPruner.pruneHistoricalObservations(messages);

        assertEquals(5, pruned.size());
        
        // Old tool observation should be pruned
        ChatMessage prunedOldTool = pruned.get(1);
        assertTrue(prunedOldTool.getContent().contains("[Observation pruned"));
        assertTrue(prunedOldTool.getContent().contains("[FILE: src/OldClass.java"));
        assertFalse(prunedOldTool.getContent().contains("class OldClass { int field50; }"));

        // Recent tool observation should remain intact
        ChatMessage activeTool = pruned.get(4);
        assertEquals("short content", activeTool.getContent());
    }

    @Test
    public void preservesShortHistoricalToolObservations() {
        ArrayList<ChatMessage> messages = new ArrayList<>();
        messages.add(new ChatMessage("u1", ChatMessage.Role.USER, "Run simple command", false));
        ChatMessage shortTool = ChatMessage.toolResult("t1", "OK: created file", "call_1", ToolNames.FILE_WRITE, false);
        messages.add(shortTool);
        messages.add(new ChatMessage("u2", ChatMessage.Role.USER, "Next step", false));

        List<ChatMessage> pruned = ObservationPruner.pruneHistoricalObservations(messages);

        assertEquals(3, pruned.size());
        assertEquals("OK: created file", pruned.get(1).getContent());
    }

    @Test
    public void contextManagerEstimationIsAwareOfPruning() {
        ContextManager manager = new ContextManager();
        ArrayList<ChatMessage> messages = new ArrayList<>();
        messages.add(new ChatMessage("u1", ChatMessage.Role.USER, "Read large file", false));

        StringBuilder fileContent = new StringBuilder();
        for (int i = 1; i <= 200; i++) {
            fileContent.append("public void method").append(i).append("() { System.out.println(\"hello\"); }\n");
        }
        messages.add(ChatMessage.toolResult("t1", fileContent.toString(), "call_1", ToolNames.FILE_READ, false));
        messages.add(new ChatMessage("u2", ChatMessage.Role.USER, "Next question", false));

        int rawSingleToolTokens = manager.estimateTokens(messages.get(1));
        int totalPrunedTokens = manager.estimateTokens(messages);

        // Raw tool alone is ~2,000 tokens, but when pruned in multi-turn context it should be very small
        assertTrue(rawSingleToolTokens > 1500);
        assertTrue(totalPrunedTokens < rawSingleToolTokens);
    }
}
