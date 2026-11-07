package io.github.x647368maker.atomicworkbench.config

import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import io.github.x647368maker.atomicworkbench.R

internal object SystemFontPicker {
    fun show(activity: AppCompatActivity, selected: String, sample: String, onSelected: (String) -> Unit) {
        fun dp(value: Int) = (value * activity.resources.displayMetrics.density).toInt()
        var choice = selected
        val all = TopTextStyle.families(activity)
        var filtered = all
        val preview = TextView(activity).apply {
            text = activity.getString(R.string.font_preview, sample.ifBlank { activity.getString(R.string.font_preview_sample) })
            textSize = 21f
            setTextColor(SettingsPalette.text(activity))
            typeface = TopTextStyle.typeface(choice, false)
            setPadding(dp(8), dp(12), dp(8), dp(12))
        }
        val search = TextInputEditText(activity).apply {
            setSingleLine(true)
            textSize = 14f
            background = null
            setPadding(dp(16), dp(14), dp(16), dp(14))
        }
        val adapter = object : ArrayAdapter<Pair<String, String>>(activity, android.R.layout.simple_list_item_1, filtered) {
            override fun getCount() = filtered.size
            override fun getItem(position: Int) = filtered[position]
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val option = filtered[position]
                return ((convertView as? TextView) ?: TextView(activity)).apply {
                    text = if (option.first == choice) "✓  ${option.second}" else option.second
                    textSize = 16f
                    minHeight = dp(52)
                    gravity = android.view.Gravity.CENTER_VERTICAL
                    setPadding(dp(12), dp(8), dp(12), dp(8))
                    setTextColor(if (option.first == choice) SettingsPalette.primary(activity) else SettingsPalette.text(activity))
                    typeface = TopTextStyle.typeface(option.first, false)
                    contentDescription = activity.getString(R.string.font_value, option.second)
                }
            }
        }
        val list = ListView(activity).apply {
            divider = null
            this.adapter = adapter
            setOnItemClickListener { _, _, position, _ ->
                choice = filtered[position].first
                preview.typeface = TopTextStyle.typeface(choice, false)
                adapter.notifyDataSetChanged()
            }
        }
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                val query = s?.toString().orEmpty().trim()
                filtered = all.filter { it.second.contains(query, true) || it.first.contains(query, true) }
                adapter.notifyDataSetChanged()
            }
            override fun afterTextChanged(s: Editable?) = Unit
        })
        val content = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), 0, dp(20), 0)
            addView(TextInputLayout(activity).apply {
                hint = activity.getString(R.string.font_search)
                boxBackgroundMode = TextInputLayout.BOX_BACKGROUND_OUTLINE
                addView(search)
            })
            addView(preview)
            addView(TextView(activity).apply {
                text = activity.getString(R.string.font_source_summary)
                textSize = 12f
                setTextColor(SettingsPalette.secondary(activity))
            })
            addView(list, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(240)))
        }
        MaterialAlertDialogBuilder(activity).setTitle(activity.getString(R.string.font_choose)).setView(content)
            .setPositiveButton(activity.getString(R.string.font_apply)) { _, _ -> onSelected(choice) }
            .setNegativeButton(activity.getString(R.string.action_cancel), null).show()
    }
}
