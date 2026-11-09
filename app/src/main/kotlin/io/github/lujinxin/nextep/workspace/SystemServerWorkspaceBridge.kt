package io.github.x647368maker.atomicworkbench.workspace

import android.content.Context
import android.database.ContentObserver
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Display
import io.github.x647368maker.atomicworkbench.logging.NeXtepLog
import java.util.concurrent.atomic.AtomicBoolean

object SystemServerWorkspaceBridge {
    private const val ACTIVE_KEY = "atomic_workspace_active_v1"
    private const val WIDTH_KEY = "atomic_workspace_screen_width_v1"
    private const val HEIGHT_KEY = "atomic_workspace_screen_height_v1"
    private const val SIDEBAR_SIDE_KEY = "atomic_workspace_sidebar_side_v1"
    private const val GEOMETRY_KEY = "atomic_workspace_geometry_v1"
    private val geometryObservationStarted = AtomicBoolean(false)
    @Volatile private var observedGeometry: WorkspaceGeometry? = null

    /** Subscribe once in system_server. WM animation callbacks then read only RAM. */
    fun startGeometryObservation(context: Context) {
        if (!geometryObservationStarted.compareAndSet(false, true)) return
        val handler = Handler(Looper.getMainLooper())
        handler.post {
            runCatching {
                val resolver = context.contentResolver
                fun refresh() { observedGeometry = readActiveGeometry(context) }
                val observer = object : ContentObserver(handler) {
                    override fun onChange(selfChange: Boolean) = refresh()
                }
                resolver.registerContentObserver(Settings.Global.getUriFor(ACTIVE_KEY), false, observer)
                resolver.registerContentObserver(Settings.Global.getUriFor(GEOMETRY_KEY), false, observer)
                refresh()
            }.onFailure {
                geometryObservationStarted.set(false)
                NeXtepLog.warn("system_server_bridge", "Geometry observation unavailable", it)
            }
        }
    }

    fun observedActiveGeometry(): WorkspaceGeometry? = observedGeometry

    fun publishLayout(context: Context, profile: WorkspaceLayoutProfile) {
        runCatching {
            Settings.Global.putString(context.contentResolver, "atomic_workspace_layout_profile_v2", profile.encode())
        }.onFailure { NeXtepLog.warn("system_server_bridge", "Unable to persist recorded layout", it) }
    }

    fun publish(context: Context, active: Boolean, geometry: WorkspaceGeometry? = null) {
        runCatching {
            val resolver = context.contentResolver
            geometry?.let {
                Settings.Global.putInt(resolver, WIDTH_KEY, it.screenWidth)
                Settings.Global.putInt(resolver, HEIGHT_KEY, it.screenHeight)
                Settings.Global.putString(resolver, SIDEBAR_SIDE_KEY, it.sidebarSide.name)
                // Publish one complete snapshot so another process cannot combine
                // new navigation insets with an old rotation or control thickness.
                Settings.Global.putString(resolver, GEOMETRY_KEY, WorkspaceGeometryCodec.encode(it))
            }
            Settings.Global.putInt(resolver, ACTIVE_KEY, if (active) 1 else 0)
        }.onFailure { error ->
            NeXtepLog.warn("system_server_bridge", "Unable to publish active=$active", error)
        }
    }

    fun activePhysicalAspectRatio(context: Context): Float? = runCatching {
        val resolver = context.contentResolver
        if (Settings.Global.getInt(resolver, ACTIVE_KEY, 0) != 1) return@runCatching null
        Settings.Global.getString(resolver, GEOMETRY_KEY)?.let(::decodeGeometry)?.let { recorded ->
            val width = recorded.screenWidth
            val height = recorded.screenHeight
            return@runCatching if (height > width) height.toFloat() / width else null
        }
        val width = Settings.Global.getInt(resolver, WIDTH_KEY, 0)
        val height = Settings.Global.getInt(resolver, HEIGHT_KEY, 0)
        if (width <= 0 || height <= width) null else height.toFloat() / width.toFloat()
    }.getOrNull()

    fun isWorkspaceActive(context: Context): Boolean =
        runCatching { readWorkspaceActive(context) }.getOrDefault(false)

    /** Register off the WM traversal thread; null keeps callers on their safe fallback. */
    fun observeWorkspaceActive(
        context: Context,
        handler: Handler,
        onChanged: (Boolean?) -> Unit,
    ): ContentObserver {
        fun refresh() {
            runCatching { readWorkspaceActive(context) }
                .onSuccess { onChanged(it) }
                .onFailure {
                    onChanged(null)
                    NeXtepLog.warn("system_server_bridge", "Unable to observe workspace activity", it)
                }
        }
        val observer = object : ContentObserver(handler) {
            override fun onChange(selfChange: Boolean) {
                refresh()
            }
        }
        // Subscribe before reading to avoid missing an activation during initialization.
        context.contentResolver.registerContentObserver(
            Settings.Global.getUriFor(ACTIVE_KEY), false, observer,
        )
        refresh()
        return observer
    }

    private fun readWorkspaceActive(context: Context): Boolean {
        val resolver = context.contentResolver
        val uncachedValue = resolver.call(
            Settings.AUTHORITY,
            "GET_global",
            ACTIVE_KEY,
            null,
        )?.getString(Settings.NameValueTable.VALUE)
        return (uncachedValue?.toIntOrNull()
            ?: Settings.Global.getInt(resolver, ACTIVE_KEY, 0)) == 1
    }

    fun sidebarSide(context: Context): SidebarSide = runCatching {
        Settings.Global.getString(context.contentResolver, SIDEBAR_SIDE_KEY)
            ?.let(SidebarSide::valueOf)
            ?: SidebarSide.RIGHT
    }.getOrDefault(SidebarSide.RIGHT)

    fun activeGeometry(context: Context): WorkspaceGeometry? =
        if (geometryObservationStarted.get()) observedGeometry else readActiveGeometry(context)

    private fun readActiveGeometry(context: Context): WorkspaceGeometry? = runCatching {
        if (!isWorkspaceActive(context)) return@runCatching null
        val resolver = context.contentResolver
        Settings.Global.getString(resolver, GEOMETRY_KEY)?.let { encoded ->
            decodeGeometry(encoded)?.let { return@runCatching it }
        }
        val width = Settings.Global.getInt(resolver, WIDTH_KEY, 0)
        val height = Settings.Global.getInt(resolver, HEIGHT_KEY, 0)
        if (width <= 1 || height <= 1) return@runCatching null
        WorkspaceGeometry.forDisplay(width, height, sidebarSide(context), context.resources.displayMetrics.density,
            displayRotation = context.getSystemService(DisplayManager::class.java)
                ?.getDisplay(Display.DEFAULT_DISPLAY)?.rotation ?: 0)
    }.getOrNull()

    private fun decodeGeometry(encoded: String): WorkspaceGeometry? = WorkspaceGeometryCodec.decode(encoded)
}
