package com.myfitai.app.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.TextView
import android.widget.GridLayout
import android.view.ViewGroup
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.myfitai.app.R
import com.myfitai.app.data.AppDataContainer
import com.myfitai.app.domain.calculation.LocalCalculationEngine
import com.myfitai.app.domain.calculation.DailyActivityCheckInEngine
import com.myfitai.app.navigation.BottomNavBinder
import com.myfitai.app.notifications.NotificationPreferences
import com.myfitai.app.data.profile.BiaFrequencyPreferences
import com.myfitai.app.data.profile.AiAutomationPreferences
import com.myfitai.app.ui.home.HomeViewModel
import com.myfitai.app.ui.widgets.MealCardView
import com.myfitai.app.ui.components.DashboardMetricPresentation
import com.myfitai.app.ui.components.DashboardMetricsPanel
import com.myfitai.app.ui.components.MetricDeltaTone
import com.myfitai.app.ui.theme.setMyFitAiContent
import com.google.android.material.textfield.MaterialAutoCompleteTextView
import com.myfitai.app.ui.widgets.TimeRangeSelectorView
import com.myfitai.app.ui.widgets.BodyMeasurementTrendView
import com.myfitai.app.ui.widgets.WorkoutCardView
import com.myfitai.app.ui.motion.UiMotion
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.DayOfWeek
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.ceil

class HomeActivity : BaseShellActivity() {

    private var selectedBodyTrendIndex = 0
    private var bodyTrendLabels: List<String> = emptyList()

    private val data by lazy { AppDataContainer.get(this) }
    private val viewModel: HomeViewModel by viewModels {
        HomeViewModel.Factory(
            profiles = data.userProfileRepository,
            biaRepository = data.biaRepository,
            bodyRepository = data.bodyMeasurementRepository,
            workoutRepository = data.workoutRepository,
            mealPlanRepository = data.mealPlanRepository,
            cheatRepository = data.cheatEntryRepository,
            recoveryRepository = data.calorieRecoveryRepository,
            foodConsumptionRepository = data.foodConsumptionRepository,
            bodyExpectationGoalRepository = data.bodyExpectationGoalRepository,
            nutritionPlanSchedulePreferences = data.nutritionPlanSchedulePreferences,
            activeProfileStore = data.activeProfileStore,
            dailyActivityCheckIns = data.dailyActivityCheckInRepository,
            profileCalculationService = data.profileCalculationService,
        )
    }

