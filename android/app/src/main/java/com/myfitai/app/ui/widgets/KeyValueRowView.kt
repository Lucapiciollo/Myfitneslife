package com.myfitai.app.ui.widgets

import android.content.Context
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import com.myfitai.app.R

/** Shared left-label/right-value row for summaries, cards and generated content. */
class KeyValueRowView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : LinearLayout(context, attrs) {
    private val keyView: TextView
    private val valueView: TextView

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(0, dp(8), 0, dp(8))
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES

        keyView = TextView(context).apply {
            layoutParams = LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)
            setTextAppearance(R.style.Text_MyFitAI_KeyValueKey)
        }
        valueView = TextView(context).apply {
            layoutParams = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                marginStart = dp(12)
            }
            setTextAppearance(R.style.Text_MyFitAI_KeyValueValue)
            gravity = Gravity.END
            textAlignment = TextView.TEXT_ALIGNMENT_VIEW_END
        }
        addView(keyView)
        addView(valueView)
    }

    fun setKey(value: String) { keyView.text = value }
    fun setValue(value: String) {
        valueView.text = value
        contentDescription = "${keyView.text}: $value"
    }
    fun setValueColor(colorRes: Int) { valueView.setTextColor(context.getColor(colorRes)) }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
