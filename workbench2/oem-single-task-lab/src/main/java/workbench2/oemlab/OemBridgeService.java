package workbench2.oemlab;

import android.app.Service;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Binder;
import android.os.IBinder;

/**
 * Private isolated-process read-only permission service.
 * No OEM calls, no task IDs, no hooks, no foreground notification or polling.
 */
public final class OemBridgeService extends Service {
    private static final String OEM_PERMISSION = "com.oplus.permission.safe.WINDOW";
    private final BridgeAdmission admission = new BridgeAdmission();
    private final LocalBinder binder = new LocalBinder();

    public final class LocalBinder extends Binder {
        public String diagnostic() {
            return admission.phase().name();
        }
        public boolean taskMutationAllowed() {
            return admission.canMutateTasks();
        }
    }

    @Override public void onCreate() {
        super.onCreate();
        boolean granted = getPackageManager().checkPermission(OEM_PERMISSION, getPackageName())
                == PackageManager.PERMISSION_GRANTED;
        ApplicationInfo app = getApplicationInfo();
        boolean system = (app.flags &
                (ApplicationInfo.FLAG_SYSTEM | ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) != 0;
        boolean oem;
        try { Class.forName("com.oplus.flexiblewindow.FlexibleTaskView"); oem = true; }
        catch (Throwable ignored) { oem = false; }
        admission.inspect(granted, system, oem);
    }

    @Override public IBinder onBind(Intent intent) {
        // Not exported. Only same-application clients can bind.
        return binder;
    }

    @Override public void onDestroy() {
        admission.onBridgeProcessRestart();
        super.onDestroy();
    }
}
