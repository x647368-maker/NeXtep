package io.github.x647368maker.atomicworkbench.config

import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.chip.ChipGroup
import io.github.x647368maker.atomicworkbench.R

internal object AcknowledgementsSection {
    fun create(activity: AppCompatActivity): View {
        fun dp(value: Int) = (value * activity.resources.displayMetrics.density).toInt()
        val names = activity.resources.getStringArray(R.array.acknowledgement_names)
            .map(String::trim).filter(String::isNotEmpty).distinct()
        return LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(8), 0, dp(16))
            addView(TextView(activity).apply {
                text = activity.getString(R.string.acknowledgements_title)
                textSize = 18f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(SettingsPalette.text(activity))
            })
            addView(TextView(activity).apply {
                text = activity.getString(R.string.acknowledgements_summary)
                textSize = 14f
                setTextColor(SettingsPalette.secondary(activity))
                setPadding(0, dp(8), 0, dp(8))
            })
            addView(ChipGroup(activity).apply {
                isSingleLine = false
                chipSpacingHorizontal = dp(6)
                chipSpacingVertical = dp(4)
                var previousColorIndex = -1
                names.forEach { name ->
                    val preferredColorIndex = Math.floorMod(name.hashCode(), pastelColors.size)
                    val colorIndex = if (preferredColorIndex == previousColorIndex) {
                        (preferredColorIndex + 1) % pastelColors.size
                    } else preferredColorIndex
                    previousColorIndex = colorIndex
                    addView(LinearLayout(activity).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                        addView(TextView(activity).apply {
                            text = name
                            textSize = 12f
                            gravity = Gravity.CENTER
                            includeFontPadding = false
                            minimumHeight = dp(24)
                            setPadding(dp(10), dp(3), dp(10), dp(3))
                            setTextColor(Color.rgb(35, 53, 64))
                            background = GradientDrawable().apply {
                                cornerRadius = dp(100).toFloat()
                                // Stable for the same list, with no repeated neighboring color.
                                setColor(pastelColors[colorIndex])
                            }
                        })
                    })
                }
            }, LinearLayout.LayoutParams(-1, -2))
        }
    }

    private val pastelColors = intArrayOf(
        Color.rgb(218, 235, 247), Color.rgb(223, 239, 222), Color.rgb(250, 233, 208),
        Color.rgb(237, 225, 247), Color.rgb(249, 224, 231), Color.rgb(215, 239, 236),
        Color.rgb(248, 242, 204), Color.rgb(220, 226, 249), Color.rgb(248, 220, 206),
        Color.rgb(230, 241, 207), Color.rgb(245, 218, 240), Color.rgb(208, 238, 246),
        Color.rgb(238, 228, 211), Color.rgb(223, 216, 247), Color.rgb(211, 237, 218),
        Color.rgb(249, 232, 218),
    )
}
