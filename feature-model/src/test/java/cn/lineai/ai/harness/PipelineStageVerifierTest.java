package cn.lineai.ai.harness;

import static org.junit.Assert.*;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.junit.Test;

import cn.lineai.model.harness.EvidenceLevel;
import cn.lineai.model.harness.TaskVerdict;

/**
 * Unit tests for {@link PipelineStageVerifier} (§24, P4).
 */
public class PipelineStageVerifierTest {

    private static VerdictEngine.EvidenceItem ok(VerdictEngine.EvidenceItem.Category c,
                                                 EvidenceLevel level) {
        return new VerdictEngine.EvidenceItem(c, level, false, "ok");
    }

    // ---- Stage order enforcement ----

    @Test
    public void cannot_skip_stages() {
        PipelineStageVerifier.GateResult r = PipelineStageVerifier.canAdvance(
                PipelineStageVerifier.Stage.EXPLORE,
                PipelineStageVerifier.Stage.IMPLEMENT,
                Collections.singletonList(ok(
                        VerdictEngine.EvidenceItem.Category.TOOL_RESULT,
                        EvidenceLevel.TOOL_OBSERVATION)),
                "plan");
        assertFalse(r.passed());
        assertTrue(r.reason().contains("skip"));
    }

    @Test
    public void null_stages_fail() {
        assertFalse(PipelineStageVerifier.canAdvance(null,
                PipelineStageVerifier.Stage.PLAN, null, null).passed());
    }

    // ---- EXPLORE → PLAN gate: needs E1+ observation ----

    @Test
    public void explore_to_plan_requires_observation() {
        List<VerdictEngine.EvidenceItem> none = Collections.emptyList();
        assertFalse(PipelineStageVerifier.canAdvance(
                PipelineStageVerifier.Stage.EXPLORE,
                PipelineStageVerifier.Stage.PLAN, none, null).passed());

        List<VerdictEngine.EvidenceItem> observed = Collections.singletonList(ok(
                VerdictEngine.EvidenceItem.Category.TOOL_RESULT,
                EvidenceLevel.TOOL_OBSERVATION));
        assertTrue(PipelineStageVerifier.canAdvance(
                PipelineStageVerifier.Stage.EXPLORE,
                PipelineStageVerifier.Stage.PLAN, observed, null).passed());
    }

    @Test
    public void failed_observations_do_not_satisfy_explore_gate() {
        VerdictEngine.EvidenceItem failed = new VerdictEngine.EvidenceItem(
                VerdictEngine.EvidenceItem.Category.TOOL_RESULT,
                EvidenceLevel.TOOL_OBSERVATION, true, "error");
        assertFalse(PipelineStageVerifier.canAdvance(
                PipelineStageVerifier.Stage.EXPLORE,
                PipelineStageVerifier.Stage.PLAN,
                Collections.singletonList(failed), null).passed());
    }

    // ---- PLAN → IMPLEMENT gate: needs plan content ----

    @Test
    public void plan_to_implement_requires_non_empty_plan() {
        List<VerdictEngine.EvidenceItem> evidence = Arrays.asList(
                ok(VerdictEngine.EvidenceItem.Category.TOOL_RESULT, EvidenceLevel.TOOL_OBSERVATION));
        assertFalse(PipelineStageVerifier.canAdvance(
                PipelineStageVerifier.Stage.PLAN,
                PipelineStageVerifier.Stage.IMPLEMENT, evidence, "   ").passed());
        assertTrue(PipelineStageVerifier.canAdvance(
                PipelineStageVerifier.Stage.PLAN,
                PipelineStageVerifier.Stage.IMPLEMENT, evidence, "1. edit file").passed());
    }

    // ---- IMPLEMENT → VERIFY gate: needs diff evidence ----

    @Test
    public void implement_to_verify_requires_diff() {
        List<VerdictEngine.EvidenceItem> noDiff = Collections.singletonList(
                ok(VerdictEngine.EvidenceItem.Category.TOOL_RESULT, EvidenceLevel.TOOL_OBSERVATION));
        assertFalse(PipelineStageVerifier.canAdvance(
                PipelineStageVerifier.Stage.IMPLEMENT,
                PipelineStageVerifier.Stage.VERIFY, noDiff, null).passed());

        List<VerdictEngine.EvidenceItem> withDiff = Collections.singletonList(
                ok(VerdictEngine.EvidenceItem.Category.DIFF_RESULT, EvidenceLevel.DETERMINISTIC_CHECK));
        assertTrue(PipelineStageVerifier.canAdvance(
                PipelineStageVerifier.Stage.IMPLEMENT,
                PipelineStageVerifier.Stage.VERIFY, withDiff, null).passed());
    }

    // ---- Final finish gate ----

    @Test
    public void verified_finish_requires_e2_evidence() {
        List<VerdictEngine.EvidenceItem> onlyE0 = Collections.singletonList(
                new VerdictEngine.EvidenceItem(
                        VerdictEngine.EvidenceItem.Category.TOOL_RESULT,
                        EvidenceLevel.MODEL_ASSERTION, false, "model says fine"));
        // Invariant 5: bare assertion cannot close a VERIFIED pipeline
        assertFalse(PipelineStageVerifier.canFinish(onlyE0, TaskVerdict.VERIFIED).passed());

        List<VerdictEngine.EvidenceItem> withE2 = Collections.singletonList(
                ok(VerdictEngine.EvidenceItem.Category.BUILD_RESULT, EvidenceLevel.DETERMINISTIC_CHECK));
        assertTrue(PipelineStageVerifier.canFinish(withE2, TaskVerdict.VERIFIED).passed());
    }

    @Test
    public void honest_partial_or_failed_verdict_may_close_without_e2() {
        assertTrue(PipelineStageVerifier.canFinish(
                Collections.emptyList(), TaskVerdict.PARTIALLY_VERIFIED).passed());
        assertTrue(PipelineStageVerifier.canFinish(
                Collections.emptyList(), TaskVerdict.FAILED).passed());
    }

    @Test
    public void null_verdict_cannot_finish() {
        assertFalse(PipelineStageVerifier.canFinish(Collections.emptyList(), null).passed());
    }

    // ---- Stage ordering helper ----

    @Test
    public void canFollow_only_allows_next_stage() {
        var s = PipelineStageVerifier.Stage.class.getEnumConstants();
        assertTrue(s[0].canFollow(null) == false); // EXPLORE has no predecessor
        assertTrue(s[1].canFollow(s[0]));
        assertTrue(s[3].canFollow(s[2]));
        assertFalse(s[2].canFollow(s[0]));
    }
}
