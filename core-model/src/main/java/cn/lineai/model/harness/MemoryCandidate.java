package cn.lineai.model.harness;

import java.util.Locale;

/**
 * A learning candidate extracted from a finished task (LCP-Harness v1 §18, P3).
 *
 * <p>Learning ≠ model self-modification: a candidate is a proposed memory that must be
 * evidence-backed before commit. In v1 the existing confidence gates
 * ({@code MemoryExtractionService.MIN_KEEP_CONFIDENCE} / {@code RULE_CONFIDENCE})
 * decide commit; full CANDIDATE → SUPPORTED → VERIFIED lifecycle is v2 (D07).
 */
public final class MemoryCandidate {

    /** Universal memory classes (§16). */
    public enum MemoryClass {
        USER_PREFERENCE,
        PROJECT_FACT,
        PROJECT_CONVENTION,
        WORKFLOW,
        FAILURE_PATTERN,
        TOOL_KNOWLEDGE,
        ENVIRONMENT_FACT,
        AGENT_SKILL;

        public static MemoryClass fromString(String value) {
            if (value == null) return WORKFLOW;
            try {
                return valueOf(value.trim().toUpperCase(Locale.US));
            } catch (IllegalArgumentException e) {
                return WORKFLOW;
            }
        }
    }

    /** Where the candidate came from (§16 sources). */
    public enum Source {
        VERIFIED_TASK_RESULT,
        USER_CORRECTION,
        FAILURE_PATTERN,
        MEMORY_EXTRACTION
    }

    private final String id;
    private final MemoryClass memoryClass;
    private final Source source;
    private final String content;
    private final String scopeHint;          // "user" | "project:<id>" | "" 
    private final double confidence;
    private final String supportingEvidence; // compact evidence summary
    private final long createdAt;

    public MemoryCandidate(String id, MemoryClass memoryClass, Source source,
                           String content, String scopeHint, double confidence,
                           String supportingEvidence, long createdAt) {
        this.id = id;
        this.memoryClass = memoryClass;
        this.source = source;
        this.content = content;
        this.scopeHint = scopeHint != null ? scopeHint : "";
        this.confidence = confidence;
        this.supportingEvidence = supportingEvidence != null ? supportingEvidence : "";
        this.createdAt = createdAt;
    }

    public String id() { return id; }
    public MemoryClass memoryClass() { return memoryClass; }
    public Source source() { return source; }
    public String content() { return content; }
    public String scopeHint() { return scopeHint; }
    public double confidence() { return confidence; }
    public String supportingEvidence() { return supportingEvidence; }
    public long createdAt() { return createdAt; }

    @Override
    public String toString() {
        return "MemoryCandidate{" + memoryClass + ", " + source +
                ", conf=" + confidence + ", '" + preview(content, 40) + "'}";
    }

    private static String preview(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }
}
