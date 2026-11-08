package io.github.x647368maker.atomicworkbench.config

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.appcompat.app.AppCompatActivity
import java.util.concurrent.Executors

/** Opt-in checks on settings entry, at most every 12 hours. No service or boot polling. */
internal object UpdateCoordinator {
    private val worker=Executors.newSingleThreadExecutor()
    @Volatile private var checking=false
    private fun prefs(c: Context)=c.getSharedPreferences("atomic_updates_v1",Context.MODE_PRIVATE)
    fun installed(c: Context)=c.packageManager.getPackageInfo(c.packageName,0)
    fun channel(c: Context): UpdatePolicy.Channel = runCatching {
        UpdatePolicy.Channel.valueOf(prefs(c).getString("channel",null) ?: if(installed(c).versionName.orEmpty().contains("-dev")) "DEV" else "STABLE")
    }.getOrDefault(UpdatePolicy.Channel.DEV)
    fun setChannel(c: Context,v: UpdatePolicy.Channel) { prefs(c).edit().putString("channel",v.name).putLong("last_check",0).apply() }
    fun auto(c: Context)=prefs(c).getBoolean("auto",false)
    fun setAuto(c: Context,v: Boolean) { prefs(c).edit().putBoolean("auto",v).apply() }
    fun available(c: Context): UpdateChecker.Release? = runCatching {
        val release=UpdateChecker.decode(prefs(c).getString("available",null) ?: return@runCatching null)
        release.takeIf { UpdatePolicy.eligible(it.metadata,installed(c).longVersionCode,channel(c)) }
    }.getOrNull()
    fun check(c: Context): UpdateChecker.Release? {
        val release=UpdateChecker.check(channel(c),installed(c).longVersionCode)
        prefs(c).edit().putString("available",release?.let(UpdateChecker::encode)).apply()
        return release
    }
    fun maybeCheck(activity: AppCompatActivity,changed: () -> Unit) {
        val p=prefs(activity);val code=installed(activity).longVersionCode
        val previous=p.getLong("seen_code",code)
        p.edit().putLong("seen_code",code).apply()
        if(previous<code) android.widget.Toast.makeText(activity,"已升级台前调度，请正常重启设备以加载新版 Vector 模块",android.widget.Toast.LENGTH_LONG).show()
        val now=System.currentTimeMillis()
        if(!auto(activity)||checking||now-p.getLong("last_check",0) < 12L*60*60*1000) return
        p.edit().putLong("last_check",now).apply();checking=true
        val app=activity.applicationContext
        worker.execute {
            val release=runCatching { check(app) }.getOrNull()
            checking=false
            if(release!=null) {
                runCatching { notify(app,release) }
                activity.runOnUiThread { if(!activity.isDestroyed&&!activity.isFinishing) changed() }
            }
        }
    }
    private fun notify(c: Context,r: UpdateChecker.Release) {
        if(c.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED) return
        val p=prefs(c);if(p.getLong("notified",0)>=r.metadata.versionCode) return
        val manager=c.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("atomic_updates","台前调度更新",NotificationManager.IMPORTANCE_DEFAULT))
        val intent=Intent(c,MainActivity::class.java).putExtra("stage_page","about")
        val pending=PendingIntent.getActivity(c,72,intent,PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        manager.notify(72,Notification.Builder(c,"atomic_updates").setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("台前调度发现新版本").setContentText(r.version+" · 点击查看更新说明")
            .setContentIntent(pending).setAutoCancel(true).build())
        p.edit().putLong("notified",r.metadata.versionCode).apply()
    }
}
