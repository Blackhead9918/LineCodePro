package cn.lineai.mvp;

import static org.junit.Assert.*;

import org.junit.Test;

/**
 * Unit tests for reasoning→content promotion in {@link GenerationFlowController}
 * (models that emit their entire answer inside an unclosed {@code <think>} block,
 * or providers that route final tokens to reasoning_content).
 */
public class ReasoningPromotionTest {

    @Test
    public void promotes_reasoning_when_text_empty() {
        GenerationFlowController.Promotion p = GenerationFlowController.promoteReasoningIfEmpty(
                "", "The answer is 42.", "");
        assertEquals("The answer is 42.", p.text);
        assertEquals("", p.reasoning); // moved, not duplicated
    }

    @Test
    public void promotes_whitespace_only_text() {
        GenerationFlowController.Promotion p = GenerationFlowController.promoteReasoningIfEmpty(
                "   \n", "  actual answer  ", "");
        assertEquals("actual answer", p.text);
    }

    @Test
    public void notice_is_prepended_to_promoted_content() {
        GenerationFlowController.Promotion p = GenerationFlowController.promoteReasoningIfEmpty(
                "", "answer body", "[notice]");
        assertEquals("[notice]\n\nanswer body", p.text);
        assertEquals("", p.reasoning);
    }

    @Test
    public void no_promotion_when_text_present() {
        GenerationFlowController.Promotion p = GenerationFlowController.promoteReasoningIfEmpty(
                "normal answer", "some reasoning", "[notice]");
        assertEquals("normal answer", p.text);
        assertEquals("some reasoning", p.reasoning); // untouched
    }

    @Test
    public void no_promotion_when_both_empty() {
        GenerationFlowController.Promotion p = GenerationFlowController.promoteReasoningIfEmpty(
                "", "", "[notice]");
        assertEquals("", p.text);
        assertEquals("", p.reasoning); // empty-response fallback handles this case later
    }

    @Test
    public void null_inputs_are_safe() {
        GenerationFlowController.Promotion p = GenerationFlowController.promoteReasoningIfEmpty(
                null, null, null);
        assertEquals("", p.text);
        assertEquals("", p.reasoning);

        GenerationFlowController.Promotion withReasoning = GenerationFlowController.promoteReasoningIfEmpty(
                null, "only reasoning", null);
        assertEquals("only reasoning", withReasoning.text);
    }
}
