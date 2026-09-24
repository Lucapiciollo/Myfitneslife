package com.myfitai.app.ui.widgets

import android.content.Context
import android.util.AttributeSet
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.myfitai.app.R

/** Selettore di intervallo temporale riutilizzabile (1W/1M/3M/1Y, single-selection). */
class TimeRangeSelectorView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : MaterialButtonToggleGroup(context, attrs) {

    private var onRangeSelected: ((Int) -> Unit)? = null

    init {
        isSingleSelection = true
        isSelectionRequired = true
    }

    fun setRanges(labels: List<String>, selectedIndex: Int = 0) {
        removeAllViews()
        labels.forEachIndexed { index, label ->
            val button = MaterialButton(context, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                id = android.view.View.generateViewId()
                text = label
                textSize = 12f
                isAllCaps = false
                cornerRadius = (16 * resources.displayMetrics.density).toInt()
                strokeWidth = 0
                setPadding(
                    (16 * resources.displayMetrics.density).toInt(), 0,
                    (16 * resources.displayMetrics.density).toInt(), 0,
                )
                minHeight = (48 * resources.displayMetrics.density).toInt()
                minimumHeight = (48 * resources.displayMetrics.density).toInt()
                contentDescription = "Intervallo $label"
                setTextColor(
                    android.content.res.ColorStateList(
                        arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                        intArrayOf(context.getColor(R.color.accent_green_dark), context.getColor(R.color.text_muted)),
                    )
                )
                backgroundTintList = android.content.res.ColorStateList(
                    arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                    intArrayOf(context.getColor(R.color.pill_selected_bg), context.getColor(android.R.color.transparent)),
                )
            }
            addView(button)
        }
        (getChildAt(selectedIndex) as? MaterialButton)?.id?.let { check(it) }
        addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (isChecked) {
                val index = (0 until childCount).firstOrNull { getChildAt(it).id == checkedId } ?: return@addOnButtonCheckedListener
                onRangeSelected?.invoke(index)
            }
        }
    }

    fun setOnRangeSelectedListener(listener: (Int) -> Unit) {
        onRangeSelected = listener
    }
}
