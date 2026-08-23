package cn.lineai.ai.harness;

import static org.junit.Assert.*;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import org.junit.Before;
import org.junit.Test;

import cn.lineai.model.harness.AgentEvidence;
import cn.lineai.model.harness.AgentTaskStore;
import cn.lineai.model.harness.EvidenceLevel;
import cn.lineai.model.harness.TaskVerdict;

/**
 * Unit tests for {@link EvidenceRecorder} (§35.2, D05).
 */
public class EvidenceRecorderTest {

    private FakeStore store;
    private EvidenceRecorder recorder;

    @Before
    public void setUp() {
        store = new FakeStore();
        recorder = new EvidenceRecorder(store);
    }

    // ---- Tool result recording ----

    @Test
    public void successful_tool_result_recorded_as_observation_e1() {
        VerdictEngine.EvidenceItem item = EvidenceClassifier.classifyToolResult(
                "FileEditTool", true, "ok");
        recorder.recordToolResult("t1", item, "tool_results", "FileEditTool");

        assertEquals(1, store.rows.size());
        AgentEvidence e = store.rows.get(0);
        assertEquals("t1", e.taskId());
        assertEquals(AgentEvidence.Type.OBSERVATION, e.type());
        assertEquals(EvidenceLevel.TOOL_OBSERVATION, e.level());
        assertEquals("tool_results", e.refTable());
    }

    @Test
    public void build_success_recorded_as_evidence_e2() {
        VerdictEngine.EvidenceItem item = EvidenceClassifier.classifyBuildResult(true, "BUILD SUCCESSFUL");
        recorder.recordToolResult("t1", item, "tool_results", "ShellExecuteTool");

        AgentEvidence e = store.rows.get(0);
        assertEquals(AgentEvidence.Type.EVIDENCE, e.type());
        assertEquals(EvidenceLevel.DETERMINISTIC_CHECK, e.level());
        assertTrue(e.summary().contains("SUCCESS"));
    }

    @Test
    public void test_pass_reaches_independent_verification_level() {
        VerdictEngine.EvidenceItem item = EvidenceClassifier.classifyTestResult(true, "tests PASS");
        recorder.recordToolResult("t1", item, "tool_results", "ShellExecuteTool");

        assertEquals(EvidenceLevel.INDEPENDENT_VERIFICATION, store.rows.get(0).level());
    }

    @Test
    public void failed_tool_result_gets_low_strength() {
        VerdictEngine.EvidenceItem item = EvidenceClassifier.classifyToolResult(
                "ShellExecuteTool", false, "boom");
        recorder.recordToolResult("t1", item, "tool_results", "ShellExecuteTool");
        assertTrue(store.rows.get(0).strength() <= 0.2);
    }

    // ---- Diff scope check recording ----

    @Test
    public void passing_scope_check_is_evidence_with_high_strength() {
        DiffScopeChecker.ScopeCheckResult result = DiffScopeChecker.check(
                null,
                java.util.Collections.singletonList("src/A.java"),
                java.util.Collections.emptyList());
        recorder.recordDiffScopeCheck("t1", result);

        AgentEvidence e = store.rows.get(0);
        assertEquals("diff", e.source());
        assertTrue(e.summary().contains("PASS"));
        assertEquals(0.9, e.strength(), 0.001);
    }

    @Test
    public void flagged_scope_check_stays_observation() {
        DiffScopeChecker.ScopeCheckResult result = DiffScopeChecker.check(
                "allowed/dir/",
                java.util.Collections.singletonList("elsewhere/B.java"),
                java.util.Collections.emptyList());
        assertFalse(result.passed());
        recorder.recordDiffScopeCheck("t1", result);

        assertEquals(AgentEvidence.Type.OBSERVATION, store.rows.get(0).type());
    }

    // ---- Verdict recording ----

    @Test
    public void verdict_recorded_with_type_verdict() {
        recorder.recordVerdict("t1", TaskVerdict.PARTIALLY_VERIFIED);
        AgentEvidence e = store.rows.get(0);
        assertEquals(AgentEvidence.Type.VERDICT, e.type());
        assertTrue(e.summary().contains("partially_verified"));
    }

    // ---- Misclassification guard (FileReadTool reading build files must not fabricate E2/E3) ----

