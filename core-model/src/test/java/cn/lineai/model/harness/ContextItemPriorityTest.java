package cn.lineai.model.harness;

import static org.junit.Assert.*;

import org.junit.Test;

/**
 * Unit tests for {@link ContextPriority} and {@link ContextItem} (§6.1, §7).
 */
public class ContextItemPriorityTest {

    @Test
    public void priority_order_p0_to_p8() {
        ContextPriority[] values = ContextPriority.values();
        assertEquals(9, values.length);
        assertEquals(ContextPriority.P0_SYSTEM_AND_CAPSULE, values[0]);
        assertEquals(ContextPriority.P8_METADATA, values[8]);
        assertTrue(values[0].ordinal() < values[1].ordinal());
    }

    @Test
    public void only_p0_and_p1_are_protected() {
        for (ContextPriority p : ContextPriority.values()) {
            if (p == ContextPriority.P0_SYSTEM_AND_CAPSULE
                    || p == ContextPriority.P1_USER_REQUEST) {
                assertTrue(p.isProtected());
            } else {
                assertFalse(p.isProtected());
            }
        }
    }

    @Test
    public void fromString_parses_short_forms() {
        assertEquals(ContextPriority.P0_SYSTEM_AND_CAPSULE, ContextPriority.fromString("P0"));
        assertEquals(ContextPriority.P1_USER_REQUEST, ContextPriority.fromString("p1"));
        assertEquals(ContextPriority.P8_METADATA, ContextPriority.fromString("P8"));
        assertEquals(ContextPriority.P3_CODE_CONTEXT, ContextPriority.fromString(null));
        assertEquals(ContextPriority.P3_CODE_CONTEXT, ContextPriority.fromString("garbage"));
    }

    // ---- ContextItem ----

    @Test
    public void token_estimate_uses_chars_per_token_4() {
        ContextItem item = new ContextItem.Builder("i1", "abcdefgh") // 8 chars
                .build();
        assertEquals(2, item.estimatedTokens());
    }

    @Test
    public void null_content_is_safe() {
        ContextItem item = new ContextItem.Builder("i1", null).build();
        assertEquals("", item.content());
        assertEquals(0, item.estimatedTokens());
    }

    @Test
    public void default_priority_is_code_context() {
        ContextItem item = new ContextItem.Builder("i1", "x").build();
        assertEquals(ContextPriority.P3_CODE_CONTEXT, item.priority());
    }

    @Test(expected = IllegalArgumentException.class)
    public void blank_id_rejected() {
        new ContextItem.Builder("", "x").build();
    }

    @Test
    public void builder_timestamps_default_to_now() {
        long before = System.currentTimeMillis();
        ContextItem item = new ContextItem.Builder("i1", "x").build();
        long after = System.currentTimeMillis();
        assertTrue(item.createdAt() >= before && item.createdAt() <= after);
        assertEquals(item.createdAt(), item.updatedAt());
        assertEquals(0, item.lastVerifiedAt()); // never verified by default
    }
}
