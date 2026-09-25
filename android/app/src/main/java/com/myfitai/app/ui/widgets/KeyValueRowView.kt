package com.myfitai.app.ui.widgets

import android.content.Context
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
        setPadding(0, context.resources.getDimensionPixelSize(R.dimen.space_8), 0, context.resources.getDimensionPixelSize(R.dimen.space_8))
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES

        keyView = TextView(context).apply {
            layoutParams = LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)
            setTextAppearance(R.style.Text_MyFitAI_KeyValueKey)
        }
        valueView = TextView(context).apply {
            layoutParams = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                marginStart = context.resources.getDimensionPixelSize(R.dimen.space_12)
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
}
