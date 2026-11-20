package workbench2.field

import android.app.Activity
import android.app.ActivityManager
import android.app.ActivityOptions
import android.app.Application
import android.content.ComponentName
import android.content.Intent
import android.graphics.Rect
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.view.MotionEvent
import android.widget.Button
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.atomic.AtomicReference
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

// One disposable task, stock emulator only. Test harness has standard adb/UiAutomation
// authority, never exported to the product. No feature, permission or security changes.
class SystemWindowResizeTest {
    private val instr = InstrumentationRegistry.getInstrumentation()
    private val ctx = instr.targetContext
    private val live = AtomicReference<NormalWindowSessionActivity?>()
    private val target = ComponentName(ctx, NormalWindowSessionActivity::class.java)
    private val am = requireNotNull(ctx.getSystemService(ActivityManager::class.java))

    private fun shell(command: String): String =
        ParcelFileDescriptor.AutoCloseInputStream(instr.uiAutomation.executeShellCommand(command))
            .bufferedReader().use { it.readText() }

    private fun awaitActivity(): NormalWindowSessionActivity {
        val end = SystemClock.elapsedRealtime() + 8000
        while (SystemClock.elapsedRealtime() < end) {
            instr.waitForIdleSync()
            live.get()?.let { a ->
                val ready = AtomicReference(false)
                instr.runOnMainSync {
                    ready.set(!a.isDestroyed && !a.isFinishing &&
                        a.hasWindowFocus() && a.window.decorView.isShown)
                }
                if (ready.get()) return a
            }
            SystemClock.sleep(80)
        }
        error("No focused own fixture")
    }

    private data class State(val task: Int, val instance: String, val marker: String,
                             val clicks: Int, val bounds: Rect, val display: Int)
    private fun state(a: NormalWindowSessionActivity): State {
        val result = AtomicReference<State>()
        instr.runOnMainSync {
            check(!a.isDestroyed && a.isInMultiWindowMode)
            val report = JSONObject(a.observationReport())
            val current = report.getJSONObject("current")
            result.set(State(a.taskId, current.getString("instance"), report.getString("session"),
                current.getInt("clicks"), Rect(a.windowManager.currentWindowMetrics.bounds),
                current.getInt("display_id")))
        }
        return requireNotNull(result.get())
    }

    private fun own(id: Int) = am.appTasks.any {
        val info = it.taskInfo
        info.taskId == id && info.topActivity == target
    }

