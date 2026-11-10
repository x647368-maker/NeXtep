package io.github.x647368maker.atomicworkbench.config

import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

/** Built only when opened. No network, polling, background work or workspace hooks. */
internal object StageChangelogPage {
    fun create(activity: AppCompatActivity): View {
        fun dp(value: Int) = (value * activity.resources.displayMetrics.density).toInt()
        fun text(value: String, size: Float, secondary: Boolean = false) = TextView(activity).apply {
            this.text = value
            textSize = size
            setTextColor(if (secondary) SettingsPalette.secondary(activity) else SettingsPalette.text(activity))
            setLineSpacing(dp(2).toFloat(), 1f)
        }
        fun cardBackground() = GradientDrawable().apply {
            cornerRadius = dp(20).toFloat()
            setColor(SettingsPalette.surface(activity))
        }
        val root = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        root.addView(text("版本重大功能与修复记录", 15f).apply {
            setPadding(dp(14), dp(8), dp(14), dp(8))
        })
        root.addView(text(
            "各版本内容均来自源码及记录。开发候选、自动测试、真机验收分别标注；不将未解决问题写成已修复。",
            12f, true
        ).apply { setPadding(dp(14), 0, dp(14), dp(14)) })

        // The version in an installed APK shows the notes bundled at its own build time.
        // Older versions remain available offline from the curated history below.
        val currentVersion = runCatching { UpdateCoordinator.installed(activity) }.getOrNull()
        val packagedNotes = runCatching {
            activity.assets.open("current-release.md")
                .bufferedReader(Charsets.UTF_8).use { it.readText() }
        }.getOrNull()
        val currentEntry = if (currentVersion != null && packagedNotes != null) {
            StageEmbeddedReleaseNotes.parse(
                currentVersion.versionName.orEmpty(),
                currentVersion.longVersionCode,
                packagedNotes
            )
        } else null
        val shownEntries = if (currentEntry == null) StageChangeHistory.entries else
            listOf(currentEntry) + StageChangeHistory.entries.filterNot { it.version == currentEntry.version }

        shownEntries.forEachIndexed { index, entry ->
            val card = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                background = cardBackground()
                clipToOutline = true
            }
            val head = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                isClickable = true
                isFocusable = true
                minimumHeight = dp(64)
                setPadding(dp(16), dp(12), dp(16), dp(12))
            }
            val labels = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
            labels.addView(text("v${entry.version} · ${entry.title}", 16f).apply { typeface = Typeface.DEFAULT_BOLD })
            labels.addView(text(entry.status, 12f, true).apply { setPadding(0, dp(5), 0, 0) })
            head.addView(labels, LinearLayout.LayoutParams(0, -2, 1f))
            val arrow = text(if (index == 0) "⌃" else "⌄", 21f, true).apply { gravity = Gravity.CENTER }
            head.addView(arrow, LinearLayout.LayoutParams(dp(28), dp(36)))

            val details = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                visibility = if (index == 0) View.VISIBLE else View.GONE
                setPadding(dp(16), 0, dp(16), dp(18))
            }
            entry.highlights.forEach { point ->
                details.addView(text("• $point", 14f).apply {
                    setPadding(0, dp(7), 0, dp(5))
                }, LinearLayout.LayoutParams(-1, -2))
            }
            if (entry.caution.isNotBlank()) details.addView(text("注意：${entry.caution}", 12f, true).apply {
                setPadding(0, dp(10), 0, 0)
            })
            fun updateAccessibility() {
                head.contentDescription = "v${entry.version} ${entry.title}，${entry.status}，${if (details.visibility == View.VISIBLE) "收起" else "展开"}详情"
            }
            updateAccessibility()
            head.setOnClickListener {
                details.visibility = if (details.visibility == View.VISIBLE) View.GONE else View.VISIBLE
                arrow.text = if (details.visibility == View.VISIBLE) "⌃" else "⌄"
                updateAccessibility()
            }
            card.addView(head, LinearLayout.LayoutParams(-1, -2))
            card.addView(details, LinearLayout.LayoutParams(-1, -2))
            root.addView(card, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) })
        }
        root.addView(text(
            "这是 APK 内置的离线版本说明，不会自动检查更新。需要下载安装时，请返回「关于」使用原有应用更新入口。",
            12f, true
        ).apply { setPadding(dp(14), dp(8), dp(14), dp(16)) })
        return root
    }
}
