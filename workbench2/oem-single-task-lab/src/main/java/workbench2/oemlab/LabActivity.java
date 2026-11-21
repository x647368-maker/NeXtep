package workbench2.oemlab;

import android.Manifest;
import android.app.Activity;
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

/**
 * Isolated proof-of-concept. No TaskOrganizer, hooks, system_server code, or background service.
 * The button is deliberately disabled unless the OEM permission is genuinely granted.
 * NEVER ship this diagnostic APK as an update to the original Atomic Workbench package.
 */
public final class LabActivity extends Activity {
    private static final String OEM_PERMISSION = "com.oplus.permission.safe.WINDOW";
    private final Rect requestedBounds = new Rect(0, 0, 500, 800);
    private View taskView;
    private Method releaseMethod;
    private TextView status;
    private LinearLayout root;

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
        boolean permitted = getPackageManager().checkPermission(OEM_PERMISSION, getPackageName())
                == PackageManager.PERMISSION_GRANTED;
        ApplicationInfo info = getApplicationInfo();
        boolean system = (info.flags & (ApplicationInfo.FLAG_SYSTEM | ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) != 0;
        boolean classPresent;
        try { Class.forName("com.oplus.flexiblewindow.FlexibleTaskView"); classPresent = true; }
        catch (Throwable ignored) { classPresent = false; }
        final boolean admitted = permitted && system && classPresent;
        status.setText("OEM permission granted=" + permitted + "; system app=" + system
                + "; OEM class present=" + classPresent + ". No operation until all true.");
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
            releaseMethod = cls.getMethod("release");
            init.invoke(oem, options);
            taskView = (View) oem;
            root.addView(taskView, new LinearLayout.LayoutParams(-1, 0, 1));
            status.setText("OEM host attached; NOT a verified task appearance or safe-return receipt");
        } catch (Throwable e) {
            status.setText("OEM host failed closed: " + e.getClass().getSimpleName());
            taskView = null;
            releaseMethod = null;
        }
    }

    @Override protected void onDestroy() {
        // A release call may have OEM-specific destructive semantics. Do not invoke it
        // until a safe-return contract is verified. This fixture is not deployable yet.
        super.onDestroy();
    }
}
