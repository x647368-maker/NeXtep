package workbench2.hostruntime;

import android.content.pm.ApplicationInfo;
import android.os.Build;
import android.os.Process;
import android.util.Log;
import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam;
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam;
import java.util.concurrent.atomic.AtomicBoolean;

/** Isolated, opt-in metadata observation. No hook registration or host method call. */
public final class HostRuntimeEntry extends XposedModule {
    private final AtomicBoolean started = new AtomicBoolean();
    private String process = "";
    private boolean systemServer = true;

    @Override public void onModuleLoaded(ModuleLoadedParam param) {
        process = param.getProcessName();
        systemServer = param.isSystemServer();
    }

    @Override public void onPackageReady(PackageReadyParam param) {
        if (!HostMetadata.HOST.equals(param.getPackageName())
                || !HostMetadata.HOST.equals(process) || systemServer) return;
        ApplicationInfo info = param.getApplicationInfo();
        if (!HostMetadata.matches(param.getPackageName(), process, param.isFirstPackage(),
                systemServer, (info.flags & (ApplicationInfo.FLAG_SYSTEM |
                    ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) != 0,
                info.uid == Process.myUid(), Build.MODEL, Build.VERSION.SDK_INT,
                Build.FINGERPRINT)) {
            log(Log.INFO, "WB2HostRuntime", "REFUSED_IDENTITY source=" + BuildConfig.SOURCE_SHA);
            return;
        }
        if (!started.compareAndSet(false, true)) return;
        final ClassLoader loader = param.getClassLoader();
        try {
            Thread worker = new Thread(() -> {
                try {
                    log(Log.INFO, "WB2HostRuntime", "BEGIN source=" + BuildConfig.SOURCE_SHA
                        + " model=" + Build.MODEL + " sdk=" + Build.VERSION.SDK_INT
                        + " firmware=" + Build.FINGERPRINT);
                    for (String type : HostMetadata.TYPES) {
                        for (String observation : HostMetadata.inspect(loader, type)) {
                            log(Log.INFO, "WB2HostRuntime", type + " " + observation);
                        }
                    }
                    log(Log.INFO, "WB2HostRuntime",
                        "END metadata_only=true oem_calls=0 hooks=0 authorization=UNKNOWN"
                        + " return=UNKNOWN input=UNKNOWN production_admitted=false"
                        + " host_signer_runtime_verified=false");
                } catch (RuntimeException | LinkageError e) {
                    log(Log.WARN, "WB2HostRuntime", "INCOMPLETE:" + e.getClass().getSimpleName());
                }
            }, "wb2-host-metadata-once");
            worker.setDaemon(true);
            worker.start();
        } catch (RuntimeException | LinkageError e) {
            log(Log.WARN, "WB2HostRuntime", "WORKER_NOT_STARTED:" + e.getClass().getSimpleName());
        }
    }
}
