package com.myfitai.app.ui.widgets

import android.content.Context
import android.util.AttributeSet
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.shape.AbsoluteCornerSize
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
        setInnerCornerSize(AbsoluteCornerSize(resources.getDimension(R.dimen.radius_pill)))
        minimumHeight = minHeightPx()
    }

    private fun minHeightPx(): Int = resources.getDimensionPixelSize(R.dimen.time_range_selector_min_height)

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        // Garanzia finale: anche se un contenitore stringe l'altezza, il selettore non scende sotto il minimo.
        val minHeight = minHeightPx()
        if (measuredHeight < minHeight && MeasureSpec.getMode(heightMeasureSpec) != MeasureSpec.EXACTLY) {
            super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(minHeight, MeasureSpec.EXACTLY))
        }
    }

    fun setRanges(labels: List<String>, selectedIndex: Int = 0) {
        removeAllViews()
        labels.forEachIndexed { index, label ->
            val button = MaterialButton(context, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                id = android.view.View.generateViewId()
                text = label
                setTextAppearance(R.style.Text_MyFitAI_BodyEmphasis)
                isAllCaps = false
                cornerRadius = resources.getDimensionPixelSize(R.dimen.radius_pill)
                strokeWidth = resources.getDimensionPixelSize(R.dimen.border_width_none)
                setPadding(
                    resources.getDimensionPixelSize(R.dimen.space_16), 0,
                    resources.getDimensionPixelSize(R.dimen.space_16), 0,
                )
                // Niente inset verticali del tema Material: la vista (e il suo target di tocco) e' alta almeno 48dp reali,
                // a contenuto cosi' con font 1,3x/2x il testo non si taglia.
                insetTop = 0
                insetBottom = 0
                maxLines = 2
                gravity = android.view.Gravity.CENTER
                minHeight = minHeightPx()
                minimumHeight = minHeightPx()
                layoutParams = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT)
                contentDescription = "Intervallo $label"
                setTextColor(
                    android.content.res.ColorStateList(
                        arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                        intArrayOf(context.getColor(R.color.segment_selected_text), context.getColor(R.color.segment_unselected_text)),
                    )
                )
                backgroundTintList = android.content.res.ColorStateList(
                    arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                    intArrayOf(context.getColor(R.color.segment_selected_bg), context.getColor(android.R.color.transparent)),
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
