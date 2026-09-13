package com.myfitai.app.ui.widgets

import android.content.Context
import android.util.AttributeSet
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.myfitai.app.R

/** Riga riutilizzabile misura/valore: icona opzionale, label a sinistra, valore a destra. */
class MeasurementRowView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : LinearLayout(context, attrs) {

    private val iconView: ImageView
    private val labelView: TextView
    private val valueView: TextView

    init {
        orientation = HORIZONTAL
        gravity = android.view.Gravity.CENTER_VERTICAL
        val paddingV = (11 * resources.displayMetrics.density).toInt()
        setPadding(0, paddingV, 0, paddingV)

        val density = resources.displayMetrics.density
        iconView = ImageView(context).apply {
            layoutParams = LayoutParams((10 * density).toInt(), (10 * density).toInt()).apply {
                marginEnd = (10 * density).toInt()
            }
            setBackgroundResource(R.drawable.bg_body_marker)
            visibility = GONE
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        addView(iconView)

        labelView = TextView(context).apply {
            layoutParams = LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)
            setTextColor(context.getColor(R.color.text_secondary))
            textSize = 14f
        }
        addView(labelView)

        valueView = TextView(context).apply {
            layoutParams = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT)
            setTextColor(context.getColor(R.color.text_primary))
            textSize = 14f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }
        addView(valueView)
    }

    fun showIcon() {
        iconView.visibility = VISIBLE
    }

    fun setLabel(label: String) {
        labelView.text = label
    }

    fun setValue(value: String) {
        valueView.text = value
    }
}
