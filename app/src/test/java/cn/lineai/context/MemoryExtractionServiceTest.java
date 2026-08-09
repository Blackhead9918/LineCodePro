package cn.lineai.context;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import cn.lineai.model.MemoryOverviewState;
import java.util.List;
import org.junit.Test;

public final class MemoryExtractionServiceTest {
    @Test
    public void projectConstraintStoresUsersOwnWordsWithProjectScope() {
        List<MemoryExtractionService.ExtractedMemory> candidates = MemoryExtractionService.ruleBasedCandidates(
                "这个项目不能用 AndroidX 库",
                ""
        );

        assertTrue(candidates.size() > 0);
        assertEquals(MemoryOverviewState.Memory.SCOPE_PROJECT, candidates.get(0).scope);
        // Never inject fabricated seed content; the user's own statement is kept verbatim.
        assertEquals("这个项目不能用 AndroidX 库", candidates.get(0).content);
    }

    @Test
    public void noFabricatedAndroidXSeedWithoutUserStatement() {
        // A transcript mention alone (no durable user statement) must not produce a seed memory.
        List<MemoryExtractionService.ExtractedMemory> candidates = MemoryExtractionService.ruleBasedCandidates(
                "",
                "提到 androidx 与当前项目"
        );

        assertTrue(candidates.isEmpty());
    }

    @Test
    public void userPreferenceUsesUserScope() {
        List<MemoryExtractionService.ExtractedMemory> candidates = MemoryExtractionService.ruleBasedCandidates(
                "我偏好默认用中文，回答要简洁直接。",
                ""
        );

        assertTrue(candidates.size() > 0);
        assertEquals(MemoryOverviewState.Memory.SCOPE_USER, candidates.get(0).scope);
    }
}
