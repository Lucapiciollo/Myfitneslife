package com.myfitai.app.ui.widgets

import android.content.Context
import android.util.AttributeSet
import android.view.Gravity
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.myfitai.app.R

/** Riga riutilizzabile per le liste impostazioni/profilo: icona + etichetta + chevron o badge. */
class SettingRowView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : LinearLayout(context, attrs) {

    private val trailingText: TextView

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        val paddingV = (12 * resources.displayMetrics.density).toInt()
        setPadding(0, paddingV, 0, paddingV)
        isClickable = true
        isFocusable = true

        val a = context.obtainStyledAttributes(attrs, R.styleable.SettingRowView)
        val iconRes = a.getResourceId(R.styleable.SettingRowView_srIcon, 0)
        val label = a.getString(R.styleable.SettingRowView_srLabel).orEmpty()
        a.recycle()

        val iconSize = (22 * resources.displayMetrics.density).toInt()
        val icon = ImageView(context).apply {
            layoutParams = LayoutParams(iconSize, iconSize)
            if (iconRes != 0) setImageResource(iconRes)
        }
        addView(icon)

        val labelView = TextView(context).apply {
            layoutParams = LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = (16 * resources.displayMetrics.density).toInt()
            }
            text = label
            setTextColor(context.getColor(R.color.text_primary))
            textSize = 15f
        }
        addView(labelView)

        trailingText = TextView(context).apply {
            layoutParams = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT)
            text = "›"
            setTextColor(context.getColor(R.color.text_muted))
            textSize = 18f
        }
        addView(trailingText)
    }

    fun setTrailingBadge(text: String, colorRes: Int) {
        trailingText.text = text
        trailingText.setTextColor(context.getColor(colorRes))
        trailingText.textSize = 13f
    }
}
