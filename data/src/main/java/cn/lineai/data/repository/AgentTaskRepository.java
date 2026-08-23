package cn.lineai.data.repository;

import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import cn.lineai.model.harness.AgentEvidence;
import cn.lineai.model.harness.AgentTask;
import cn.lineai.model.harness.AgentTaskStore;
import cn.lineai.model.harness.ExecutionProfile;
import cn.lineai.model.harness.TaskBudget;
import cn.lineai.model.harness.TaskMode;
import cn.lineai.model.harness.TaskRiskLevel;
import cn.lineai.model.harness.TaskStatus;
import cn.lineai.model.harness.TaskVerificationPolicy;
import cn.lineai.model.harness.TaskVerdict;

/**
 * Repository for agent task persistence (LCP-Harness v1 §35, §45.1).
 *
 * <p>Single point of access for {@code agent_tasks}, {@code agent_evidence}, and
 * {@code agent_events} tables. All other code must go through this class.
 *
 * <p>Reconciliation: {@link #reconcileStaleTasks()} marks non-terminal tasks as
 * INTERRUPTED when they exceed the stale threshold (§45.8, D04).
 *
 * <p>Text length limits enforced per §45.12.
 */
public final class AgentTaskRepository implements AgentTaskStore {

    private static final long STALE_THRESHOLD_MS = 5 * 60 * 1000; // 5 minutes

    private final SQLiteOpenHelper dbHelper;

    public AgentTaskRepository(SQLiteOpenHelper dbHelper) {
        this.dbHelper = dbHelper;
    }

    // ---- Task CRUD ----

    /**
     * Insert a new task.
     * Enforces text length limits (§45.12).
     */
    @Override
    public void put(AgentTask task) {
        SQLiteDatabase db = dbHelper.getWritableDatabase();
        ContentValues cv = new ContentValues();
        cv.put("id", task.id());
        cv.put("conversation_id", task.conversationId());
        cv.put("project_id", task.projectId());
        cv.put("parent_task_id", task.parentTaskId());
        cv.put("mode", task.mode().name());
        cv.put("goal", limit(task.goal(), AgentTask.MAX_GOAL_CHARS));
        cv.put("scope", limit(task.scope(), AgentTask.MAX_SCOPE_CHARS));
        cv.put("constraints", limit(task.constraints(), AgentTask.MAX_CONSTRAINTS_CHARS));
        cv.put("status", task.status().name());
        cv.put("risk_level", task.riskLevel().name());
        cv.put("execution_profile", task.executionProfile().name());
        cv.put("verification_policy", task.verificationPolicy().name());
        cv.put("completion_condition", limit(task.completionCondition(), AgentTask.MAX_COMPLETION_CHARS));
        cv.put("failure_condition", limit(task.failureCondition(), AgentTask.MAX_COMPLETION_CHARS));
        String budgetJson = task.budget().toJson();
        cv.put("budget_json", limit(budgetJson, AgentTask.MAX_BUDGET_JSON_CHARS));
        cv.put("verdict", task.verdict() != null ? task.verdict().name() : null);
        cv.put("attempt_count", task.attemptCount());
        cv.put("max_attempts", task.maxAttempts());
        cv.put("created_at", task.createdAt());
        cv.put("updated_at", task.updatedAt());
        cv.put("completed_at", task.completedAt());
        db.insert("agent_tasks", null, cv);
    }

    /**
     * Update mutable fields of an existing task.
     */
    @Override
    public void update(AgentTask task) {
        SQLiteDatabase db = dbHelper.getWritableDatabase();
        ContentValues cv = new ContentValues();
        cv.put("status", task.status().name());
        cv.put("mode", task.mode().name());
        cv.put("verdict", task.verdict() != null ? task.verdict().name() : null);
        cv.put("attempt_count", task.attemptCount());
        cv.put("updated_at", System.currentTimeMillis());
        if (task.completedAt() > 0) {
            cv.put("completed_at", task.completedAt());
        }
        db.update("agent_tasks", cv, "id = ?", new String[]{task.id()});
    }

    /**
     * Load a task by ID. Returns null if not found.
     */
    @Override
    public AgentTask getById(String taskId) {
        SQLiteDatabase db = dbHelper.getReadableDatabase();
        Cursor c = db.query("agent_tasks", null, "id = ?", new String[]{taskId},
                null, null, null);
        try {
            if (c.moveToFirst()) {
                return fromCursor(c);
            }
            return null;
        } finally {
            c.close();
        }
    }

