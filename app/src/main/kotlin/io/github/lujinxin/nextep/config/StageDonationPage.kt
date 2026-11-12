package io.github.x647368maker.atomicworkbench.config

import android.content.ContentValues
import android.content.Intent
import android.net.Uri
import android.graphics.Typeface
import android.graphics.Bitmap
import android.graphics.drawable.GradientDrawable
import android.os.Environment
import android.provider.MediaStore
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

/**
 * Entirely voluntary and offline.
 * Constructed ONLY on explicit Support > Donate tap.
 * No on-start popup, background task, timer, analytics, network or payment SDK.
 */
internal object StageDonationPage {
    fun create(activity: AppCompatActivity): View {
        fun dp(n: Int) = (n * activity.resources.displayMetrics.density).toInt()
        fun txt(value: String, size: Float = 15f, muted: Boolean = false): TextView =
            TextView(activity).apply {
                text = value
                textSize = size
                setTextColor(if (muted) SettingsPalette.secondary(activity) else SettingsPalette.text(activity))
                setLineSpacing(dp(4).toFloat(), 1f)
            }
        fun card(): LinearLayout = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(18), dp(18), dp(20))
            background = GradientDrawable().apply {
                setColor(SettingsPalette.surface(activity))
                cornerRadius = dp(20).toFloat()
            }
        }
        val root = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        root.addView(txt("❤️ 支持台前调度持续维护", 21f).apply {
            gravity = Gravity.CENTER
            typeface = Typeface.DEFAULT_BOLD
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(16) })
        root.addView(txt("更新优化和长期维护并不容易，感谢每一份支持。", 14f, true).apply {
            gravity = Gravity.CENTER
            setPadding(dp(8), dp(10), dp(8), dp(12))
        })
        root.addView(card().apply {
            addView(txt("台前调度承诺永久免费", 17f).apply { typeface = Typeface.DEFAULT_BOLD })
            addView(txt(
                "所有现有及后续功能、更新和设备适配均可免费使用。打赏完全自愿，金额随意、不设最低金额。不打赏也不会影响任何功能、更新、使用体验或反馈服务。",
                14f
            ).apply { setPadding(0, dp(10), 0, 0) })
            addView(txt(
                "如果这款 App 对你有所帮助，欢迎自愿支持 ZMW小武 持续进行开发、优化、真机测试和维护。",
                14f, true
            ).apply { setPadding(0, dp(12), 0, 0) })
        }, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) })

        StageDonationQr.codes.forEach { code ->
            val box = card()
            box.addView(txt(code.name, 18f).apply { typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER })
            box.addView(txt(
                if (code.name == "支付宝收款码")
                    "下方是已核对的收款二维码。支付宝部分版本可能只打开首页；如未出现付款页，请使用扫一扫识别二维码，支付前确认收款人。"
                else "下方是本人最新微信收款二维码。微信个人收款码没有可靠的一键支付接口；按钮仅打开微信，付款请用扫一扫识别二维码。",
                13f, true
            ).apply { gravity = Gravity.CENTER; setPadding(0, dp(9), 0, dp(12)) })
            box.addView(Button(activity).apply {
                text = if (code.name == "支付宝收款码") "尝试在支付宝打开收款链接" else "打开微信（需手动扫码）"
                contentDescription = text
                setOnClickListener {
                    if (code.name == "支付宝收款码") openAlipayReceipt(activity)
                    else openWechatApp(activity)
                }
            }, LinearLayout.LayoutParams(-1, dp(52)))
            // The static image is decoded only on this user-requested page.
            box.addView(ImageView(activity).apply {
                setImageBitmap(StageDonationQr.bitmap(code))
                // Independent white background + fixed preview bounds keep QR legible
                // on ColorOS, foldables and tablets regardless of app night mode.
                setBackgroundColor(android.graphics.Color.WHITE)
                setPadding(dp(8), dp(8), dp(8), dp(8))
                scaleType = ImageView.ScaleType.FIT_CENTER
                adjustViewBounds = false
                contentDescription = code.name + "，长按保存二维码"
                setOnLongClickListener { saveToGallery(activity, code); true }
            }, LinearLayout.LayoutParams(-1, dp(312)))
            box.addView(Button(activity).apply {
                text = "保存" + code.name + "到相册"
                contentDescription = text
                setOnClickListener { saveToGallery(activity, code) }
            }, LinearLayout.LayoutParams(-1, dp(52)).apply { topMargin = dp(12) })
            root.addView(box, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) })
        }
        root.addView(txt(
            "这是支持二次开发者 ZMW小武 的个人自愿打赏，并非使用门槛或付费解锁。可直接用另一台设备扫码；同一设备可先保存二维码，再从微信/支付宝的扫一扫中选择相册图片识别。付款前请确认收款人并自行选择金额；打开支付 App 并不代表付款成功。所有功能与更新永久免费。不主动弹窗、不暗中检测支付。原项目 NeXtep（Jensen Lu），许可证 Apache-2.0。",
            12f, true
        ).apply { setPadding(dp(12), dp(8), dp(12), dp(24)) })
        return root
    }

    /** User action only: a verified owner QR URL, not a fabricated payment API. */
    private fun openAlipayReceipt(activity: AppCompatActivity) {
        val url = StageDonationLinkPolicy.ALIPAY_RECEIPT_URL
        if (!StageDonationLinkPolicy.isApprovedReceiptLink(url)) {
            Toast.makeText(activity, "收款链接校验失败，请使用二维码", Toast.LENGTH_LONG).show()
            return
        }
        val uri = Uri.parse(url)
        val targeted = Intent(Intent.ACTION_VIEW, uri).apply {
            setPackage(StageDonationLinkPolicy.ALIPAY_PACKAGE)
            addCategory(Intent.CATEGORY_BROWSABLE)
        }
        // Some Alipay versions do not register the https intent. Fall back to
        // normal Android intent resolution; NEVER invent a personal-pay scheme.
        if (runCatching { activity.startActivity(targeted) }.isSuccess) {
            Toast.makeText(activity, "若只进入支付宝首页，请保存收款码并使用扫一扫识别", Toast.LENGTH_LONG).show()
            return
        }
        val browser = Intent(Intent.ACTION_VIEW, uri).apply {
            addCategory(Intent.CATEGORY_BROWSABLE)
        }
        if (runCatching { activity.startActivity(browser) }.isFailure) {
            Toast.makeText(activity, "无法打开支付宝收款链接，请使用下方二维码", Toast.LENGTH_LONG).show()
        }
    }

    /** Never call wxp:// as a payment deep link: it's receipt QR data only. */
    private fun openWechatApp(activity: AppCompatActivity) {
        val launch = activity.packageManager.getLaunchIntentForPackage(StageDonationLinkPolicy.WECHAT_PACKAGE)
        if (launch == null || runCatching { activity.startActivity(launch) }.isFailure) {
            Toast.makeText(activity, "未能打开微信，可用另一台设备扫描下方收款码", Toast.LENGTH_LONG).show()
        }
    }

    private fun saveToGallery(activity: AppCompatActivity, code: StageDonationQr.Code) {
        if (activity.isDestroyed || activity.isFinishing) return
        val resolver = activity.contentResolver
        var uri: android.net.Uri? = null
        try {
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, code.filename)
                put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/台前调度")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
            uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                ?: error("无法创建相册图片")
            resolver.openOutputStream(uri)?.use { output ->
                val bitmap = StageDonationQr.bitmap(code)
                try {
                    check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) { "二维码写入失败" }
                } finally {
                    bitmap.recycle()
                }
            } ?: error("无法保存二维码文件")
            resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
            Toast.makeText(activity, "收款码已保存到相册", Toast.LENGTH_SHORT).show()
        } catch (_: Exception) {
            uri?.let { runCatching { resolver.delete(it, null, null) } }
            Toast.makeText(activity, "保存失败，请直接截图后识别", Toast.LENGTH_LONG).show()
        }
    }
}
