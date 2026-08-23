package cn.lineai.mvp.harness;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import cn.lineai.model.harness.AgentEvidence;
import cn.lineai.model.harness.AgentTask;
import cn.lineai.model.harness.AgentTaskStore;

/** Simple thread-safe in-memory {@link AgentTaskStore} for unit tests. */
public final class InMemoryAgentTaskStore implements AgentTaskStore {

    public final Map<String, AgentTask> tasks = new LinkedHashMap<>();
    public final List<AgentEvidence> evidenceRows = new ArrayList<>();
    public final List<String> events = new ArrayList<>();

    @Override
    public synchronized void put(AgentTask task) {
        tasks.put(task.id(), task);
    }

    @Override
    public synchronized void update(AgentTask task) {
        tasks.put(task.id(), task);
    }

    @Override
    public synchronized AgentTask getById(String taskId) {
        return tasks.get(taskId);
    }

    @Override
    public synchronized AgentTask getActiveForConversation(String conversationId) {
        AgentTask newest = null;
        for (AgentTask t : tasks.values()) {
            if (t.conversationId().equals(conversationId) && t.isActive()
                    && (newest == null || t.createdAt() >= newest.createdAt())) {
                newest = t;
            }
        }
        return newest;
    }

    @Override
    public synchronized List<AgentTask> getAllForConversation(String conversationId) {
        List<AgentTask> out = new ArrayList<>();
        for (AgentTask t : tasks.values()) {
            if (t.conversationId().equals(conversationId)) out.add(t);
        }
        out.sort(Comparator.comparingLong(AgentTask::createdAt));
        return out;
    }

    @Override
    public synchronized void deleteForConversation(String conversationId) {
        tasks.values().removeIf(t -> t.conversationId().equals(conversationId));
        evidenceRows.removeIf(e -> {
            AgentTask t = tasks.get(e.taskId());
            return t == null || t.conversationId().equals(conversationId);
        });
    }

    @Override
    public synchronized int reconcileStaleTasks() {
        int n = 0;
        for (AgentTask t : tasks.values()) {
            if (t.isActive()) {
                t.markInterrupted();
                n++;
            }
        }
        return n;
    }

    @Override
    public synchronized void insertEvidence(String id, String taskId, String type, int level,
                                            String source, String claim, String summary,
                                            String refTable, String refId, double strength) {
        evidenceRows.add(new AgentEvidence(id, taskId,
                AgentEvidence.Type.fromString(type),
                cn.lineai.model.harness.EvidenceLevel.fromLevel(level),
                source, claim, summary, refTable, refId, strength,
                false, System.currentTimeMillis()));
    }

    @Override
    public synchronized List<AgentEvidence> getEvidenceForTask(String taskId) {
        List<AgentEvidence> out = new ArrayList<>();
        for (AgentEvidence e : evidenceRows) {
            if (e.taskId().equals(taskId)) out.add(e);
        }
        out.sort(Comparator.comparingInt((AgentEvidence e) -> e.level().level()).reversed());
        return out;
    }

    @Override
    public synchronized void insertEvent(String id, String taskId, String eventType,
                                         String source, String payload) {
        events.add(eventType);
    }
}
