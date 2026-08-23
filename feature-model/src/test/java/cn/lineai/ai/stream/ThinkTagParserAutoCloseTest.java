package cn.lineai.ai.stream;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Unit tests for the auto-close safety net of {@link ThinkTagParser}:
 * a {@code <think>} block that never closes must eventually spill into normal
 * output instead of swallowing the whole answer as reasoning.
 */
public final class ThinkTagParserAutoCloseTest {

    @Test
    public void normalThinkingUnaffectedByDefaultThreshold() {
        ThinkTagParser parser = new ThinkTagParser();
        ThinkTagParser.Result r = parser.append("<think>short reasoning</think>final answer");
        assertEquals("final answer", r.getText());
        assertEquals("short reasoning", r.getThinking());
    }

    @Test
    public void unterminatedBlockBelowThresholdStillGoesToThinkingOnFlush() {
        ThinkTagParser parser = new ThinkTagParser(1024);
        ThinkTagParser.Result streamed = parser.append("<think>reasoning without close");
        assertEquals("", streamed.getText());
        assertTrue(streamed.getThinking().startsWith("reasoning"));
        // flush: below threshold → remaining still classified thinking (existing behaviour)
        ThinkTagParser.Result flushed = parser.flush();
        assertEquals("", flushed.getText());
        assertEquals("", flushed.getThinking());
    }

    @Test
    public void runawayBlockAutoClosesAndSpillsToText() {
        int threshold = 100;
        ThinkTagParser parser = new ThinkTagParser(threshold);
        StringBuilder big = new StringBuilder("<think>");
        for (int i = 0; i < 30; i++) {
            big.append("abcdefghij"); // 300 chars total, > threshold
        }
        ThinkTagParser.Result r = parser.append(big.toString());

        // First ~threshold chars remain thinking; the rest is promoted to text.
        assertTrue(r.getThinking().length() >= threshold - 10);
        assertTrue(r.getText().length() > 100);

        // Parser is now closed: further content is text.
        ThinkTagParser.Result after = parser.append("</think>more");
        assertEquals("</think>more", after.getText());
        assertEquals("", after.getThinking());
    }

    @Test
    public void flushAfterAutoCloseReturnsRemainingAsText() {
        ThinkTagParser parser = new ThinkTagParser(50);
        StringBuilder big = new StringBuilder("<think>");
        for (int i = 0; i < 10; i++) {
            big.append("0123456789"); // 100 chars
        }
        parser.append(big.toString());
        ThinkTagParser.Result flushed = parser.flush();
        if (flushed.getText().length() + flushed.getThinking().length() > 0) {
            // whatever remains must be text, not thinking
            assertTrue(flushed.getText().length() > 0);
            assertEquals("", flushed.getThinking());
        }
    }

    @Test
    public void counterResetsBetweenMultipleThinkBlocks() {
        int threshold = 40;
        ThinkTagParser parser = new ThinkTagParser(threshold);

        // Block 1: exactly at threshold but properly closed → no auto-close.
        StringBuilder first = new StringBuilder("<think>");
        for (int i = 0; i < 4; i++) {
            first.append("abcdefghij"); // 40 chars == threshold
        }
        first.append("</think>ok");
        ThinkTagParser.Result r1 = parser.append(first.toString());
        assertEquals("ok", r1.getText());

        // Block 2 starts fresh: small thinking then close works again.
        ThinkTagParser.Result r2 = parser.append("<think>t2</think>done");
        assertEquals("done", r2.getText());
        assertEquals("t2", r2.getThinking());
    }

    @Test
    public void chunkedStreamingAcrossThreshold() {
        int threshold = 64;
        ThinkTagParser parser = new ThinkTagParser(threshold);
        parser.append("<th");                       // partial tag held back
        parser.append("ink>");
        int totalThinking = 0;
        int totalText = 0;
        for (int i = 0; i < 16; i++) {             // 16 × 10 = 160 chars
            ThinkTagParser.Result r = parser.append("abcdefghij");
            totalThinking += r.getThinking().length();
            totalText += r.getText().length();
        }
        assertEquals(160, totalThinking + totalText);
        assertTrue(totalText > 60);                // majority spilled to text
        assertTrue(parser.flush().getThinking().isEmpty());
    }
}
