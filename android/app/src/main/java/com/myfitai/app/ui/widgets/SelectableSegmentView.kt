package com.myfitai.app.ui.widgets

import android.content.Context
import android.content.res.ColorStateList
import android.util.AttributeSet
import android.view.Gravity
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.myfitai.app.R
import com.myfitai.app.ui.motion.UiMotion

/** Segmented control riutilizzabile a selezione singola (es. Misura/Storico, Peso/Grasso/Massa). */
open class SelectableSegmentView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : MaterialButtonToggleGroup(context, attrs) {

    private var onSegmentSelected: ((Int) -> Unit)? = null
    private var onSegmentMotion: ((Int, Int?) -> Unit)? = null
    private var currentSelectionIndex: Int? = null

    init {
        isSingleSelection = true
        isSelectionRequired = true
        setBackgroundResource(R.drawable.bg_segment_track)
        val trackInset = resources.getDimensionPixelSize(R.dimen.space_4)
        setPadding(trackInset, trackInset, trackInset, trackInset)
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
                setTextAppearance(R.style.Text_MyFitAI_BodyEmphasis)
                isAllCaps = false
                maxLines = 1
                gravity = Gravity.CENTER
                cornerRadius = resources.getDimensionPixelSize(R.dimen.radius_small)
                strokeWidth = 0
                insetTop = 0
                insetBottom = 0
                minHeight = resources.getDimensionPixelSize(R.dimen.control_min_height)
                val horizontalPadding = resources.getDimensionPixelSize(R.dimen.space_8)
                setPadding(horizontalPadding, 0, horizontalPadding, 0)
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
        currentSelectionIndex = safeIndex
        addOnButtonCheckedListener { _, checkedId, isChecked ->
                if (!isChecked) return@addOnButtonCheckedListener
                val index = (0 until childCount).firstOrNull { getChildAt(it).id == checkedId } ?: return@addOnButtonCheckedListener
                val previousIndex = currentSelectionIndex?.takeUnless { it == index }
                if (previousIndex != null) onSegmentMotion?.invoke(index, previousIndex)
                currentSelectionIndex = index
                (0 until childCount).forEach { childIndex ->
                    UiMotion.selection(getChildAt(childIndex), childIndex == index)
                }
                onSegmentSelected?.invoke(index)
            }
    }

    open fun setOnSegmentSelectedListener(listener: (Int) -> Unit) {
        onSegmentSelected = listener
    }

    fun setOnSegmentMotionListener(listener: ((selectedIndex: Int, previousIndex: Int?) -> Unit)?) {
        onSegmentMotion = listener
    }
}
