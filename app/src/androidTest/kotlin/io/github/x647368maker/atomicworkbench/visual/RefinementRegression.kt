package io.github.x647368maker.atomicworkbench.visual

import android.app.*
import android.content.Context
import android.graphics.*
import android.os.*
import android.view.*
import android.widget.*
import io.github.x647368maker.atomicworkbench.systemui.*
import io.github.x647368maker.atomicworkbench.workspace.*
import io.github.x647368maker.atomicworkbench.display.TaskSwitcherView
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Real HWUI/TextureView/popup fixtures; no claim of ColorOS module/task-migration profiling. */
internal class RefinementRegression(private val inst:Instrumentation) {
    private fun ui(f:()->Unit)=inst.runOnMainSync(f)
    private fun settle(){inst.waitForIdleSync();Thread.sleep(180)}
    private fun click(label:String) {
        // Compact menus can expose only part of a row. Scroll the actual
        // popup until the touch coordinates lie inside its visible viewport.
        fun scrollContainers(node: android.view.accessibility.AccessibilityNodeInfo): List<android.view.accessibility.AccessibilityNodeInfo> {
            if(node.isScrollable)return listOf(node)
            return (0 until node.childCount).flatMap { index ->
                node.getChild(index)?.let(::scrollContainers).orEmpty()
            }
        }
        val b = Rect()
        for (attempt in 0..20) {
            val root = checkNotNull(inst.uiAutomation.rootInActiveWindow)
            val node = root.findAccessibilityNodeInfosByText(label)
                .firstOrNull { (it.text?.toString()==label || it.contentDescription?.toString()==label) && it.isVisibleToUser }
            val scroll = if (node != null) {
                generateSequence(node.parent) { it.parent }.firstOrNull { it.isScrollable }
            } else {
                val containers=scrollContainers(root)
                // An offscreen action belongs to the right pane, not the
                // first scrollable category list. Leave single-pane unchanged.
                val category=label in StageMenuPolicy.groups
                if(category) containers.minByOrNull { Rect().also(it::getBoundsInScreen).left }
                else containers.maxByOrNull { Rect().also(it::getBoundsInScreen).left }
            }
            val viewport = Rect()
            (scroll ?: root).getBoundsInScreen(viewport)
            if (node != null) {
                node.getBoundsInScreen(b)
                if (b.intersect(viewport) && b.height() >= 8 && b.width() >= 8) break
            }
            check(attempt < 20 && scroll?.performAction(
                android.view.accessibility.AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
            ) == true) { "Cannot reach command $label by scrolling the popup" }
            // The actual ScrollView animates an accessibility scroll for up to
            // 250 ms. A tap during that motion is consumed to stop the scroll.
            // Wait one scroll interval with compositor headroom before
            // recomputing the visible row and sending one physical tap.
            Thread.sleep(320)
            inst.waitForIdleSync()
        }
        android.util.Log.i("StageMenuRegression","physical tap label=$label bounds=$b")
        val t=SystemClock.uptimeMillis()
        for(a in listOf(MotionEvent.ACTION_DOWN,MotionEvent.ACTION_UP)){
            val e=MotionEvent.obtain(t,SystemClock.uptimeMillis(),a,b.exactCenterX(),b.exactCenterY(),0);e.source=InputDevice.SOURCE_TOUCHSCREEN
            check(inst.uiAutomation.injectInputEvent(e,true));e.recycle()
        }
        settle()
    }
    fun run(activity:Activity,root:FrameLayout,out:File):List<String> {
        val log=mutableListOf<String>()
        fun record(s:String){log.add(s);File(out,"refinement-results.txt").writeText(log.joinToString("\n")+"\n")}
        // Rasterize the exact production material over a transparent bitmap.
        // This is an alpha/rim contract, not a claim of live optical refraction.
        ui {
            val material=AtomicPopupMenu.dualGlassPane(2f)
            val raster=Bitmap.createBitmap(240,400,Bitmap.Config.ARGB_8888)
            try {
                material.setBounds(0,0,raster.width,raster.height)
                material.draw(Canvas(raster))
                val center=Color.alpha(raster.getPixel(120,200))
                check(center in 110..200){"Glass center must transmit underlying content: alpha=$center"}
                check(Color.alpha(raster.getPixel(0,0))<=12){"Glass corners must remain transparent"}
                val edge=(0..5).maxOf{y->Color.alpha(raster.getPixel(40,y))}
                check(edge>center){"Directional rim must have a brighter local edge: $edge vs $center"}
            } finally { raster.recycle() }
        }
        record("PASS production glass material alpha/transparent corners/directional rim raster contract")
        val cards=mutableListOf<TaskSwitcherView>();val surfaces=mutableListOf<Surface>();val textures=mutableListOf<SurfaceTexture>()
        val ready=CountDownLatch(4);var destroyed=0
        lateinit var anchor:TextView
        ui {
            root.removeAllViews();root.setBackgroundColor(Color.rgb(65,120,155))
            anchor=TextView(activity).apply{text="⋯";textSize=22f;setTextColor(Color.WHITE)}
            root.addView(anchor,FrameLayout.LayoutParams(120,120).apply{leftMargin=25;topMargin=100})
            repeat(4){i->
                val card=TaskSwitcherView(activity,i);cards.add(card);card.textureView.alpha=1f
                root.addView(card,FrameLayout.LayoutParams(180,300).apply{leftMargin=40+i*230;topMargin=280})
                card.textureView.surfaceTextureListener=object:TextureView.SurfaceTextureListener {
                    override fun onSurfaceTextureAvailable(t:SurfaceTexture,w:Int,h:Int){
                        textures.add(t);t.setDefaultBufferSize(720,1120)
                        val s=Surface(t);surfaces.add(s);val c=s.lockCanvas(null);c.drawColor(Color.rgb(50+i*35,180,140));s.unlockCanvasAndPost(c)
                    }
                    override fun onSurfaceTextureSizeChanged(t:SurfaceTexture,w:Int,h:Int){t.setDefaultBufferSize(720,1120)}
                    override fun onSurfaceTextureDestroyed(t:SurfaceTexture):Boolean {destroyed++;return true}
                    override fun onSurfaceTextureUpdated(t:SurfaceTexture){if(ready.count>0)ready.countDown()}
                }
            }
        }
        check(ready.await(10,TimeUnit.SECONDS));settle()
        val initial=textures.toSet();check(initial.size==4)
        // Optional smart hiding uses alpha only: the live hosts stay attached and sized.
        var allowed=true
        lateinit var smart:StageSmartRail
        val oldSizes=cards.map { it.width to it.height }
        ui {
            smart=StageSmartRail(activity,Handler(Looper.getMainLooper()),{ allowed }) { hidden -> cards.forEach { it.alpha=if(hidden)0f else 1f } }
            smart.setEnabled(false);smart.setPinned(false);smart.setIdleSeconds(3)
            check(!smart.hidden)
            smart.setEnabled(true);smart.touch(true)
        }
        Thread.sleep(3800)
        ui { check(!smart.hidden) { "Long touch was hidden" };smart.touch(false) }
        Thread.sleep(3800)
        ui { check(smart.hidden && cards.all { it.alpha==0f });smart.interact();check(!smart.hidden)
            smart.hideNow();check(smart.hidden);smart.setPinned(true);check(!smart.hidden)
            smart.hideNow();check(!smart.hidden);smart.setPinned(false);allowed=false
            smart.hideNow();check(!smart.hidden);smart.setEnabled(false);smart.stop();smart.setIdleSeconds(5)
        }
        check(destroyed==0 && cards.map { it.width to it.height }==oldSizes && cards.mapNotNull{it.textureView.surfaceTexture}.toSet()==initial)
        record("PASS optional idle hide/restore/pin/blocked native state/disable retain all four attached TextureViews and exact dimensions; main expansion intentionally absent")
        val metrics=mutableListOf<Long>()
        val metricThread=HandlerThread("StageFrameMetrics").apply{start()}
        val listener=Window.OnFrameMetricsAvailableListener{_,m,_->synchronized(metrics){metrics.add(m.getMetric(FrameMetrics.TOTAL_DURATION))}}
        ui{activity.window.addOnFrameMetricsAvailableListener(listener,Handler(metricThread.looper))}
        val commands=listOf("切换全屏（保留工作区）","顶部横排","左侧排列","右侧排列","收起常用应用快捷栏","常用应用管理","快捷栏样式","显示与动画","完整显示布局","✓ 主窗口优先布局","主窗口与小窗交换","窗口顺序调整","隐藏/显示侧边栏","添加小窗","影音布局与自动模式","开启智能侧栏（静置收起）","固定侧栏","将当前微信任务移入小窗","替换主应用","关闭主窗（移回后台）","退出台前调度")
        val fired=java.util.concurrent.atomic.AtomicInteger(-1)
        val actions=commands.mapIndexed{i,label->label to {fired.set(i)}}
        val expected=(0..3).map{category->commands.indices.filter{StageMenuPolicy.group(commands[it])==category}}
        val m=activity.resources.displayMetrics
        val counts=mutableListOf<String>()
        for(mode in listOf("top","left","right")) {
            val profile=WorkspaceLayoutProfile.create(m.widthPixels,m.heightPixels,m.densityDpi,WorkspaceNavigationInsets())
            val toolbar=profile.geometry.copy(topHeight=(96*m.density).toInt())
            val geom=if(mode=="top") StageAdaptiveLayout.rail(toolbar.copy(railAtTop=true,sidebarWidth=StageTopLayoutMetrics.depth(toolbar,m.density,4)),4,m.density)
                else toolbar.copy(sidebarSide=if(mode=="left")SidebarSide.LEFT else SidebarSide.RIGHT)
            val p=profile.copy(geometry=geom,activeCount=4)
            val boxes=StageLiveCardLayout.bounds(p,List(4){p.canvas},List(4){true},listOf(0,1,2,3))
            val avoid=mutableListOf(Rect(geom.contentLeft,geom.contentTop,geom.contentRight,geom.contentBottom))
            ui {
                for(i in 0..3){val b=boxes[i];val x=if(mode=="top")b.left else if(mode=="left")0 else m.widthPixels-b.width
                    cards[i].layoutParams=FrameLayout.LayoutParams(b.width,b.height).apply{leftMargin=x;topMargin=geom.sidebarTop+b.top}
                    avoid.add(Rect(x,geom.sidebarTop+b.top,x+b.width,geom.sidebarTop+b.top+b.height))
                }
            };settle()
            for(category in 0..3){
                ui{AtomicPopupMenu.show(activity,anchor,actions,avoid)};settle()
                val dual=inst.uiAutomation.rootInActiveWindow!!.findAccessibilityNodeInfosByText(StageMenuPolicy.groups.first())
                    .any { it.text?.toString()==StageMenuPolicy.groups.first() && it.isSelected }
                if(category==0){
                    val b=Rect();inst.uiAutomation.rootInActiveWindow!!.getBoundsInScreen(b)
                    check(b.width() > 0 && b.width() <= m.widthPixels && b.height() > 0 && b.height() <= m.heightPixels)
                    for (group in StageMenuPolicy.groups) {
                        val label=if(dual)group else "$group  ›"
                        check(inst.uiAutomation.rootInActiveWindow!!.findAccessibilityNodeInfosByText(label).any { it.text?.toString()==label && it.isVisibleToUser }) { "Primary category is clipped: $group" }
                    }
                    inst.uiAutomation.takeScreenshot()?.let{shot->File(out,"011-menu-$mode.png").outputStream().use{shot.compress(Bitmap.CompressFormat.PNG,100,it)};shot.recycle()}
                }
                click(if(dual)StageMenuPolicy.groups[category] else "${StageMenuPolicy.groups[category]}  ›")
                if(dual) {
                    check(inst.uiAutomation.rootInActiveWindow!!.findAccessibilityNodeInfosByText(StageMenuPolicy.groups[category])
                        .any { it.text?.toString()==StageMenuPolicy.groups[category] && it.isSelected && it.isVisibleToUser })
                } else click("‹  ${StageMenuPolicy.groups[category]}")
                ui{AtomicPopupMenu.dismissAll()};settle()
                for(index in expected[category]){
                    fired.set(-1);ui{AtomicPopupMenu.show(activity,anchor,actions,avoid)};settle()
                    val nowDual=inst.uiAutomation.rootInActiveWindow!!.findAccessibilityNodeInfosByText(StageMenuPolicy.groups.first())
                        .any { it.text?.toString()==StageMenuPolicy.groups.first() && it.isSelected }
                    click(if(nowDual)StageMenuPolicy.groups[category] else "${StageMenuPolicy.groups[category]}  ›")
                    click(commands[index])
                    // Menu actions dismiss with a 70ms compositor fade and then post
                    // their callback on the anchor. An HWUI-saturated emulator can
                    // dispatch this after the general 180ms idle settle. Retain
                    // the exact callback assertion, but await the real delivery.
                    val callbackDeadline = SystemClock.uptimeMillis() + 2000L
                    while (fired.get() != index && SystemClock.uptimeMillis() < callbackDeadline) {
                        Thread.sleep(25)
                    }
                    if(fired.get()!=index) {
                        ui { android.util.Log.e("StageMenuRegression",
                            "callback missing mode=$mode index=$index showing=${AtomicPopupMenu.isShowing()} panel=${SystemUiPanelMonitor.state()}") }
                        inst.uiAutomation.takeScreenshot()?.let { shot ->
                            File(out,"menu-callback-failure-$mode-$index.png").outputStream().use {
                                shot.compress(Bitmap.CompressFormat.PNG,100,it)
                            }
                            shot.recycle()
                        }
                    }
                    check(fired.get()==index){"Wrong callback $mode $index -> $fired"};ui{check(!AtomicPopupMenu.isShowing())}
                }
            }
            val scrolled=java.util.concurrent.atomic.AtomicInteger(-1)
            ui{AtomicPopupMenu.show(activity,anchor,(0..19).map{i->"SCROLL_ACTION_$i" to {scrolled.set(i)}},avoid)};settle()
            click("SCROLL_ACTION_19")
            val scrollCallbackDeadline = SystemClock.uptimeMillis() + 2000L
            while (scrolled.get() == -1 && SystemClock.uptimeMillis() < scrollCallbackDeadline) {
                Thread.sleep(25)
            }
            check(scrolled.get()==19){"Last scrolled command was not dispatched"}
            check(textures.toSet()==initial && destroyed==0){"Presentation rebuilt TextureView producers"}
            counts.add("$mode=${commands.size} callbacks, all four categories, producer identities unchanged")
        }
        record("PASS real grouped native popup: ${counts.joinToString("; ")}; bounded dual glass or compact fallback; all original actions reachable")
        // Use an isolated density-qualified context to exercise the wide
        // popup on the same display without changing device configuration.
        val wideConfig=android.content.res.Configuration(activity.resources.configuration).apply {
            densityDpi=160
        }
        val wideContext=activity.createConfigurationContext(wideConfig)
        fun verifyDualMenu(menuContext:Context,protected:Rect,viewport:String) {
            fired.set(-1)
            ui { AtomicPopupMenu.show(menuContext,anchor,actions,listOf(protected),
                selectedLabels=setOf("左侧排列","主窗口优先布局")) }
            settle()
            fun visibleWideNode(label:String):android.view.accessibility.AccessibilityNodeInfo {
                val active=checkNotNull(inst.uiAutomation.rootInActiveWindow)
                return checkNotNull(active.findAccessibilityNodeInfosByText(label)
                    .firstOrNull{it.text?.toString()==label && it.isVisibleToUser})
            }
            fun visibleWideRow(label:String):Rect = Rect().also(visibleWideNode(label)::getBoundsInScreen)
            fun verifySelectedCategory(selected:String) {
                check(StageMenuPolicy.groups.all { label->visibleWideNode(label).isSelected==(label==selected) }) {
                    "Wrong selected category in $viewport"
                }
            }
            verifySelectedCategory(StageMenuPolicy.groups.first())
            check(visibleWideNode("左侧排列").isSelected)
            check(!visibleWideNode("顶部横排").isSelected)
            fun saveCategory(index:Int) {
                val label=StageMenuPolicy.groups[index]
                verifySelectedCategory(label)
                val popupBounds=Rect().also(checkNotNull(inst.uiAutomation.rootInActiveWindow)::getBoundsInScreen)
                check(!Rect.intersects(popupBounds,protected)) { "Category $label covers protected content in $viewport" }
                val preview=checkNotNull(inst.uiAutomation.takeScreenshot()) { "Missing $viewport $label pixels" }
                try {
                    File(out,"menu-${viewport.replace(' ','-')}-category-${index+1}.png").outputStream().use {
                        check(preview.compress(Bitmap.CompressFormat.PNG,100,it))
                    }
                } finally { preview.recycle() }
            }
            saveCategory(0)
            for(index in 1..3) {
                click(StageMenuPolicy.groups[index])
                saveCategory(index)
            }
            click("窗口管理")
            verifySelectedCategory("窗口管理")
            val category=visibleWideRow("窗口管理")
            val action=visibleWideRow("添加小窗")
            check(category.right<=action.left) { "Categories and actions are not side by side" }
            val popupBounds=Rect().also(checkNotNull(inst.uiAutomation.rootInActiveWindow)::getBoundsInScreen)
            check(!Rect.intersects(popupBounds,protected)) { "Grouped menu covers protected content" }
            val preview=checkNotNull(inst.uiAutomation.takeScreenshot()) { "Missing $viewport menu pixels" }
            try {
                File(out,"menu-${viewport.replace(' ','-')}-selected-glass.png").outputStream().use {
                    check(preview.compress(Bitmap.CompressFormat.PNG,100,it))
                }
            } finally { preview.recycle() }
            click("添加小窗")
            val deadline=SystemClock.uptimeMillis()+2000L
            while(fired.get()!=commands.indexOf("添加小窗") && SystemClock.uptimeMillis()<deadline) Thread.sleep(25)
            check(fired.get()==commands.indexOf("添加小窗") && !AtomicPopupMenu.isShowing())
            record("PASS $viewport grouped menu: persistent categories physically beside actions and clear of protected content")

            // The original root and two child levels must share one physical
            // popup window. Opening/back/category switching are not leaf actions.
            val accepted=java.util.concurrent.atomic.AtomicInteger(0)
            val leaf=java.util.concurrent.atomic.AtomicInteger(0)
            val nested=listOf<Pair<String,()->Unit>>("固定应用" to {leaf.incrementAndGet();Unit})
            val sub=listOf<Pair<String,()->Unit>>("固定应用管理" to {
                AtomicPopupMenu.show(menuContext,anchor,nested,listOf(protected))
            })
            val inlineActions=actions.map{entry->
                if(entry.first=="快捷栏样式")entry.first to {
                    accepted.incrementAndGet()
                    AtomicPopupMenu.show(menuContext,anchor,sub,listOf(protected),
                        submenuLabels=setOf("固定应用管理"))
                } else entry
            }
            ui { AtomicPopupMenu.show(menuContext,anchor,inlineActions,listOf(protected),
                submenuLabels=setOf("快捷栏样式")) };settle()
            click("快捷栏与显示")
            val windowId=checkNotNull(inst.uiAutomation.rootInActiveWindow).windowId
            val before=visibleWideRow("快捷栏与显示")
            fun verifyInline(label:String) {
                check(checkNotNull(inst.uiAutomation.rootInActiveWindow).windowId==windowId)
                verifySelectedCategory("快捷栏与显示")
                check(visibleWideRow("快捷栏与显示")==before)
                check(visibleWideRow(label).left>=before.right)
                check(!Rect.intersects(Rect().also(checkNotNull(inst.uiAutomation.rootInActiveWindow)::getBoundsInScreen),protected))
            }
            click("快捷栏样式");verifyInline("固定应用管理")
            click("固定应用管理");verifyInline("固定应用")
            click("‹  固定应用管理");verifyInline("固定应用管理")
            click("‹  快捷栏样式");verifyInline("快捷栏样式")
            check(accepted.get()==1 && leaf.get()==0)
            click("快捷栏样式");click("固定应用管理");verifyInline("固定应用")
            click("窗口布局");verifySelectedCategory("窗口布局")
            click("快捷栏与显示");verifyInline("快捷栏样式")
            click("快捷栏样式");click("固定应用管理");click("固定应用")
            val inlineDeadline=SystemClock.uptimeMillis()+2000L
            while(leaf.get()==0 && SystemClock.uptimeMillis()<inlineDeadline)Thread.sleep(25L)
            check(accepted.get()==3 && leaf.get()==1 && !AtomicPopupMenu.isShowing())
            ui { AtomicPopupMenu.show(menuContext,anchor,inlineActions,listOf(protected),
                submenuLabels=setOf("快捷栏样式")) };settle()
            click("快捷栏与显示");click("快捷栏样式");click("固定应用管理")
            ui { AtomicPopupMenu.dismissAll() };settle()
            check(accepted.get()==4 && leaf.get()==1 && !AtomicPopupMenu.isShowing())
            check(textures.toSet()==initial && destroyed==0)
            record("PASS $viewport inline submenu: same window and left-column bounds, nested back/category reset, exactly-once leaf and cancel; protected content and producers unchanged")

        }
        verifyDualMenu(wideContext,Rect(m.widthPixels/2,0,m.widthPixels,m.heightPixels),"wide")
        verifyDualMenu(activity,Rect(0,m.heightPixels/2,m.widthPixels,m.heightPixels),"phone portrait")
        // Exercise the real popup action/dismiss/reopen path used by the
        // production submenus. Callbacks are fixture counters, not task moves.
        val submenuCalls=java.util.concurrent.atomic.AtomicInteger(0)
        val leafCalls=java.util.concurrent.atomic.AtomicInteger(0)
        val submenuActions=listOf<Pair<String,()->Unit>>("固定应用" to {
            leafCalls.incrementAndGet();Unit
        })
        val chainActions=listOf<Pair<String,()->Unit>>("快捷栏样式" to {
            submenuCalls.incrementAndGet()
            AtomicPopupMenu.show(activity,anchor,submenuActions)
        })
        fun awaitMenuLabel(label:String) {
            val until=SystemClock.uptimeMillis()+2000L
            while(SystemClock.uptimeMillis()<until) {
                if(inst.uiAutomation.rootInActiveWindow?.findAccessibilityNodeInfosByText(label)
                    ?.any { it.text?.toString()==label && it.isVisibleToUser }==true)return
                Thread.sleep(25L)
            }
            error("Chained popup label unavailable: $label")
        }
        ui { AtomicPopupMenu.show(activity,anchor,chainActions) };settle()
        click("快捷栏样式")
        awaitMenuLabel("固定应用")
        check(submenuCalls.get()==1 && leafCalls.get()==0)
        click("固定应用")
        val chainDeadline=SystemClock.uptimeMillis()+2000L
        while(leafCalls.get()==0 && SystemClock.uptimeMillis()<chainDeadline)Thread.sleep(25L)
        check(submenuCalls.get()==1 && leafCalls.get()==1)
        ui { check(!AtomicPopupMenu.isShowing()) }
        ui { AtomicPopupMenu.show(activity,anchor,chainActions) };settle()
        click("快捷栏样式")
        awaitMenuLabel("固定应用")
        ui { AtomicPopupMenu.dismissAll() };settle()
        check(submenuCalls.get()==2 && leafCalls.get()==1)
        ui { check(!AtomicPopupMenu.isShowing()) }
        check(textures.toSet()==initial && destroyed==0)
        record("PASS real popup chaining: open, exactly-once leaf dispatch, dismiss without leaf dispatch; producer identities retained")
        // Same emulator, same four live producers. Real screenshot API is the blocking
        // workload; this measures scheduling only, NOT native task migration or PMS110 FPS.
        val beatTimes=mutableListOf<Long>();var tracing=true
        val beat=object:Choreographer.FrameCallback{
            override fun doFrame(t:Long){beatTimes.add(t);if(tracing)Choreographer.getInstance().postFrameCallback(this)}
        }
        ui{Choreographer.getInstance().postFrameCallback(beat)}
        fun snapshotStats():Pair<Int,Long> {val t=beatTimes.toList();return t.size to (t.zipWithNext().maxOfOrNull{(a,b)->(b-a)/1_000_000} ?: 0)}
        ui{beatTimes.clear()};Thread.sleep(120)
        val beforeTime=SystemClock.uptimeMillis()
        repeat(4){ui{inst.uiAutomation.takeScreenshot()?.recycle()};Thread.sleep(45)}
        Thread.sleep(120);lateinit var before:Pair<Int,Long>;ui{before=snapshotStats()}
        val beforeMs=SystemClock.uptimeMillis()-beforeTime
        val helper=StageAsyncPreviewCapture();var nonUi=0;var completed=0;var fallbacks=0
        val loads=CountDownLatch(4)
        ui{beatTimes.clear()};Thread.sleep(120);val afterTime=SystemClock.uptimeMillis()
        repeat(4){val latch=CountDownLatch(1);val captureDone=CountDownLatch(1)
            ui{helper.request(load={check(Looper.myLooper()!=Looper.getMainLooper());nonUi++;try{inst.uiAutomation.takeScreenshot()}finally{loads.countDown();captureDone.countDown()}},valid={true},accept={if(it==null)fallbacks++;it?.recycle();completed++;latch.countDown()},onCancel={error("Capture cancelled unexpectedly")})}
            check(latch.await(5,TimeUnit.SECONDS) && captureDone.await(5,TimeUnit.SECONDS));Thread.sleep(45)
        }
        Thread.sleep(120);lateinit var after:Pair<Int,Long>;ui{after=snapshotStats();tracing=false;helper.cancel()}
        check(loads.await(8,TimeUnit.SECONDS) && completed==4 && nonUi==4)
        record("MEASURE screenshot scheduling fixture, same emulator/four live producers/right layout: baseline_ui_capture elapsed_ms=$beforeMs frames=${before.first} max_gap_ms=${before.second}; candidate_worker_capture elapsed_ms=${SystemClock.uptimeMillis()-afterTime} frames=${after.first} max_gap_ms=${after.second} deadline_fallbacks=$fallbacks; no assertion that fixture timing equals ColorOS task performance")
        val cancelDone=CountDownLatch(1);val release=CountDownLatch(1);var staleAccepted=false
        ui{helper.request(load={release.await(2,TimeUnit.SECONDS);null},valid={true},accept={staleAccepted=true},onCancel={cancelDone.countDown()});helper.cancel()}
        release.countDown();check(cancelDone.await(2,TimeUnit.SECONDS));Thread.sleep(250);check(!staleAccepted)
        // Hold an actual worker call while cancelling a second queued request.
        val running=CountDownLatch(1);val unblock=CountDownLatch(1)
        val skippedLoads=java.util.concurrent.atomic.AtomicInteger(0)
        val skippedCancels=java.util.concurrent.atomic.AtomicInteger(0)
        val skippedAccepts=java.util.concurrent.atomic.AtomicInteger(0)
        val queuedHelper=StageAsyncPreviewCapture()
        ui{queuedHelper.request(load={running.countDown();unblock.await(3,TimeUnit.SECONDS);null},valid={true},accept={skippedAccepts.incrementAndGet()},onCancel={skippedCancels.incrementAndGet()})}
        try {
            check(running.await(2,TimeUnit.SECONDS))
            ui{queuedHelper.request(load={skippedLoads.incrementAndGet();null},valid={true},accept={skippedAccepts.incrementAndGet()},onCancel={skippedCancels.incrementAndGet()});queuedHelper.cancel()}
        } finally {unblock.countDown()}
        Thread.sleep(350)
        check(skippedLoads.get()==0 && skippedAccepts.get()==0 && skippedCancels.get()==2){"Cancelled queued capture executed or delivered stale result"}
        record("PASS cancelled queued capture skips acquisition; both generations cancelled once")
        for(failValid in listOf(true,false)) {
            val done=CountDownLatch(1);var bitmap:Bitmap?=null;var cancels=0
            ui {helper.request(load={Bitmap.createBitmap(4,4,Bitmap.Config.ARGB_8888).also{bitmap=it}},
                valid={if(failValid)error("intentional validation failure") else true},
                accept={error("intentional accept failure")},onCancel={cancels++;done.countDown()})}
            check(done.await(3,TimeUnit.SECONDS));Thread.sleep(100)
            check(cancels==1 && bitmap?.isRecycled==true){"Failed async callback leaked its bitmap or transition"}
        }
        record("PASS asynchronous capture never blocks UI workload; exactly-once result and cancelled generation never accepts stale capture; validation/accept exceptions recycle and cancel exactly once; stable handoff unit tests retain final geometry")
        val samples=synchronized(metrics){metrics.toList().sorted()}
        check(samples.isNotEmpty() && after.first>=3){"No real HWUI/frame-clock samples"}
        record("MEASURE HWUI FrameMetrics across top/left/right popup/producer fixtures: samples=${samples.size} p50_ms=${samples.getOrElse(samples.size/2){0}/1_000_000.0} p95_ms=${samples.getOrElse((samples.size*.95).toInt().coerceAtMost((samples.size-1).coerceAtLeast(0))){0}/1_000_000.0} over_16.67ms=${samples.count{it>16_666_667}}. No actual third-party task is launched or migrated in this fixture.")
        ui{activity.window.removeOnFrameMetricsAvailableListener(listener);root.removeAllViews();surfaces.forEach{it.release()}}
        metricThread.quitSafely()
        return log
    }
}
