package workbench2.hostruntime;

import android.content.pm.ApplicationInfo;
import android.os.Build;
import android.os.Process;
import android.util.Log;
import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam;
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam;
import io.github.libxposed.api.XposedModuleInterface.PackageLoadedParam;
import java.util.concurrent.atomic.AtomicBoolean;

/** Isolated, opt-in metadata observation. No hook registration or host method call. */
public final class HostRuntimeEntry extends XposedModule {
    private final AtomicBoolean started = new AtomicBoolean();
    private String process = "";
    private boolean systemServer = true;

    // The framework's default log tag is collected by Vector's module log.
    // Keep our identity in the message rather than using an uncollected custom tag.
    private void evidence(int level, String message) {
        log(level, null, "WB2HostRuntime " + message);
    }

    @Override public void onModuleLoaded(ModuleLoadedParam param) {
        process = param.getProcessName();
        systemServer = param.isSystemServer();
        evidence(Log.INFO, "LOADED source=" + BuildConfig.SOURCE_SHA
            + " targetProcess=" + HostMetadata.HOST.equals(process)
            + " systemServer=" + systemServer);
    }

    @Override public void onPackageLoaded(PackageLoadedParam param) {
        if (!HostMetadata.HOST.equals(param.getPackageName())) return;
        evidence(Log.INFO, "PACKAGE_LOADED first=" + param.isFirstPackage());
    }

    @Override public void onPackageReady(PackageReadyParam param) {
        if (!HostMetadata.HOST.equals(param.getPackageName())) return;
        evidence(Log.INFO, "PACKAGE_READY targetProcess=" + HostMetadata.HOST.equals(process)
            + " systemServer=" + systemServer + " first=" + param.isFirstPackage());
        if (!HostMetadata.HOST.equals(process) || systemServer) {
            evidence(Log.INFO, "REFUSED_PROCESS source=" + BuildConfig.SOURCE_SHA);
            return;
        }
        ApplicationInfo info = param.getApplicationInfo();
        boolean systemApp = (info.flags & (ApplicationInfo.FLAG_SYSTEM |
            ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) != 0;
        boolean uidMatches = info.uid == Process.myUid();
        if (!HostMetadata.matches(param.getPackageName(), process, param.isFirstPackage(),
                systemServer, systemApp, uidMatches, Build.MODEL, Build.VERSION.SDK_INT,
                Build.FINGERPRINT)) {
            evidence(Log.INFO, "REFUSED_IDENTITY source=" + BuildConfig.SOURCE_SHA
                + " first=" + param.isFirstPackage() + " systemApp=" + systemApp
                + " uidMatches=" + uidMatches + " modelMatch=" + HostMetadata.MODEL.equals(Build.MODEL)
                + " sdkMatch=" + (Build.VERSION.SDK_INT == 37)
                + " firmwareMatch=" + HostMetadata.FIRMWARE.equals(Build.FINGERPRINT));
            return;
        }
        if (!started.compareAndSet(false, true)) return;
        final ClassLoader loader = param.getClassLoader();
        try {
            Thread worker = new Thread(() -> {
                try {
                    evidence(Log.INFO, "BEGIN source=" + BuildConfig.SOURCE_SHA
                        + " model=" + Build.MODEL + " sdk=" + Build.VERSION.SDK_INT
                        + " firmware=" + Build.FINGERPRINT);
                    for (String type : HostMetadata.TYPES) {
                        for (String observation : HostMetadata.inspect(loader, type)) {
                            evidence(Log.INFO, type + " " + observation);
                        }
                    }
                    evidence(Log.INFO,
                        "END metadata_only=true oem_calls=0 hooks=0 authorization=UNKNOWN"
                        + " return=UNKNOWN input=UNKNOWN production_admitted=false"
                        + " host_signer_runtime_verified=false");
                } catch (RuntimeException | LinkageError e) {
                    evidence(Log.WARN, "INCOMPLETE:" + e.getClass().getSimpleName());
                }
            }, "wb2-host-metadata-once");
            worker.setDaemon(true);
            worker.start();
        } catch (RuntimeException | LinkageError e) {
            evidence(Log.WARN, "WORKER_NOT_STARTED:" + e.getClass().getSimpleName());
        }
    }
}
