package workbench2.nativelab.identity;
import android.app.ActivityManager;
/** Compiled solely against official Android 35 TEST SDK; no reflection or exemption. */
public final class TaskIdentity {
    public static Object running(ActivityManager.RunningTaskInfo info) { return info.getToken(); }
    public static Object recent(ActivityManager.RecentTaskInfo info) { return info.getToken(); }
}
