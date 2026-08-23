package cn.lineai.ai.harness;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

import org.junit.Before;
import org.junit.Test;

import cn.lineai.model.harness.AgentEvidence;
import cn.lineai.model.harness.AgentTask;
import cn.lineai.model.harness.EvidenceLevel;
import cn.lineai.model.harness.ExecutionProfile;
import cn.lineai.model.harness.MemoryCandidate;
import cn.lineai.model.harness.TaskStatus;
import cn.lineai.model.harness.TaskVerdict;
import cn.lineai.model.harness.TaskVerificationPolicy;

/**
 * Monte-Carlo accuracy simulation for the LCP-Harness v1 decision components
 * (fixed seed — reproducible).
 *
 * <p>Measures three properties:
 * <ol>
 *   <li><b>Verdict accuracy</b> — VERIFIED must require deterministic evidence
 *       (broken work can only slip through via stacked spurious passes);
 *       PARTIALLY_VERIFIED is intentionally permissive (mutation-level evidence).</li>
 *   <li><b>Memory precision</b> — lessons come only from earned verdicts
 *       (Invariant 5), calibrated against simulated ground truth.</li>
 *   <li><b>Failure-pattern recall</b> — recurring signatures are detected,
 *       one-off noise never fires.</li>
 * </ol>
 */
public final class HarnessAccuracySimulationTest {

    private static final int TRIALS = 4000;
    private Random random;

    @Before
    public void setUp() {
        random = new Random(42L); // fixed seed → deterministic simulation
    }

    // ------------------------------------------------------------------
    // Simulation world model
    // ------------------------------------------------------------------

    private static final class SimulatedRun {
        final boolean reallyDone;
        final boolean claimed;
        final List<VerdictEngine.EvidenceItem> evidence = new ArrayList<>();
        /** Parallel AgentEvidence rows (for lesson extraction). */
        final List<AgentEvidence> rows = new ArrayList<>();
        boolean scopeCheckPassed;

        SimulatedRun(boolean reallyDone, boolean claimed) {
            this.reallyDone = reallyDone;
            this.claimed = claimed;
        }
    }

    /**
     * Behaviour profile (calibrated to typical coding-agent failure modes):
     * - claimRate: probability the model claims completion even when it failed
     * - writeRate: probability it mutates files despite failing
     * - buildSpuriousPass / testFlakyPass: noise rates of CI signals
     */
    private SimulatedRun simulate(boolean reallyDone, double claimRate, double writeRate,
                                  TaskVerificationPolicy policy, ExecutionProfile profile,
                                  int seq) {
        boolean claimed = reallyDone || random.nextDouble() < claimRate;
        SimulatedRun run = new SimulatedRun(reallyDone, claimed);
        if (!claimed) {
            return run;
        }

        // Model always produces an E0 claim first.
        VerdictEngine.EvidenceItem claim = new VerdictEngine.EvidenceItem(
                VerdictEngine.EvidenceItem.Category.TOOL_RESULT,
                EvidenceLevel.MODEL_ASSERTION, false, "claim: done");
        run.evidence.add(claim);

        boolean attemptedWrite = random.nextDouble() < (reallyDone ? 0.95 : writeRate);
        if (!attemptedWrite) {
            return run;
        }
        run.evidence.add(EvidenceClassifier.classifyToolResult("file_edit", true, "edited"));

        // On-device diff scope check always runs for mutations (TaskController.onDiffRecorded).
        boolean inScope = random.nextDouble() < (reallyDone ? 0.95 : 0.85);
        run.scopeCheckPassed = inScope;

        boolean buildChecksRequested = policyRank(policy) >= 2
                && profile.supportsBuildVerification();
        if (buildChecksRequested) {
            boolean buildRan = random.nextDouble() < (reallyDone ? 0.9 : 0.4);
            if (buildRan) {
                boolean buildOk = reallyDone
                        ? random.nextDouble() < 0.9
                        : random.nextDouble() < 0.10; // broken code rarely builds
                run.evidence.add(EvidenceClassifier.classifyBuildResult(buildOk, "gradle build"));

                if (policyRank(policy) >= 3 && buildOk) {
                    boolean testRan = random.nextDouble() < 0.85;
                    if (testRan) {
                        boolean testOk = reallyDone
                                ? random.nextDouble() < 0.9
                                : random.nextDouble() < 0.15; // flaky tests sometimes lie
                        run.evidence.add(EvidenceClassifier.classifyTestResult(testOk, "unit"));
                    }
                }
            }
        }

        // Mirror evidence into persisted AgentEvidence rows (as EvidenceRecorder would).
        int i = 0;
        for (VerdictEngine.EvidenceItem item : run.evidence) {
            run.rows.add(new AgentEvidence("e" + seq + "_" + (i++), "t_sim",
                    AgentEvidence.Type.EVIDENCE, item.level(), "tool", item.summary(),
                    item.summary(), "tool_results", "r", item.level().level() / 4.0,
                    item.level().atLeast(EvidenceLevel.DETERMINISTIC_CHECK), 0L));
        }
        run.rows.add(new AgentEvidence("e" + seq + "_diff", "t_sim",
                AgentEvidence.Type.EVIDENCE,
                inScope ? EvidenceLevel.DETERMINISTIC_CHECK : EvidenceLevel.MODEL_ASSERTION,
                "diff", inScope ? "scope check: PASS (in-scope)" : "scope check: OUT",
                "", "diff_records", "d", inScope ? 0.5 : 0.1, inScope, 0L));
        return run;
    }

