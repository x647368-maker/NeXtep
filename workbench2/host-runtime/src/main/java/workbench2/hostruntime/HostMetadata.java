package workbench2.hostruntime;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

/** Metadata only. Never initialize target classes, invoke methods, or read fields. */
final class HostMetadata {
    static final String HOST = "com.oplus.pscanvas";
    static final String MODEL = "PMS110";
    static final String FIRMWARE = "OnePlus/PMS110/OP64F1L1:17/CP2A.260605.016/C.147dc4b_1201fc3_11f9c73:user/release-keys";
    static final int MAX_METHODS = 40;
    static final Set<String> NAMES = Set.of("init", "setListener", "resize", "release");
    static final String[] TYPES = {
        "com.oplus.flexiblewindow.FlexibleTaskView",
        "com.oplus.flexiblewindow.FlexibleTaskView$Listener"
    };

    private HostMetadata() {}

    static boolean matches(String pkg, String process, boolean first, boolean systemServer,
            boolean systemApp, boolean uidMatches, String model, int sdk, String firmware) {
        return HOST.equals(pkg) && HOST.equals(process) && first && !systemServer
                && systemApp && uidMatches && MODEL.equals(model) && sdk == 37
                && FIRMWARE.equals(firmware);
    }

    static List<String> inspect(ClassLoader loader, String binaryName) {
        try {
            Class<?> type = Class.forName(binaryName, false, loader);
            if (!Modifier.isPublic(type.getModifiers())) return List.of("NOT_PUBLIC");
            ArrayList<String> signatures = new ArrayList<>();
            for (Method method : type.getMethods()) {
                if (!Modifier.isPublic(method.getModifiers())
                        || !NAMES.contains(method.getName())) continue;
                if (signatures.size() >= MAX_METHODS) return List.of("METADATA_LIMIT");
                StringBuilder s = new StringBuilder(method.getName()).append('(');
                Class<?>[] args = method.getParameterTypes();
                for (int i = 0; i < args.length; i++) {
                    if (i != 0) s.append(',');
                    s.append(args[i].getName());
                }
                s.append("):").append(method.getReturnType().getName());
                s.append(Modifier.isStatic(method.getModifiers()) ? ":static" : ":instance");
                signatures.add(s.toString());
            }
            Collections.sort(signatures);
            if (signatures.isEmpty()) return List.of("NO_MATCHING_PUBLIC_METHODS");
            return List.copyOf(signatures);
        } catch (ClassNotFoundException | LinkageError | SecurityException e) {
            return List.of("UNRESOLVED:" + e.getClass().getSimpleName());
        }
    }
}
