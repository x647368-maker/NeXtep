package io.github.x647368maker.atomicworkbench.config

import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.util.Locale
import java.util.concurrent.Executors

/**
 * ColorOS-like native update center. Static transparent gradients instead of
 * expensive Blur/Bitmap loops. Presentation only: updater/signing/channel
 * verification, downloads and installer are untouched.
 */
internal object UpdateSection {
    fun create(activity: AppCompatActivity, onShowChangelog: (() -> Unit)? = null): View {
        fun dp(n: Int) = (n * activity.resources.displayMetrics.density).toInt()
        val installed = UpdateCoordinator.installed(activity)
        val white = Color.WHITE
        val muted = Color.rgb(230, 216, 247)

        fun text(value: String, size: Float = 14f, color: Int = white, bold: Boolean = false) =
            TextView(activity).apply {
                this.text = value
                textSize = size
                setTextColor(color)
                includeFontPadding = false
                if (bold) typeface = Typeface.DEFAULT_BOLD
                setLineSpacing(dp(2).toFloat(), 1f)
            }
        fun glass(): GradientDrawable = GradientDrawable(
            GradientDrawable.Orientation.TL_BR, intArrayOf(
                Color.argb(222, 60, 43, 112), Color.argb(222, 34, 26, 75)
            )
        ).apply {
            cornerRadius = dp(26).toFloat()
            setStroke(dp(1).coerceAtLeast(1), Color.argb(125, 231, 208, 255))
        }
        fun buttonBg(): GradientDrawable = GradientDrawable(
            GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(
                Color.rgb(239, 230, 255), Color.rgb(198, 176, 252)
            )
        ).apply { cornerRadius = dp(27).toFloat() }
        fun gap(parent: LinearLayout, height: Int) {
            parent.addView(View(activity), LinearLayout.LayoutParams(1, dp(height)))
        }
        fun formattedVersion(value: String) =
            if (value.startsWith("v", ignoreCase = true)) value else "v$value"

        // Scroll only on genuinely small viewports; on normal phones and tablets
        // the weighted empty space pins the card to the bottom without a timer.
        val scroll = ScrollView(activity).apply {
            isFillViewport = true
            isVerticalScrollBarEnabled = false
            clipToPadding = false
        }
        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(3), dp(58), dp(3), dp(20))
        }
        scroll.addView(root, android.widget.FrameLayout.LayoutParams(-1, -2))
        val hero = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            tag = "stage_update_hero"
        }
        hero.addView(text("台前调度", 34f, white, true).apply { gravity = Gravity.CENTER })
        gap(hero, 8)
        val deviceLabel = StageUpdateDeviceLabel.current()
        hero.addView(text(deviceLabel, 16f, muted).apply {
            gravity = Gravity.CENTER
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
            contentDescription = "当前设备：" + deviceLabel
        }, LinearLayout.LayoutParams(-1, -2))
        root.addView(hero, LinearLayout.LayoutParams(-1, -2))
        root.addView(View(activity), LinearLayout.LayoutParams(1, dp(54), 1f))

        val card = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            tag = "stage_update_bottom_card"
            background = glass()
            setPadding(dp(18), dp(22), dp(18), dp(18))
        }
        root.addView(card, LinearLayout.LayoutParams(-1, -2))
        val title = text("检查新版本", 24f, white, true).apply { gravity = Gravity.CENTER }
        card.addView(title, LinearLayout.LayoutParams(-1, -2))
        gap(card, 10)
        val status = text("点击检查项目官方发布的新版本。", 13f, muted).apply {
            gravity = Gravity.CENTER
        }
        card.addView(status, LinearLayout.LayoutParams(-1, -2))

        val notesHeading = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            visibility = View.GONE
        }
        val notesTitle = text("更新内容", 14f, white, true)
        notesHeading.addView(notesTitle, LinearLayout.LayoutParams(0, -2, 1f))
        val expand = text("查看全部  ⌄", 12f, muted).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(6), dp(10), 0, dp(10))
            isClickable = true
            isFocusable = true
            contentDescription = "展开更新内容"
        }
        notesHeading.addView(expand)
        card.addView(notesHeading, LinearLayout.LayoutParams(-1, -2))
        val teaser = text("", 13f, muted).apply {
            visibility = View.GONE
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        card.addView(teaser, LinearLayout.LayoutParams(-1, -2))
        val fullNotes = text("", 13f, muted).apply { visibility = View.GONE }
        card.addView(fullNotes, LinearLayout.LayoutParams(-1, -2))
        gap(card, 18)

        val primary = Button(activity).apply {
            isAllCaps = false
            textSize = 16f
            setTextColor(Color.rgb(30, 22, 65))
            typeface = Typeface.DEFAULT_BOLD
            background = buttonBg()
            elevation = 0f
        }
        card.addView(primary, LinearLayout.LayoutParams(-1, dp(52)))
        val secondary = text("", 13f, muted).apply {
            gravity = Gravity.CENTER
            visibility = View.GONE
            isClickable = true
            isFocusable = true
            setPadding(dp(10), dp(12), dp(10), dp(6))
        }
        card.addView(secondary, LinearLayout.LayoutParams(-1, -2))

        var release: UpdateChecker.Release? = null
        var latestConfirmed = false
        var notesExpanded = false
        var busy = false
        var disposed = false
        val worker = Executors.newSingleThreadExecutor()
        scroll.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) = Unit
            override fun onViewDetachedFromWindow(v: View) {
                disposed = true
                worker.shutdownNow()
            }
        })
        fun valid() = !disposed && !activity.isDestroyed && !activity.isFinishing
        fun resetNotes() {
            notesExpanded = false
            notesHeading.visibility = View.GONE
            teaser.visibility = View.GONE
            fullNotes.visibility = View.GONE
        }
        fun installRelease(selected: UpdateChecker.Release) {
            MaterialAlertDialogBuilder(activity).setTitle("台前调度 " + selected.version)
                .setMessage("版本码：${selected.metadata.versionCode}\n大小：" +
                    String.format(Locale.ROOT, "%.1f", selected.metadata.size / 1048576.0) +
                    " MB\n\n" + selected.notes.ifBlank { "发布者未填写变更说明" } +
                    "\n\n下载后会校验 SHA-256、包名、版本和原证书，再由系统确认安装。升级后正常重启设备。")
                .setNegativeButton("稍后", null)
                .setPositiveButton("下载并验证") { _, _ ->
                    busy = true
                    primary.isEnabled = false
                    status.text = "下载并校验中…"
                    worker.execute {
                        val result = runCatching { UpdateInstaller.download(activity.applicationContext, selected) }
                        activity.runOnUiThread {
                            if (!valid()) return@runOnUiThread
                            busy = false
                            primary.isEnabled = true
                            result.onSuccess { file ->
                                status.text = "校验通过，请在系统安装界面确认。"
                                if (!activity.packageManager.canRequestPackageInstalls()) {
                                    status.text = "需要允许安装此来源后重试。"
                                    MaterialAlertDialogBuilder(activity).setTitle("允许安装更新")
                                        .setMessage("只调用 Android 系统安装器，不使用 Root 静默安装。")
                                        .setNegativeButton("取消", null)
                                        .setPositiveButton("打开设置") { _, _ ->
                                            runCatching {
                                                activity.startActivity(Intent(
                                                    android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                                                    Uri.parse("package:" + activity.packageName)
                                                ))
                                            }
                                        }.show()
                                } else runCatching {
                                    UpdateInstaller.install(activity, file, selected)
                                }.onFailure { status.text = it.message ?: "系统安装器不可用" }
                            }.onFailure {
                                status.text = "更新失败，可安全重试：" + (it.message ?: "网络或校验失败")
                            }
                        }
                    }
                }.show()
        }
        fun displayRelease(found: UpdateChecker.Release?, confirmed: Boolean) {
            release = found
            latestConfirmed = confirmed && found == null
            resetNotes()
            when {
                found != null -> {
                    title.text = "发现新版本"
                    val size = String.format(Locale.ROOT, "%.1f", found.metadata.size / 1048576.0)
                    status.text = "可升级至 ${formattedVersion(found.version)}（${found.metadata.versionCode}）  |  约 $size MB"
                    val notes = found.notes.ifBlank { "发布者未填写更新说明" }.take(5000)
                    teaser.text = notes.lineSequence().firstOrNull { it.isNotBlank() }?.trim()
                        ?.take(108) ?: "发布者未填写更新说明"
                    fullNotes.text = notes
                    notesHeading.visibility = View.VISIBLE
                    teaser.visibility = View.VISIBLE
                    primary.text = "立即更新"
                    secondary.text = "稍后再说"
                    secondary.visibility = View.VISIBLE
                }
                confirmed -> {
                    title.text = "已是最新版本"
                    status.text = "${formattedVersion(installed.versionName.orEmpty())}（${installed.longVersionCode}）"
                    primary.text = "查看更新说明"
                    secondary.text = "检查更新"
                    secondary.visibility = View.VISIBLE
                }
                else -> {
                    title.text = "检查新版本"
                    status.text = "点击检查项目官方发布的新版本。"
                    primary.text = "检查更新"
                    secondary.visibility = View.GONE
                }
            }
        }
        fun checkNow() {
            if (busy) return
            busy = true
            primary.isEnabled = false
            status.text = "正在检查项目官方发布…"
            worker.execute {
                val result = runCatching { UpdateCoordinator.check(activity.applicationContext) }
                activity.runOnUiThread {
                    if (!valid()) return@runOnUiThread
                    busy = false
                    primary.isEnabled = true
                    result.onSuccess { displayRelease(it, true) }
                        .onFailure {
                            title.text = "检查暂不可用"
                            status.text = "检查失败，不影响工作区：" + (it.message ?: "请稍后重试")
                            primary.text = "重试检查"
                            resetNotes()
                            secondary.visibility = View.GONE
                            release = null
                            latestConfirmed = false
                        }
                }
            }
        }
        expand.setOnClickListener {
            notesExpanded = !notesExpanded
            fullNotes.visibility = if (notesExpanded) View.VISIBLE else View.GONE
            teaser.visibility = if (notesExpanded) View.GONE else View.VISIBLE
            expand.text = if (notesExpanded) "收起  ⌃" else "查看全部  ⌄"
            expand.contentDescription = if (notesExpanded) "收起更新内容" else "展开更新内容"
        }
        primary.setOnClickListener {
            if (busy) return@setOnClickListener
            val available = release?.takeIf {
                UpdatePolicy.eligible(it.metadata, installed.longVersionCode, UpdateCoordinator.channel(activity))
            }
            when {
                available != null -> installRelease(available)
                latestConfirmed -> onShowChangelog?.invoke()
                    ?: Toast.makeText(activity, "请从右上角菜单查看更新说明", Toast.LENGTH_SHORT).show()
                else -> checkNow()
            }
        }
        secondary.setOnClickListener {
            if (release != null) {
                // No hidden reminder job is scheduled; the user can return later.
                Toast.makeText(activity, "稍后可再次进入软件更新检查", Toast.LENGTH_SHORT).show()
            } else checkNow()
        }
        val cached = UpdateCoordinator.available(activity)?.takeIf {
            UpdatePolicy.eligible(it.metadata, installed.longVersionCode, UpdateCoordinator.channel(activity))
        }
        displayRelease(cached, false)
        return scroll
    }

    internal fun openBrowser(activity: AppCompatActivity, address: String) {
        runCatching {
            activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(UpdateChecker.requireHttps(address)))
                .addCategory(Intent.CATEGORY_BROWSABLE))
        }.onFailure { Toast.makeText(activity, "无法打开浏览器", Toast.LENGTH_LONG).show() }
    }
}
