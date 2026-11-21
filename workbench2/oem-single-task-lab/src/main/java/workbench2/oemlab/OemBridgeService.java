package workbench2.oemlab;

import android.app.Service;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.PermissionInfo;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.Messenger;
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
    public static final String EXTRA_GRANTED = "permissionGranted";
    public static final String EXTRA_OWNER = "permissionOwner";
    public static final String EXTRA_PROTECTION = "permissionProtection";
    public static final String EXTRA_SYSTEM = "systemApp";
    public static final String EXTRA_OEM_CLASS = "oemClassPresent";
    private static final String OEM_PERMISSION = "com.oplus.permission.safe.WINDOW";
    private final BridgeAdmission admission = new BridgeAdmission();
    private Messenger messenger;
    private boolean permissionGranted;
    private boolean systemApp;
    private boolean oemClassPresent;
    private String permissionOwner = "missing";
    private int permissionProtection = -1;

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
        permissionGranted = granted;
        systemApp = system;
        oemClassPresent = oem;
        try {
            PermissionInfo info = getPackageManager().getPermissionInfo(OEM_PERMISSION, 0);
            permissionOwner = info.packageName;
            permissionProtection = info.protectionLevel;
        } catch (PackageManager.NameNotFoundException ignored) {
            permissionOwner = "missing";
        }
        admission.inspect(granted, system, oem);
        messenger = new Messenger(new Handler(Looper.getMainLooper(), this::handle));
    }

    private boolean handle(Message message) {
        // Manifest exported=false restricts binding to this application.
        // Handler executes asynchronously: Binder.getCallingUid() is NOT a caller identity here.
        if (message.what != MSG_READ_STATUS || message.replyTo == null) return true;
        // Never report stale privilege after a runtime permission/package change.
        permissionGranted = getPackageManager().checkPermission(OEM_PERMISSION, getPackageName())
                == PackageManager.PERMISSION_GRANTED;
        admission.inspect(permissionGranted, systemApp, oemClassPresent);
        Message reply = Message.obtain(null, MSG_STATUS_REPLY);
        reply.getData().putString(EXTRA_PHASE, admission.phase().name());
        reply.getData().putBoolean(EXTRA_MUTATION, false);
        reply.getData().putBoolean(EXTRA_GRANTED, permissionGranted);
        reply.getData().putBoolean(EXTRA_SYSTEM, systemApp);
        reply.getData().putBoolean(EXTRA_OEM_CLASS, oemClassPresent);
        reply.getData().putString(EXTRA_OWNER, permissionOwner);
        reply.getData().putInt(EXTRA_PROTECTION, permissionProtection);
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
