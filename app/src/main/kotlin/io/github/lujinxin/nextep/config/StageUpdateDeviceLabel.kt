package io.github.x647368maker.atomicworkbench.config

import android.os.Build
import java.util.Locale

/** Display-only human-readable system-reported model. No identifier, permission or network. */
internal object StageUpdateDeviceLabel {
    fun current(): String = fromProperties(Build.MANUFACTURER, Build.MODEL)

    fun fromProperties(manufacturer: String?, model: String?): String {
        val raw = model.orEmpty().trim().filter { !it.isISOControl() }.take(64)
        val key = raw.uppercase(Locale.ROOT).replace("-", "").replace("_", "")
        when (key) {
            "PMS110" -> return "一加 Ace 7 Pro"
            "PKH110" -> return "OPPO Find N5"
            "PKH120" -> return "OPPO Find N5 卫星通信版"
            "TB9707F" -> return "联想拯救者 Y700"
            "TB320FC" -> return "联想拯救者 Y700 2023"
            "TB321FU" -> return "联想拯救者 Y700 2025"
            "TB322FU" -> return "联想拯救者 Y700 四代"
            "TB323FU" -> return "联想拯救者 Y700 五代"
        }
        if (raw.isBlank() || key in setOf("UNKNOWN", "GENERIC", "ANDROID", "SDK", "EMULATOR"))
            return "设备型号未识别"
        val maker = manufacturer.orEmpty().trim().lowercase(Locale.ROOT)
        val brand = when {
            maker.contains("oneplus") -> "一加"
            maker.contains("oppo") -> "OPPO"
            maker.contains("lenovo") -> "联想"
            else -> ""
        }
        return if (brand.isNotBlank() && !raw.contains(brand, true)) "$brand $raw" else raw
    }
}
