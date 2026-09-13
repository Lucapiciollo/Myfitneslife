package com.myfitai.app.ui

import android.os.Bundle
import android.widget.TextView
import androidx.activity.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.myfitai.app.R
import com.myfitai.app.data.AppDataContainer
import com.myfitai.app.domain.calculation.LocalCalculationEngine
import com.myfitai.app.navigation.BottomNavBinder
import com.myfitai.app.ui.home.HomeViewModel
import com.myfitai.app.ui.widgets.MealCardView
import com.myfitai.app.ui.widgets.MetricCardView
import com.myfitai.app.ui.widgets.TimeRangeSelectorView
import com.myfitai.app.ui.widgets.WeightTrendChartView
import com.myfitai.app.ui.widgets.WorkoutCardView
import kotlinx.coroutines.launch
import java.util.Locale

class HomeActivity : BaseShellActivity() {

    private val data by lazy { AppDataContainer.get(this) }
    private val viewModel: HomeViewModel by viewModels {
        HomeViewModel.Factory(
            profiles = data.userProfileRepository,
            biaRepository = data.biaRepository,
            bodyRepository = data.bodyMeasurementRepository,
            activeProfileStore = data.activeProfileStore,
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_home)
        bindBottom(BottomNavBinder.Tab.HOME)

        findViewById<android.view.View>(R.id.profileButton).setOnClickListener { go(ProfileActivity::class.java) }
        findViewById<android.view.View>(R.id.nextMealCard).setOnClickListener { go(MealDetailActivity::class.java) }
        findViewById<android.view.View>(R.id.nextWorkoutCard).setOnClickListener { go(WorkoutsActivity::class.java) }

        findViewById<MetricCardView>(R.id.metricWeight).setLabel(getString(R.string.dashboard_metric_weight))
        findViewById<MetricCardView>(R.id.metricFat).setLabel(getString(R.string.dashboard_metric_fat))
        findViewById<MetricCardView>(R.id.metricMuscle).setLabel(getString(R.string.dashboard_metric_muscle))

        // Queste due card verranno collegate ai dati reali nei rispettivi step Piano/Allenamenti.
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

        findViewById<WeightTrendChartView>(R.id.weightTrendChart).showYAxisLabels()
        findViewById<TimeRangeSelectorView>(R.id.timeRangeSelector).apply {
            setRanges(listOf("1W", "1M", "3M", "1Y"), selectedIndex = 1)
            setOnRangeSelectedListener(viewModel::selectRange)
        }

        observeDashboard()
    }

    private fun observeDashboard() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.state.collect(::renderDashboard)
            }
        }
    }

    private fun renderDashboard(state: HomeViewModel.DashboardState) {
        val firstName = state.profileName?.trim()?.substringBefore(' ')?.takeIf { it.isNotBlank() }
        findViewById<TextView>(R.id.greetingText).text = firstName?.let { "Ciao $it 👋" } ?: "Ciao 👋"

        renderMetric(
            view = findViewById(R.id.metricWeight),
            value = state.weight.value,
            delta = state.weight.deltaFromPrevious,
            unit = "kg",
            semantic = DeltaSemantic.NEUTRAL,
        )
        renderMetric(
            view = findViewById(R.id.metricFat),
            value = state.bodyFat.value,
            delta = state.bodyFat.deltaFromPrevious,
            unit = "%",
            semantic = DeltaSemantic.DOWN_IS_POSITIVE,
        )
        renderMetric(
            view = findViewById(R.id.metricMuscle),
            value = state.muscleMass.value,
            delta = state.muscleMass.deltaFromPrevious,
            unit = "kg",
            semantic = DeltaSemantic.UP_IS_POSITIVE,
        )

        findViewById<WeightTrendChartView>(R.id.weightTrendChart).setData(state.weightSeries)
        findViewById<TextView>(R.id.recompositionStateText).text = recompositionText(state.recompositionState)
    }

    private enum class DeltaSemantic { NEUTRAL, DOWN_IS_POSITIVE, UP_IS_POSITIVE }

    private fun renderMetric(
        view: MetricCardView,
        value: Float?,
        delta: Float?,
        unit: String,
        semantic: DeltaSemantic,
    ) {
        view.setValue(value?.let { "${formatNumber(it)} $unit" } ?: "—")
        if (delta == null) {
            view.setDelta("Dati insufficienti", MetricCardView.DeltaState.NEUTRAL)
            return
        }

        val state = when (semantic) {
            DeltaSemantic.NEUTRAL -> MetricCardView.DeltaState.NEUTRAL
            DeltaSemantic.DOWN_IS_POSITIVE -> when {
                delta < 0f -> MetricCardView.DeltaState.POSITIVE
                delta > 0f -> MetricCardView.DeltaState.NEGATIVE
                else -> MetricCardView.DeltaState.NEUTRAL
            }
            DeltaSemantic.UP_IS_POSITIVE -> when {
                delta > 0f -> MetricCardView.DeltaState.POSITIVE
                delta < 0f -> MetricCardView.DeltaState.NEGATIVE
                else -> MetricCardView.DeltaState.NEUTRAL
            }
        }
        view.setDelta("${formatSigned(delta)} $unit", state)
    }

    private fun recompositionText(state: LocalCalculationEngine.RecompositionState): String = when (state) {
        LocalCalculationEngine.RecompositionState.FAVORABLE -> "Trend: grasso in calo e massa muscolare in aumento."
        LocalCalculationEngine.RecompositionState.FAT_LOSS_WITH_STABLE_MUSCLE -> "Trend: grasso in calo con massa muscolare sostanzialmente stabile."
        LocalCalculationEngine.RecompositionState.MUSCLE_GAIN_WITH_STABLE_FAT -> "Trend: massa muscolare in aumento con grasso sostanzialmente stabile."
        LocalCalculationEngine.RecompositionState.STABLE -> "Trend corporeo sostanzialmente stabile."
        LocalCalculationEngine.RecompositionState.MIXED -> "Trend misto: servono più rilevazioni per una lettura più chiara."
        LocalCalculationEngine.RecompositionState.NOT_ENOUGH_DATA -> "Aggiungi almeno due rilevazioni comparabili per vedere il trend corporeo."
    }

    private fun formatNumber(value: Float): String =
        if (value % 1f == 0f) value.toInt().toString() else String.format(Locale.ITALIAN, "%.1f", value)

    private fun formatSigned(value: Float): String = String.format(Locale.ITALIAN, "%+.1f", value)
}
