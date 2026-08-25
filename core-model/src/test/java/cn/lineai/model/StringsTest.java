package cn.lineai.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class StringsTest {

    @Test
    public void nullToEmptyHandlesNullAndNonNull() {
        assertEquals("", Strings.nullToEmpty(null));
        assertEquals("", Strings.nullToEmpty(""));
        assertEquals("hello", Strings.nullToEmpty("hello"));
    }

    @Test
    public void isNullOrEmptyChecks() {
        assertTrue(Strings.isNullOrEmpty(null));
        assertTrue(Strings.isNullOrEmpty(""));
        assertFalse(Strings.isNullOrEmpty(" "));
        assertFalse(Strings.isNullOrEmpty("valid"));
    }
}
