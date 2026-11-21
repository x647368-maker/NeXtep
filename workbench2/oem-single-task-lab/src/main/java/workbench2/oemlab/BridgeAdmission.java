package workbench2.oemlab;

/**
 * Pure state model for an OEM bridge. No task mutation is implemented.
 * Host process death is not equivalent to task restoration.
 */
public final class BridgeAdmission {
    public enum Phase { LOCKED, PERMISSION_VERIFIED, RETURN_UNPROVEN, READY_FOR_ISOLATED_TEST }
    private Phase phase = Phase.LOCKED;
    private boolean permissionGranted;
    private boolean oemPresent;
    private boolean systemApp;
    private boolean returnVerified;
    private boolean crashReturnVerified;

    public synchronized void inspect(boolean granted, boolean system, boolean oem) {
        permissionGranted = granted;
        systemApp = system;
        oemPresent = oem;
        returnVerified = false;
        crashReturnVerified = false;
        phase = (granted && system && oem) ? Phase.PERMISSION_VERIFIED : Phase.LOCKED;
    }

    public synchronized void noteNormalReturnVerified(boolean verified) {
        returnVerified = verified;
        refresh();
    }

    public synchronized void noteCrashReturnVerified(boolean verified) {
        crashReturnVerified = verified;
        refresh();
    }

    private void refresh() {
        if (!(permissionGranted && systemApp && oemPresent)) {
            phase = Phase.LOCKED;
        } else if (returnVerified && crashReturnVerified) {
            phase = Phase.READY_FOR_ISOLATED_TEST;
        } else {
            phase = Phase.RETURN_UNPROVEN;
        }
    }

    public synchronized Phase phase() { return phase; }

    /**
     * Runtime OEM mutation is permanently disabled in this first bridge revision.
     * The state machine cannot override this hardware-safety gate.
     */
    public boolean canMutateTasks() { return false; }

    public synchronized void onBridgeProcessRestart() {
        // No durable OEM session/return ACK exists. Never restore a previous READY state.
        phase = Phase.LOCKED;
        permissionGranted = false;
        systemApp = false;
        oemPresent = false;
        returnVerified = false;
        crashReturnVerified = false;
    }
}
