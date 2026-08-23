package cn.lineai.model.harness;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Cached view of project structure (LCP-Harness v1 §31 Project Snapshot).
 *
 * <p>The snapshot is a <b>cache only</b> — the filesystem/workspace remains the source
 * of truth. Stale cache triggers an on-demand refresh; stale cache is never trusted.
 * No persistent filesystem watcher exists in v1.
 */
public final class ProjectSnapshot {

    private final String projectId;
    private final long timestamp;
    private final List<String> modules;
    private final List<String> sourceRoots;
    private final List<String> importantFiles;
    private final String buildSystem;                 // "gradle" | "maven" | ... (nullable)
    private final List<String> dependencies;
    private final List<String> recentlyChangedFiles;
    private final List<String> knownErrors;

    private ProjectSnapshot(Builder b) {
        this.projectId = b.projectId;
        this.timestamp = b.timestamp;
        this.modules = Collections.unmodifiableList(b.modules);
        this.sourceRoots = Collections.unmodifiableList(b.sourceRoots);
        this.importantFiles = Collections.unmodifiableList(b.importantFiles);
        this.buildSystem = b.buildSystem;
        this.dependencies = Collections.unmodifiableList(b.dependencies);
        this.recentlyChangedFiles = Collections.unmodifiableList(b.recentlyChangedFiles);
        this.knownErrors = Collections.unmodifiableList(b.knownErrors);
    }

    public String projectId() { return projectId; }
    public long timestamp() { return timestamp; }
    public List<String> modules() { return modules; }
    public List<String> sourceRoots() { return sourceRoots; }
    public List<String> importantFiles() { return importantFiles; }
    public String buildSystem() { return buildSystem; }
    public List<String> dependencies() { return dependencies; }
    public List<String> recentlyChangedFiles() { return recentlyChangedFiles; }
    public List<String> knownErrors() { return knownErrors; }

    /**
     * Render a compact summary suitable for prompt injection (P3 code-context region).
     * Kept deliberately small — details belong to on-demand reads, not the prompt.
     */
    public String renderSummary(int maxFilesPerList) {
        StringBuilder sb = new StringBuilder();
        sb.append("## Project Snapshot\n");
        sb.append("Build system: ").append(buildSystem != null ? buildSystem : "unknown").append('\n');
        appendList(sb, "Modules", modules, maxFilesPerList);
        appendList(sb, "Source roots", sourceRoots, maxFilesPerList);
        appendList(sb, "Key files", importantFiles, maxFilesPerList);
        appendList(sb, "Recently changed", recentlyChangedFiles, maxFilesPerList);
        appendList(sb, "Known errors", knownErrors, maxFilesPerList);
        return sb.toString();
    }

    private static void appendList(StringBuilder sb, String label, List<String> list, int max) {
        if (list.isEmpty()) return;
        sb.append(label).append(':');
        int shown = Math.min(list.size(), max);
        for (int i = 0; i < shown; i++) {
            sb.append(' ').append(list.get(i));
            if (i < shown - 1) sb.append(',');
        }
        if (list.size() > shown) sb.append(" (+").append(list.size() - shown).append(" more)");
        sb.append('\n');
    }

    /** Builder. */
    public static final class Builder {
        private final String projectId;
        private long timestamp = System.currentTimeMillis();
        private List<String> modules = new ArrayList<>();
        private List<String> sourceRoots = new ArrayList<>();
        private List<String> importantFiles = new ArrayList<>();
        private String buildSystem;
        private List<String> dependencies = new ArrayList<>();
        private List<String> recentlyChangedFiles = new ArrayList<>();
        private List<String> knownErrors = new ArrayList<>();

        public Builder(String projectId) {
            this.projectId = projectId;
        }

        public Builder timestamp(long t) { this.timestamp = t; return this; }
        public Builder modules(List<String> l) { this.modules = l != null ? l : modules; return this; }
        public Builder sourceRoots(List<String> l) { this.sourceRoots = l != null ? l : sourceRoots; return this; }
        public Builder importantFiles(List<String> l) { this.importantFiles = l != null ? l : importantFiles; return this; }
        public Builder buildSystem(String s) { this.buildSystem = s; return this; }
        public Builder dependencies(List<String> l) { this.dependencies = l != null ? l : dependencies; return this; }
        public Builder recentlyChangedFiles(List<String> l) { this.recentlyChangedFiles = l != null ? l : recentlyChangedFiles; return this; }
        public Builder knownErrors(List<String> l) { this.knownErrors = l != null ? l : knownErrors; return this; }

        public ProjectSnapshot build() {
            return new ProjectSnapshot(this);
        }
    }

    /** Suppress unused warning for potential future JSON persistence. */
    static String optString(JSONObject o, String key) {
        try {
            return o.has(key) ? o.getString(key) : null;
        } catch (JSONException e) {
            return null;
        }
    }
}
