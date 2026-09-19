package com.myfitai.app.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.myfitai.app.R
import com.myfitai.app.data.AppDataContainer
import com.myfitai.app.domain.calculation.LocalCalculationEngine
import com.myfitai.app.navigation.BottomNavBinder
import com.myfitai.app.notifications.NotificationPreferences
import com.myfitai.app.ui.home.HomeViewModel
import com.myfitai.app.ui.widgets.MealCardView
import com.myfitai.app.ui.widgets.MetricCardView
import com.myfitai.app.ui.widgets.TimeRangeSelectorView
import com.myfitai.app.ui.widgets.WeightTrendChartView
import com.myfitai.app.ui.widgets.WorkoutCardView
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

class HomeActivity : BaseShellActivity() {

    private val data by lazy { AppDataContainer.get(this) }
    private val viewModel: HomeViewModel by viewModels {
        HomeViewModel.Factory(
            profiles = data.userProfileRepository,
            biaRepository = data.biaRepository,
            bodyRepository = data.bodyMeasurementRepository,
            workoutRepository = data.workoutRepository,
            mealPlanRepository = data.mealPlanRepository,
            activeProfileStore = data.activeProfileStore,
        )
    }

    private val notificationPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) lifecycleScope.launch { runCatching { data.notificationScheduler.refresh() } }
    }

    private var currentNextMealId: Long? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_home)
        bindBottom(BottomNavBinder.Tab.HOME)
        requestNotificationPermissionOnce()

        findViewById<android.view.View>(R.id.profileButton).setOnClickListener { go(ProfileActivity::class.java) }
        findViewById<android.view.View>(R.id.nextMealCard).setOnClickListener {
            if (!com.myfitai.app.ai.AiProviderAccess.requireConfigured(this)) return@setOnClickListener
            currentNextMealId?.let { mealId ->
                startActivity(Intent(this, MealDetailActivity::class.java).putExtra(MealDetailActivity.EXTRA_MEAL_ID, mealId))
            } ?: openFoodPlan()
        }
        findViewById<android.view.View>(R.id.nextWorkoutCard).setOnClickListener { go(WorkoutsActivity::class.java) }
        findViewById<android.view.View>(R.id.measurementsButton).setOnClickListener { go(MeasurementsActivity::class.java) }
        findViewById<TextView>(R.id.todayLabel).text = todayLabel()

        findViewById<MetricCardView>(R.id.metricWeight).setLabel(getString(R.string.dashboard_metric_weight))
        findViewById<MetricCardView>(R.id.metricFat).setLabel(getString(R.string.dashboard_metric_fat))
        findViewById<MetricCardView>(R.id.metricMuscle).setLabel(getString(R.string.dashboard_metric_muscle))

        findViewById<WeightTrendChartView>(R.id.weightTrendChart).showYAxisLabels()
        findViewById<TimeRangeSelectorView>(R.id.timeRangeSelector).apply {
            setRanges(listOf("1W", "1M", "3M", "1Y"), selectedIndex = 1)
            setOnRangeSelectedListener(viewModel::selectRange)
        }

        observeDashboard()
    }

    private fun requestNotificationPermissionOnce() {
        if (Build.VERSION.SDK_INT < 33) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) return
        val prefs = NotificationPreferences(this)
        if (prefs.permissionPrompted) return
        prefs.permissionPrompted = true
        notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
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

        renderMetric(findViewById(R.id.metricWeight), state.weight.value, state.weight.deltaFromPrevious, "kg", DeltaSemantic.NEUTRAL)
        renderMetric(findViewById(R.id.metricFat), state.bodyFat.value, state.bodyFat.deltaFromPrevious, "%", DeltaSemantic.DOWN_IS_POSITIVE)
        renderMetric(findViewById(R.id.metricMuscle), state.muscleMass.value, state.muscleMass.deltaFromPrevious, "kg", DeltaSemantic.UP_IS_POSITIVE)

        findViewById<WeightTrendChartView>(R.id.weightTrendChart).setSeries(
            state.trendSeries.map { it.label to it.values },
        )
        findViewById<TextView>(R.id.recompositionStateText).text = recompositionText(state.recompositionState)
        renderCalories(state.calories)
        renderNextMeal(state.nextMeal)
        renderNextWorkout(state.nextWorkout)
    }

    private fun renderCalories(calories: HomeViewModel.CalorieState) {
        fun kcal(value: Int?) = value?.let { "$it kcal" } ?: "—"
        findViewById<TextView>(R.id.caloriesBmrValue).text = kcal(calories.bmr)
        findViewById<TextView>(R.id.caloriesTdeeValue).text = kcal(calories.tdee)
        findViewById<TextView>(R.id.caloriesTargetValue).text = kcal(calories.target)
    }

    private fun renderNextMeal(next: HomeViewModel.NextMealState?) {
        currentNextMealId = next?.mealId
        findViewById<MealCardView>(R.id.nextMealCard).apply {
            if (next == null) {
                setTime("—")
                setTitle("Nessun pasto pianificato")
                setKcal("Apri il piano alimentare")
                setImage(R.drawable.img_next_meal)
                contentDescription = "Nessun pasto pianificato. Apri il piano alimentare"
                return@apply
            }
            val date = LocalDate.ofEpochDay(next.dateEpochDay)
            val today = LocalDate.now()
            val dayLabel = when (date) {
                today -> "Oggi"
                today.plusDays(1) -> "Domani"
                else -> date.format(DateTimeFormatter.ofPattern("EEE d MMM", Locale.ITALIAN))
            }
            val time = next.timeMinutes?.let { String.format(Locale.ITALIAN, "%02d:%02d", it / 60, it % 60) }
            setTime(listOfNotNull(dayLabel, time).joinToString(" "))
            setTitle(next.title)
            setKcal(next.kcal?.let { "$it kcal" } ?: next.type)
            setImage(R.drawable.img_next_meal)
            contentDescription = "Prossimo pasto: ${next.title}"
        }
    }

    private fun renderNextWorkout(next: HomeViewModel.NextWorkoutState?) {
        findViewById<WorkoutCardView>(R.id.nextWorkoutCard).apply {
            if (next == null) {
                setTime("—")
                setTitle("Nessun allenamento pianificato")
                setImage(R.drawable.img_next_workout)
                contentDescription = "Nessun allenamento pianificato. Apri Allenamenti"
                return@apply
            }
            val dateTime = Instant.ofEpochMilli(next.startedAtEpochMillis).atZone(ZoneId.systemDefault())
            val today = LocalDate.now()
            val dayLabel = when (dateTime.toLocalDate()) {
                today -> "Oggi"
                today.plusDays(1) -> "Domani"
                else -> dateTime.format(DateTimeFormatter.ofPattern("EEE d MMM", Locale.ITALIAN))
            }
            setTime("$dayLabel ${dateTime.format(DateTimeFormatter.ofPattern("HH:mm", Locale.ITALIAN))}")
            setTitle(next.title)
            setImage(if (next.type.equals("Cardio", true)) R.drawable.img_workout_cardio else R.drawable.img_workout_weights)
            contentDescription = "Prossimo allenamento: ${next.title}"
        }
    }

    private fun todayLabel(): String = LocalDate.now()
        .format(DateTimeFormatter.ofPattern("EEEE d MMMM", Locale.ITALIAN))
        .replaceFirstChar { it.uppercase(Locale.ITALIAN) }

    private enum class DeltaSemantic { NEUTRAL, DOWN_IS_POSITIVE, UP_IS_POSITIVE }

    private fun renderMetric(view: MetricCardView, value: Float?, delta: Float?, unit: String, semantic: DeltaSemantic) {
        view.setValue(value?.let { "${formatNumber(it)} $unit" } ?: "—")
        if (delta == null) {
            view.setDelta("Dati insufficienti", MetricCardView.DeltaState.NEUTRAL)
            return
        }
        val state = when (semantic) {
            DeltaSemantic.NEUTRAL -> MetricCardView.DeltaState.NEUTRAL
            DeltaSemantic.DOWN_IS_POSITIVE -> when { delta < 0f -> MetricCardView.DeltaState.POSITIVE; delta > 0f -> MetricCardView.DeltaState.NEGATIVE; else -> MetricCardView.DeltaState.NEUTRAL }
            DeltaSemantic.UP_IS_POSITIVE -> when { delta > 0f -> MetricCardView.DeltaState.POSITIVE; delta < 0f -> MetricCardView.DeltaState.NEGATIVE; else -> MetricCardView.DeltaState.NEUTRAL }
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

    private fun formatNumber(value: Float): String = if (value % 1f == 0f) value.toInt().toString() else String.format(Locale.ITALIAN, "%.1f", value)
    private fun formatSigned(value: Float): String = String.format(Locale.ITALIAN, "%+.1f", value)
}
