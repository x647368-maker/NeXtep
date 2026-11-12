package io.github.x647368maker.atomicworkbench.config

import android.content.Intent
import android.net.Uri
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.util.concurrent.Executors

/** Native update center. Presentation only; verified update/download/install policies are unchanged. */
internal object UpdateSection {
    fun create(activity: AppCompatActivity): View {
        fun dp(n:Int)=(n*activity.resources.displayMetrics.density).toInt()
        val installed=UpdateCoordinator.installed(activity)
        val ink=Color.WHITE
        val muted=Color.rgb(219,207,239)
        fun text(value:String,size:Float=14f,color:Int=ink,bold:Boolean=false)=TextView(activity).apply {
            text=value; textSize=size; setTextColor(color); includeFontPadding=false
            setLineSpacing(dp(3).toFloat(),1f)
            if(bold)typeface=Typeface.DEFAULT_BOLD
        }
        fun background(color:Int,radius:Int=22,stroke:Boolean=false)=GradientDrawable().apply {
            setColor(color);cornerRadius=dp(radius).toFloat()
            if(stroke)setStroke(dp(1).coerceAtLeast(1),Color.argb(108,220,202,255))
        }
        fun gap(parent:LinearLayout,n:Int) { parent.addView(View(activity),LinearLayout.LayoutParams(1,dp(n))) }
        val root=LinearLayout(activity).apply {
            orientation=LinearLayout.VERTICAL
            setPadding(dp(3),dp(48),dp(3),dp(32))
        }
        val hero=LinearLayout(activity).apply {
            orientation=LinearLayout.VERTICAL;gravity=Gravity.CENTER_HORIZONTAL
        }
        hero.addView(text("台前调度",35f,Color.WHITE,true))
        gap(hero,10)
        hero.addView(text("v"+installed.versionName.orEmpty(),49f,Color.rgb(214,185,255)))
        gap(hero,11)
        hero.addView(text(StageUpdateDeviceLabel.current(),16f).apply {
            maxLines=1; ellipsize=android.text.TextUtils.TruncateAt.END
            contentDescription="当前设备："+StageUpdateDeviceLabel.current()
        })
        root.addView(hero,LinearLayout.LayoutParams(-1,-2))
        gap(root,135)
        val details=LinearLayout(activity).apply {
            orientation=LinearLayout.VERTICAL
            setPadding(dp(23),dp(24),dp(23),dp(23))
            background=background(Color.argb(211,38,32,83),28,true)
        }
        root.addView(details,LinearLayout.LayoutParams(-1,-2))
        val heroStatus=text("检查新版本",26f,ink,true)
        details.addView(heroStatus)
        gap(details,11)
        val status=text("点击下方按钮检查项目官方发布的新版本。",14f,muted)
        details.addView(status)
        val releaseNotes=text("",14f,Color.rgb(240,230,249))
        releaseNotes.visibility=View.GONE
        details.addView(releaseNotes)
        gap(details,18)
        var release=UpdateCoordinator.available(activity)
        var disposed=false
        val worker=Executors.newSingleThreadExecutor()
        root.addOnAttachStateChangeListener(object:View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v:View)=Unit
            override fun onViewDetachedFromWindow(v:View) {disposed=true;worker.shutdownNow()}
        })
        fun valid()=!disposed&&!activity.isDestroyed&&!activity.isFinishing
        val check=Button(activity).apply {text="检查更新"}
        val download=Button(activity).apply {text="立即更新";visibility=View.GONE}
        fun showRelease(r:UpdateChecker.Release) {
            MaterialAlertDialogBuilder(activity).setTitle("台前调度 ${r.version}")
                .setMessage("版本码：${r.metadata.versionCode}\n大小：${String.format(java.util.Locale.ROOT,"%.1f",r.metadata.size/1048576.0)} MB\n\n"+
                    r.notes.ifBlank{"发布者未填写变更说明"}+
                    "\n\n下载后会校验 SHA-256、包名、版本和签名，再由系统请求确认安装。升级后请正常重启设备。")
                .setNegativeButton("稍后",null).setPositiveButton("下载并验证") { _,_ ->
                    check.isEnabled=false;download.isEnabled=false;status.text="下载并校验中…"
                    worker.execute {
                        val result=runCatching {UpdateInstaller.download(activity.applicationContext,r)}
                        activity.runOnUiThread {
                            if(!valid())return@runOnUiThread
                            check.isEnabled=true;download.isEnabled=true
                            result.onSuccess { file ->
                                status.text="校验通过，请在系统安装界面确认。"
                                if(!activity.packageManager.canRequestPackageInstalls()) {
                                    status.text="请允许此来源，返回后点击安装重试（已下载文件会复用）。"
                                    MaterialAlertDialogBuilder(activity).setTitle("允许安装更新")
                                        .setMessage("需要允许台前调度调用系统安装器。不会使用 Root 静默安装。")
                                        .setNegativeButton("取消",null).setPositiveButton("打开设置") { _,_ ->
                                            runCatching {activity.startActivity(Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,Uri.parse("package:"+activity.packageName)))}
                                        }.show()
                                } else runCatching {UpdateInstaller.install(activity,file,r)}
                                    .onFailure {status.text=it.message ?: "系统安装器不可用"}
                            }.onFailure {status.text="更新失败，可安全重试："+(it.message ?: "网络或校验失败")}
                        }
                    }
                }.show()
        }
        fun displayRelease(found:UpdateChecker.Release?) {
            release=found
            if(found==null) {
                heroStatus.text="已是最新版本"
                status.text="当前未发现可验证的更高版本，可随时手动检查。"
                releaseNotes.visibility=View.GONE
                download.visibility=View.GONE
            } else {
                heroStatus.text="发现新版本"
                val size=String.format(java.util.Locale.ROOT,"%.1f",found.metadata.size/1048576.0)
                status.text="${found.version}  ·  ${size} MB"
                releaseNotes.text="\n更新内容\n"+found.notes.ifBlank{"发布者未填写变更说明"}.take(5000)
                releaseNotes.visibility=View.VISIBLE
                download.visibility=View.VISIBLE
                download.text="立即更新"
                if(android.animation.ValueAnimator.areAnimatorsEnabled()) {
                    releaseNotes.alpha=0f
                    releaseNotes.animate().alpha(1f).setDuration(200L).start()
                }
            }
        }
        check.setOnClickListener {
            check.isEnabled=false;status.text="正在检查项目官方发布…"
            worker.execute {
                val result=runCatching {UpdateCoordinator.check(activity.applicationContext)}
                activity.runOnUiThread {
                    if(!valid())return@runOnUiThread
                    check.isEnabled=true
                    result.onSuccess { displayRelease(it) }.onFailure {
                        heroStatus.text="检查暂不可用"
                        status.text="检查失败，不影响工作区："+(it.message ?: "请稍后重试")
                    }
                }
            }
        }
        download.setOnClickListener {
            val selected=release?.takeIf{UpdatePolicy.eligible(it.metadata,installed.longVersionCode,UpdateCoordinator.channel(activity))}
            if(selected!=null)showRelease(selected) else check.performClick()
        }
        check.textSize=16f;check.isAllCaps=false;check.setTextColor(Color.rgb(27,21,57))
        check.background=background(Color.rgb(201,185,255),26)
        check.elevation=0f
        details.addView(check,LinearLayout.LayoutParams(-1,dp(55)))
        download.textSize=16f;download.isAllCaps=false;download.setTextColor(Color.WHITE)
        download.background=background(Color.rgb(36,145,245),26)
        download.elevation=0f
        details.addView(download,LinearLayout.LayoutParams(-1,dp(55)).apply{topMargin=dp(10)})
        if(release!=null) {
            val latest=release
            if(latest!=null && UpdatePolicy.eligible(latest.metadata,installed.longVersionCode,UpdateCoordinator.channel(activity)))displayRelease(latest)
        }
        return root
    }
    internal fun openBrowser(activity:AppCompatActivity,address:String) {
        runCatching {activity.startActivity(Intent(Intent.ACTION_VIEW,Uri.parse(UpdateChecker.requireHttps(address))).addCategory(Intent.CATEGORY_BROWSABLE))}
            .onFailure {Toast.makeText(activity,"无法打开浏览器",Toast.LENGTH_LONG).show()}
    }
}
