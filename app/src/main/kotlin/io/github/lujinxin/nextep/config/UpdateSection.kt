package io.github.x647368maker.atomicworkbench.config

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
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

internal object UpdateSection {
    fun create(activity: AppCompatActivity): View {
        fun dp(n:Int)=(n*activity.resources.displayMetrics.density).toInt()
        val installed=UpdateCoordinator.installed(activity)
        val dark=true  // Update page intentionally uses its own immersive palette.
        val ink=Color.WHITE
        val muted=Color.rgb(209,201,224)
        val accent=Color.rgb(102,91,187)
        val updateSurface=Color.rgb(42,35,71)
        val updateContainer=Color.rgb(75,66,105)
        fun rounded(color:Int,radius:Int=21)=GradientDrawable().apply {
            setColor(color);cornerRadius=dp(radius).toFloat()
        }
        fun text(value:String,size:Float=14f,color:Int=ink,bold:Boolean=false)=TextView(activity).apply {
            text=value;textSize=size;setTextColor(color)
            includeFontPadding=false;setLineSpacing(dp(3).toFloat(),1f)
            if(bold) typeface=Typeface.DEFAULT_BOLD
        }
        val root=LinearLayout(activity).apply {
            orientation=LinearLayout.VERTICAL
            setPadding(dp(2),dp(12),dp(2),dp(22))
        }
        fun card():LinearLayout=LinearLayout(activity).apply {
            orientation=LinearLayout.VERTICAL
            setPadding(dp(18),dp(18),dp(18),dp(18))
            background=rounded(updateSurface)
            root.addView(this,LinearLayout.LayoutParams(-1,-2).apply { bottomMargin=dp(12) })
        }
        fun gap(parent:LinearLayout,height:Int=10) {
            parent.addView(View(activity),LinearLayout.LayoutParams(1,dp(height)))
        }
        // Version-first hero like an OS updater, but native to this app.
        val hero=LinearLayout(activity).apply {
            orientation=LinearLayout.VERTICAL
            setPadding(dp(23),dp(23),dp(23),dp(23))
            background=GradientDrawable(GradientDrawable.Orientation.TL_BR,intArrayOf(
                if(dark)Color.rgb(28,25,59) else Color.rgb(45,42,102),
                if(dark)Color.rgb(65,45,105) else Color.rgb(100,76,153),
                if(dark)Color.rgb(94,61,91) else Color.rgb(185,124,153)
            )).apply { cornerRadius=dp(26).toFloat() }
        }
        hero.addView(text("台前调度 · 软件更新",14f,Color.rgb(235,226,250),true))
        gap(hero,15)
        hero.addView(text("v"+installed.versionName.orEmpty(),32f,Color.WHITE,true))
        gap(hero,9)
        hero.addView(text(StageUpdateDeviceLabel.current(),15f,Color.WHITE).apply {
            maxLines=1
            ellipsize=android.text.TextUtils.TruncateAt.END
            contentDescription="当前设备："+StageUpdateDeviceLabel.current()
        })
        gap(hero,7)
        hero.addView(text("已安装版本 · "+installed.longVersionCode,12f,Color.rgb(242,234,251)))
        gap(hero,18)
        val heroStatus=text("点击下方检查更新",19f,Color.WHITE,true)
        hero.addView(heroStatus)
        gap(hero,8)
        hero.addView(text("原证书签名 · 安全校验 · 系统安装器",12f,Color.rgb(236,229,247)))
        root.addView(hero,LinearLayout.LayoutParams(-1,-2).apply { bottomMargin=dp(14) })

        // One continuous details area: no developer/stable selector and
        // no nested cards that make the native settings page feel fragmented.
        val details=card()
        val settings=details
        settings.addView(text("自动发现新版本",17f,ink,true))
        gap(settings,10)
        settings.addView(Switch(activity).apply {
            text="启动时低频检查新版本";textSize=14f;setTextColor(ink)
            isChecked=UpdateCoordinator.auto(activity)
            minimumHeight=dp(51)
            setOnCheckedChangeListener { _,enabled ->
                UpdateCoordinator.setAuto(activity,enabled)
                if(enabled) UpdateCoordinator.maybeCheck(activity) {
                    if(!activity.isDestroyed && !activity.isFinishing)
                        Toast.makeText(activity,"发现可验证的新版本，请点击查看 / 安装新版本",Toast.LENGTH_LONG).show()
                }
                if(enabled && activity.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED)
                    activity.requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS),72)
            }
        },LinearLayout.LayoutParams(-1,dp(51)))
        settings.addView(text("统一更新渠道 · 约每 12 小时、仅打开 App 时检查。不常驻后台；关闭提醒也能手动检查。",12f,muted))

        val operations=details
        gap(operations,22)
        operations.addView(text("版本检测与安装",17f,ink,true))
        gap(operations,11)
        val status=text("点击“检查更新”获取已校验的官方版本信息。",13f,muted)
        operations.addView(status)
        gap(operations,12)
        var release=UpdateCoordinator.available(activity)
        var disposed=false
        val worker=Executors.newSingleThreadExecutor()
        root.addOnAttachStateChangeListener(object:View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v:View)=Unit
            override fun onViewDetachedFromWindow(v:View) { disposed=true;worker.shutdownNow() }
        })
        val check=Button(activity).apply { text="检查更新" }
        val download=Button(activity).apply { text="查看 / 安装新版本";isEnabled=true }
        fun valid()=!disposed&&!activity.isDestroyed&&!activity.isFinishing
        fun showRelease(r:UpdateChecker.Release) {
            MaterialAlertDialogBuilder(activity).setTitle("台前调度 ${r.version}")
                .setMessage("版本码：${r.metadata.versionCode}\n大小：${String.format(java.util.Locale.ROOT,"%.1f",r.metadata.size/1048576.0)} MB\n\n"+r.notes.ifBlank { "发布者未填写变更说明" }+"\n\n下载后会校验 SHA-256、包名、版本和签名，再由系统请求确认安装。升级后请正常重启设备。")
                .setNegativeButton("稍后",null).setPositiveButton("下载并验证") { _,_ ->
                    check.isEnabled=false;download.isEnabled=false;status.text="下载并校验中…"
                    worker.execute {
                        val result=runCatching { UpdateInstaller.download(activity.applicationContext,r) }
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
                                            runCatching { activity.startActivity(Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,Uri.parse("package:"+activity.packageName))) }
                                        }.show()
                                } else runCatching { UpdateInstaller.install(activity,file,r) }.onFailure { status.text=it.message ?: "系统安装器不可用" }
                            }.onFailure { status.text="更新失败，可安全重试："+(it.message ?: "网络或校验失败") }
                        }
                    }
                }.show()
        }
        check.setOnClickListener {
            check.isEnabled=false;status.text="正在检查本项目发布…"
            worker.execute {
                val result=runCatching { UpdateCoordinator.check(activity.applicationContext) }
                activity.runOnUiThread {
                    if(!valid()) return@runOnUiThread
                    check.isEnabled=true
                    result.onSuccess { found ->
                        release=found;download.isEnabled=found!=null
                        status.text=if(found==null)"当前未发现可验证的更高版本；历史无校验元数据的版本会忽略。" else "发现 ${found.version}，版本码 ${found.metadata.versionCode}"
                        heroStatus.text=if(found==null)"暂无可验证的新版本" else "发现新版本"
                        found?.let(::showRelease)
                    }.onFailure {
                        heroStatus.text="更新检查暂不可用"
                        status.text="检查失败，不影响工作区："+(it.message ?: "请稍后重试")
                    }
                }
            }
        }
        download.setOnClickListener { release=UpdateCoordinator.available(activity); if(release==null) { check.performClick(); return@setOnClickListener }; release?.takeIf { UpdatePolicy.eligible(it.metadata,installed.longVersionCode,UpdateCoordinator.channel(activity)) }?.let(::showRelease) }
        for (button in listOf(check,download)) {
            val emphasized=button===check
            button.textSize=16f
            button.isAllCaps=false
            button.setTextColor(if(emphasized)Color.WHITE else ink)
            button.background=rounded(if(emphasized)accent else updateContainer,15)
            button.elevation=0f
            operations.addView(button,LinearLayout.LayoutParams(-1,dp(53)).apply { bottomMargin=dp(9) })
        }
        operations.addView(Button(activity).apply {
            text="查看 GitHub 版本发布记录";isAllCaps=false;textSize=15f
            setTextColor(ink)
            background=rounded(updateContainer,15)
            elevation=0f
            setOnClickListener { openBrowser(activity,UpdateChecker.RELEASES_URL) }
        },LinearLayout.LayoutParams(-1,dp(52)))
        // The usage guide is always shown directly below the update controls;
        // never hide it in About, a collapsed section, or a dialog.
        val help=details
        gap(help,22)
        help.addView(text("使用说明",17f,ink,true))
        gap(help,11)
        help.addView(text("① 点击「检查更新」，自动检查所有较新的可信版本。\n② 如有新版，先查看更新说明，再「下载并验证」。\n③ 由系统安装器确认覆盖安装，不必主动卸载旧版。",14f,ink))
        gap(help,11)
        help.addView(text("安全校验：SHA-256、包名、版本码、原签名证书全部匹配才允许安装。",13f,muted))
        gap(help,10)
        help.addView(text("升级后请正常重启一次，让 Vector 模块加载新版。更新不会清除工作组、固定应用或设置。",13f,muted))
        return root
    }
    internal fun openBrowser(activity:AppCompatActivity,address:String) {
        runCatching { activity.startActivity(Intent(Intent.ACTION_VIEW,Uri.parse(UpdateChecker.requireHttps(address))).addCategory(Intent.CATEGORY_BROWSABLE)) }
            .onFailure { Toast.makeText(activity,"无法打开浏览器",Toast.LENGTH_LONG).show() }
    }
}
