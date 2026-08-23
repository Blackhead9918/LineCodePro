package cn.lineai.ai.harness;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Diff-as-evidence scope check (LCP-Harness v1 §14, §13 rule 3).
 *
 * <p>The diff scope check is the <b>only E2-class deterministic check that is always
 * available on-device</b> (no toolchain needed) — it is the backbone of LOCAL verification.
 *
 * <p>Checks performed on the set of changed file paths:
 * <ul>
 *   <li><b>intended files?</b> — all changed files fall within the task scope</li>
 *   <li><b>unexpected files?</b> — out-of-scope changes are listed explicitly</li>
 *   <li><b>suspicious deletion?</b> — mass deletions are flagged</li>
 *   <li><b>dependency modification?</b> — build file changes are flagged separately</li>
 * </ul>
 *
 * <p>Thread-safety: stateless — safe for concurrent use.
 */
public final class DiffScopeChecker {

    /** Ratio of deleted-to-total files above which the change is flagged as suspicious. */
    public static final double SUSPICIOUS_DELETION_RATIO = 0.8;

    /** Minimum absolute deletion count before the ratio check applies (avoids 1/1 flags). */
    public static final int SUSPICIOUS_DELETION_MIN_COUNT = 5;

    private static final List<String> BUILD_FILE_MARKERS = Arrays.asList(
            "build.gradle", "build.gradle.kts", "settings.gradle", "settings.gradle.kts",
            "gradle.properties", "gradle/libs.versions.toml",
            "pom.xml", "package.json", "Cargo.toml", "go.mod");

    private DiffScopeChecker() {} // utility class

    /**
     * Check a set of changed files against the task scope.
     *
     * @param taskScope    task scope string from the capsule (nullable — null means unrestricted)
     * @param changedFiles list of changed file paths (relative or absolute)
     * @param deletedFiles subset of {@code changedFiles} that were deletions
     * @return never-null result
     */
    public static ScopeCheckResult check(String taskScope, List<String> changedFiles,
                                         List<String> deletedFiles) {
        if (changedFiles == null) changedFiles = Collections.emptyList();
        if (deletedFiles == null) deletedFiles = Collections.emptyList();

        String normalizedScope = normalize(taskScope);

        List<String> inScope = new ArrayList<>();
        List<String> outOfScope = new ArrayList<>();

        for (String path : changedFiles) {
            String p = normalize(path);
            if (normalizedScope == null || p.startsWith(normalizedScope)) {
                inScope.add(path);
            } else {
                outOfScope.add(path);
            }
        }

        boolean suspiciousDeletion = isSuspiciousDeletion(changedFiles, deletedFiles);
        List<String> dependencyChanges = findDependencyChanges(changedFiles);

        return new ScopeCheckResult(inScope, outOfScope, suspiciousDeletion, dependencyChanges);
    }

    private static boolean isSuspiciousDeletion(List<String> changedFiles, List<String> deletedFiles) {
        int total = changedFiles.size();
        int deleted = deletedFiles.size();
        if (deleted < SUSPICIOUS_DELETION_MIN_COUNT || total == 0) return false;
        return ((double) deleted / total) >= SUSPICIOUS_DELETION_RATIO;
    }

    private static List<String> findDependencyChanges(List<String> changedFiles) {
        List<String> hits = new ArrayList<>();
        for (String path : changedFiles) {
            String name = basename(path).toLowerCase(Locale.US);
            for (String marker : BUILD_FILE_MARKERS) {
                if (name.equals(marker)) { // exact filename match only
                    hits.add(path);
                    break;
                }
            }
        }
        return hits;
    }

    private static String normalize(String path) {
        if (path == null) return "";
        String p = path.trim().replace('\\', '/');
        while (p.startsWith("/")) p = p.substring(1);
        while (p.endsWith("/") && p.length() > 0) p = p.substring(0, p.length() - 1);
        return p.toLowerCase(Locale.US);
    }

    private static String basename(String path) {
        if (path == null) return "";
        int idx = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
        return idx >= 0 ? path.substring(idx + 1) : path;
    }

    /** Result of a diff scope check. Immutable value object. */
    public static final class ScopeCheckResult {

        private final List<String> inScope;
        private final List<String> outOfScope;
        private final boolean suspiciousDeletion;
        private final List<String> dependencyChanges;

        ScopeCheckResult(List<String> inScope, List<String> outOfScope,
                         boolean suspiciousDeletion, List<String> dependencyChanges) {
            this.inScope = Collections.unmodifiableList(inScope);
            this.outOfScope = Collections.unmodifiableList(outOfScope);
            this.suspiciousDeletion = suspiciousDeletion;
            this.dependencyChanges = Collections.unmodifiableList(dependencyChanges);
        }

        public List<String> inScope() { return inScope; }
        public List<String> outOfScope() { return outOfScope; }
        public boolean hasSuspiciousDeletion() { return suspiciousDeletion; }
        public List<String> dependencyChanges() { return dependencyChanges; }

        /** True if all changed files respect the task scope and nothing looks dangerous. */
        public boolean passed() {
            return outOfScope.isEmpty()
                    && !suspiciousDeletion
                    && dependencyChanges.isEmpty();
        }

        /** True when at least one real change was recorded inside scope. */
        public boolean hasInScopeChanges() {
            return !inScope.isEmpty();
        }

        /** Compact human-readable summary for evidence storage (≤ ~200 chars). */
        public String summary() {
            StringBuilder sb = new StringBuilder();
            sb.append("scope-check: ").append(passed() ? "PASS" : "FLAG");
            sb.append(", in=").append(inScope.size());
            sb.append(", out=").append(outOfScope.size());
            if (suspiciousDeletion) sb.append(", mass-deletion");
            if (!dependencyChanges.isEmpty()) sb.append(", deps-modified=").append(dependencyChanges.size());
            if (!outOfScope.isEmpty()) {
                sb.append(", e.g.").append(basename(outOfScope.get(0)));
            }
            return sb.toString();
        }
    }
}
