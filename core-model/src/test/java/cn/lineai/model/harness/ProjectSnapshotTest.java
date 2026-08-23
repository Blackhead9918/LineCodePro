package cn.lineai.model.harness;

import static org.junit.Assert.*;

import java.util.Arrays;

import org.junit.Test;

/**
 * Unit tests for {@link ProjectSnapshot} rendering and structure (§31).
 */
public class ProjectSnapshotTest {

    @Test
    public void builder_defaults_are_safe() {
        ProjectSnapshot s = new ProjectSnapshot.Builder("p1").build();
        assertNotNull(s.modules());
        assertNotNull(s.sourceRoots());
        assertTrue(s.timestamp() > 0);
        assertEquals("p1", s.projectId());
    }

    @Test
    public void null_lists_become_empty_not_exception() {
        ProjectSnapshot s = new ProjectSnapshot.Builder("p1")
                .modules(null)
                .importantFiles(null)
                .build();
        assertTrue(s.modules().isEmpty());
        assertTrue(s.importantFiles().isEmpty());
    }

    @Test
    public void lists_are_unmodifiable() {
        ProjectSnapshot s = new ProjectSnapshot.Builder("p1")
                .modules(Arrays.asList("app", "core"))
                .build();
        try {
            s.modules().add("hack");
            fail("Expected UnsupportedOperationException");
        } catch (UnsupportedOperationException expected) {
            // ok
        }
    }

    @Test
    public void render_summary_includes_build_system_and_sections() {
        ProjectSnapshot s = new ProjectSnapshot.Builder("p1")
                .buildSystem("gradle")
                .sourceRoots(Arrays.asList("app/src/main"))
                .importantFiles(Arrays.asList("settings.gradle.kts"))
                .recentlyChangedFiles(Arrays.asList("LoginController.java"))
                .knownErrors(Arrays.asList("cannot find symbol Foo"))
                .build();
        String out = s.renderSummary(5);
        assertTrue(out.contains("## Project Snapshot"));
        assertTrue(out.contains("gradle"));
        assertTrue(out.contains("app/src/main"));
        assertTrue(out.contains("LoginController.java"));
        assertTrue(out.contains("cannot find symbol Foo"));
    }

    @Test
    public void render_summary_truncates_long_lists_with_count() {
        ProjectSnapshot s = new ProjectSnapshot.Builder("p1")
                .modules(Arrays.asList("m1", "m2", "m3", "m4", "m5", "m6", "m7"))
                .build();
        String out = s.renderSummary(3);
        assertTrue(out.contains("(+4 more)"));
        assertFalse(out.contains("m7"));
    }

    @Test
    public void empty_snapshot_renders_minimal_output() {
        ProjectSnapshot s = new ProjectSnapshot.Builder("p1").build();
        String out = s.renderSummary(5);
        assertTrue(out.contains("unknown")); // build system fallback
        assertFalse(out.contains("Modules:"));
    }
}
