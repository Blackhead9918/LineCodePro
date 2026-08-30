package cn.lineai.data.repository;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import cn.lineai.model.grounding.GroundedSourceType;
import org.junit.Before;
import org.junit.Test;

public class AgentAccuracyAndLearningTest {

    @Before
    public void setUp() {
        GroundedStateManager.getInstance().clear();
    }

    @Test
    public void testGroundedStateLocalAndRemote() {
        GroundedStateManager manager = GroundedStateManager.getInstance();

        // Local state recording
        manager.recordState("/workspace/MainActivity.kt", "class MainActivity", GroundedSourceType.READ, "sess1");
        assertTrue(manager.isGrounded("/workspace/MainActivity.kt"));
        assertTrue(manager.verifyHash("/workspace/MainActivity.kt", "class MainActivity"));
        assertFalse(manager.verifyHash("/workspace/MainActivity.kt", "class Changed"));

        // Remote SSH state recording
        manager.recordRemoteState("192.168.1.100", 22, "/var/www/index.js", "console.log('hi');", GroundedSourceType.READ, "sess2");
        assertTrue(manager.isRemoteGrounded("192.168.1.100", 22, "/var/www/index.js"));
        assertNotNull(manager.getRemoteState("192.168.1.100", 22, "/var/www/index.js"));
    }

    @Test
    public void testScopedMemoryCandidatePromotion() {
        ScopedMemoryRegistry registry = ScopedMemoryRegistry.getInstance();

        // Propose candidate
        ScopedMemoryRule candidate = registry.proposeCandidate(
                ScopedMemoryRule.Hierarchy.WORKSPACE,
                ScopedMemoryRule.Category.CONDITIONAL_FAILURE,
                "/app",
                "When compiling fails",
                "target not found",
                "Read file first before edit"
        );

        assertNotNull(candidate);
        assertEquals(ScopedMemoryRule.Status.CANDIDATE, candidate.getStatus());

        // Reinforce same rule
        ScopedMemoryRule reinforced = registry.proposeCandidate(
                ScopedMemoryRule.Hierarchy.WORKSPACE,
                ScopedMemoryRule.Category.CONDITIONAL_FAILURE,
                "/app",
                "When compiling fails",
                "target not found",
                "Read file first before edit"
        );

        assertNotNull(reinforced);
        assertEquals(ScopedMemoryRule.Status.VALIDATED, reinforced.getStatus());
        assertTrue(reinforced.getConfidence() >= 0.85);
    }

    @Test
    public void testPostMortemLearningEngine() {
        PostMortemLearningEngine engine = PostMortemLearningEngine.getInstance();
        String workspace = "/test-workspace";

        // Step 1: Failure
        engine.recordFailure(
                workspace,
                "file_edit",
                "{\"path\":\"MainActivity.kt\"}",
                "target content not found in file"
        );

        // Step 2: Fix / Success
        engine.recordSuccess(
                workspace,
                "file_read",
                "{\"path\":\"MainActivity.kt\"}",
                "Read 50 lines successfully"
        );

        // Verify lesson distilled
        java.util.List<ScopedMemoryRule> rules = ScopedMemoryRegistry.getInstance().selectEligibleRules(workspace, null);
        // Candidates won't be in selectEligibleRules (until validated), but will be in searchCandidates or getRulesByStatus
        java.util.List<ScopedMemoryRule> candidates = ScopedMemoryRegistry.getInstance().getRulesByStatus(ScopedMemoryRule.Status.CANDIDATE);
        assertFalse(candidates.isEmpty());
    }
}