    private val notificationPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val prefs = NotificationPreferences(this)
        prefs.permissionPrompted = true
        if (granted) {
            lifecycleScope.launch { runCatching { data.notificationScheduler.refresh() } }
        }
    }

    private var currentNextMealId: Long? = null
    private var currentCalories: HomeViewModel.CalorieState? = null
    private var currentUpcomingMeals: List<HomeViewModel.NextMealState> = emptyList()
    private val motionVisibilityTargets = mutableMapOf<Int, Boolean>()
    private var dashboardMetricPresentations by mutableStateOf(emptyList<DashboardMetricPresentation>())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_home)
        moveBodyOverviewToSecondBlock()
        moveRecoveryToThirdBlock()
        moveOperationalSectionsToEnd()
        adaptLandscapeContent()
        adaptQuickActions()
        bindBottom(BottomNavBinder.Tab.HOME)
        installDashboardMetricsComposeView()
        renderWorkoutConfiguration()
        requestNotificationPermissionOnce()

        findViewById<android.view.View>(R.id.profileButton).setOnClickListener { go(ProfileActivity::class.java) }
        findViewById<android.view.View>(R.id.planUpdateNoticeCard).setOnClickListener { openFoodPlan() }
        findViewById<android.view.View>(R.id.planUpdateNoticeButton).apply {
            setAiActionEnabled(this)
            setOnClickListener { openFoodPlan() }
        }
        findViewById<android.view.View>(R.id.aiConfigurationNoticeCard).setOnClickListener { startActivity(Intent(this, SettingsActivity::class.java).putExtra(BottomNavBinder.EXTRA_SELECTED_TAB, BottomNavBinder.Tab.AI.name)) }
        findViewById<android.view.View>(R.id.aiConfigurationNoticeButton).setOnClickListener { startActivity(Intent(this, SettingsActivity::class.java).putExtra(BottomNavBinder.EXTRA_SELECTED_TAB, BottomNavBinder.Tab.AI.name)) }
        findViewById<View>(R.id.aiConfigurationNoticeButton).contentDescription = "Apri impostazioni IA"
        findViewById<android.view.View>(R.id.biaDueNoticeCard).setOnClickListener { startActivity(Intent(this, SettingsActivity::class.java)) }
        findViewById<android.view.View>(R.id.biaDueNoticeButton).setOnClickListener { startActivity(Intent(this, SettingsActivity::class.java)) }
        findViewById<android.view.View>(R.id.analysisHubButton).setOnClickListener { startActivity(Intent(this, MeasurementsActivity::class.java)) }
        findViewById<android.view.View>(R.id.weeklyExpectationActionButton).setOnClickListener {
            viewModel.confirmExpectation("REACHED")
        }
        findViewById<android.view.View>(R.id.weeklyExpectationNegativeButton).setOnClickListener {
            viewModel.confirmExpectation("NOT_REACHED")
        }
        findViewById<android.view.View>(R.id.quickAddBiaButton).setOnClickListener {
            startActivity(Intent(this, BiaActivity::class.java))
        }
        findViewById<android.view.View>(R.id.quickAddBodyMeasurementButton).setOnClickListener {
            startActivity(Intent(this, NewBodyMeasurementActivity::class.java))
        }
        findViewById<android.view.View>(R.id.quickOpenHistoryButton).setOnClickListener {
            startActivity(Intent(this, HistoryActivity::class.java))
        }
        findViewById<android.view.View>(R.id.nextMealCard).setOnClickListener {
            currentNextMealId?.let { mealId ->
                startActivity(Intent(this, MealDetailActivity::class.java).putExtra(MealDetailActivity.EXTRA_MEAL_ID, mealId))
            } ?: openFoodPlan()
        }
        findViewById<android.view.View>(R.id.secondNextMealCard).setOnClickListener {
            currentUpcomingMeals.getOrNull(1)?.let { meal ->
                startActivity(Intent(this, MealDetailActivity::class.java).putExtra(MealDetailActivity.EXTRA_MEAL_ID, meal.mealId))
            }
        }
        findViewById<android.view.View>(R.id.nextWorkoutCard).setOnClickListener { go(WorkoutsActivity::class.java) }
        findViewById<android.view.View>(R.id.caloriesCard).setOnClickListener { showTdeeExplanation() }
        findViewById<android.view.View>(R.id.caloriesHelpButton).setOnClickListener { showTdeeExplanation() }
        findViewById<android.view.View>(R.id.activityCheckInRestButton).setOnClickListener { viewModel.setRestDay() }
        findViewById<android.view.View>(R.id.activityCheckInWorkoutButton).setOnClickListener { showActivityCheckInDialog() }
        findViewById<android.view.View>(R.id.activityCheckInEditButton).setOnClickListener { showActivityCheckInDialog() }
        findViewById<android.view.View>(R.id.activityCheckInClearButton).setOnClickListener { viewModel.clearActivityCheckIn() }
        findViewById<android.view.View>(R.id.bodyOverviewHelpButton).setOnClickListener {
            showHomeHelp("Panoramica del corpo", "Le tre card mostrano l'ultima rilevazione disponibile di peso, percentuale di grasso e massa muscolare. Il grafico mostra l'andamento del peso nel periodo selezionato.")
        }
        findViewById<android.view.View>(R.id.weeklyExpectationHelpButton).setOnClickListener {
            showHomeHelp(
                "Possibile calo teorico",
                "Confronta il consumo giornaliero stimato con le calorie dei giorni già presenti nel piano di questa settimana. " +
                    "Il risultato indica quanta energia potrebbe corrispondere a grasso, non quanto peso perderai davvero. " +
                    "Acqua, glicogeno, adattamenti e composizione corporea possono cambiare il risultato."
            )
        }
        findViewById<android.view.View>(R.id.recoveryHelpButton).setOnClickListener {
            showHomeHelp("Riserva di recupero", "Indica le calorie extra da distribuire nei giorni successivi quando hai consumato meno del previsto o hai registrato uno sgarro. È un supporto al riequilibrio, non una misura clinica.")
        }
        findViewById<android.view.View>(R.id.nextMealHelpButton).setOnClickListener {
            showHomeHelp("Prossimo pasto", "Mostra il prossimo pasto previsto dal piano alimentare di oggi, con orario, nome e calorie quando disponibili.")
        }
        findViewById<android.view.View>(R.id.nextWorkoutHelpButton).setOnClickListener {
            showHomeHelp("Prossimo allenamento", "Mostra il prossimo allenamento programmato, così puoi vedere rapidamente cosa è previsto oggi o nei prossimi giorni.")
        }
        findViewById<android.view.View>(R.id.todayMenuButton).setOnClickListener { showTodayMenu() }
        findViewById<android.view.View>(R.id.measurementsButton).setOnClickListener { go(MeasurementsActivity::class.java) }
        findViewById<android.view.View>(R.id.addExtraButton).apply {
            setAiActionEnabled(this)
            setOnClickListener {
                startActivity(Intent(this@HomeActivity, CheatEntryActivity::class.java))
            }
        }
        findViewById<TextView>(R.id.todayLabel).text = todayLabel()
        renderAiConfigurationNotice()

        findViewById<TimeRangeSelectorView>(R.id.timeRangeSelector).apply {
            // Keep the dashboard selector aligned with the full body-history view so
            // less frequent measurements are available without an extra navigation step.
            setRanges(listOf("1W", "1M", "3M", "1Y"), selectedIndex = 3)
            setOnRangeSelectedListener(viewModel::selectRange)
        }

        observeDashboard()
    }

    private fun installDashboardMetricsComposeView() {
        val composeView = findViewById<ComposeView>(R.id.dashboardMetricsPanel)
        dashboardMetricPresentations = listOf(
            loadingMetric(getString(R.string.dashboard_metric_weight), R.drawable.ic_trend_down),
            loadingMetric(getString(R.string.dashboard_metric_fat), R.drawable.ic_trend_down),
            loadingMetric(getString(R.string.dashboard_metric_muscle), R.drawable.ic_trend_up),
        )
        composeView.setMyFitAiContent {
            DashboardMetricsPanel(dashboardMetricPresentations)
        }
    }

    private fun loadingMetric(label: String, trendIcon: Int) = DashboardMetricPresentation(
        label = label,
        value = getString(R.string.dashboard_loading),
        delta = getString(R.string.dashboard_loading),
        tone = MetricDeltaTone.Neutral,
        trendIcon = trendIcon,
    )

    private fun renderDashboardMetrics(state: HomeViewModel.DashboardState) {
        dashboardMetricPresentations = listOf(
            metricPresentation(
                getString(R.string.dashboard_metric_weight), state.weight.value,
                state.weight.deltaFromPrevious, "kg", DeltaSemantic.NEUTRAL,
                state.loading, state.weight.sourceLabel, R.drawable.ic_trend_down,
            ),
            metricPresentation(
                getString(R.string.dashboard_metric_fat), state.bodyFat.value,
                state.bodyFat.deltaFromPrevious, "%", DeltaSemantic.DOWN_IS_POSITIVE,
                state.loading, state.bodyFat.sourceLabel, R.drawable.ic_trend_down,
            ),
            metricPresentation(
                getString(R.string.dashboard_metric_muscle), state.muscleMass.value,
                state.muscleMass.deltaFromPrevious, "kg", DeltaSemantic.UP_IS_POSITIVE,
                state.loading, state.muscleMass.sourceLabel, R.drawable.ic_trend_up,
            ),
        )
    }

    private fun metricPresentation(
        label: String,
        value: Float?,
        delta: Float?,
        unit: String,
        semantic: DeltaSemantic,
        loading: Boolean,
        sourceLabel: String?,
        trendIcon: Int,
    ): DashboardMetricPresentation {
        val displayLabel = label + sourceLabel?.let { " · $it" }.orEmpty()
        if (loading) return loadingMetric(displayLabel, trendIcon)

        val displayValue = value?.let { "${formatNumber(it)} $unit" }
            ?: getString(R.string.dashboard_metric_unavailable)
        if (delta == null) {
            return DashboardMetricPresentation(
                displayLabel, displayValue,
                getString(R.string.dashboard_metric_insufficient_data),
                MetricDeltaTone.Neutral, trendIcon,
            )
        }
        val tone = when (semantic) {
            DeltaSemantic.NEUTRAL -> MetricDeltaTone.Neutral
            DeltaSemantic.DOWN_IS_POSITIVE -> when {
                delta < 0f -> MetricDeltaTone.Positive
                delta > 0f -> MetricDeltaTone.Negative
                else -> MetricDeltaTone.Neutral
            }
            DeltaSemantic.UP_IS_POSITIVE -> when {
                delta > 0f -> MetricDeltaTone.Positive
                delta < 0f -> MetricDeltaTone.Negative
                else -> MetricDeltaTone.Neutral
            }
        }
        return DashboardMetricPresentation(
            displayLabel,
            displayValue,
            "${formatNumber(kotlin.math.abs(delta))} $unit",
            tone,
            if (tone == MetricDeltaTone.Positive) R.drawable.ic_trend_up else R.drawable.ic_trend_down,
        )
    }

    private fun adaptLandscapeContent() {
        if (resources.configuration.orientation != android.content.res.Configuration.ORIENTATION_LANDSCAPE) return
        val metrics = findViewById<View>(R.id.dashboardMetricsPanel) ?: return
        val sheet = metrics.parent as? ViewGroup ?: return
        if (sheet.findViewWithTag<View>(LANDSCAPE_GRID_TAG) != null) return

        val children = (0 until sheet.childCount).map { sheet.getChildAt(it) }
        val grid = GridLayout(this).apply {
            tag = LANDSCAPE_GRID_TAG
            columnCount = 2
            rowCount = GridLayout.UNDEFINED
            useDefaultMargins = false
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        sheet.removeAllViews()
        sheet.addView(grid)

        children.forEach { child ->
            val fullWidth = child.id == R.id.planUpdateNoticeCard ||
                child.id == R.id.aiConfigurationNoticeCard ||
                child.id == R.id.biaDueNoticeCard ||
                child.id == R.id.analysisHubCard ||
                child.id == R.id.quickMeasurementsCard ||
                child.id == R.id.quickActionsCard ||
                child.id == R.id.activityCheckInCard ||
                child.id == R.id.dashboardMetricsPanel ||
                child.findViewById<View>(R.id.bodyOverviewHelpButton) != null
            val params = GridLayout.LayoutParams().apply {
                width = 0
                height = ViewGroup.LayoutParams.WRAP_CONTENT
                columnSpec = GridLayout.spec(GridLayout.UNDEFINED, if (fullWidth) 2 else 1, 1f)
                rowSpec = GridLayout.spec(GridLayout.UNDEFINED)
                setMargins(
                    resources.getDimensionPixelSize(R.dimen.space_6),
                    resources.getDimensionPixelSize(R.dimen.space_6),
                    resources.getDimensionPixelSize(R.dimen.space_6),
                    resources.getDimensionPixelSize(R.dimen.space_6),
                )
            }
            grid.addView(child, params)
        }
    }

    private fun adaptQuickActions() {
        val actions = findViewById<android.widget.LinearLayout>(R.id.quickActionsRow) ?: return
        if (resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE) {
            actions.orientation = android.widget.LinearLayout.HORIZONTAL
            actions.findViewById<View>(R.id.measurementsButton)?.layoutParams =
                android.widget.LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            actions.findViewById<View>(R.id.addExtraButton)?.layoutParams =
                android.widget.LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginStart = resources.getDimensionPixelSize(R.dimen.space_8)
                    topMargin = 0
                }
        }
    }

    private fun moveOperationalSectionsToEnd() {
        val sections = listOf(
            findViewById<View>(R.id.analysisHubCard),
            findViewById<View>(R.id.quickMeasurementsCard),
            findViewById<View>(R.id.quickActionsCard),
        )
        val parent = sections.firstOrNull()?.parent as? ViewGroup ?: return
        if (sections.any { it.parent !== parent }) return
        sections.forEach(parent::removeView)
        sections.forEach { parent.addView(it) }
    }

    private fun moveBodyOverviewToSecondBlock() {
        val bodyCard = findViewById<View>(R.id.bodyOverviewCard)
        val parent = bodyCard.parent as? ViewGroup ?: return
        parent.removeView(bodyCard)
        parent.addView(bodyCard, 0)
    }

    private fun moveRecoveryToThirdBlock() {
        val bodyCard = findViewById<View>(R.id.bodyOverviewCard)
        val recoveryCard = findViewById<View>(R.id.recoveryCard)
        val parent = bodyCard.parent as? ViewGroup ?: return
        if (recoveryCard.parent !== parent) return
        parent.removeView(recoveryCard)
        parent.addView(recoveryCard, 1.coerceAtMost(parent.childCount))
    }

    override fun onResume() {
        super.onResume()
        renderWorkoutConfiguration()
        renderPlanUpdateNotice()
        renderAiConfigurationNotice()
        renderBiaDueNotice(viewModel.state.value)
    }

    private fun renderPlanUpdateNotice() {
        val profileId = data.activeProfileStore.currentIdOrNull()
        findViewById<android.view.View>(R.id.planUpdateNoticeCard)?.let { card ->
            revealState(card, profileId != null && data.nutritionPlanUpdatePreferences.isPending(profileId))
        }
    }

    private fun renderAiConfigurationNotice() {
        val providerAvailable = aiProviderConfigured
        findViewById<android.view.View>(R.id.aiConfigurationNoticeCard)?.let { card -> revealState(card, !providerAvailable) }
        findViewById<View?>(R.id.todayMenuButton)?.let { setAiActionEnabled(it, providerAvailable) }
        findViewById<View?>(R.id.planUpdateNoticeButton)?.let { setAiActionEnabled(it, providerAvailable) }
        findViewById<View?>(R.id.addExtraButton)?.let { setAiActionEnabled(it, providerAvailable) }
    }

    private fun renderWorkoutConfiguration() {
        val profileId = data.activeProfileStore.currentIdOrNull() ?: return
        val enabled = data.workoutPreferences.isEnabled(profileId)
        findViewById<android.view.View>(R.id.workoutSectionCard)?.let { card -> revealState(card, enabled) }
    }

    private fun revealState(view: android.view.View, visible: Boolean) {
        val previous = motionVisibilityTargets.put(view.id, visible)
        UiMotion.reveal(view, visible, animateChange = previous != null)
    }

    private fun requestNotificationPermissionOnce() {
        if (Build.VERSION.SDK_INT < 33) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) return
        val prefs = NotificationPreferences(this)
        if (prefs.permissionPrompted) return
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
        renderPlanUpdateNotice()
        renderAiConfigurationNotice()
        renderBiaDueNotice(state)
        val firstName = state.profileName?.trim()?.substringBefore(' ')?.takeIf { it.isNotBlank() }
        findViewById<TextView>(R.id.greetingText).text = firstName?.let { "Ciao $it 👋" } ?: "Ciao 👋"

        renderDashboardMetrics(state)

        renderBodyTrend(state.bodyMeasurementTrendSeries)
        findViewById<TextView>(R.id.recompositionStateText).text = recompositionText(state.recompositionState)
        renderCalories(state.calories)
        renderActivityCheckIn(state.activityCheckIn, state.calories)
        renderWeeklyExpectation(state.weeklyExpectation)
        renderRecovery(state.recovery)
        renderUpcomingMeals(state.upcomingMeals)
        renderNextMeal(state.nextMeal)
        renderNextWorkout(state.nextWorkout)
    }

    private fun renderBodyTrend(series: List<HomeViewModel.TrendSeries>) {
        val selector = findViewById<MaterialAutoCompleteTextView>(R.id.bodyTrendMetricSelector)
        val labels = series.map { it.label }
        if (labels != bodyTrendLabels) {
            bodyTrendLabels = labels
            selectedBodyTrendIndex = selectedBodyTrendIndex.coerceIn(0, (series.size - 1).coerceAtLeast(0))
            selector.setMyFitAiDropdownItems(labels)
            selector.setText(labels.getOrNull(selectedBodyTrendIndex).orEmpty(), false)
        }
        val selected = series.getOrNull(selectedBodyTrendIndex)
        selector.threshold = 0
        selector.setOnItemClickListener { _, _, index, _ ->
            selectedBodyTrendIndex = index.coerceIn(0, (series.size - 1).coerceAtLeast(0))
            selector.setText(labels.getOrNull(selectedBodyTrendIndex).orEmpty(), false)
            renderBodyTrend(viewModel.state.value.bodyMeasurementTrendSeries)
        }
        val unit = when {
            selected?.label == "Grasso corporeo" || selected?.label == "Acqua corporea" -> "%"
            selected?.label == "Peso" || selected?.label?.startsWith("Massa") == true -> "kg"
            else -> "cm"
        }
        findViewById<BodyMeasurementTrendView>(R.id.bodyMeasurementTrendChart).setRealSeries(
            selected?.let { item ->
                BodyMeasurementTrendView.Series(
                    label = item.label,
                    points = item.points.map { point ->
                        BodyMeasurementTrendView.Point(
                            label = Instant.ofEpochMilli(point.timestamp).atZone(ZoneId.systemDefault())
                                .format(DateTimeFormatter.ofPattern("dd/MM", Locale.ITALIAN)),
                            value = point.value,
                        )
                    },
                )
            },
            unit,
        )
    }

    private fun renderWeeklyExpectation(result: com.myfitai.app.domain.calculation.WeeklyBodyExpectation.Result) {
        val status = findViewById<TextView>(R.id.weeklyExpectationStatus)
        val detail = findViewById<TextView>(R.id.weeklyExpectationDetail)
        val caution = findViewById<TextView>(R.id.weeklyExpectationCaution)
        val monday = LocalDate.now().with(java.time.temporal.TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        val periodEnd = monday.plusWeeks(result.periodWeeks.toLong()).minusDays(1)
        val period = "${monday.format(DateTimeFormatter.ofPattern("dd/MM", Locale.ITALIAN))}–${periodEnd.format(DateTimeFormatter.ofPattern("dd/MM", Locale.ITALIAN))}"
        when {
            result.available -> {
                val minLoss = requireNotNull(result.expectedFatLossKgMin)
                val maxLoss = requireNotNull(result.expectedFatLossKgMax)
                status.text = "≈ ${formatExpectedLossRange(minLoss, maxLoss)} di grasso"
                detail.text = "Periodo $period · ${result.plannedDays}/${result.periodWeeks * 7} giorni pianificati · deficit ≈ ${result.theoreticalDeficitKcal} kcal"
            }
            result.plannedDays > 0 -> {
                status.text = "Nessuna perdita stimabile"
                detail.text = "Periodo $period · bilancio energetico non in deficit"
            }
            else -> {
                status.text = "Dati insufficienti"
                detail.text = "Genera un piano alimentare per visualizzare una stima indicativa."
            }
        }
        caution.text = result.caution
        val hasResult = result.plannedDays > 0
        val currentGoal = viewModel.state.value.expectationGoals.firstOrNull { it.periodStartEpochDay == monday.toEpochDay() }
        findViewById<TextView>(R.id.weeklyExpectationActionButton).apply {
            visibility = if (hasResult) View.VISIBLE else View.GONE
            val goal = viewModel.state.value.expectationGoals.firstOrNull { it.periodStartEpochDay == monday.toEpochDay() }
            text = if (goal?.status == "REACHED") "Sì, traguardo raggiunto" else "Sì, traguardo raggiunto"
        }
        findViewById<TextView>(R.id.weeklyExpectationNegativeButton).apply {
            visibility = if (hasResult) View.VISIBLE else View.GONE
            text = "No, da migliorare"
        }
        findViewById<TextView>(R.id.weeklyExpectationOutcomeText).apply {
            visibility = if (currentGoal != null) View.VISIBLE else View.GONE
            text = when (currentGoal?.status) {
                "REACHED" -> "Esito registrato: sì, traguardo raggiunto."
                "NOT_REACHED" -> "Esito registrato: no, da migliorare."
                else -> ""
            }
        }
    }

    private fun formatExpectedLossRange(minKg: Double, maxKg: Double): String {
        val minText = formatExpectedLoss(minKg)
        val maxText = formatExpectedLoss(maxKg)
        return if (minText == maxText) minText else "$minText–$maxText"
    }

    private fun formatExpectedLoss(kg: Double): String = if (kg < 1.0) {
        "${ceil(kg * 10).toInt().coerceAtLeast(1)} etti"
    } else {
        "${String.format(Locale.ITALIAN, "%.1f", kg)} kg"
    }

    private fun renderRecovery(recovery: HomeViewModel.RecoveryState) {
        val value = findViewById<TextView>(R.id.recoveryValueText)
        val hint = findViewById<TextView>(R.id.recoveryHintText)
        if (recovery.pendingKcal > 0) {
            value.text = "${recovery.pendingKcal} kcal"
            val credits = if (recovery.creditCount == 1) "1 sgarro recente" else "${recovery.creditCount} sgarri recenti"
            val expiry = recovery.nextExpiry?.format(DateTimeFormatter.ofPattern("dd/MM", Locale.ITALIAN))
            hint.text = "Da distribuire gradualmente nei prossimi giorni · $credits${expiry?.let { " · prima scadenza $it" }.orEmpty()}. Evita compensazioni drastiche."
        } else {
            value.text = "0 kcal"
            hint.text = "Nessun extra da distribuire: sei in pari con il recupero."
        }
    }

    private fun renderUpcomingMeals(meals: List<HomeViewModel.NextMealState>) {
        currentUpcomingMeals = meals
        findViewById<android.view.View>(R.id.todayMenuButton).visibility =
            if (meals.size >= 2) android.view.View.VISIBLE else android.view.View.GONE
        val second = meals.getOrNull(1)
        findViewById<MealCardView>(R.id.secondNextMealCard).apply {
            visibility = android.view.View.VISIBLE
            if (second == null) {
                setTime("—")
                setTitle("Nessun altro pasto pianificato")
                setKcal("Apri il piano alimentare")
                setImage(R.drawable.img_next_meal)
            } else {
                renderMeal(this, second)
            }
        }
    }

    private fun showTodayMenu() {
        val meals = currentUpcomingMeals
        if (meals.isEmpty()) {
            openFoodPlan()
            return
        }
        val items = meals.map { meal ->
            val date = LocalDate.ofEpochDay(meal.dateEpochDay)
            val today = LocalDate.now()
            val dayLabel = when (date) {
                today -> "Oggi"
                today.plusDays(1) -> "Domani"
                else -> date.format(DateTimeFormatter.ofPattern("EEE d MMM", Locale.ITALIAN))
            }
            val time = meal.timeMinutes?.let { String.format(Locale.ITALIAN, "%02d:%02d", it / 60, it % 60) }
            val header = listOfNotNull(dayLabel, time).joinToString(" ")
            val kcal = meal.kcal?.let { " · ${NutritionEstimateFormatter.formatEstimatedKcal(it)}" } ?: ""
            "$header — ${meal.title}$kcal"
        }.toTypedArray()
        MaterialAlertDialogBuilder(this)
            .setTitle("Prossimi pasti")
            .setItems(items) { _, which ->
                if (!com.myfitai.app.ai.AiProviderAccess.requireConfigured(this)) return@setItems
                val meal = meals.getOrNull(which) ?: return@setItems
                startActivity(Intent(this, MealDetailActivity::class.java).putExtra(MealDetailActivity.EXTRA_MEAL_ID, meal.mealId))
            }
            .setNegativeButton("Chiudi", null)
            .show()
    }

    private fun renderCalories(calories: HomeViewModel.CalorieState) {
        currentCalories = calories
        fun kcal(value: Int?) = value?.let { "$it kcal" } ?: "—"
        findViewById<TextView>(R.id.caloriesBmrValue).text = kcal(calories.bmr)
        findViewById<TextView>(R.id.caloriesBiaBmrValue).text = kcal(calories.biaBmr)
        findViewById<TextView>(R.id.caloriesTdeeValue).text = kcal(calories.tdee)
        findViewById<TextView>(R.id.caloriesTargetValue).text = kcal(calories.target)
        findViewById<TextView>(R.id.caloriesConsumedText).text = calories.consumedKcal?.let { "$it kcal" } ?: "—"
        findViewById<TextView>(R.id.caloriesConsumedProteinText).text =
            calories.consumedProteinG?.let { NutritionEstimateFormatter.formatEstimatedMacro(it, "g") } ?: "—"
        findViewById<TextView>(R.id.caloriesConsumptionNote).text = when {
            calories.consumedCount == 0 && calories.recordedCount > 0 -> "Gli elementi registrati risultano saltati."
            calories.consumedCount == 0 -> "Nessun alimento registrato come consumato oggi."
            calories.consumedCount < calories.recordedCount -> "Calorie e proteine sommano i soli elementi consumati (${calories.consumedCount})."
            else -> "Totale di ${calories.consumedCount} elementi segnati come consumati."
        }
        findViewById<TextView>(R.id.caloriesRemainingText).apply {
            val target = calories.target
            text = if (calories.consumedKcal == null) {
                "—"
            } else if (target != null && target > 0) {
                val remaining = target - calories.consumedKcal
                if (remaining >= 0) "$remaining kcal" else "${-remaining} oltre"
            } else {
                "—"
            }
        }
        val target = calories.target ?: 0
        val consumedProgress = if (target > 0 && calories.consumedKcal != null) {
            (calories.consumedKcal * 100 / target).coerceIn(0, 100)
        } else {
            0
        }
        val remainingProgress = if (target > 0 && calories.consumedKcal != null) {
            ((target - calories.consumedKcal) * 100 / target).coerceIn(0, 100)
        } else {
            0
        }
        findViewById<com.google.android.material.progressindicator.LinearProgressIndicator>(R.id.caloriesConsumedProgress).apply {
            max = 100
            progress = consumedProgress
            setIndicatorColor(getColor(if (consumedProgress > 0) R.color.accent_green else R.color.divider))
        }
        findViewById<com.google.android.material.progressindicator.LinearProgressIndicator>(R.id.caloriesRemainingProgress).apply {
            max = 100
            progress = remainingProgress
            setIndicatorColor(getColor(if (remainingProgress > 0) R.color.text_muted else R.color.divider))
        }
        val targetDifference = if (calories.targetBeforeAdaptation != null && calories.target != null) calories.targetBeforeAdaptation - calories.target else null
        findViewById<TextView>(R.id.caloriesTargetSourceText).text = when {
            calories.activityAdjustmentKcal > 0 -> "TDEE abituale ${kcal(calories.habitualTdee)} · TDEE operativo ${kcal(calories.tdee)} · attività +${calories.activityAdjustmentKcal} kcal"
            currentCalories?.activityAdjustmentKcal == 0 && viewModel.state.value.activityCheckIn.status != "PLANNED_WORKOUT" -> "TDEE abituale ${kcal(calories.habitualTdee)} · TDEE operativo da riposo ${kcal(calories.tdee)}"
            targetDifference != null && targetDifference != 0 -> "Profilo ${kcal(calories.targetBeforeAdaptation)} · target attuale ${kcal(calories.target)}"
            calories.targetFromCurrentPlan -> "Target del giorno selezionato nel piano alimentare"
            calories.targetFromWeeklyPlan -> "Target settimanale del piano · target specifico del giorno non disponibile"
            calories.target != null -> "Target calcolato dal profilo · nessun piano corrente"
            else -> "Target non disponibile: completa i dati richiesti nel profilo"
        }
        findViewById<android.view.View>(R.id.caloriesSummaryBody).contentDescription = buildString {
            append("BMR locale: ${kcal(calories.bmr)}. ")
            append("BMR riportato dalla BIA: ${kcal(calories.biaBmr)}. ")
            append("TDEE operativo ${kcal(calories.tdee)}. TDEE abituale ${kcal(calories.habitualTdee)}. ")
            append("Target ${kcal(calories.target)}. ")
            append("Consumate ${calories.consumedKcal?.let { kcal(it) } ?: "nessuna registrazione"}. ")
            append("Proteine consumate ${calories.consumedProteinG?.let { NutritionEstimateFormatter.formatEstimatedMacro(it, "g") } ?: "non disponibili"}.")
        }
        findViewById<TextView>(R.id.caloriesModeText).apply {
            val percent = calories.energyPercent?.let { p ->
                when {
                    p > 0 -> "+$p% sul consumo"
                    p < 0 -> "$p% sul consumo"
                    else -> "in pari col consumo"
                }
            }
            text = when {
                calories.goalLabel != null && percent != null -> "${calories.goalLabel} · $percent"
                calories.goalLabel != null -> calories.goalLabel
                percent != null -> percent
                else -> "Obiettivo non impostato"
            }
        }
    }

    private fun showTdeeExplanation() {
        val c = currentCalories
        fun kcal(value: Int?) = value?.let { "$it kcal" } ?: "—"
        val percentLine = c?.energyPercent?.let { p ->
            when {
                p > 0 -> "Surplus del +$p% rispetto al consumo, per favorire l'aumento di massa."
                p < 0 -> "Deficit del $p% rispetto al consumo, per favorire la perdita di grasso."
                else -> "Target in pari col consumo, per mantenere il peso."
            }
        } ?: "Imposta un obiettivo nel profilo per calcolare il target."
        val message = buildString {
            appendLine("• BMR locale: ${kcal(c?.bmr)}")
            appendLine("  Stima usata dall'app per calcolare TDEE e target.")
            appendLine()
            appendLine("• BMR riportato dalla BIA: ${kcal(c?.biaBmr)}")
            appendLine("  Valore salvato dalla rilevazione, mostrato come riferimento.")
            appendLine()
            appendLine("• Consumo giornaliero (TDEE): ${kcal(c?.tdee)}")
            appendLine("  BMR locale moltiplicato per il livello di attività.")
            appendLine()
            appendLine("• Target calorico: ${kcal(c?.target)}")
            append("  $percentLine")
        }
        showHelpCard("Come calcoliamo le calorie", message)
    }

    private fun showHomeHelp(title: String, message: String) {
        showHelpCard(title, message)
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
            renderMeal(this, next)
            contentDescription = "Prossimo pasto: ${next.title}"
        }
    }

    private fun renderMeal(card: MealCardView, meal: HomeViewModel.NextMealState) {
        val date = LocalDate.ofEpochDay(meal.dateEpochDay)
        val today = LocalDate.now()
        val dayLabel = when (date) {
            today -> "Oggi"
            today.plusDays(1) -> "Domani"
            else -> date.format(DateTimeFormatter.ofPattern("EEE d MMM", Locale.ITALIAN))
        }
        val time = meal.timeMinutes?.let { String.format(Locale.ITALIAN, "%02d:%02d", it / 60, it % 60) }
        card.setTime(listOfNotNull(dayLabel, time).joinToString(" "))
        card.setTitle(meal.title)
        card.setKcal(meal.kcal?.let { NutritionEstimateFormatter.formatEstimatedKcal(it) } ?: meal.type)
        card.setImage(R.drawable.img_next_meal)
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

    private companion object {
        const val LANDSCAPE_GRID_TAG = "home_landscape_grid"
    }

    private fun renderActivityCheckIn(
        checkIn: HomeViewModel.ActivityCheckInState,
        calories: HomeViewModel.CalorieState,
    ) {
        val summary = findViewById<TextView>(R.id.activityCheckInSummary)
        val status = checkIn.status
        when (status) {
            "REST" -> {
                summary.text = "Riposo registrato per oggi. TDEE e target operativo sono ricalcolati sul livello sedentario di base."
                findViewById<View>(R.id.activityCheckInRestButton).visibility = View.GONE
                findViewById<View>(R.id.activityCheckInWorkoutButton).visibility = View.VISIBLE
                findViewById<View>(R.id.activityCheckInEditButton).visibility = View.GONE
                findViewById<View>(R.id.activityCheckInClearButton).visibility = View.VISIBLE
            }
            "PLANNED_WORKOUT" -> {
                val intensity = when (checkIn.intensity) {
                    "LIGHT" -> "leggera"
                    "HARD" -> "intensa"
                    else -> "media"
                }
                summary.text = "Allenamento previsto: ${checkIn.durationMinutes} min · intensità $intensity. " +
                    "Target operativo ${calories.target?.let { "$it kcal" } ?: "non disponibile"} (+${checkIn.adjustmentKcal} kcal)."
                findViewById<View>(R.id.activityCheckInRestButton).visibility = View.GONE
                findViewById<View>(R.id.activityCheckInWorkoutButton).visibility = View.GONE
                findViewById<View>(R.id.activityCheckInEditButton).visibility = View.VISIBLE
                findViewById<View>(R.id.activityCheckInClearButton).visibility = View.VISIBLE
            }
            else -> {
                summary.text = "Nessun allenamento registrato oggi. Il TDEE operativo usa il livello di riposo."
                findViewById<View>(R.id.activityCheckInRestButton).visibility = View.VISIBLE
                findViewById<View>(R.id.activityCheckInWorkoutButton).visibility = View.VISIBLE
                findViewById<View>(R.id.activityCheckInEditButton).visibility = View.GONE
                findViewById<View>(R.id.activityCheckInClearButton).visibility = View.GONE
            }
        }
    }

    private fun showActivityCheckInDialog() {
        val options = arrayOf("30 minuti · leggera", "45 minuti · media", "60 minuti · media", "60 minuti · intensa", "75+ minuti · intensa")
        MaterialAlertDialogBuilder(this)
            .setTitle("Allenamento previsto oggi")
            .setItems(options) { _, which ->
                val values = when (which) {
                    0 -> 30 to DailyActivityCheckInEngine.Intensity.LIGHT
                    1 -> 45 to DailyActivityCheckInEngine.Intensity.MODERATE
                    2 -> 60 to DailyActivityCheckInEngine.Intensity.MODERATE
                    3 -> 60 to DailyActivityCheckInEngine.Intensity.HARD
                    else -> 75 to DailyActivityCheckInEngine.Intensity.HARD
                }
                viewModel.setPlannedWorkout(values.first, values.second)
            }
            .setNegativeButton("Annulla", null)
            .show()
    }

    private fun renderBiaDueNotice(state: HomeViewModel.DashboardState) {
        val card = findViewById<View>(R.id.biaDueNoticeCard)
        val text = findViewById<TextView>(R.id.biaDueNoticeText)
        val measuredAt = state.latestBiaTimestamp
        if (measuredAt == null) {
            revealState(card, false)
            return
        }
        val interval = BiaFrequencyPreferences(this).intervalDays
        val dueAt = measuredAt + interval * 24L * 60L * 60L * 1000L
        val now = System.currentTimeMillis()
        val warningWindow = 7L * 24L * 60L * 60L * 1000L
        val due = now >= dueAt
        val expiringSoon = due || dueAt - now <= warningWindow
        val intervalLabel = when (interval) {
            BiaFrequencyPreferences.WEEKLY -> "settimanale"
            BiaFrequencyPreferences.BIMONTHLY -> "ogni 2 mesi"
            else -> "mensile"
        }
        text.text = if (due) getString(R.string.home_bia_due_text, intervalLabel) else getString(R.string.home_bia_due_soon_text, ((dueAt - now) / 86_400_000L).coerceAtLeast(1), intervalLabel)
        revealState(card, expiringSoon)
    }
}