    @Test
    public void file_read_output_mentioning_gradle_stays_tool_observation() {
        VerdictEngine.EvidenceItem item = EvidenceClassifier.classifyToolResult(
                "FileReadTool", true,
                "build.gradle: dependencies { gradle plugin } BUILD SUCCESSFUL text");
        assertEquals(VerdictEngine.EvidenceItem.Category.TOOL_RESULT, item.category());
        assertEquals(EvidenceLevel.TOOL_OBSERVATION, item.level());
    }

    @Test
    public void file_read_output_containing_test_pass_does_not_reach_e3() {
        VerdictEngine.EvidenceItem item = EvidenceClassifier.classifyToolResult(
                "FileReadTool", true,
                "test report: 10 tests PASS, junit summary");
        assertNotEquals(VerdictEngine.EvidenceItem.Category.TEST_RESULT, item.category());
        assertFalse(item.level().atLeast(EvidenceLevel.INDEPENDENT_VERIFICATION));
    }

    @Test
    public void shell_execute_with_gradle_output_is_build_result() {
        VerdictEngine.EvidenceItem item = EvidenceClassifier.classifyToolResult(
                "ShellExecuteTool", true, "gradle BUILD SUCCESSFUL");
        assertEquals(VerdictEngine.EvidenceItem.Category.BUILD_RESULT, item.category());
    }

    @Test
    public void git_tool_counts_as_command_capable() {
        VerdictEngine.EvidenceItem item = EvidenceClassifier.classifyToolResult(
                "git_push", true, "pushed to origin");
        assertEquals(VerdictEngine.EvidenceItem.Category.TOOL_RESULT, item.category());
    }

    // ---- Null safety & ordering ----

    @Test
    public void null_inputs_are_ignored() {
        recorder.recordToolResult(null, null, null, null);
        recorder.recordDiffScopeCheck(null, null);
        recorder.recordVerdict(null, null);
        assertTrue(store.rows.isEmpty());
    }

    @Test
    public void evidence_query_returns_strongest_first() {
        recorder.recordVerdict("t1", TaskVerdict.FAILED); // E0-ish
        recorder.recordToolResult("t1",
                EvidenceClassifier.classifyBuildResult(true, "ok"), "tool_results", "x");
        recorder.recordToolResult("t1",
                EvidenceClassifier.classifyToolResult("GlobTool", true, "ok"),
                "tool_results", "y"); // E1

        List<AgentEvidence> all = store.getEvidenceForTask("t1");
        assertEquals(3, all.size());
        assertEquals(EvidenceLevel.DETERMINISTIC_CHECK, all.get(0).level()); // strongest first
    }

    // ---- Minimal fake store ----

    private static final class FakeStore implements AgentTaskStore {
        final List<AgentEvidence> rows = new ArrayList<>();

        @Override public void put(cn.lineai.model.harness.AgentTask task) { }
        @Override public void update(cn.lineai.model.harness.AgentTask task) { }
        @Override public cn.lineai.model.harness.AgentTask getById(String taskId) { return null; }
        @Override public cn.lineai.model.harness.AgentTask getActiveForConversation(String conversationId) { return null; }
        @Override public List<cn.lineai.model.harness.AgentTask> getAllForConversation(String conversationId) {
            return new ArrayList<>();
        }
        @Override public void deleteForConversation(String conversationId) { rows.clear(); }
        @Override public int reconcileStaleTasks() { return 0; }
        @Override public void insertEvidence(String id, String taskId, String type, int level,
                                             String source, String claim, String summary,
                                             String refTable, String refId, double strength) {
            rows.add(new AgentEvidence(id, taskId, AgentEvidence.Type.fromString(type),
                    EvidenceLevel.fromLevel(level), source, claim, summary,
                    refTable, refId, strength, false, System.currentTimeMillis()));
        }
        @Override public List<AgentEvidence> getEvidenceForTask(String taskId) {
            List<AgentEvidence> out = new ArrayList<>();
            for (AgentEvidence e : rows) {
                if (e.taskId().equals(taskId)) out.add(e);
            }
            out.sort(Comparator.comparingInt((AgentEvidence e) -> e.level().level()).reversed());
            return out;
        }
        @Override public void insertEvent(String id, String taskId, String eventType,
                                          String source, String payload) { }
    }
}
