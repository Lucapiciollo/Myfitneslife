package com.myfitai.app.ui.widgets

import android.content.Context
import android.content.res.ColorStateList
import android.util.AttributeSet
import android.view.Gravity
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.myfitai.app.R

/** Segmented control riutilizzabile a selezione singola (es. Misura/Storico, Peso/Grasso/Massa). */
open class SelectableSegmentView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : MaterialButtonToggleGroup(context, attrs) {

    private var onSegmentSelected: ((Int) -> Unit)? = null

    init {
        isSingleSelection = true
        isSelectionRequired = true
        setBackgroundResource(R.drawable.bg_segment_track)
        setPadding(dp(4), dp(4), dp(4), dp(4))
        clipToPadding = false
    }

    open fun setSegments(labels: List<String>, selectedIndex: Int = 0) {
        clearOnButtonCheckedListeners()
        removeAllViews()
        val safeIndex = selectedIndex.coerceIn(0, (labels.size - 1).coerceAtLeast(0))
        labels.forEachIndexed { index, label ->
            val button = MaterialButton(context, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                id = android.view.View.generateViewId()
                text = label
                contentDescription = label
                textSize = 13f
                isAllCaps = false
                maxLines = 1
                gravity = Gravity.CENTER
                cornerRadius = dp(10)
                strokeWidth = 0
                insetTop = 0
                insetBottom = 0
                minHeight = 0
                setPadding(dp(8), 0, dp(8), 0)
                layoutParams = LayoutParams(0, LayoutParams.MATCH_PARENT, 1f)
                setTextColor(
                    ColorStateList(
                        arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                        intArrayOf(context.getColor(R.color.white), context.getColor(R.color.text_secondary)),
                    )
                )
                backgroundTintList = ColorStateList(
                    arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                    intArrayOf(context.getColor(R.color.accent_green), context.getColor(android.R.color.transparent)),
                )
            }
            addView(button)
        }
        (getChildAt(safeIndex) as? MaterialButton)?.id?.let { check(it) }
        addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (isChecked) {
                val index = (0 until childCount).firstOrNull { getChildAt(it).id == checkedId } ?: return@addOnButtonCheckedListener
                onSegmentSelected?.invoke(index)
            }
        }
    }

    open fun setOnSegmentSelectedListener(listener: (Int) -> Unit) {
        onSegmentSelected = listener
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
