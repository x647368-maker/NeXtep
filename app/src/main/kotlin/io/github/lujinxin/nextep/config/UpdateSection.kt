package io.github.x647368maker.atomicworkbench.config

import android.Manifest
import android.app.NotificationManager
import android.provider.Settings
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.util.concurrent.Executors

internal object UpdateSection {
    fun create(activity: AppCompatActivity): View {
        fun dp(n:Int)=(n*activity.resources.displayMetrics.density).toInt()
        val installed=UpdateCoordinator.installed(activity)
        val root=LinearLayout(activity).apply { orientation=LinearLayout.VERTICAL;setPadding(dp(16),dp(20),dp(16),dp(12)) }
        fun label(value:String)=TextView(activity).apply { text=value;textSize=14f;setTextColor(SettingsPalette.text(activity));setPadding(0,dp(8),0,dp(8)) }
        root.addView(label("在线更新 · ${installed.versionName} (${installed.longVersionCode})"))
        val channel=Spinner(activity)
        channel.adapter=ArrayAdapter(activity,android.R.layout.simple_spinner_dropdown_item,listOf("开发版（含稳定版）","仅稳定版"))
        channel.setSelection(if(UpdateCoordinator.channel(activity)==UpdatePolicy.Channel.DEV)0 else 1)
        root.addView(channel)
        channel.onItemSelectedListener=object:AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent:AdapterView<*>?)=Unit
            override fun onItemSelected(parent:AdapterView<*>?,view:View?,position:Int,id:Long) {
                val selected=if(position==0)UpdatePolicy.Channel.DEV else UpdatePolicy.Channel.STABLE
                if(UpdateCoordinator.channel(activity)!=selected) UpdateCoordinator.setChannel(activity,selected)
            }
        }
        root.addView(Switch(activity).apply {
            text="启动应用时自动检查更新（正常每12小时一次）";isChecked=UpdateCoordinator.auto(activity)
            setOnCheckedChangeListener { _,enabled ->
                UpdateCoordinator.setAuto(activity,enabled)
                if(enabled) UpdateCoordinator.maybeCheck(activity) {
                    if(!activity.isDestroyed && !activity.isFinishing) Toast.makeText(activity,"发现可验证的新版本，请点击查看 / 安装新版本",Toast.LENGTH_LONG).show()
                }
                if(enabled && activity.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED)
                    activity.requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS),72)
            }
        })
        val status=label("默认开启低频检查（正常每12小时一次），可手动关闭；仅打开 App 时检查，不常驻后台。即使拒绝通知仍可在应用内查看更新。")
        root.addView(status)
        // An available Release is required before any update notification can be sent.
        // Distinguish OS permission/channel restrictions from an empty update feed.
        val notifications=label("")
        fun refreshNotificationStatus() {
            val manager=activity.getSystemService(NotificationManager::class.java)
            val permission=activity.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)==PackageManager.PERMISSION_GRANTED
            val enabled=manager?.areNotificationsEnabled()!=false
            val channel=manager?.getNotificationChannel("atomic_updates")
            val channelEnabled=channel?.importance!=NotificationManager.IMPORTANCE_NONE
            notifications.text=when {
                !permission || !enabled || !channelEnabled -> "更新通知：当前受系统权限或通知渠道限制；手动检查仍可使用。"
                else -> "更新通知：系统允许（仅发现已发布、已验证的新版本时才提示）。"
            }
        }
        refreshNotificationStatus()
        root.addView(notifications)
        root.addView(Button(activity).apply {
            text="管理系统更新通知权限"
            setOnClickListener {
                runCatching {
                    activity.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                        .putExtra(Settings.EXTRA_APP_PACKAGE,activity.packageName))
                }.onFailure {Toast.makeText(activity,"无法打开系统通知设置",Toast.LENGTH_SHORT).show()}
            }
        },LinearLayout.LayoutParams(-1,dp(48)))
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
                        status.text=if(found==null)"暂无可验证的更高版本。历史无校验元数据的版本会忽略。" else "发现 ${found.version}，版本码 ${found.metadata.versionCode}"
                        found?.let(::showRelease)
                    }.onFailure { status.text="检查失败，不影响工作区："+(it.message ?: "请稍后重试") }
                }
            }
        }
        download.setOnClickListener { release=UpdateCoordinator.available(activity); if(release==null) { check.performClick(); return@setOnClickListener }; release?.takeIf { UpdatePolicy.eligible(it.metadata,installed.longVersionCode,UpdateCoordinator.channel(activity)) }?.let(::showRelease) }
        for(button in listOf(check,download)) root.addView(button,LinearLayout.LayoutParams(-1,dp(52)))
        root.addView(Button(activity).apply { text="查看项目发布页面";setOnClickListener { openBrowser(activity,UpdateChecker.RELEASES_URL) } },LinearLayout.LayoutParams(-1,dp(52)))
        root.addView(label("更新后需要正常重启一次加载新版模块；不会清除工作组、固定应用或设置。"))
        return root
    }
    internal fun openBrowser(activity:AppCompatActivity,address:String) {
        runCatching { activity.startActivity(Intent(Intent.ACTION_VIEW,Uri.parse(UpdateChecker.requireHttps(address))).addCategory(Intent.CATEGORY_BROWSABLE)) }
            .onFailure { Toast.makeText(activity,"无法打开浏览器",Toast.LENGTH_LONG).show() }
    }
}
