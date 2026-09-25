package com.myfitai.app.ui.widgets

import android.content.Context
import android.util.AttributeSet
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import com.myfitai.app.R

/** Selettore settimanale riutilizzabile (7 colonne giorno+numero, singola selezione). */
class WeekDaySelectorView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : LinearLayout(context, attrs) {

    data class Day(val abbreviation: String, val dayNumber: String)

    private var onDaySelected: ((Int) -> Unit)? = null
    private var selectedIndex: Int = 0
    private val columns = mutableListOf<LinearLayout>()

    init {
        orientation = HORIZONTAL
    }

    fun setDays(days: List<Day>, selectedIndex: Int = 0) {
        removeAllViews()
        columns.clear()
        this.selectedIndex = selectedIndex
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
        applySelectionStyle()
    }

    fun select(index: Int) {
        selectedIndex = index
        applySelectionStyle()
    }

    private fun applySelectionStyle() {
        columns.forEachIndexed { index, column ->
            val isSelected = index == selectedIndex
            column.background = if (isSelected) context.getDrawable(R.drawable.bg_day_selected) else null
            val textColor = context.getColor(if (isSelected) R.color.white else R.color.text_secondary)
            (column.getChildAt(0) as TextView).setTextColor(textColor)
            (column.getChildAt(1) as TextView).setTextColor(textColor)
        }
    }

    fun setOnDaySelectedListener(listener: (Int) -> Unit) {
        onDaySelected = listener
    }
}
