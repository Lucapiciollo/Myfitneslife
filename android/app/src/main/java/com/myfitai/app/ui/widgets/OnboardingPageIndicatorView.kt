package com.myfitai.app.ui.widgets

import android.content.Context
import android.util.AttributeSet
import android.widget.LinearLayout
import com.myfitai.app.R

/** Indicatore di pagina riutilizzabile per l'onboarding: N pallini reali, nessun carattere Unicode. */
class OnboardingPageIndicatorView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : LinearLayout(context, attrs) {

    private var count = 3
    private var selectedIndex = 0
    private val dots = mutableListOf<android.view.View>()

    init {
        orientation = HORIZONTAL
        gravity = android.view.Gravity.CENTER
        buildDots()
    }

    private fun buildDots() {
        removeAllViews()
        dots.clear()
        val size = resources.getDimensionPixelSize(R.dimen.space_8)
        val gap = resources.getDimensionPixelSize(R.dimen.space_8)
        repeat(count) { index ->
            val dot = android.view.View(context).apply {
                layoutParams = LayoutParams(size, size).apply {
                    marginStart = if (index == 0) 0 else gap
                }
            }
            addView(dot)
            dots.add(dot)
        }
        applySelection()
    }

    private fun applySelection() {
        dots.forEachIndexed { index, dot ->
            dot.background = context.getDrawable(
                if (index == selectedIndex) R.drawable.bg_page_indicator_selected else R.drawable.bg_page_indicator_unselected
            )
        }
    }

    fun setCount(newCount: Int) {
        count = newCount
        buildDots()
    }

    fun setSelectedIndex(index: Int) {
        selectedIndex = index
        applySelection()
    }
}