    // Desktop freeform tasks may be child tasks inside a system-owned Desk root.
    // Verify the exact ActivityRecord/task id and its containing Task object rather
    // than assuming every app task is itself a top-level "* Task" entry.
    private fun systemTask(id: Int): Pair<String, Rect> {
        check(own(id)) { "Not the exact disposable fixture task" }
        val dump = shell("dumpsys activity activities")
        val lines = dump.lineSequence().toList()
        val activityHeader = Regex("""^[ \t]*\* Hist[ \t]+#\d+:[ \t]+ActivityRecord\{([0-9a-fA-F]+)[ \t]+u\d+[ \t]+[^\r\n]*\bt$id\}""")
        val activityMatches = lines.withIndex().filter { activityHeader.matches(it.value) }
        check(activityMatches.size == 1) {
            "No unique system ActivityRecord for task=$id count=${activityMatches.size}"
        }
        val activityIndex = activityMatches.single().index
        val activityLine = lines[activityIndex]
        val shortClass = target.className.substringAfterLast('.')
        check(activityLine.contains(target.packageName) &&
            (activityLine.contains(target.className) || activityLine.contains("/.$shortClass"))) {
            "System ActivityRecord does not match the disposable fixture: $activityLine"
        }
        val activityObject = requireNotNull(activityHeader.matchEntire(activityLine)).groupValues[1]

        val taskHeader = Regex("""^[ \t]*\*?[ \t]*Task\{([0-9a-fA-F]+) #(\d+)(?:\s|\})[^\r\n]*""")
        val activityIndent = activityLine.takeWhile { it == ' ' || it == '\t' }.length
        val taskHeaders = lines.take(activityIndex).withIndex().filter { (_, line) ->
            taskHeader.matches(line) && line.takeWhile { it == ' ' || it == '\t' }.length < activityIndent
        }
        val containing = taskHeaders.lastOrNull()
            ?: error("No containing system Task object for ActivityRecord task=$id")
        val taskMatch = requireNotNull(taskHeader.matchEntire(containing.value))
        val taskId = taskMatch.groupValues[2].toInt()
        check(taskHeaders.any { it.value.contains("mode=freeform") }) {
            "No freeform Task ancestor for ActivityRecord task=$id: ${taskHeaders.map { it.value }}"
        }

        // ActivityWindowInfo is the system's own task-bounds report for this ActivityRecord.
        // Fall back only to the same record's CurrentConfiguration mBounds; never use
        // the root Desk bounds or the host Activity's requested launch bounds.
        val recordLines = lines.drop(activityIndex + 1).takeWhile { line ->
            !Regex("""^[ \t]*\* Hist[ \t]+#\d+:""").containsMatchIn(line) &&
                !taskHeader.matches(line)
        }
        val record = recordLines.joinToString("\n")
        val bounds = Regex(
            """taskBounds=Rect\((-?\d+),\s*(-?\d+)\s*-\s*(-?\d+),\s*(-?\d+)\)"""
        ).find(record) ?: Regex(
            """CurrentConfiguration=.*?mBounds=Rect\((-?\d+),\s*(-?\d+)\s*-\s*(-?\d+),\s*(-?\d+)\)"""
        ).find(record) ?: error("No system-reported ActivityRecord task bounds for task=$id")
        val v = bounds.groupValues.drop(1).map { it.toInt() }
        val identity = "${taskMatch.groupValues[1]}#${taskId}/leaf#${id}"
        Log.i("WB2SystemWindow", "SYSTEM_TASK task=$id parentOrRoot=${taskId} object=${taskMatch.groupValues[1]} activityRecord=${activityObject} bounds=${Rect(v[0],v[1],v[2],v[3])}")
        return identity to Rect(v[0],v[1],v[2],v[3])
    }

