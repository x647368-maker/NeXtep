package workbench2.nativelab.identity;
import android.app.ActivityManager;
import android.os.UserHandle;
/** Compiled solely against official Android 35 TEST SDK; no reflection or exemption. */
public final class TaskIdentity {
    public static int userId(UserHandle user) { return user.getIdentifier(); }
    public static Object running(ActivityManager.RunningTaskInfo info) { return info.getToken(); }
    public static Object recent(ActivityManager.RecentTaskInfo info) { return info.getToken(); }
}