    /**
     * Load the active (non-terminal) task for a conversation. Returns null if none.
     * Enforces single active task per conversation (§45.11, M3).
     */
    @Override
    public AgentTask getActiveForConversation(String conversationId) {
        SQLiteDatabase db = dbHelper.getReadableDatabase();
        Cursor c = db.rawQuery(
                "SELECT * FROM agent_tasks "
                + "WHERE conversation_id = ? AND status NOT IN ('COMPLETED','FAILED','CANCELLED') "
                + "ORDER BY created_at DESC LIMIT 1",
                new String[]{conversationId});
        try {
            if (c.moveToFirst()) {
                return fromCursor(c);
            }
            return null;
        } finally {
            c.close();
        }
    }

    /**
     * Load all tasks for a conversation (any status), ordered by creation time.
     */
    @Override
    public List<AgentTask> getAllForConversation(String conversationId) {
        SQLiteDatabase db = dbHelper.getReadableDatabase();
        Cursor c = db.query("agent_tasks", null, "conversation_id = ?",
                new String[]{conversationId}, null, null, "created_at ASC");
        try {
            List<AgentTask> tasks = new ArrayList<>();
            while (c.moveToNext()) {
                tasks.add(fromCursor(c));
            }
            return tasks;
        } finally {
            c.close();
        }
    }

    /**
     * Delete all tasks (and their evidence/events) for a conversation.
     * Called from deleteConversation() for cleanup (§45.10, M2).
     */
    @Override
    public void deleteForConversation(String conversationId) {
        SQLiteDatabase db = dbHelper.getWritableDatabase();
        // Delete events for this conversation's tasks
        db.execSQL("DELETE FROM agent_events WHERE task_id IN "
                + "(SELECT id FROM agent_tasks WHERE conversation_id = ?)",
                new Object[]{conversationId});
        // Delete evidence for this conversation's tasks
        db.execSQL("DELETE FROM agent_evidence WHERE task_id IN "
                + "(SELECT id FROM agent_tasks WHERE conversation_id = ?)",
                new Object[]{conversationId});
        // Delete tasks
        db.delete("agent_tasks", "conversation_id = ?", new String[]{conversationId});
    }

    // ---- Reconciliation (§45.8, D04) ----

    /**
     * Mark stale non-terminal tasks as INTERRUPTED. Called on app startup.
     * Runs asynchronously (§45.8). Exception → catch + log, never crash.
     *
     * @return number of tasks reconciled
     */
    @Override
    public int reconcileStaleTasks() {
        try {
            SQLiteDatabase db = dbHelper.getWritableDatabase();
            long threshold = System.currentTimeMillis() - STALE_THRESHOLD_MS;
            ContentValues cv = new ContentValues();
            cv.put("status", TaskStatus.INTERRUPTED.name());
            cv.put("updated_at", System.currentTimeMillis());
            return db.update("agent_tasks", cv,
                    "status NOT IN ('COMPLETED','FAILED','CANCELLED') AND updated_at < ?",
                    new String[]{String.valueOf(threshold)});
        } catch (Exception e) {
            // §45.8: catch + log, never crash
            android.util.Log.e("AgentTaskRepo", "Reconciliation failed", e);
            return 0;
        }
    }

    // ---- Evidence CRUD ----

    /**
     * Insert an evidence record. References tool_results/diff_records by soft FK (§45.10).
     */
    @Override
    public void insertEvidence(String id, String taskId, String type, int level,
                               String source, String claim, String summary,
                               String refTable, String refId, double strength) {
        SQLiteDatabase db = dbHelper.getWritableDatabase();
        ContentValues cv = new ContentValues();
        cv.put("id", id);
        cv.put("task_id", taskId);
        cv.put("type", type);
        cv.put("level", level);
        cv.put("source", source);
        cv.put("claim", claim);
        cv.put("summary", limit(summary, 2048)); // §45.12
        cv.put("ref_table", refTable);
        cv.put("ref_id", refId);
        cv.put("strength", strength);
        cv.put("verified", 0);
        cv.put("created_at", System.currentTimeMillis());
        db.insert("agent_evidence", null, cv);
    }

    /**
     * Load evidence for a task, strongest first (level DESC, then time DESC).
     */
    @Override
    public List<AgentEvidence> getEvidenceForTask(String taskId) {
        SQLiteDatabase db = dbHelper.getReadableDatabase();
        Cursor c = db.query("agent_evidence", null, "task_id = ?",
                new String[]{taskId}, null, null, "level DESC, created_at DESC");
        try {
            List<AgentEvidence> results = new ArrayList<>();
            while (c.moveToNext()) {
                results.add(evidenceFromCursor(c));
            }
            return results;
        } finally {
            c.close();
        }
    }

