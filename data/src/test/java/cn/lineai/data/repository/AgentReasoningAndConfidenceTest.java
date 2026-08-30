package cn.lineai.data.repository;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import cn.lineai.model.grounding.AgentConfidenceReport;
import cn.lineai.model.grounding.GroundedSourceType;
import org.junit.Before;
import org.junit.Test;

public class AgentReasoningAndConfidenceTest {

    @Before
    public void setUp() {
        GroundedStateManager.getInstance().clear();
        AgentAccuracyLogger.getInstance().clear();
        ScopedMemoryRegistry.getInstance().getAllRules().forEach(r ->
                ScopedMemoryRegistry.getInstance().removeRule(r.getId()));
    }

    @Test
    public void testConfidenceCalculatorGroundedVsUngrounded() {
        AgentConfidenceCalculator calculator = AgentConfidenceCalculator.getInstance();

        // 1. Ungrounded file edit
        AgentConfidenceReport ungroundedReport = calculator.evaluateConfidence(
                "/workspace/App.kt",
                false,
                null,
                22,
                "file_edit"
        );
        assertTrue(ungroundedReport.getTotalScore() < 0.70);
        assertTrue(ungroundedReport.getGroundedFactor() <= 0.30);

        // 2. Record grounded state
        GroundedStateManager.getInstance().recordState("/workspace/App.kt", "class App", GroundedSourceType.READ, "sess1");

        // 3. Grounded file edit
        AgentConfidenceReport groundedReport = calculator.evaluateConfidence(
                "/workspace/App.kt",
                false,
                null,
                22,
                "file_edit"
        );
        assertTrue(groundedReport.getTotalScore() >= 0.80);
        assertEquals(1.0, groundedReport.getGroundedFactor(), 0.01);
        assertEquals(AgentConfidenceReport.Level.HIGH, groundedReport.getLevel());
    }

    @Test
    public void testRetryPenaltyOnFailures() {
        AgentConfidenceCalculator calculator = AgentConfidenceCalculator.getInstance();
        AgentAccuracyLogger logger = AgentAccuracyLogger.getInstance();

        String target = "/workspace/Buggy.kt";
        GroundedStateManager.getInstance().recordState(target, "fun buggy()", GroundedSourceType.READ, "sess1");

        // Initial confidence
        AgentConfidenceReport initial = calculator.evaluateConfidence(target, false, null, 22, "file_edit");
        assertEquals(0.0, initial.getRetryPenalty(), 0.01);

        // Simulate 2 failed executions on target
        logger.logExecution("file_edit", target, false, true, false, 0.7, "Failed match", 50);
        logger.logExecution("file_edit", target, false, true, false, 0.6, "Failed match again", 60);

        AgentConfidenceReport penalized = calculator.evaluateConfidence(target, false, null, 22, "file_edit");
        assertTrue(penalized.getRetryPenalty() > 0.20);
        assertTrue(penalized.getTotalScore() < initial.getTotalScore());
    }

    @Test
    public void testAccuracyLoggerMetrics() {
        AgentAccuracyLogger logger = AgentAccuracyLogger.getInstance();

        logger.logExecution("file_read", "/app/MainActivity.kt", false, true, true, 0.95, "Success", 12);
        logger.logExecution("file_edit", "/app/MainActivity.kt", false, true, true, 0.90, "Success", 25);
        logger.logExecution("shell_execute", "npm test", false, false, false, 0.50, "Command failed", 120);

        AgentAccuracyLogger.AccuracyStats stats = logger.getStats();
        assertEquals(3, stats.getTotalActions());
        assertEquals(2, stats.getSuccessfulActions());
        assertEquals(2, stats.getGroundedActions());
        assertEquals(2.0 / 3.0, stats.getSuccessRate(), 0.01);
        assertEquals(2.0 / 3.0, stats.getGroundedRate(), 0.01);
        assertTrue(stats.getAverageConfidence() > 0.70);
    }

    @Test
    public void testWorkspaceContextProfiler() {
        WorkspaceContextProfiler profiler = WorkspaceContextProfiler.getInstance();
        WorkspaceContextProfiler.WorkspaceProfile profile = profiler.profileWorkspace("/nonexistent_dummy_workspace");

        assertNotNull(profile);
        assertNotNull(profile.formatForPrompt());
        assertTrue(profile.formatForPrompt().contains("Active Workspace Context Profile"));
    }
}
