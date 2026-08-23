package cn.lineai.ai.harness;

import static org.junit.Assert.*;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.junit.Test;

/**
 * Unit tests for {@link DiffScopeChecker} (§14 diff-as-evidence, §13 rule 3).
 */
public class DiffScopeCheckerTest {

    private static final String SCOPE = "app/src/main/java/cn/lineai/login/";

    // ---- In-scope changes pass ----

    @Test
    public void in_scope_changes_pass() {
        DiffScopeChecker.ScopeCheckResult r = DiffScopeChecker.check(SCOPE,
                Collections.singletonList("app/src/main/java/cn/lineai/login/LoginController.java"),
                Collections.emptyList());
        assertTrue(r.passed());
        assertTrue(r.hasInScopeChanges());
        assertTrue(r.outOfScope().isEmpty());
    }

    @Test
    public void out_of_scope_changes_flagged() {
        DiffScopeChecker.ScopeCheckResult r = DiffScopeChecker.check(SCOPE,
                Arrays.asList(
                        "app/src/main/java/cn/lineai/login/LoginController.java",
                        "core-model/src/main/java/cn/lineai/model/Other.java"),
                Collections.emptyList());
        assertFalse(r.passed());
        assertEquals(1, r.outOfScope().size());
        assertTrue(r.outOfScope().get(0).contains("Other.java"));
    }

    @Test
    public void scope_prefix_matching_is_case_insensitive_and_slash_normalized() {
        DiffScopeChecker.ScopeCheckResult r = DiffScopeChecker.check(
                "/App/Src/Main/Java/Cn/Lineai/Login/",
                Collections.singletonList("app\\src\\main\\java\\cn\\lineai\\login\\Auth.java"),
                Collections.emptyList());
        assertTrue(r.passed());
    }

    // ---- Null/unrestricted scope allows everything ----

    @Test
    public void null_scope_allows_all_files() {
        DiffScopeChecker.ScopeCheckResult r = DiffScopeChecker.check(null,
                Collections.singletonList("anywhere/file.txt"),
                Collections.emptyList());
        assertTrue(r.passed());
        assertTrue(r.hasInScopeChanges());
    }

    // ---- Suspicious deletion detection ----

    @Test
    public void mass_deletion_flagged() {
        // 6 of 6 files deleted → ratio 1.0 ≥ 0.8 and count ≥ 5
        List<String> files = Arrays.asList("a", "b", "c", "d", "e", "f");
        DiffScopeChecker.ScopeCheckResult r = DiffScopeChecker.check(null, files, files);
        assertTrue(r.hasSuspiciousDeletion());
        assertFalse(r.passed());
    }

    @Test
    public void small_deletions_not_flagged() {
        // 2 of 10 deleted → below min count
        List<String> files = Arrays.asList(
                "a", "b", "c", "d", "e", "f", "g", "h", "i", "j");
        DiffScopeChecker.ScopeCheckResult r = DiffScopeChecker.check(
                null, files, Arrays.asList("a", "b"));
        assertFalse(r.hasSuspiciousDeletion());
    }

    @Test
    public void single_deletion_of_single_file_not_flagged() {
        // avoids 1/1 = 100% false positive via MIN_COUNT guard
        DiffScopeChecker.ScopeCheckResult r = DiffScopeChecker.check(null,
                Collections.singletonList("a"), Collections.singletonList("a"));
        assertFalse(r.hasSuspiciousDeletion());
    }

    // ---- Dependency modification detection ----

    @Test
    public void build_gradle_change_flagged_as_dependency_modification() {
        DiffScopeChecker.ScopeCheckResult r = DiffScopeChecker.check(null,
                Arrays.asList(
                        "app/build.gradle.kts",
                        "app/src/main/java/cn/lineai/Main.java"),
                Collections.emptyList());
        assertEquals(1, r.dependencyChanges().size());
        assertTrue(r.dependencyChanges().get(0).endsWith("build.gradle.kts"));
        assertFalse(r.passed());
    }

    @Test
    public void package_json_change_flagged() {
        DiffScopeChecker.ScopeCheckResult r = DiffScopeChecker.check(null,
                Collections.singletonList("web/package.json"), Collections.emptyList());
        assertEquals(1, r.dependencyChanges().size());
    }

    @Test
    public void file_named_like_marker_in_subpath_not_exact_match_not_flagged() {
        // "my-build.gradle.bak" is not an exact filename match
        DiffScopeChecker.ScopeCheckResult r = DiffScopeChecker.check(null,
                Collections.singletonList("tools/my-build.gradle.bak"),
                Collections.emptyList());
        assertTrue(r.dependencyChanges().isEmpty());
    }

    // ---- Empty inputs ----

    @Test
    public void empty_changed_files_yields_pass_with_no_changes() {
        DiffScopeChecker.ScopeCheckResult r = DiffScopeChecker.check(SCOPE,
                Collections.emptyList(), Collections.emptyList());
        assertTrue(r.passed());
        assertFalse(r.hasInScopeChanges()); // pass but nothing to show for it
    }

    @Test
    public void null_inputs_are_safe() {
        DiffScopeChecker.ScopeCheckResult r = DiffScopeChecker.check(SCOPE, null, null);
        assertNotNull(r);
        assertTrue(r.passed());
    }

    // ---- Summary format ----

    @Test
    public void summary_shows_pass_for_clean_check() {
        DiffScopeChecker.ScopeCheckResult r = DiffScopeChecker.check(SCOPE,
                Collections.singletonList(SCOPE + "Login.java"), Collections.emptyList());
        String s = r.summary();
        assertTrue(s.contains("PASS"));
        assertTrue(s.contains("in=1"));
    }

    @Test
    public void summary_shows_flag_with_example_for_out_of_scope() {
        DiffScopeChecker.ScopeCheckResult r = DiffScopeChecker.check(SCOPE,
                Collections.singletonList("other/File.java"), Collections.emptyList());
        String s = r.summary();
        assertTrue(s.contains("FLAG"));
        assertTrue(s.contains("out=1"));
    }
}