    /** Ordinal rank: NONE=1, LIGHT=2, BUILD=3, BUILD_AND_TEST=4, FULL=5. */
    private static int policyRank(TaskVerificationPolicy policy) {
        return policy.ordinal() + 1;
    }

    private static AgentTask task(TaskVerificationPolicy policy, ExecutionProfile profile) {
        return new AgentTask.Builder("t_sim", "c_sim", "simulated goal")
                .verificationPolicy(policy)
                .executionProfile(profile)
                .status(TaskStatus.VERIFYING)
                .build();
    }

    private static TaskVerdict verdictOf(SimulatedRun run, AgentTask task) {
        return VerificationCapabilityMatrix.resolve(task.verificationPolicy(), task.executionProfile())
                .cap(VerdictEngine.evaluate(task, run.evidence));
    }

    private static boolean isPositiveVerdict(TaskVerdict v) {
        return v == TaskVerdict.VERIFIED || v == TaskVerdict.PARTIALLY_VERIFIED;
    }

    // ------------------------------------------------------------------
    // 1. Verdict accuracy
    // ------------------------------------------------------------------

    @Test
    public void fullVerified_onBrokenWork_isExtremelyRare() {
        TaskVerificationPolicy policy = TaskVerificationPolicy.BUILD_AND_TEST;
        ExecutionProfile profile = ExecutionProfile.SSH;

        int falseFullVerified = 0;
        for (int i = 0; i < TRIALS; i++) {
            AgentTask t = task(policy, profile);
            SimulatedRun run = simulate(/*reallyDone*/ false, /*claimRate*/ 0.5,
                    /*writeRate*/ 0.7, policy, profile, i);
            if (verdictOf(run, t) == TaskVerdict.VERIFIED) {
                falseFullVerified++;
            }
        }
        double rate = falseFullVerified / (double) TRIALS;
        // Requires spurious build pass AND flaky test pass stacking (~10% × 15% × run rates).
        assertTrue("false VERIFIED rate too high: " + rate, rate <= 0.03);
    }

    @Test
    public void localProfile_neverIssuesFullVerified_evenWithPerfectSignals() {
        int verified = 0;
        for (int i = 0; i < 500; i++) {
            AgentTask t = task(TaskVerificationPolicy.FULL, ExecutionProfile.LOCAL);
            List<VerdictEngine.EvidenceItem> perfect = Arrays.asList(
                    EvidenceClassifier.classifyToolResult("file_write", true, ""),
                    EvidenceClassifier.classifyBuildResult(true, ""),
                    EvidenceClassifier.classifyTestResult(true, ""));
            if (VerificationCapabilityMatrix.resolve(
                    TaskVerificationPolicy.FULL, ExecutionProfile.LOCAL)
                    .cap(VerdictEngine.evaluate(t, perfect)) == TaskVerdict.VERIFIED) {
                verified++;
            }
        }
        assertEquals("LOCAL cannot honestly issue VERIFIED (D01/D12 downgrade)",
                0, verified);
    }