    private AgentEvidence evidenceFromCursor(Cursor c) {
        return new AgentEvidence(
                c.getString(c.getColumnIndexOrThrow("id")),
                c.getString(c.getColumnIndexOrThrow("task_id")),
                AgentEvidence.Type.fromString(c.getString(c.getColumnIndexOrThrow("type"))),
                cn.lineai.model.harness.EvidenceLevel.fromLevel(
                        c.getInt(c.getColumnIndexOrThrow("level"))),
                c.getString(c.getColumnIndexOrThrow("source")),
                c.getString(c.getColumnIndexOrThrow("claim")),
                c.getString(c.getColumnIndexOrThrow("summary")),
                c.getString(c.getColumnIndexOrThrow("ref_table")),
                c.getString(c.getColumnIndexOrThrow("ref_id")),
                c.getDouble(c.getColumnIndexOrThrow("strength")),
                c.getInt(c.getColumnIndexOrThrow("verified")) == 1,
                c.getLong(c.getColumnIndexOrThrow("created_at")));
    }

    // ---- Events ----

    /**
     * Insert a task lifecycle event.
     * Events are task-level only (§35.3, D05) — no tool-level events.
     */
    @Override
    public void insertEvent(String id, String taskId, String eventType,
                            String source, String payload) {
        SQLiteDatabase db = dbHelper.getWritableDatabase();
        ContentValues cv = new ContentValues();
        cv.put("id", id);
        cv.put("task_id", taskId);
        cv.put("event_type", eventType);
        cv.put("source", source);
        cv.put("payload", limit(payload, 2048)); // §45.12
        cv.put("created_at", System.currentTimeMillis());
        db.insert("agent_events", null, cv);
    }

    /**
     * Prune old events (§45.20): keep max 500 per task, delete events older than 7 days.
     */
    public void pruneEvents(String taskId) {
        SQLiteDatabase db = dbHelper.getWritableDatabase();
        long cutoff = System.currentTimeMillis() - (7L * 24 * 60 * 60 * 1000);
        // Delete old events
        db.delete("agent_events",
                "task_id = ? AND created_at < ?",
                new String[]{taskId, String.valueOf(cutoff)});
        // Keep only latest 500
        db.execSQL("DELETE FROM agent_events WHERE id IN "
                + "(SELECT id FROM agent_events WHERE task_id = ? "
                + "ORDER BY created_at DESC LIMIT -1 OFFSET 500)",
                new Object[]{taskId});
    }

    // ---- Helpers ----

    private AgentTask fromCursor(Cursor c) {
        String budgetJson = c.getString(c.getColumnIndexOrThrow("budget_json"));
        TaskBudget budget = TaskBudget.parse(budgetJson);

        return new AgentTask.Builder(
                c.getString(c.getColumnIndexOrThrow("id")),
                c.getString(c.getColumnIndexOrThrow("conversation_id")),
                c.getString(c.getColumnIndexOrThrow("goal")))
                .projectId(c.getString(c.getColumnIndexOrThrow("project_id")))
                .parentTaskId(c.getString(c.getColumnIndexOrThrow("parent_task_id")))
                .mode(TaskMode.fromString(c.getString(c.getColumnIndexOrThrow("mode"))))
                .scope(c.getString(c.getColumnIndexOrThrow("scope")))
                .constraints(c.getString(c.getColumnIndexOrThrow("constraints")))
                .status(TaskStatus.fromString(c.getString(c.getColumnIndexOrThrow("status"))))
                .riskLevel(TaskRiskLevel.fromString(c.getString(c.getColumnIndexOrThrow("risk_level"))))
                .executionProfile(ExecutionProfile.fromString(c.getString(c.getColumnIndexOrThrow("execution_profile"))))
                .verificationPolicy(TaskVerificationPolicy.fromString(c.getString(c.getColumnIndexOrThrow("verification_policy"))))
                .completionCondition(c.getString(c.getColumnIndexOrThrow("completion_condition")))
                .failureCondition(c.getString(c.getColumnIndexOrThrow("failure_condition")))
                .budget(budget)
                .verdict(TaskVerdict.fromString(c.getString(c.getColumnIndexOrThrow("verdict"))))
                .attemptCount(c.getInt(c.getColumnIndexOrThrow("attempt_count")))
                .maxAttempts(c.getInt(c.getColumnIndexOrThrow("max_attempts")))
                .createdAt(c.getLong(c.getColumnIndexOrThrow("created_at")))
                .updatedAt(c.getLong(c.getColumnIndexOrThrow("updated_at")))
                .completedAt(c.getLong(c.getColumnIndexOrThrow("completed_at")))
                .build();
    }

    private static String limit(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max);
    }
}
