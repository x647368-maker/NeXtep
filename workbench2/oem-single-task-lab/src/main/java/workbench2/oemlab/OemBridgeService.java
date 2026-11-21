package workbench2.oemlab;

import android.app.Service;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.Messenger;
import android.os.Process;
import android.os.RemoteException;

/**
 * Non-exported, isolated-process OEM permission probe.
 * Messenger is a real cross-process IPC endpoint; a Java LocalBinder is not.
 * This revision has NO task mutation command and cannot migrate any task.
 */
public final class OemBridgeService extends Service {
    public static final int MSG_READ_STATUS = 1;
    public static final int MSG_STATUS_REPLY = 2;
    public static final String EXTRA_PHASE = "phase";
    public static final String EXTRA_MUTATION = "taskMutationAllowed";
    private static final String OEM_PERMISSION = "com.oplus.permission.safe.WINDOW";
    private final BridgeAdmission admission = new BridgeAdmission();
    private Messenger messenger;

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
        messenger = new Messenger(new Handler(Looper.getMainLooper(), this::handle));
    }

    private boolean handle(Message message) {
        // Defense in depth: even if manifest export settings change, reject foreign UIDs.
        if (Binder.getCallingUid() != Process.myUid()) return true;
        if (message.what != MSG_READ_STATUS || message.replyTo == null) return true;
        Message reply = Message.obtain(null, MSG_STATUS_REPLY);
        reply.getData().putString(EXTRA_PHASE, admission.phase().name());
        reply.getData().putBoolean(EXTRA_MUTATION, false);
        try { message.replyTo.send(reply); }
        catch (RemoteException ignored) { /* Client disappeared: no state or task change. */ }
        return true;
    }

    @Override public IBinder onBind(Intent intent) {
        return messenger.getBinder();
    }

    @Override public void onDestroy() {
        admission.onBridgeProcessRestart();
        super.onDestroy();
    }
}
