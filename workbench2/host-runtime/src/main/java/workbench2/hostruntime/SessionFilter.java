package workbench2.hostruntime;

import android.content.ComponentName;
import android.content.Intent;
import android.os.Bundle;
import java.lang.ref.WeakReference;
import java.util.List;

/** One own-fixture view per host process, no saved Intent or external task data. */
final class SessionFilter {
    static final String PKG = "io.github.x647368maker.atomicworkbench.hostruntime";
    static final String ACTIVITY = "workbench2.hostruntime.SessionActivity";
    static final String LAUNCHER = "com.android.launcher";
    static final long WINDOW_MS = 180_000L;
    static boolean host(String p) { return HostMetadata.HOST.equals(p) || LAUNCHER.equals(p); }
    static boolean exactHost(String p, String proc, boolean first, boolean ss,
            boolean system, boolean uid, String model, int sdk, String fp) {
        return host(p) && p.equals(proc) && first && !ss && system && uid
            && HostMetadata.MODEL.equals(model) && sdk == 37 && HostMetadata.FIRMWARE.equals(fp);
    }
    static boolean own(Intent i) {
        return i != null && new ComponentName(PKG, ACTIVITY).equals(i.getComponent());
    }
    static boolean ownArgs(List<Object> args) {
        try {
            // Only the two observed public signatures, no arbitrary object introspection.
            if (args.size()==1 && args.get(0) instanceof Bundle b)
                return own(new Bundle(b).getParcelable("intent", Intent.class));
            if (args.size()==7 && args.get(4) instanceof Intent i) return own(i);
        } catch (RuntimeException | LinkageError ignored) {}
        return false;
    }
    private final long deadline;
    private WeakReference<Object> view = new WeakReference<>(null);
    private boolean used;
    SessionFilter(long now) { deadline = now + WINDOW_MS; }
    synchronized boolean open(long now) { return !used && now < deadline; }
    synchronized boolean start(Object v, long now) {
        if (v==null || !open(now)) return false;
        used=true; view=new WeakReference<>(v); return true;
    }
    synchronized boolean finish(Object v, long now) {
        if (v==null || now>=deadline || view.get()!=v) return false;
        view.clear(); return true;
    }
    synchronized void clear() { used=true; view.clear(); }
}
