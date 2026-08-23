package cn.lineai.model.harness;

import java.util.Locale;

/**
 * A single item considered for inclusion in the model context
 * (LCP-Harness v1 §7).
 *
 * <p>Carries freshness metadata so the harness can decide
 * CACHE_HIT / CACHE_STALE / REFRESH_REQUIRED without a filesystem watcher.
 *
 * <p>Token estimate uses the same convention as {@code ContextManager}:
 * {@code CHARS_PER_TOKEN = 4}.
 */
public final class ContextItem {

    /** Characters per token estimate — mirrors ContextManager convention (§6.2). */
    public static final int CHARS_PER_TOKEN = 4;

    private final String id;
    private final String source;          // e.g. "file", "tool_result", "capsule", "memory"
    private final String scope;           // e.g. file path or topic key (nullable)
    private final ContextPriority priority;
    private final long createdAt;
    private final long updatedAt;
    private final long lastVerifiedAt;    // when the harness last confirmed freshness (0 = never)
    private final String content;
    private final boolean immutable;      // e.g. system prompt fragments never go stale

    public ContextItem(String id, String source, String scope, ContextPriority priority,
                       long createdAt, long updatedAt, long lastVerifiedAt,
                       String content, boolean immutable) {
        this.id = id;
        this.source = source;
        this.scope = scope;
        this.priority = priority != null ? priority : ContextPriority.P3_CODE_CONTEXT;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
        this.lastVerifiedAt = lastVerifiedAt;
        this.content = content != null ? content : "";
        this.immutable = immutable;
    }

    public String id() { return id; }
    public String source() { return source; }
    public String scope() { return scope; }
    public ContextPriority priority() { return priority; }
    public long createdAt() { return createdAt; }
    public long updatedAt() { return updatedAt; }
    public long lastVerifiedAt() { return lastVerifiedAt; }
    public String content() { return content; }
    public boolean isImmutable() { return immutable; }

    /** Estimated token cost of this item. */
    public int estimatedTokens() {
        return content.length() / CHARS_PER_TOKEN;
    }

    @Override
    public String toString() {
        return String.format(Locale.US, "ContextItem{%s, %s, %s, ~%dt}",
                id, priority.name(), source, estimatedTokens());
    }

    /** Builder. */
    public static final class Builder {
        private final String id;
        private final String content;
        private String source = "unknown";
        private String scope;
        private ContextPriority priority = ContextPriority.P3_CODE_CONTEXT;
        private long createdAt = System.currentTimeMillis();
        private long updatedAt = createdAt;
        private long lastVerifiedAt = 0;
        private boolean immutable = false;

        public Builder(String id, String content) {
            this.id = id;
            this.content = content;
        }

        public Builder source(String s) { this.source = s; return this; }
        public Builder scope(String s) { this.scope = s; return this; }
        public Builder priority(ContextPriority p) { this.priority = p; return this; }
        public Builder createdAt(long t) { this.createdAt = t; return this; }
        public Builder updatedAt(long t) { this.updatedAt = t; return this; }
        public Builder lastVerifiedAt(long t) { this.lastVerifiedAt = t; return this; }
        public Builder immutable(boolean b) { this.immutable = b; return this; }

        public ContextItem build() {
            if (id == null || id.isEmpty()) throw new IllegalArgumentException("id required");
            return new ContextItem(id, source, scope, priority,
                    createdAt, updatedAt, lastVerifiedAt, content, immutable);
        }
    }
}
