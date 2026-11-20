package workbench2.hostruntime;

import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.graphics.Rect;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.os.SystemClock;
import android.util.Log;
import android.view.View;
import io.github.libxposed.api.XposedInterface.HookHandle;
import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam;
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

/** Authorized isolated experiment only. Observe normal system calls; proceed once, unchanged. */
public final class HostRuntimeEntry extends XposedModule {
    private static final String CERT = "e49802409584ce53152a9000820a51e4fa8a723b7bcc263e335240acf100bf9e";
    private final AtomicBoolean started = new AtomicBoolean();
    private final ArrayList<HookHandle> handles = new ArrayList<>();
    private String process = "";
    private boolean systemServer = true;
    private SessionFilter session;
    private Handler handler;
    private final Runnable expire = () -> close("WINDOW_EXPIRED");

    private void evidence(String event) {
        try { log(Log.INFO, null, "WB2Dual source="+BuildConfig.SOURCE_SHA
                    +" host="+process+" elapsed="+SystemClock.elapsedRealtime()+" "+event); }
        catch (RuntimeException | LinkageError ignored) {}
    }
    @Override public void onModuleLoaded(ModuleLoadedParam p) {
        process=p.getProcessName(); systemServer=p.isSystemServer();
    }
    @Override public void onPackageReady(PackageReadyParam p) {
        ApplicationInfo info=p.getApplicationInfo();
        boolean system=(info.flags&(ApplicationInfo.FLAG_SYSTEM|ApplicationInfo.FLAG_UPDATED_SYSTEM_APP))!=0;
        if (!SessionFilter.exactHost(p.getPackageName(),process,p.isFirstPackage(),systemServer,
                system,info.uid==Process.myUid(),Build.MODEL,Build.VERSION.SDK_INT,Build.FINGERPRINT)) return;
        if (!started.compareAndSet(false,true)) return;
        try {
            Class<?> type=Class.forName("com.oplus.flexiblewindow.FlexibleTaskView",false,p.getClassLoader());
            Method init=SessionFilter.LAUNCHER.equals(process)
                ? type.getMethod("init",Bundle.class)
                : type.getMethod("init",int.class,float.class,float.class,Rect.class,Intent.class,int.class,int.class);
            Method release=type.getMethod("release");
            if (!valid(init)||!valid(release)||!View.class.isAssignableFrom(type)) {
                evidence("REFUSED_CONTRACT"); return;
            }
            session=new SessionFilter(SystemClock.elapsedRealtime());
            handler=new Handler(Looper.getMainLooper());
            // Serialize registration/cleanup against the timeout. No worker or polling.
            synchronized (this) {
                handles.add(hook(release).intercept(chain -> {
                    Object obj=chain.getThisObject();
                    Object result=chain.proceed();
                    try {
                        if (session.finish(obj,SystemClock.elapsedRealtime())) {
                            evidence("OWN_VIEW_RELEASE_RETURNED");
                            handler.post(() -> close("ONE_SESSION_COMPLETE"));
                        }
                    } catch (RuntimeException | LinkageError ignored) {}
                    return result;
                }));
                handles.add(hook(init).intercept(chain -> {
                    boolean matched=false;
                    try {
                        Object obj=chain.getThisObject();
                        long now=SystemClock.elapsedRealtime();
                        if (session.open(now) && SessionFilter.ownArgs(chain.getArgs())
                                && obj instanceof View v && verified(v)) {
                            matched=session.start(obj,now);
                            if(matched) evidence("OWN_VIEW_INIT_ENTER signer_verified=true");
                        }
                    } catch (RuntimeException | LinkageError ignored) {}
                    // Do not catch, replace, repeat, or alter original behavior.
                    Object result=chain.proceed();
                    if(matched) evidence("OWN_VIEW_INIT_RETURNED");
                    return result;
                }));
                if(!handler.postDelayed(expire,SessionFilter.WINDOW_MS)) throw new IllegalStateException();
            }
            evidence("ARMED_180S no_oem_calls=true production_admitted=false");
        } catch (Exception | LinkageError e) {
            close("REFUSED_"+e.getClass().getSimpleName());
        }
    }
    private static boolean valid(Method m) {
        return Modifier.isPublic(m.getModifiers()) && !Modifier.isStatic(m.getModifiers())
            && m.getReturnType()==void.class;
    }
    private boolean verified(View v) {
        try {
            PackageInfo p=v.getContext().getPackageManager().getPackageInfo(process,PackageManager.GET_SIGNING_CERTIFICATES);
            if (p.applicationInfo==null || p.applicationInfo.uid!=Process.myUid()
                || p.getLongVersionCode()!=(SessionFilter.LAUNCHER.equals(process)?170030013L:20L)
                || p.signingInfo==null) return false;
            Signature[] signatures=p.signingInfo.getApkContentsSigners();
            if(signatures==null||signatures.length!=1) return false;
            byte[] digest=MessageDigest.getInstance("SHA-256").digest(signatures[0].toByteArray());
            StringBuilder hex=new StringBuilder(64);
            for(byte b:digest) { hex.append(Character.forDigit((b>>>4)&15,16)); hex.append(Character.forDigit(b&15,16)); }
            return CERT.contentEquals(hex);
        } catch (Exception | LinkageError ignored) { return false; }
    }
    private synchronized void close(String reason) {
        if(session!=null) session.clear();
        if(handler!=null) handler.removeCallbacks(expire);
        boolean ok=true;
        for(HookHandle h:handles) {
            try { h.unhook(); } catch (RuntimeException | LinkageError e) { ok=false; }
        }
        handles.clear();
        evidence(reason+" unhooked="+ok+" authorization=UNKNOWN return=UNKNOWN");
    }
}
