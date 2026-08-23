package cn.lineai.model.harness;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * Typed DTO for one row of the {@code agent_evidence} table (LCP-Harness v1 §35.2, D05).
 *
 * <p>Evidence stores a <em>summary + reference</em> to the original source row
 * ({@code tool_results}, {@code diff_records}, {@code messages}), never a full copy.
 */
public final class AgentEvidence {

    /** Evidence row types (§13 CLAIM/OBSERVATION/EVIDENCE/VERDICT distinction). */
    public enum Type {
        CLAIM,
        OBSERVATION,
        EVIDENCE,
        VERDICT;

        public static Type fromString(String value) {
            if (value == null) return OBSERVATION;
            try {
                return valueOf(value.trim().toUpperCase());
            } catch (IllegalArgumentException e) {
                return OBSERVATION;
            }
        }
    }

    /** Maximum characters for summary field (§45.12). */
    public static final int MAX_SUMMARY_CHARS = 2048;

    private final String id;
    private final String taskId;
    private final Type type;
    private final EvidenceLevel level;
    private final String source;
    private final String claim;
    private final String summary;
    private final String refTable;
    private final String refId;
    private final double strength;
    private final boolean verified;
    private final long createdAt;

    public AgentEvidence(String id, String taskId, Type type, EvidenceLevel level,
                         String source, String claim, String summary,
                         String refTable, String refId, double strength,
                         boolean verified, long createdAt) {
        this.id = id;
        this.taskId = taskId;
        this.type = type;
        this.level = level;
        this.source = source;
        this.claim = claim;
        this.summary = truncate(summary, MAX_SUMMARY_CHARS);
        this.refTable = refTable;
        this.refId = refId;
        this.strength = strength;
        this.verified = verified;
        this.createdAt = createdAt;
    }

    public String id() { return id; }
    public String taskId() { return taskId; }
    public Type type() { return type; }
    public EvidenceLevel level() { return level; }
    public String source() { return source; }
    public String claim() { return claim; }
    public String summary() { return summary; }
    public String refTable() { return refTable; }
    public String refId() { return refId; }
    public double strength() { return strength; }
    public boolean verified() { return verified; }
    public long createdAt() { return createdAt; }

    /** Serialize summary metadata as compact JSON for debugging / UI display. */
    public String toJson() {
        try {
            JSONObject o = new JSONObject();
            o.put("id", id);
            o.put("task_id", taskId);
            o.put("type", type.name());
            o.put("level", level.level());
            o.put("source", source);
            if (summary != null) o.put("summary", summary);
            if (refTable != null) o.put("ref_table", refTable);
            if (refId != null) o.put("ref_id", refId);
            o.put("strength", strength);
            o.put("verified", verified);
            return o.toString();
        } catch (JSONException e) {
            return "{}";
        }
    }

    @Override
    public String toString() {
        return "AgentEvidence{" + type + ", E" + level.level() +
                ", source=" + source +
                (summary != null && summary.length() > 40
                        ? ", '" + summary.substring(0, 40) + "...'" : "") + "}";
    }

    private static String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max);
    }
}