    private fun click(a: NormalWindowSessionActivity, before: State): State {
        val center = FloatArray(2)
        instr.runOnMainSync {
            check(!a.isDestroyed && a.hasWindowFocus())
            val b = a.window.decorView.findViewWithTag<Button>("session_increment")
            val r = Rect()
            check(b.isShown && b.getLocalVisibleRect(r) && r.width() > 0 && r.height() > 0)
            val xy = IntArray(2)
            b.getLocationOnScreen(xy)
            center[0] = xy[0] + r.exactCenterX()
            center[1] = xy[1] + r.exactCenterY()
        }
        val t = SystemClock.uptimeMillis()
        for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
            val ev = requireNotNull(MotionEvent.obtain(t, SystemClock.uptimeMillis(), action, center[0], center[1], 0))
            try { instr.sendPointerSync(ev) } finally { ev.recycle() }
        }
        instr.waitForIdleSync()
        val after = state(awaitActivity())
        assertEquals(before.task, after.task)
        assertEquals(before.marker, after.marker)
        assertEquals(before.clicks + 1, after.clicks)
        return after
    }

    private fun waitGeometry(id: Int, identity: String, expected: Rect): State {
        val deadline = SystemClock.elapsedRealtime() + 8000
        while (SystemClock.elapsedRealtime() < deadline) {
            val a = awaitActivity()
            val s = state(a)
            check(s.task == id && s.display == 0)
            val (key,bounds) = systemTask(id)
            check(key == identity) { "The system task object changed" }
            if (bounds == expected && s.bounds == expected) return s
            SystemClock.sleep(100)
        }
        error("System geometry or real Activity bounds did not settle: $expected")
    }

    private fun screenshot(name: String) {
        require(name in setOf("before","changed","restored"))
        shell("screencap -p /data/local/tmp/wb2-system-window-$name.png")
        // Raw physical-emulator screen artifact for later review; not a visual substitute.
    }

    @Test fun existingOwnTaskChangesAndRestoresNativeGeometry() {
        assertTrue("Native freeform feature required, no configuration overrides",
            ctx.packageManager.hasSystemFeature("android.software.freeform_window_management"))
        val app = ctx.applicationContext as Application
        val cb = object : Application.ActivityLifecycleCallbacks {
            override fun onActivityCreated(a: Activity, b: Bundle?) {}
            override fun onActivityStarted(a: Activity) {}
            override fun onActivityResumed(a: Activity) {
                if(a is NormalWindowSessionActivity) live.set(a)
            }
            override fun onActivityPaused(a: Activity) {}
            override fun onActivityStopped(a: Activity) {}
            override fun onActivitySaveInstanceState(a: Activity,b: Bundle) {}
            override fun onActivityDestroyed(a: Activity) {
                if(a is NormalWindowSessionActivity) live.compareAndSet(a,null)
            }
        }
        val previous = am.appTasks.map { it.taskInfo.taskId }.toSet()
        app.registerActivityLifecycleCallbacks(cb)
        var id: Int? = null
        var identity: String? = null
        var original: Rect? = null
        var restored = false
        try {
            val dm = ctx.resources.displayMetrics
            val requested = Rect(dm.widthPixels/8,dm.heightPixels/8,
                dm.widthPixels*7/8,dm.heightPixels*7/8)
            val a = instr.startActivitySync(Intent(ctx,NormalWindowSessionActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK),
                ActivityOptions.makeBasic().setLaunchBounds(requested).toBundle()) as NormalWindowSessionActivity
            live.compareAndSet(null,a)
            val initial = state(awaitActivity())
            check(initial.task !in previous && own(initial.task))
            id = initial.task
            assertEquals(0, initial.display)
            val (key,bounds) = systemTask(initial.task)
            identity = key
            original = Rect(bounds)
            assertEquals(bounds,initial.bounds)
            check(bounds.width() >= 320 && bounds.height() >= 320)
            // Resize within the original visible geometry, no forced compatibility overrides.
            val changed = Rect(bounds.left + 16,bounds.top + 16,bounds.right - 16,bounds.bottom - 16)
            val first = click(awaitActivity(),initial)
            screenshot("before")
            check(own(initial.task))
            shell("am task resize ${initial.task} ${changed.left} ${changed.top} ${changed.right} ${changed.bottom}")
            val resized = waitGeometry(initial.task,key,changed)
            assertEquals(first.marker,resized.marker)
            assertEquals(first.clicks,resized.clicks)
            val second = click(awaitActivity(),resized)
            screenshot("changed")
            check(own(initial.task))
            shell("am task resize ${initial.task} ${bounds.left} ${bounds.top} ${bounds.right} ${bounds.bottom}")
            val returned = waitGeometry(initial.task,key,bounds)
            assertEquals(second.marker,returned.marker)
            assertEquals(second.clicks,returned.clicks)
            val third = click(awaitActivity(),returned)
            screenshot("restored")
            restored = true
            android.util.Log.i("WB2SystemWindow","GEOMETRY_OK task=${initial.task} display=0 " +
                "before=$bounds changed=$changed restored=${third.bounds} instances=" +
                "${initial.instance},${resized.instance},${returned.instance} clicks=${third.clicks}")
        } finally {
            // Restore only the independently reidentified disposable task if an adjustment began.
            val task = id
            val key = identity
            val b = original
            try {
                if (!restored && task != null && key != null && b != null && own(task)) {
                    val (actual,_) = systemTask(task)
                    if (actual == key) shell("am task resize $task ${b.left} ${b.top} ${b.right} ${b.bottom}")
                }
            } finally {
                instr.runOnMainSync {
                    val a = live.get()
                    if (a != null && !a.isDestroyed && a.taskId !in previous &&
                        (task == null || a.taskId == task)) a.finishAndRemoveTask()
                }
                app.unregisterActivityLifecycleCallbacks(cb)
            }
        }
        assertTrue(restored)
        val end = SystemClock.elapsedRealtime()+5000
        while (id != null && am.appTasks.any { it.taskInfo.taskId == id } &&
            SystemClock.elapsedRealtime()<end) SystemClock.sleep(80)
        assertFalse(am.appTasks.any { it.taskInfo.taskId == id })
    }
}
