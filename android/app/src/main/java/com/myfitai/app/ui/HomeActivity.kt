package com.myfitai.app.ui

import android.os.Bundle
import com.myfitai.app.R
import com.myfitai.app.navigation.BottomNavBinder
import com.myfitai.app.ui.widgets.MealCardView
import com.myfitai.app.ui.widgets.MetricCardView
import com.myfitai.app.ui.widgets.TimeRangeSelectorView
import com.myfitai.app.ui.widgets.WeightTrendChartView
import com.myfitai.app.ui.widgets.WorkoutCardView

class HomeActivity : BaseShellActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_home)
        bindBottom(BottomNavBinder.Tab.HOME)

        findViewById<android.view.View>(R.id.profileButton).setOnClickListener { go(ProfileActivity::class.java) }
        findViewById<android.view.View>(R.id.nextMealCard).setOnClickListener { go(MealDetailActivity::class.java) }
        findViewById<android.view.View>(R.id.nextWorkoutCard).setOnClickListener { go(WorkoutsActivity::class.java) }

        findViewById<MetricCardView>(R.id.metricWeight).apply {
            setLabel(getString(R.string.dashboard_metric_weight))
            setValue("78,4 kg")
            setDelta("-2,1 kg", MetricCardView.DeltaState.POSITIVE)
        }
        findViewById<MetricCardView>(R.id.metricFat).apply {
            setLabel(getString(R.string.dashboard_metric_fat))
            setValue("14,2 %")
            setDelta("-2,8 %", MetricCardView.DeltaState.POSITIVE)
        }
        findViewById<MetricCardView>(R.id.metricMuscle).apply {
            setLabel(getString(R.string.dashboard_metric_muscle))
            setValue("66,8 kg")
            setDelta("+0,6 kg", MetricCardView.DeltaState.POSITIVE)
        }

        findViewById<MealCardView>(R.id.nextMealCard).apply {
            setTime("12:30")
            setTitle("Riso basmati, pollo e verdure")
            setKcal("520 kcal")
            setImage(R.drawable.img_next_meal)
        }
        findViewById<WorkoutCardView>(R.id.nextWorkoutCard).apply {
            setTime("Oggi 18:30")
            setTitle("Upper Body")
            setImage(R.drawable.img_next_workout)
        }

        findViewById<WeightTrendChartView>(R.id.weightTrendChart).setData(
            listOf(80.1f, 79.8f, 80.3f, 79.5f, 79.9f, 79.2f, 79.6f, 78.9f, 78.4f, 78.8f, 78.1f, 77.6f, 78.0f, 77.2f, 78.4f)
        )
        findViewById<TimeRangeSelectorView>(R.id.timeRangeSelector).setRanges(listOf("1W", "1M", "3M", "1Y"), selectedIndex = 1)
    }
}