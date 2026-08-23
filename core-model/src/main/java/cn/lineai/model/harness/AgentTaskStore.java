package cn.lineai.model.harness;

import java.util.List;

/**
 * Persistence contract for agent task state (LCP-Harness v1, DIP convention).
 *
 * <p>Implemented by {@code AgentTaskRepository} (:data, SQLite) in production and by
 * in-memory fakes in unit tests. Harness components ({@code TaskController},
 * {@code EvidenceRecorder}) depend on this interface only.
 */
public interface AgentTaskStore {

    /** Insert a new task row. */
    void put(AgentTask task);

    /** Update mutable fields of an existing task. No-op if the id is unknown. */
    void update(AgentTask task);

    /** Load a task by ID; null when absent. */
    AgentTask getById(String taskId);

    /** Newest non-terminal task for a conversation; null when none. */
    AgentTask getActiveForConversation(String conversationId);

    /** All tasks of a conversation ordered by creation time. */
    List<AgentTask> getAllForConversation(String conversationId);

    /** Delete tasks + evidence + events of a conversation. */
    void deleteForConversation(String conversationId);

    /**
     * Mark stale non-terminal tasks INTERRUPTED (crash reconciliation §45.8).
     * Returns count reconciled. Must never throw.
     */
    int reconcileStaleTasks();

    /** Insert an evidence summary row (§35.2). */
    void insertEvidence(String id, String taskId, String type, int level,
                        String source, String claim, String summary,
                        String refTable, String refId, double strength);

    /** Evidence rows of a task, strongest first. */
    List<AgentEvidence> getEvidenceForTask(String taskId);

    /** Insert a task-level lifecycle event (§35.3). */
    void insertEvent(String id, String taskId, String eventType,
                     String source, String payload);
}
