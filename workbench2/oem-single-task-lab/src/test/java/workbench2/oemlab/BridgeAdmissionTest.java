package workbench2.oemlab;

import org.junit.Test;
import static org.junit.Assert.*;

public final class BridgeAdmissionTest {
    @Test public void ordinaryAppNeverAdmitted() {
        BridgeAdmission bridge = new BridgeAdmission();
        bridge.inspect(false, false, true);
        assertEquals(BridgeAdmission.Phase.LOCKED, bridge.phase());
        assertFalse(bridge.canMutateTasks());
    }

    @Test public void permissionAloneDoesNotProveReturn() {
        BridgeAdmission bridge = new BridgeAdmission();
        bridge.inspect(true, true, true);
        assertEquals(BridgeAdmission.Phase.PERMISSION_VERIFIED, bridge.phase());
        assertFalse(bridge.canMutateTasks());
        bridge.noteNormalReturnVerified(true);
        assertEquals(BridgeAdmission.Phase.RETURN_UNPROVEN, bridge.phase());
        assertFalse(bridge.canMutateTasks());
    }

    @Test public void evenVerifiedStateCannotEnableTaskMutation() {
        BridgeAdmission bridge = new BridgeAdmission();
        bridge.inspect(true, true, true);
        bridge.noteNormalReturnVerified(true);
        bridge.noteCrashReturnVerified(true);
        assertEquals(BridgeAdmission.Phase.READY_FOR_ISOLATED_TEST, bridge.phase());
        assertFalse(bridge.canMutateTasks());
    }

    @Test public void bridgeRestartInvalidatesAllPriorEvidence() {
        BridgeAdmission bridge = new BridgeAdmission();
        bridge.inspect(true, true, true);
        bridge.noteNormalReturnVerified(true);
        bridge.noteCrashReturnVerified(true);
        bridge.onBridgeProcessRestart();
        assertEquals(BridgeAdmission.Phase.LOCKED, bridge.phase());
        assertFalse(bridge.canMutateTasks());
    }

    @Test public void permissionRevocationLocksBridge() {
        BridgeAdmission bridge = new BridgeAdmission();
        bridge.inspect(true, true, true);
        bridge.noteNormalReturnVerified(true);
        bridge.noteCrashReturnVerified(true);
        bridge.inspect(false, true, true);
        assertEquals(BridgeAdmission.Phase.LOCKED, bridge.phase());
        assertFalse(bridge.canMutateTasks());
    }
}