    @Test
    public void positiveVerdict_recall_forGenuinelyCompletedWork_ssh() {
        TaskVerificationPolicy policy = TaskVerificationPolicy.BUILD_AND_TEST;
        ExecutionProfile profile = ExecutionProfile.SSH;

        int hits = 0;
        for (int i = 0; i < TRIALS; i++) {
            AgentTask t = task(policy, profile);
            SimulatedRun run = simulate(/*reallyDone*/ true, 0.05, 0.3, policy, profile, i);
            if (isPositiveVerdict(verdictOf(run, t))) {
                hits++;
            }
        }
        double recall = hits / (double) TRIALS;
        assertTrue("recall too low: " + recall, recall >= 0.60);
    }

    @Test
    public void overallPositivePrecision_meetsFloor_acrossProfiles() {
        TaskVerificationPolicy[] policies = {
                TaskVerificationPolicy.LIGHT, TaskVerificationPolicy.BUILD};
        ExecutionProfile[] profiles = {ExecutionProfile.LOCAL, ExecutionProfile.SSH};

        for (TaskVerificationPolicy policy : policies) {
            for (ExecutionProfile profile : profiles) {
                int tp = 0, fp = 0;
                for (int i = 0; i < TRIALS / 2; i++) {
                    boolean reallyDone = random.nextBoolean();
                    AgentTask t = task(policy, profile);
                    SimulatedRun run = simulate(reallyDone, 0.35, 0.6, policy, profile, i);
                    if (isPositiveVerdict(verdictOf(run, t))) {
                        if (reallyDone) tp++; else fp++;
                    }
                }
                double precision = tp / (double) Math.max(1, tp + fp);
                assertTrue("precision too low for " + policy + "/" + profile + ": " + precision,
                        precision >= 0.50);
            }
        }
    }

    /**
     * Diagnostic: prints measured precision/recall numbers so they can be reported.
     * Assertions stay loose here; the strict properties are asserted above.
     */
    @Test
    public void diagnostic_measuredMetrics() {
        TaskVerificationPolicy policy = TaskVerificationPolicy.BUILD_AND_TEST;
        ExecutionProfile profile = ExecutionProfile.SSH;
        Random rng = new Random(42L);
        int tp = 0, fp = 0, fn = 0, falseFull = 0;
        java.util.List<Boolean> doneSeq = new ArrayList<>();
        for (int i = 0; i < TRIALS; i++) {
            boolean reallyDone = rng.nextBoolean();
            doneSeq.add(reallyDone);
            AgentTask t = task(policy, profile);
            SimulatedRun run = simulate(reallyDone, 0.5, 0.7, policy, profile, i);
            TaskVerdict v = verdictOf(run, t);
            boolean positive = isPositiveVerdict(v);
            if (v == TaskVerdict.VERIFIED) {
                if (!reallyDone) falseFull++;
            }
            if (positive) { if (reallyDone) tp++; else fp++; }
            else if (reallyDone && run.claimed) fn++;
        }
        double precision = tp / (double) Math.max(1, tp + fp);
        double recall = tp / (double) Math.max(1, tp + fn);
        System.out.println("[harness-sim] BUILD_AND_TEST/SSH precision=" + precision
                + " recall=" + recall + " falseFullVerifiedRate="
                + (falseFull / (double) TRIALS));
        assertTrue(true);
    }

    // ------------------------------------------------------------------
    // 2. Memory precision (TaskLessonExtractor + Invariant 5)
    // ------------------------------------------------------------------

