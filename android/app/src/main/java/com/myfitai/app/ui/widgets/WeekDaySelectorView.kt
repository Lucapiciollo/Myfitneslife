package com.myfitai.app.ui.widgets

import android.content.Context
import android.util.AttributeSet
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import com.myfitai.app.R
import com.myfitai.app.ui.motion.UiMotion

/** Selettore settimanale riutilizzabile (7 colonne giorno+numero, singola selezione). */
class WeekDaySelectorView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : LinearLayout(context, attrs) {

    data class Day(val abbreviation: String, val dayNumber: String)

    private var onDaySelected: ((Int) -> Unit)? = null
    private var selectedIndex: Int = 0
    private var selectionInitialized: Boolean = false
    private var renderedDays: List<Day> = emptyList()
    private val columns = mutableListOf<LinearLayout>()

    init {
        orientation = HORIZONTAL
    }

    fun setDays(days: List<Day>, selectedIndex: Int = 0) {
        val safeIndex = selectedIndex.coerceIn(0, (days.size - 1).coerceAtLeast(0))
        if (days == renderedDays && columns.isNotEmpty()) {
            if (this.selectedIndex != safeIndex) select(safeIndex)
            return
        }
        val animateSelection = selectionInitialized && this.selectedIndex != safeIndex
        removeAllViews()
        columns.clear()
        this.selectedIndex = safeIndex
        renderedDays = days.toList()
        days.forEachIndexed { index, day ->
            val column = LinearLayout(context).apply {
                orientation = VERTICAL
                gravity = Gravity.CENTER
                layoutParams = LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f).apply {
                    val columnGap = resources.getDimensionPixelSize(R.dimen.space_2)
                    marginStart = columnGap
                    marginEnd = columnGap
                }
                val verticalPadding = resources.getDimensionPixelSize(R.dimen.space_6)
                setPadding(0, verticalPadding, 0, verticalPadding)
                isClickable = true
                isFocusable = true
                minimumHeight = resources.getDimensionPixelSize(R.dimen.control_min_height)
                contentDescription = "${day.abbreviation} ${day.dayNumber}"
                setOnClickListener {
                    select(index)
                    onDaySelected?.invoke(index)
                }
            }
            column.addView(
                TextView(context).apply {
                    text = day.abbreviation
                    setTextAppearance(R.style.Text_MyFitAI_Micro)
                    gravity = Gravity.CENTER
                }
            )
            column.addView(
                TextView(context).apply {
                    text = day.dayNumber
                    setTextAppearance(R.style.Text_MyFitAI_BodyEmphasis)
                    gravity = Gravity.CENTER
                    layoutParams = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                        topMargin = resources.getDimensionPixelSize(R.dimen.space_2)
                    }
                }
            )
            addView(column)
            columns.add(column)
        }
        applySelectionStyle(animateSelection)
        selectionInitialized = true
    }

    fun select(index: Int) {
        if (index !in columns.indices || selectedIndex == index) return
        selectedIndex = index
        applySelectionStyle(animateSelection = true)
    }

    private fun applySelectionStyle(animateSelection: Boolean = false) {
        columns.forEachIndexed { index, column ->
            val isSelected = index == selectedIndex
            column.background = if (isSelected) context.getDrawable(R.drawable.bg_day_selected) else null
            val textColor = context.getColor(if (isSelected) R.color.white else R.color.text_secondary)
            (column.getChildAt(0) as TextView).setTextColor(textColor)
            (column.getChildAt(1) as TextView).setTextColor(textColor)
            UiMotion.selection(column, isSelected, animateSelection)
        }
    }

    fun setOnDaySelectedListener(listener: (Int) -> Unit) {
        onDaySelected = listener
    }
}
