package com.myfitai.app.ui

import android.os.Bundle
import android.widget.TextView
import com.myfitai.app.R
import com.myfitai.app.navigation.BottomNavBinder
import com.myfitai.app.ui.widgets.WeightTrendChartView

class HomeActivity : BaseShellActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_home)
        bindBottom(BottomNavBinder.Tab.HOME)

        findViewById<android.view.View>(R.id.profileButton).setOnClickListener { go(ProfileActivity::class.java) }
        findViewById<android.view.View>(R.id.nextMealCard).setOnClickListener { go(MealDetailActivity::class.java) }
        findViewById<android.view.View>(R.id.nextWorkoutCard).setOnClickListener { go(WorkoutsActivity::class.java) }

        findViewById<WeightTrendChartView>(R.id.weightTrendChart).setData(
            listOf(80.1f, 79.8f, 80.3f, 79.5f, 79.9f, 79.2f, 79.6f, 78.9f, 78.4f, 78.8f, 78.1f, 77.6f, 78.0f, 77.2f, 78.4f)
        )
        bindTimeRangeSelector()
    }

    private fun bindTimeRangeSelector() {
        val buttons = listOf(R.id.rangeButton1W, R.id.rangeButton1M, R.id.rangeButton3M, R.id.rangeButton1Y)
        buttons.forEach { id ->
            findViewById<TextView>(id).setOnClickListener { selected ->
                buttons.forEach { otherId ->
                    val button = findViewById<TextView>(otherId)
                    val isSelected = otherId == id
                    button.setBackgroundResource(if (isSelected) R.drawable.bg_pill_selected else 0)
                    button.setTextColor(getColor(if (isSelected) R.color.accent_green_dark else R.color.text_muted))
                    button.setTypeface(null, if (isSelected) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
                }
            }
        }
    }
}