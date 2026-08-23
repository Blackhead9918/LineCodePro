package cn.lineai.ai.harness;

import static org.junit.Assert.*;

import java.util.List;

import org.junit.Test;

import cn.lineai.model.harness.ExecutionProfile;
import cn.lineai.model.harness.TaskVerificationPolicy;
import cn.lineai.model.harness.TaskVerdict;

/**
 * Unit tests for {@link VerificationCapabilityMatrix} (§13, D01/D12).
 */
public class VerificationCapabilityMatrixTest {

    // ---- §13 matrix: LOCAL downgrades BUILD+ to LIGHT ----

    @Test
    public void local_downgrades_build_to_light() {
        VerificationCapabilityMatrix.Capability c = VerificationCapabilityMatrix.resolve(
                TaskVerificationPolicy.BUILD, ExecutionProfile.LOCAL);
        assertTrue(c.downgraded());
        assertEquals(TaskVerificationPolicy.LIGHT, c.effective());
        assertFalse(c.buildAvailable());
        assertFalse(c.testAvailable());
    }

    @Test
    public void local_downgrades_build_and_test_to_light() {
        VerificationCapabilityMatrix.Capability c = VerificationCapabilityMatrix.resolve(
                TaskVerificationPolicy.BUILD_AND_TEST, ExecutionProfile.LOCAL);
        assertTrue(c.downgraded());
        assertEquals(TaskVerificationPolicy.LIGHT, c.effective());
    }

    @Test
    public void local_downgrades_full_to_light() {
        VerificationCapabilityMatrix.Capability c = VerificationCapabilityMatrix.resolve(
                TaskVerificationPolicy.FULL, ExecutionProfile.LOCAL);
        assertTrue(c.downgraded());
    }

    // ---- §13 matrix: SSH supports everything ----

    @Test
    public void ssh_supports_full_without_downgrade() {
        VerificationCapabilityMatrix.Capability c = VerificationCapabilityMatrix.resolve(
                TaskVerificationPolicy.FULL, ExecutionProfile.SSH);
        assertFalse(c.downgraded());
        assertEquals(TaskVerificationPolicy.FULL, c.effective());
        assertTrue(c.buildAvailable());
        assertTrue(c.testAvailable());
    }

    // ---- §13 matrix: NONE and LIGHT never downgrade ----

    @Test
    public void none_never_downgrades_on_any_profile() {
        for (ExecutionProfile p : ExecutionProfile.values()) {
            VerificationCapabilityMatrix.Capability c =
                    VerificationCapabilityMatrix.resolve(TaskVerificationPolicy.NONE, p);
            assertFalse("NONE should not downgrade on " + p, c.downgraded());
            assertEquals(TaskVerificationPolicy.NONE, c.effective());
        }
    }

    @Test
    public void light_supported_on_local() {
        VerificationCapabilityMatrix.Capability c = VerificationCapabilityMatrix.resolve(
                TaskVerificationPolicy.LIGHT, ExecutionProfile.LOCAL);
        assertFalse(c.downgraded());
        assertEquals(TaskVerificationPolicy.LIGHT, c.effective());
        assertFalse(c.buildAvailable()); // light needs no build
    }

    // ---- PHONE ----

    @Test
    public void phone_downgrades_build_policy() {
        VerificationCapabilityMatrix.Capability c = VerificationCapabilityMatrix.resolve(
                TaskVerificationPolicy.BUILD_AND_TEST, ExecutionProfile.PHONE);
        assertTrue(c.downgraded());
        assertEquals(TaskVerificationPolicy.LIGHT, c.effective());
    }

    // ---- Verdict cap (§13 rule 2: honest verdict) ----

    @Test
    public void cap_downgrades_verified_to_partially_when_downgraded() {
        VerificationCapabilityMatrix.Capability c = VerificationCapabilityMatrix.resolve(
                TaskVerificationPolicy.BUILD_AND_TEST, ExecutionProfile.LOCAL);
        assertEquals(TaskVerdict.PARTIALLY_VERIFIED,
                c.cap(TaskVerdict.VERIFIED));
    }

    @Test
    public void cap_keeps_other_verdicts_unchanged() {
        VerificationCapabilityMatrix.Capability c = VerificationCapabilityMatrix.resolve(
                TaskVerificationPolicy.BUILD_AND_TEST, ExecutionProfile.LOCAL);
        assertEquals(TaskVerdict.UNVERIFIED, c.cap(TaskVerdict.UNVERIFIED));
        assertEquals(TaskVerdict.FAILED, c.cap(TaskVerdict.FAILED));
        assertEquals(TaskVerdict.PARTIALLY_VERIFIED, c.cap(TaskVerdict.PARTIALLY_VERIFIED));
    }

    @Test
    public void cap_noop_when_not_downgraded() {
        VerificationCapabilityMatrix.Capability c = VerificationCapabilityMatrix.resolve(
                TaskVerificationPolicy.BUILD_AND_TEST, ExecutionProfile.SSH);
        assertFalse(c.downgraded());
        assertEquals(TaskVerdict.VERIFIED, c.cap(TaskVerdict.VERIFIED));
    }

    // ---- Null safety ----

    @Test
    public void null_policy_defaults_to_light() {
        VerificationCapabilityMatrix.Capability c = VerificationCapabilityMatrix.resolve(
                null, ExecutionProfile.LOCAL);
        assertEquals(TaskVerificationPolicy.LIGHT, c.requested());
    }

    @Test
    public void null_profile_defaults_to_local() {
        VerificationCapabilityMatrix.Capability c = VerificationCapabilityMatrix.resolve(
                TaskVerificationPolicy.FULL, null);
        assertTrue(c.downgraded());
    }

    // ---- Supporting profiles helper ----

    @Test
    public void supporting_profiles_for_build_are_ssh_and_ipc_only() {
        List<ExecutionProfile> profiles =
                VerificationCapabilityMatrix.supportingProfiles(TaskVerificationPolicy.BUILD);
        assertEquals(2, profiles.size());
        assertTrue(profiles.contains(ExecutionProfile.SSH));
        assertTrue(profiles.contains(ExecutionProfile.IPC_TERMINAL));
    }

    @Test
    public void explain_mentions_downgrade() {
        VerificationCapabilityMatrix.Capability c = VerificationCapabilityMatrix.resolve(
                TaskVerificationPolicy.FULL, ExecutionProfile.LOCAL);
        String s = c.explain();
        assertTrue(s.contains("downgrade"));
        assertTrue(s.contains("build=unavailable"));
    }
}
