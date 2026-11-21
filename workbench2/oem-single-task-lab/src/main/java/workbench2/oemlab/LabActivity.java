package workbench2.oemlab;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.Messenger;
import android.os.RemoteException;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.Rect;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import android.content.pm.PermissionInfo;

/**
 * Isolated proof-of-concept. No TaskOrganizer, hooks, system_server code, or background service.
 * The button is deliberately disabled unless the OEM permission is genuinely granted.
 * NEVER ship this diagnostic APK as an update to the original Atomic Workbench package.
 */
public final class LabActivity extends Activity {
    private static final String OEM_PERMISSION = "com.oplus.permission.safe.WINDOW";
    private final Rect requestedBounds = new Rect(0, 0, 500, 800);
    private View taskView;
    private TextView status;
    private LinearLayout root;
    private Messenger bridge;
    private boolean bound;
    private final Messenger reply = new Messenger(new Handler(Looper.getMainLooper(), msg -> {
        if (msg.what != OemBridgeService.MSG_STATUS_REPLY) return false;
        status.setText("Bridge process: " +
                msg.getData().getString(OemBridgeService.EXTRA_PHASE, "unknown") +
                "; task mutation=" + msg.getData().getBoolean(OemBridgeService.EXTRA_MUTATION, true)
                + "; granted=" + msg.getData().getBoolean(OemBridgeService.EXTRA_GRANTED, false)
                + "; system=" + msg.getData().getBoolean(OemBridgeService.EXTRA_SYSTEM, false)
                + "; OEM class=" + msg.getData().getBoolean(OemBridgeService.EXTRA_OEM_CLASS, false)
                + "; owner=" + msg.getData().getString(OemBridgeService.EXTRA_OWNER, "missing")
                + "; protection=0x" + Integer.toHexString(
                    msg.getData().getInt(OemBridgeService.EXTRA_PROTECTION, -1)));
        return true;
    }));
    private final ServiceConnection bridgeConnection = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName name, IBinder binder) {
            bridge = new Messenger(binder);
            Message request = Message.obtain(null, OemBridgeService.MSG_READ_STATUS);
            request.replyTo = reply;
            try { bridge.send(request); }
            catch (RemoteException e) { status.setText("Bridge IPC failed"); }
        }
        @Override public void onServiceDisconnected(ComponentName name) {
            bridge = null;
            status.setText("Bridge disconnected; no task operation performed");
        }
        @Override public void onBindingDied(ComponentName name) {
            bridge = null;
            status.setText("Bridge binding died; no task operation performed");
        }
    };

    @Override public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        status = new TextView(this);
        root.addView(status);
        EditText id = new EditText(this);
        id.setInputType(2);
        id.setHint("Existing task ID (isolated fixture only)");
        root.addView(id);
        Button start = new Button(this);
        start.setText("Start one OEM task (manual)");
        root.addView(start);
        setContentView(root);
        Button probe = new Button(this);
        probe.setText("Probe separate bridge process (read only)");
        root.addView(probe);
        probe.setOnClickListener(v -> {
            if (!bound) {
                bound = bindService(new Intent(this, OemBridgeService.class),
                        bridgeConnection, Context.BIND_AUTO_CREATE);
                if (!bound) status.setText("Bridge binding denied");
            } else if (bridge != null) {
                Message request = Message.obtain(null, OemBridgeService.MSG_READ_STATUS);
                request.replyTo = reply;
                try { bridge.send(request); }
                catch (RemoteException e) { status.setText("Bridge IPC failed"); }
            }
        });
        boolean permitted = getPackageManager().checkPermission(OEM_PERMISSION, getPackageName())
                == PackageManager.PERMISSION_GRANTED;
        ApplicationInfo info = getApplicationInfo();
        boolean system = (info.flags & (ApplicationInfo.FLAG_SYSTEM | ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) != 0;
        boolean classPresent;
        try { Class.forName("com.oplus.flexiblewindow.FlexibleTaskView"); classPresent = true; }
        catch (Throwable ignored) { classPresent = false; }
        String permissionOwner = "unknown";
        int protection = -1;
        try {
            PermissionInfo p = getPackageManager().getPermissionInfo(OEM_PERMISSION, 0);
            permissionOwner = p.packageName;
            protection = p.protectionLevel;
        } catch (PackageManager.NameNotFoundException ignored) {
            permissionOwner = "not declared on this device";
        }
        // No verified OEM return protocol or host-death rollback yet.
        // Even a privileged granted process must not mutate an existing task.
        final boolean admitted = false;
        status.setText("OEM permission granted=" + permitted + "; system app=" + system
                + "; OEM class present=" + classPresent
                + "; permission owner=" + permissionOwner
                + "; protection=0x" + Integer.toHexString(protection)
                + ". Task mutation DISABLED until return and death recovery are proven.");
        start.setEnabled(admitted);
        start.setOnClickListener(v -> {
            if (!admitted || taskView != null) return;
            int taskId;
            try { taskId = Integer.parseInt(id.getText().toString()); }
            catch (NumberFormatException e) { status.setText("Invalid task ID"); return; }
            if (taskId < 0) { status.setText("Task ID must be nonnegative"); return; }
            attachSingleTask(taskId);
        });
    }

    private void attachSingleTask(int taskId) {
        try {
            Class<?> cls = Class.forName("com.oplus.flexiblewindow.FlexibleTaskView");
            Constructor<?> ctor = cls.getConstructor(android.content.Context.class);
            Object oem = ctor.newInstance(this);
            if (!(oem instanceof View)) throw new IllegalStateException("OEM host not a View");
            // OEM PMS110 framework-1.jar defines init(Bundle), and two launch/detach flags.
            Bundle options = new Bundle();
            options.putInt("taskId", taskId);
            options.putBoolean("flexible_key_remove_task_detach", false);
            options.putBoolean("allow_task_detach_from_embedding", true);
            options.putParcelable("launchBounds", requestedBounds);
            Method init = cls.getMethod("init", Bundle.class);
            init.invoke(oem, options);
            taskView = (View) oem;
            root.addView(taskView, new LinearLayout.LayoutParams(-1, 0, 1));
            status.setText("OEM host attached; NOT a verified task appearance or safe-return receipt");
        } catch (Throwable e) {
            status.setText("OEM host failed closed: " + e.getClass().getSimpleName());
            taskView = null;
        }
    }

    @Override protected void onDestroy() {
        // No task mutation is enabled; nothing to detach or release.
        if (bound) { unbindService(bridgeConnection); bound = false; }
        super.onDestroy();
    }
}
