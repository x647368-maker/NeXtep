package workbench2.field

import android.app.ActivityManager
import android.content.ComponentName
import android.content.pm.PackageManager
import android.graphics.Point
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.Process
import android.os.SystemClock
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Test

/**
 * Official documented @TestApi members accessed only if normally available in
 * this instrumentation environment. No hidden-API exemption or organizer
 * registration. A single WCT changes two existing disposable leaf tasks.
 * Final test intentionally remains alive for external controller-only stop;
 * host verifies outcomes independently, never counts a killed test as JUnit pass.
 */
class SystemWindowBatchTest {
    private val ins=InstrumentationRegistry.getInstrumentation()
    private val ctx=ins.targetContext
    private val am=requireNotNull(ctx.getSystemService(ActivityManager::class.java))
    private val targetPkg="io.github.x647368maker.atomicworkbench.batchtarget"
    private val controllerPkg="io.github.x647368maker.atomicworkbench.batchcontroller"
    private val component=ComponentName(targetPkg,"workbench2.field.BatchTargetActivity")
    private fun shell(s:String)=ParcelFileDescriptor.AutoCloseInputStream(ins.uiAutomation.executeShellCommand(s))
        .bufferedReader().use { it.readText() }
    private fun log(s:String)=Log.i("WB2Batch",s)
    private fun rect(r:Rect)=listOf(r.left,r.top,r.right,r.bottom).joinToString(",")
    private fun await(label:String,predicate:()->Boolean) {
        val end=SystemClock.elapsedRealtime()+12000
        while(SystemClock.elapsedRealtime()<end) {
            if(predicate()) return
            SystemClock.sleep(200)
        }
        error("NO_GO "+label)
    }
    private fun states():Map<String,JSONObject> {
        val m=mutableMapOf<String,JSONObject>()
        for(line in shell("logcat -d -v raw -s WB2BatchTarget:I").lineSequence()) {
            if(!line.startsWith("{")) continue
            val j=JSONObject(line)
            if(j.optString("slot") in listOf("A","B")) m[j.getString("slot")]=j
        }
        return m
    }
    private fun active(j:JSONObject)=j.getBoolean("multi") && j.getInt("display")==0 &&
        j.getBoolean("shown") && j.getString("event")!="DESTROY"
    private fun exact(id:Int):ActivityManager.RunningTaskInfo {
        val t=am.getRunningTasks(100).filter { it.taskId==id }
        check(t.size==1 && t.single().topActivity==component && t.single().baseActivity==component) {
            "NO_GO exact independent target not queryable"
        }
        return t.single()
    }
    private fun record(id:Int):Pair<String,String> {
        val d=shell("dumpsys activity activities")
        val lines=d.lines()
        val pat=Regex("""^[ \t]*\* Hist[ \t]+#\d+:[ \t]+ActivityRecord\{([0-9a-fA-F]+)[ \t]+u\d+[ \t]+[^\r\n]*\bt"""+id+"""\}""")
        val h=lines.withIndex().filter { pat.matches(it.value) }
        check(h.size==1 && h.single().value.contains(targetPkg) &&
            h.single().value.contains("BatchTargetActivity")) { "NO_GO exact ActivityRecord" }
        val at=h.single()
        val text=lines.drop(at.index+1).takeWhile { !it.trimStart().startsWith("* Hist") &&
            !Regex("""^[ \t]*\*?[ \t]*Task\{""").containsMatchIn(it) }.joinToString("\n")
        val b=Regex("""taskBounds=Rect\((-?\d+),\s*(-?\d+)\s*-\s*(-?\d+),\s*(-?\d+)\)""").find(text)
            ?: Regex("""CurrentConfiguration=.*?mBounds=Rect\((-?\d+),\s*(-?\d+)\s*-\s*(-?\d+),\s*(-?\d+)\)""").find(text)
            ?: error("NO_GO system-reported bounds")
        return requireNotNull(pat.matchEntire(at.value)).groupValues[1] to b.groupValues.drop(1).joinToString(",")
    }
    @Test fun batchSwapAndAwaitIndependentControllerStop() {
        check(ctx.packageName==controllerPkg && Build.VERSION.SDK_INT==37 &&
            Build.FINGERPRINT.contains("generic",true).or(Build.FINGERPRINT.contains("sdk",true)).or(Build.FINGERPRINT.contains("emu",true)))
        check(ctx.packageManager.hasSystemFeature("android.software.freeform_window_management"))
        val tokenClass=Class.forName("android.window.WindowContainerToken")
        val wctClass=Class.forName("android.window.WindowContainerTransaction")
        val organizerClass=Class.forName("android.window.WindowOrganizer")
        val taskClass=Class.forName("android.app.TaskInfo")
        val tokenOf=taskClass.getMethod("getToken")
        val parentOf=taskClass.getMethod("getParentTaskId")
        val displayOf=taskClass.getMethod("getDisplayId")
        val setBounds=wctClass.getMethod("setBounds",tokenClass,Rect::class.java)
        val apply=organizerClass.getMethod("applyTransaction",wctClass)
        val permissions=arrayOf("android.permission.MANAGE_ACTIVITY_TASKS","android.permission.REAL_GET_TASKS")
        val ui=ins.uiAutomation
        ui.adoptShellPermissionIdentity(*permissions)
        try {
            check(permissions.all { ctx.checkSelfPermission(it)==PackageManager.PERMISSION_GRANTED }) { "NO_GO test permissions" }
            val organizer=organizerClass.getConstructor().newInstance()
            wctClass.getConstructor().newInstance() // constructor admission; no system mutation
            check(am.getRunningTasks(100).none { it.topActivity==component }) { "NO_GO fixture already exists" }
            log("TEST_API_ADMITTED no_registration=true")
            for(slot in listOf("A","B")) {
                val r=shell("am start -W -f 0x18000000 -n "+component.flattenToString()+" -d wb2://"+slot+" --es slot "+slot)
                check(!r.contains("Error") && !r.contains("Exception")) { r }
            }
            await("two distinct visible native fixtures") {
                val s=states()
                s.size==2 && s.values.all(::active) && s.values.map { it.getInt("task") }.distinct().size==2
            }
            val launched=states()
            val ids=listOf("A","B").associateWith { launched.getValue(it).getInt("task") }
            val tokens=ids.mapValues { requireNotNull(tokenOf.invoke(exact(it.value))) }
            check(tokens.getValue("A")!=tokens.getValue("B"))
            val parents=ids.mapValues { parentOf.invoke(exact(it.value)) }
            fun assertIdentity() {
                for((slot,id) in ids) {
                    val t=exact(id)
                    check(tokenOf.invoke(t)==tokens.getValue(slot) && parentOf.invoke(t)==parents.getValue(slot) &&
                        displayOf.invoke(t)==0) { "NO_GO system identity/parent/display changed" }
                }
            }
            fun batch(a:Rect,b:Rect) {
                assertIdentity()
                val t=wctClass.getConstructor().newInstance()
                setBounds.invoke(t,tokens.getValue("A"),a)
                setBounds.invoke(t,tokens.getValue("B"),b)
                apply.invoke(organizer,t)
                log("ONE_WCT_SUBMITTED tasks="+ids+" bounds="+rect(a)+";"+rect(b))
            }
            val size=Point()
            @Suppress("DEPRECATION")
            requireNotNull(ctx.getSystemService(DisplayManager::class.java)?.getDisplay(0)).getRealSize(size)
            val a=Rect(size.x*5/100,size.y*15/100,size.x*52/100,size.y*85/100)
            val b=Rect(size.x*57/100,size.y*15/100,size.x*95/100,size.y*85/100)
            check(a.width()>=320 && b.width()>=320 && a.height()>=320 && !Rect.intersects(a,b))
            fun geometry(ra:Rect,rb:Rect) {
                await("dual system and activity bounds") {
                    assertIdentity()
                    val s=states()
                    val expected=mapOf("A" to rect(ra),"B" to rect(rb))
                    expected.all { (slot,r)-> val j=s.getValue(slot)
                        active(j) && j.getInt("task")==ids.getValue(slot) && j.getString("bounds")==r &&
                            record(ids.getValue(slot)).second==r
                    }
                }
            }
            fun tap(slot:String) {
                val s=states(); val j=s.getValue(slot)
                val other=if(slot=="A")"B" else "A"
                check(active(j))
                val x=j.getInt("x"); val y=j.getInt("y")
                val r=j.getString("bounds").split(",").map(String::toInt)
                check(x in r[0] until r[2] && y in r[1] until r[3])
                shell("input tap "+x+" "+y)
                await("real input "+slot) {
                    val n=states()
                    n.getValue(slot).getInt("clicks")==j.getInt("clicks")+1 &&
                        n.getValue(other).getInt("clicks")==s.getValue(other).getInt("clicks")
                }
            }
            batch(a,b); geometry(a,b)
            tap("A"); tap("B")
            val before=states()
            val records=ids.mapValues { record(it.value).first }
            shell("screencap -p /data/local/tmp/wb2-batch-before.png")
            batch(b,a); geometry(b,a)
            val swapped=states()
            for(slot in ids.keys) {
                val o=before.getValue(slot); val n=swapped.getValue(slot)
                for(key in listOf("instance","marker")) check(o.getString(key)==n.getString(key)) { "NO_GO Activity/content recreation "+slot+" "+key }
                check(o.getInt("clicks")==n.getInt("clicks") && o.getInt("pid")==n.getInt("pid") &&
                    record(ids.getValue(slot)).first==records.getValue(slot))
            }
            tap("A"); tap("B"); assertIdentity()
            shell("screencap -p /data/local/tmp/wb2-batch-swapped.png")
            val final=states()
            for(slot in ids.keys) {
                val o=before.getValue(slot); val n=final.getValue(slot)
                check(o.getString("instance")==n.getString("instance") &&
                    o.getString("marker")==n.getString("marker") &&
                    n.getInt("clicks")==o.getInt("clicks")+1 && record(ids.getValue(slot)).first==records.getValue(slot))
            }
            val evidence=JSONObject().put("controller_pid",Process.myPid()).put("source_sha",BuildConfig.SOURCE_SHA)
            for(slot in ids.keys) evidence.put(slot,final.getValue(slot).put("activity_record",records.getValue(slot)))
            log("BATCH_GEOMETRY_AND_INPUT_OK")
            ui.dropShellPermissionIdentity()
            log("READY_FOR_CONTROLLER_DEATH "+evidence)
            // Host must stop this process while both independent targets remain live.
            // If it does not, this is a failed experiment, not a passing JUnit test.
            SystemClock.sleep(65000)
            error("NO_GO controller stop not performed")
        } finally {
            ui.dropShellPermissionIdentity()
        }
    }
}
