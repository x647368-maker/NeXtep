package io.github.x647368maker.atomicworkbench.config

import android.os.Build
import java.util.Locale

/**
 * Purely local human-readable device label. Check hardware-facing DEVICE and
 * PRODUCT before MODEL so a ported phone ROM can still expose a Y700 model.
 * Do not read serial/IMEI, contact a server or infer identity from screen size.
 */
internal object StageUpdateDeviceLabel {
    fun current(): String = fromSystemProperties(
        Build.MANUFACTURER, Build.BRAND, Build.MODEL, Build.DEVICE, Build.PRODUCT
    )

    /** Retained for existing callers/tests and older ROMs. */
    fun fromProperties(manufacturer: String?, model: String?): String =
        fromSystemProperties(manufacturer, null, model, null, null)

    private fun clean(value: String?): String =
        value.orEmpty().trim().filterNot { Character.isISOControl(it) }.take(64)

    private fun key(value: String?): String = clean(value).uppercase(Locale.ROOT)
        .filter { it.isLetterOrDigit() }

    private fun officialName(code: String): String? = when (code) {
        "PMS110", "OP64F1L1" -> "一加 Ace 7 Pro"
        "PKH110" -> "OPPO Find N5"
        "PKH120" -> "OPPO Find N5 卫星通信版"
        "TB9707F" -> "联想拯救者 Y700"
        "TB320FC" -> "联想拯救者 Y700 2023"
        "TB321FU" -> "联想拯救者 Y700 2025"
        "TB322FU" -> "联想拯救者 Y700 四代"
        "TB323FU" -> "联想拯救者 Y700 五代"
        "TB324ZC" -> "联想拯救者 Y700 无极"
        else -> null
    }

    fun fromSystemProperties(
        manufacturer: String?, brand: String?, model: String?,
        device: String?, product: String?,
    ): String {
        // DEVICE / PRODUCT can retain the real board identity even on a phone-ROM
        // port. Contradictory known identities must not be silently mislabelled.
        val hardware = listOf(device, product).mapNotNull { officialName(key(it)) }.distinct()
        if (hardware.size > 1) return "设备型号待核实"
        if (hardware.size == 1) return hardware.first()
        officialName(key(model))?.let { return it }
        val raw = clean(model)
        if (raw.isBlank() || key(raw) in setOf("UNKNOWN","GENERIC","ANDROID","SDK","EMULATOR"))
            return "设备型号未识别"
        val maker = listOf(manufacturer, brand).joinToString(" ").lowercase(Locale.ROOT)
        val prefix = when {
            "oneplus" in maker || "一加" in maker -> "一加"
            "oppo" in maker -> "OPPO"
            "lenovo" in maker || "联想" in maker -> "联想"
            else -> ""
        }
        // Do not show "OPPO OPPO Find N5" or a similarly duplicated brand.
        return if (prefix.isNotEmpty() && !raw.contains(prefix,ignoreCase=true)) "$prefix $raw" else raw
    }
}
