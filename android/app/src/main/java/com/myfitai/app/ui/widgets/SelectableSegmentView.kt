package com.myfitai.app.ui.widgets

import android.content.Context
import android.util.AttributeSet
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.myfitai.app.R

/** Segmented control riutilizzabile a selezione singola (es. Misura/Storico, Peso/Grasso/Massa). */
class SelectableSegmentView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : MaterialButtonToggleGroup(context, attrs) {

    private var onSegmentSelected: ((Int) -> Unit)? = null

    init {
        isSingleSelection = true
        isSelectionRequired = true
    }

    fun setSegments(labels: List<String>, selectedIndex: Int = 0) {
        removeAllViews()
        labels.forEachIndexed { index, label ->
            val button = MaterialButton(context, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                id = android.view.View.generateViewId()
                text = label
                textSize = 14f
                isAllCaps = false
                cornerRadius = (10 * resources.displayMetrics.density).toInt()
                strokeWidth = 0
                layoutParams = LayoutParams(0, LayoutParams.MATCH_PARENT, 1f)
                setTextColor(
                    android.content.res.ColorStateList(
                        arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                        intArrayOf(context.getColor(R.color.text_primary), context.getColor(R.color.text_secondary)),
                    )
                )
                backgroundTintList = android.content.res.ColorStateList(
                    arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                    intArrayOf(context.getColor(R.color.accent_green), context.getColor(android.R.color.transparent)),
                )
            }
            addView(button)
        }
        (getChildAt(selectedIndex) as? MaterialButton)?.id?.let { check(it) }
        addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (isChecked) {
                val index = (0 until childCount).firstOrNull { getChildAt(it).id == checkedId } ?: return@addOnButtonCheckedListener
                onSegmentSelected?.invoke(index)
            }
        }
    }

    fun setOnSegmentSelectedListener(listener: (Int) -> Unit) {
        onSegmentSelected = listener
    }
}