    @Test
    public void memoryExtraction_neverEmitsFromFailedOrUnverifiedTasks() {
        int emitted = 0;
        for (int i = 0; i < TRIALS / 2; i++) {
            AgentTask failed = task(TaskVerificationPolicy.BUILD, ExecutionProfile.LOCAL);
            failed.setStatus(TaskStatus.FAILED);
            failed.markFailed(TaskVerdict.FAILED);
            MemoryCandidate c = TaskLessonExtractor.extract(failed, Arrays.asList(
                    new AgentEvidence("e" + i, "t_sim", AgentEvidence.Type.OBSERVATION,
                            EvidenceLevel.DETERMINISTIC_CHECK, "diff", "scope check: PASS",
                            "", "diff_records", "d1", 0.5, true, 0L)));
            if (c != null) emitted++;

            AgentTask unverified = task(TaskVerificationPolicy.NONE, ExecutionProfile.LOCAL);
            unverified.setVerdict(TaskVerdict.UNVERIFIED);
            MemoryCandidate c2 = TaskLessonExtractor.extract(unverified, Arrays.asList(
                    new AgentEvidence("f" + i, "t_sim", AgentEvidence.Type.CLAIM,
                            EvidenceLevel.MODEL_ASSERTION, "model", "looks fine",
                            "", "", "", 0.1, false, 0L)));
            if (c2 != null) emitted++;
        }
        assertEquals("FAILED/UNVERIFIED tasks must never yield candidates (Invariant 5)",
                0, emitted);
    }

    @Test
    public void memoryExtraction_precision_onlyEarnedLessonsSurvive() {
        int lessons = 0, lessonsFromTrulyDone = 0;
        for (int i = 0; i < TRIALS / 2; i++) {
            boolean reallyDone = random.nextBoolean();
            TaskVerificationPolicy policy = random.nextBoolean()
                    ? TaskVerificationPolicy.BUILD : TaskVerificationPolicy.BUILD_AND_TEST;
            SimulatedRun run = simulate(reallyDone, 0.4, 0.5, policy, ExecutionProfile.SSH, i);
            AgentTask t = task(policy, ExecutionProfile.SSH);
            t.setStatus(TaskStatus.COMPLETED);
            t.setVerdict(verdictOf(run, t));

            MemoryCandidate c = TaskLessonExtractor.extract(t, run.rows);
            if (c != null) {
                lessons++;
                if (reallyDone) lessonsFromTrulyDone++;
            }
        }
        assertTrue("expected lessons in simulation, got " + lessons, lessons > 100);
        double precision = lessonsFromTrulyDone / (double) lessons;
        assertTrue("memory precision too low: " + precision, precision >= 0.75);
    }

    // ------------------------------------------------------------------
    // 3. Failure-pattern recall (FailurePatternTracker)
    // ------------------------------------------------------------------

    @Test
    public void failurePattern_detectsRecurringSignature_andIgnoresOneOffs() {
        String recurring = "gradle-config-conflict";
        int detections = 0;
        int trials = 200;

        for (int i = 0; i < trials; i++) {
            FailurePatternTracker tracker = new FailurePatternTracker(3);
            assertNull(tracker.recordFailure(recurring, "a"));   // 1st — below threshold
            assertNull(tracker.recordFailure(recurring, "b"));   // 2nd
            MemoryCandidate third = tracker.recordFailure(recurring, "c"); // crossing
            MemoryCandidate fourth = tracker.recordFailure(recurring, "d"); // already emitted

            assertNull(tracker.recordFailure("noise-" + i, "x"));
            assertNull(tracker.recordFailure("noise-" + i + "-b", "y"));

            if (third != null && fourth == null) {
                detections++;
            }
        }
        assertEquals("recurring pattern must be detected exactly once per streak",
                trials, detections);
    }

    @Test
    public void failurePattern_successResetsStreak() {
        FailurePatternTracker tracker = new FailurePatternTracker(3);
        tracker.recordFailure("flaky", "1");
        tracker.recordFailure("flaky", "2");
        // Success clears the record entirely; nothing pending was superseded.
        assertFalse(tracker.recordSuccess("flaky"));
        assertEquals(0, tracker.failureCount("flaky"));

        tracker.recordFailure("flaky", "3");
        // Only 1 consecutive failure since reset so far → no emission yet.
        assertNull(tracker.recordFailure("flaky", "4"));
        assertNull(tracker.recordFailure("other", "z"));
        assertEquals(2, tracker.failureCount("flaky"));
    }
}
