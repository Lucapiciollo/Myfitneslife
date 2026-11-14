package com.myfitai.app.ui

import com.myfitai.app.navigation.AiSettingsEntry
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
import com.myfitai.app.domain.calculation.PlanStaleness
import com.myfitai.app.domain.calculation.WeightSource
import com.myfitai.app.domain.display.DayTargetResolver
import com.myfitai.app.domain.display.DisplayNumberFormatter
import com.myfitai.app.domain.display.ConsumptionNote
import com.myfitai.app.domain.display.HomeProgressHint
import com.myfitai.app.domain.display.MetricDeltaPresenter
import com.myfitai.app.domain.display.QuickConsumeRule
import com.myfitai.app.domain.display.RemainingKcal
import com.myfitai.app.domain.display.TargetModeLine
import com.myfitai.app.domain.display.WeeklyEstimateNote
import com.myfitai.app.domain.food.FoodConsumptionStatus
import androidx.core.view.ViewCompat
import com.myfitai.app.data.local.entity.FoodConsumptionEntity
import com.myfitai.app.ui.widgets.MealSlotIcons
import com.myfitai.app.navigation.BottomNavBinder
import com.myfitai.app.notifications.ExactAlarmAccess
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
import com.myfitai.app.ui.widgets.ValueUnitFormatter
import com.myfitai.app.ui.widgets.TargetProgressRingView
import com.myfitai.app.ui.widgets.BodyMeasurementTrendView
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
            mealPlanRepository = data.mealPlanRepository,
            cheatRepository = data.cheatEntryRepository,
            recoveryRepository = data.calorieRecoveryRepository,
            foodConsumptionRepository = data.foodConsumptionRepository,
            bodyExpectationGoalRepository = data.bodyExpectationGoalRepository,
            nutritionPlanSchedulePreferences = data.nutritionPlanSchedulePreferences,
            activeProfileStore = data.activeProfileStore,
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
    private var renderedPlanUpdatePending: Boolean? = null
    private var currentUpcomingMeals: List<HomeViewModel.NextMealState> = emptyList()
    /** "Dettagli del calcolo" open/closed: open by default (basic needs BMR/TDEE stay in view), kept only while this screen instance lives. */
    private var calorieDetailsExpanded = true
    private val motionVisibilityTargets = mutableMapOf<Int, Boolean>()
    private var dashboardMetricPresentations by mutableStateOf(emptyList<DashboardMetricPresentation>())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_home)
        moveBodyOverviewToSecondBlock()
        moveRecoveryToThirdBlock()
        movePlanUpdateNoticeBelowCalories()
        moveOperationalSectionsToEnd()
        adaptLandscapeContent()
        bindCalorieDetailsToggle()
        bindBottom(BottomNavBinder.Tab.HOME)
        installDashboardMetricsComposeView()
        requestNotificationPermissionOnce()

        findViewById<android.view.View>(R.id.profileButton).setOnClickListener { go(ProfileActivity::class.java) }
        findViewById<android.view.View>(R.id.planUpdateNoticeCard).setOnClickListener { openFoodPlan() }
        findViewById<android.view.View>(R.id.planUpdateNoticeButton).apply {
            setAiActionEnabled(this)
            setOnClickListener { openFoodPlan() }
        }
        findViewById<android.view.View>(R.id.aiConfigurationNoticeCard).setOnClickListener { AiSettingsEntry.open(this) }
        findViewById<android.view.View>(R.id.aiConfigurationNoticeButton).setOnClickListener { AiSettingsEntry.open(this) }
        findViewById<android.view.View>(R.id.biaDueNoticeCard).setOnClickListener { startActivity(Intent(this, SettingsActivity::class.java)) }
        findViewById<android.view.View>(R.id.biaDueNoticeButton).setOnClickListener { startActivity(Intent(this, SettingsActivity::class.java)) }
        // "Analisi e review" lives in the Progress tab; the Rilevazioni hub stays reachable from Altro.
        findViewById<android.view.View>(R.id.analysisHubButton).setOnClickListener { go(PhysicalEvolutionActivity::class.java) }
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
            } ?: openFoodPlan(requiresAi = false)
        }
        findViewById<android.view.View>(R.id.secondNextMealCard).setOnClickListener {
            currentUpcomingMeals.getOrNull(1)?.let { meal ->
                startActivity(Intent(this, MealDetailActivity::class.java).putExtra(MealDetailActivity.EXTRA_MEAL_ID, meal.mealId))
            }
        }
        findViewById<android.view.View>(R.id.caloriesCard).setOnClickListener { showTdeeExplanation() }
        findViewById<android.view.View>(R.id.caloriesHelpButton).setOnClickListener { showTdeeExplanation() }
        findViewById<android.view.View>(R.id.bodyOverviewHelpButton).setOnClickListener {
            showHelpCard("Panoramica del corpo", "Le tre card mostrano l'ultima rilevazione disponibile di peso, percentuale di grasso e massa muscolare. Il grafico mostra l'andamento del peso nel periodo selezionato.")
        }
        findViewById<android.view.View>(R.id.weeklyExpectationHelpButton).setOnClickListener {
            showHelpCard(
                "Possibile calo teorico",
                "Confronta il consumo giornaliero stimato con le calorie dei giorni già presenti nel piano di questa settimana. " +
                    "Il risultato indica quanta energia potrebbe corrispondere a grasso, non quanto peso perderai davvero. " +
                    "Acqua, glicogeno, adattamenti e composizione corporea possono cambiare il risultato."
            )
        }
        findViewById<android.view.View>(R.id.recoveryHelpButton).setOnClickListener {
            showHelpCard("Riserva di recupero", "Indica le calorie da recuperare e distribuire nei giorni successivi quando hai consumato meno del previsto o hai registrato uno sgarro. È un supporto al riequilibrio, non una misura clinica.")
        }
        findViewById<android.view.View>(R.id.nextMealHelpButton).setOnClickListener {
            showHelpCard("Prossimo pasto", "Mostra il prossimo pasto previsto dal piano alimentare di oggi, con orario, nome e calorie quando disponibili.")
        }
        findViewById<android.view.View>(R.id.todayMenuButton).setOnClickListener { showTodayMenu() }
        findViewById<android.view.View>(R.id.addExtraButton).apply {
            setAiActionEnabled(this)
            setOnClickListener {
                startActivity(Intent(this@HomeActivity, CheatEntryActivity::class.java))
            }
        }
        findViewById<MealCardView>(R.id.secondNextMealCard).setSecondary(true)
        findViewById<TextView>(R.id.todayLabel).text = todayLabel()
        renderAiConfigurationNotice()

        findViewById<TimeRangeSelectorView>(R.id.timeRangeSelector).apply {
            // Keep the dashboard selector aligned with the full body-history view so
            // less frequent measurements are available without an extra navigation step.
            setRanges(resources.getStringArray(R.array.home_chart_range_labels).toList(), selectedIndex = 3)
            // The sigla is only a label: the accessible name states the full interval, as in Progress.
            resources.getStringArray(R.array.home_chart_range_descriptions).forEachIndexed { index, description ->
                getChildAt(index)?.contentDescription = description
            }
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
                state.loading, weightSourceLabel(state.weight.source), R.drawable.ic_trend_down,
            ),
            metricPresentation(
                getString(R.string.dashboard_metric_fat), state.bodyFat.value,
                state.bodyFat.deltaFromPrevious, "%", DeltaSemantic.DOWN_IS_POSITIVE,
                state.loading, weightSourceLabel(state.bodyFat.source), R.drawable.ic_trend_down,
            ),
            metricPresentation(
                getString(R.string.dashboard_metric_muscle), state.muscleMass.value,
                state.muscleMass.deltaFromPrevious, "kg", DeltaSemantic.UP_IS_POSITIVE,
                state.loading, weightSourceLabel(state.muscleMass.source), R.drawable.ic_trend_up,
            ),
        )
    }

    private fun weightSourceLabel(source: WeightSource?): String? = when (source) {
        WeightSource.BIA -> getString(R.string.weight_source_bia)
        WeightSource.MISURA -> getString(R.string.weight_source_misura)
        WeightSource.PROFILO -> getString(R.string.weight_source_profilo)
        WeightSource.NESSUNO, null -> null
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
        val presentation = MetricDeltaPresenter.present(
            delta, unit,
            when (semantic) {
                DeltaSemantic.NEUTRAL -> MetricDeltaPresenter.Preference.NEUTRAL
                DeltaSemantic.DOWN_IS_POSITIVE -> MetricDeltaPresenter.Preference.DOWN_IS_POSITIVE
                DeltaSemantic.UP_IS_POSITIVE -> MetricDeltaPresenter.Preference.UP_IS_POSITIVE
            },
        )
        // Sign and arrow follow the DIRECTION of the change; colour only reinforces the judgement.
        val tone = when (presentation.judgement) {
            MetricDeltaPresenter.Judgement.POSITIVE -> MetricDeltaTone.Positive
            MetricDeltaPresenter.Judgement.NEGATIVE -> MetricDeltaTone.Negative
            MetricDeltaPresenter.Judgement.NEUTRAL -> MetricDeltaTone.Neutral
        }
        val icon = when (presentation.direction) {
            MetricDeltaPresenter.Direction.UP -> R.drawable.ic_trend_up
            MetricDeltaPresenter.Direction.DOWN -> R.drawable.ic_trend_down
            MetricDeltaPresenter.Direction.FLAT -> trendIcon
        }
        val description = when (presentation.direction) {
            MetricDeltaPresenter.Direction.UP -> getString(R.string.dashboard_delta_up_description, presentation.magnitudeText)
            MetricDeltaPresenter.Direction.DOWN -> getString(R.string.dashboard_delta_down_description, presentation.magnitudeText)
            MetricDeltaPresenter.Direction.FLAT -> getString(R.string.dashboard_delta_flat_description)
        }
        return DashboardMetricPresentation(
            label = displayLabel,
            value = displayValue,
            delta = presentation.signedText,
            tone = tone,
            trendIcon = icon,
            deltaDescription = description,
            showDeltaIcon = presentation.direction != MetricDeltaPresenter.Direction.FLAT,
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
                child.id == R.id.quickActionsCard ||
                child.id == R.id.cheatActionCard ||
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

    private fun bindCalorieDetailsToggle() {
        findViewById<View>(R.id.caloriesDetailsToggle).setOnClickListener {
            calorieDetailsExpanded = !calorieDetailsExpanded
            renderCalorieDetails(animate = true)
        }
        renderCalorieDetails(animate = false)
    }

    /** BMR, BMR da BIA and TDEE live here, open by default and collapsible; the values are the same as before and stay in the explanation dialog. */
    private fun renderCalorieDetails(animate: Boolean) {
        val expanded = calorieDetailsExpanded
        UiMotion.reveal(findViewById(R.id.caloriesDetailsContainer), expanded, animateChange = animate)
        ViewCompat.setStateDescription(
            findViewById(R.id.caloriesDetailsToggle),
            getString(if (expanded) R.string.home_ux5a_details_state_expanded else R.string.home_ux5a_details_state_collapsed),
        )
        val chevron = findViewById<View>(R.id.caloriesDetailsChevron)
        val rotation = if (expanded) 180f else 0f
        chevron.animate().cancel()
        if (animate && UiMotion.animationsEnabled(this)) {
            chevron.animate().rotation(rotation).setDuration(resources.getInteger(R.integer.motion_selection_ms).toLong()).start()
        } else {
            chevron.rotation = rotation
        }
    }

    private fun moveOperationalSectionsToEnd() {
        val sections = listOf(
            findViewById<View>(R.id.analysisHubCard),
            findViewById<View>(R.id.quickActionsCard),
            findViewById<View>(R.id.cheatActionCard),
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

    /** The "plan not updated" notice sits right under the calorie card whose numbers it qualifies, not at the bottom. */
    private fun movePlanUpdateNoticeBelowCalories() {
        val bodyCard = findViewById<View>(R.id.bodyOverviewCard)
        val notice = findViewById<View>(R.id.planUpdateNoticeCard)
        val parent = bodyCard.parent as? ViewGroup ?: return
        if (notice.parent !== parent) return
        parent.removeView(notice)
        parent.addView(notice, parent.indexOfChild(bodyCard) + 1)
    }

    override fun onResume() {
        super.onResume()
        renderPlanUpdateNotice()
        renderAiConfigurationNotice()
        renderBiaDueNotice(viewModel.state.value)
        // After the notification permission question, so the two system prompts never overlap.
        if (Build.VERSION.SDK_INT < 33 || NotificationPreferences(this).permissionPrompted) offerExactAlarmAccessOnce()
    }

    /** UX7: UNA regola ([PlanStaleness]): impronta del piano vs impronta di adesso; il flag vale solo per i piani senza impronta. */
    private fun isPlanUpdatePending(calories: HomeViewModel.CalorieState = viewModel.state.value.calories): Boolean {
        val profileId = data.activeProfileStore.currentIdOrNull() ?: return false
        // Finche i dati non sono caricati manca l'impronta: non mostrare un avviso che poi sparirebbe.
        if (viewModel.state.value.loading) return false
        return PlanStaleness.isStale(
            planFingerprint = calories.planInputsFingerprint,
            currentFingerprint = calories.currentInputsFingerprint,
            legacyPendingFlag = data.nutritionPlanUpdatePreferences.isPending(profileId),
        )
    }

    private fun renderPlanUpdateNotice() {
        val pending = isPlanUpdatePending()
        findViewById<android.view.View>(R.id.planUpdateNoticeCard)?.let { card -> revealState(card, pending) }
        // The flag is changed by other screens: when it differs from what the calorie card last drew, draw it again.
        if (pending != renderedPlanUpdatePending) {
            renderedPlanUpdatePending = pending
            currentCalories?.let(::renderCalories)
            renderWeeklyPlanPendingNote(viewModel.state.value.weeklyExpectation.plannedDays)
        }
    }

    /** The weekly estimate compares the new TDEE with the old plan while the update is pending: say so, change nothing else. */
    private fun renderWeeklyPlanPendingNote(plannedDays: Int) {
        findViewById<View>(R.id.weeklyExpectationPlanPendingNote).visibility =
            if (WeeklyEstimateNote.shouldShow(isPlanUpdatePending(), plannedDays)) View.VISIBLE else View.GONE
    }

    private fun renderAiConfigurationNotice() {
        val providerAvailable = aiProviderConfigured
        findViewById<android.view.View>(R.id.aiConfigurationNoticeCard)?.let { card -> revealState(card, !providerAvailable) }
        findViewById<View?>(R.id.planUpdateNoticeButton)?.let { setAiActionEnabled(it, providerAvailable) }
        findViewById<View?>(R.id.addExtraButton)?.let { setAiActionEnabled(it, providerAvailable) }
    }

    private fun revealState(view: android.view.View, visible: Boolean) {
        val previous = motionVisibilityTargets.put(view.id, visible)
        UiMotion.reveal(view, visible, animateChange = previous != null)
    }

    /** Explains once why meal reminders need the exact-alarm access and opens its settings page on request. */
    private fun offerExactAlarmAccessOnce() {
        val prefs = NotificationPreferences(this)
        if (!prefs.mealRemindersEnabled || prefs.exactAlarmPrompted || ExactAlarmAccess.isAllowed(this)) return
        val settings = ExactAlarmAccess.settingsIntent(this) ?: return
        prefs.exactAlarmPrompted = true
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.exact_alarm_title)
            .setMessage(R.string.exact_alarm_message)
            .setNegativeButton(R.string.exact_alarm_later, null)
            .setPositiveButton(R.string.exact_alarm_open_settings) { _, _ -> runCatching { startActivity(settings) } }
            .show()
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
        // The state re-emits at midnight, so the date label follows the day instead of the day the screen opened.
        findViewById<TextView>(R.id.todayLabel).text = todayLabel()
        val firstName = state.profileName?.trim()?.substringBefore(' ')?.takeIf { it.isNotBlank() }
        findViewById<TextView>(R.id.greetingText).text = firstName?.let { "Ciao $it 👋" } ?: "Ciao 👋"

        renderDashboardMetrics(state)

        renderBodyTrend(state.bodyMeasurementTrendSeries)
        findViewById<TextView>(R.id.recompositionStateText).text = recompositionText(state.recompositionState)
        renderCalories(state.calories)
        renderWeeklyExpectation(state.weeklyExpectation)
        renderRecovery(state.recovery)
        renderUpcomingMeals(state.upcomingMeals)
        renderNextMeal(state.nextMeal, state.todayEpochDay, state.consumptionRecords)
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
                detail.text = "Periodo $period · ${result.plannedDays}/${result.periodWeeks * 7} giorni pianificati · deficit ≈ ${result.theoreticalDeficitKcal?.let { DisplayNumberFormatter.integer(it) } ?: "-"} kcal"
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
        renderWeeklyPlanPendingNote(result.plannedDays)
        val hasResult = result.plannedDays > 0
        val currentGoal = viewModel.state.value.expectationGoals.firstOrNull { it.periodStartEpochDay == monday.toEpochDay() }
        findViewById<TextView>(R.id.weeklyExpectationQuestionText).visibility = if (hasResult) View.VISIBLE else View.GONE
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
        val compact = recovery.pendingKcal <= 0
        // 0 kcal is a real value (kept on screen), but it does not deserve a full panel: one compact row instead.
        findViewById<View>(R.id.recoveryDetailPanel).visibility = if (compact) View.GONE else View.VISIBLE
        findViewById<View>(R.id.recoveryCompactRow).visibility = if (compact) View.VISIBLE else View.GONE
        if (!compact) {
            value.text = DisplayNumberFormatter.kcal(recovery.pendingKcal)
            val credits = if (recovery.creditCount == 1) "1 sgarro recente" else "${recovery.creditCount} sgarri recenti"
            val expiry = recovery.nextExpiry?.format(DateTimeFormatter.ofPattern("dd/MM", Locale.ITALIAN))
            hint.text = "Da distribuire gradualmente nei prossimi giorni · $credits${expiry?.let { " · prima scadenza $it" }.orEmpty()}. Evita compensazioni drastiche."
        } else {
            value.text = "0 kcal"
            hint.text = "Nessun recupero da distribuire: sei in pari."
            ValueUnitFormatter.apply(findViewById<TextView>(R.id.recoveryCompactValueText))
        }
        ValueUnitFormatter.apply(value)
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
                setMeal(null, null, describe = false)
            } else {
                renderMeal(this, second)
            }
        }
    }

    private fun showTodayMenu() {
        val meals = currentUpcomingMeals
        if (meals.isEmpty()) {
            openFoodPlan(requiresAi = false)
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
                // Opening a saved meal is consultation: it never needs an AI provider.
                val meal = meals.getOrNull(which) ?: return@setItems
                startActivity(Intent(this, MealDetailActivity::class.java).putExtra(MealDetailActivity.EXTRA_MEAL_ID, meal.mealId))
            }
            .setNegativeButton("Chiudi", null)
            .show()
    }

    private fun renderCalories(calories: HomeViewModel.CalorieState) {
        currentCalories = calories
        val planPending = isPlanUpdatePending(calories)
        renderedPlanUpdatePending = planPending
        val planStale = TargetModeLine.isStale(planPending, calories.targetSource)
        fun kcal(value: Int?) = value?.let { DisplayNumberFormatter.kcal(it) } ?: "-"
        findViewById<TextView>(R.id.caloriesBmrValue).text = kcal(calories.bmr)
        findViewById<TextView>(R.id.caloriesBiaBmrValue).text = kcal(calories.biaBmr)
        // The BIA reference is shown only when a BIA provided it.
        val hasBiaBmr = com.myfitai.app.domain.display.BiaBmrRowRule.visible(calories.bmr, calories.biaBmr)
        findViewById<View>(R.id.caloriesBiaBmrRow).visibility = if (hasBiaBmr) View.VISIBLE else View.GONE
        findViewById<View>(R.id.caloriesBiaBmrDivider).visibility = if (hasBiaBmr) View.VISIBLE else View.GONE
        findViewById<TextView>(R.id.caloriesTdeeValue).text = kcal(calories.tdee)
        findViewById<TextView>(R.id.caloriesBaseNoSportValue).text = kcal(calories.baseNoSport)
        findViewById<TextView>(R.id.caloriesTargetValue).text = kcal(calories.target)
        val caloriesConsumedText = findViewById<TextView>(R.id.caloriesConsumedText)
        caloriesConsumedText.text = calories.consumedKcal?.let { DisplayNumberFormatter.integer(it) } ?: "-"
        // One note under the rings (the two old notes merged into one).
        val consumptionDetail = when (ConsumptionNote.kindOf(calories.consumedCount, calories.recordedCount)) {
            ConsumptionNote.Kind.ALL_SKIPPED -> getString(R.string.home_ux5a_note_all_skipped)
            ConsumptionNote.Kind.NONE_REGISTERED -> getString(R.string.home_ux5a_note_none_registered)
            ConsumptionNote.Kind.PARTIAL -> getString(R.string.home_ux5a_note_partial, calories.consumedCount)
            ConsumptionNote.Kind.ALL_CONSUMED -> resources.getQuantityString(R.plurals.home_ux5a_note_all_consumed, calories.consumedCount, calories.consumedCount)
        }
        findViewById<TextView>(R.id.caloriesConsumptionNote).text = getString(R.string.home_ux5a_calories_note, consumptionDetail)
        val remaining = RemainingKcal.of(calories.target, calories.consumedKcal)
        val remainingLabel = findViewById<TextView>(R.id.caloriesRemainingLabel)
        // The overshoot is stated in words and with a sign, never only through colour.
        remainingLabel.text = getString(
            if (remaining.kind == RemainingKcal.Kind.OVER) R.string.home_over_target_label else R.string.home_remaining_label,
        )
        val remainingText = findViewById<TextView>(R.id.caloriesRemainingText).apply {
            text = when (remaining.kind) {
                RemainingKcal.Kind.REMAINING -> DisplayNumberFormatter.integer(remaining.amountKcal)
                RemainingKcal.Kind.OVER -> DisplayNumberFormatter.signedInteger(remaining.amountKcal)
                RemainingKcal.Kind.UNAVAILABLE -> "-"
            }
        }
        (remainingLabel.parent as? View)?.contentDescription = when (remaining.kind) {
            RemainingKcal.Kind.REMAINING -> getString(
                R.string.home_remaining_description,
                DisplayNumberFormatter.kcal(remaining.amountKcal), kcal(calories.target),
            )
            RemainingKcal.Kind.OVER -> getString(
                R.string.home_over_target_description,
                DisplayNumberFormatter.kcal(remaining.amountKcal), kcal(calories.consumedKcal), kcal(calories.target),
            )
            RemainingKcal.Kind.UNAVAILABLE -> null
        }
        val target = calories.target ?: 0
        val proteinTarget = calories.proteinTargetG?.let { kotlin.math.round(it).toInt() } ?: 0
        findViewById<TextView>(R.id.caloriesConsumedMaximumText).text =
            calories.target?.takeIf { it > 0 }?.let { DisplayNumberFormatter.integer(it) }.orEmpty()
        findViewById<TextView>(R.id.caloriesConsumedProteinMaximumText).text =
            proteinTarget.takeIf { it > 0 }?.toString().orEmpty()
        findViewById<TextView>(R.id.caloriesRemainingMaximumText).text =
            calories.target?.takeIf { it > 0 }?.let { DisplayNumberFormatter.integer(it) }.orEmpty()
        val consumedProgress = if (target > 0 && calories.consumedKcal != null) {
            (calories.consumedKcal * 100 / target).coerceIn(0, 100)
        } else {
            0
        }
        findViewById<TargetProgressRingView>(R.id.caloriesConsumedProgressRing).apply {
            setRingDimensions(
                resources.getDimensionPixelSize(R.dimen.home_calorie_ring_size),
                resources.getDimensionPixelSize(R.dimen.home_calorie_ring_thickness),
            )
            visibility = if (target > 0) View.VISIBLE else View.GONE
            setProgress(target, calories.consumedKcal ?: 0, target > 0 && calories.consumedKcal != null)
        }
        val proteinConsumed = calories.consumedProteinG?.let { kotlin.math.round(it).toInt() }
        val formattedProtein = calories.consumedProteinG?.let { NutritionEstimateFormatter.formatEstimatedMacro(it, "g") }
        findViewById<TextView>(R.id.caloriesConsumedProteinText).text = when {
            proteinConsumed == null -> "—"
            formattedProtein?.startsWith("≈") == true -> "≈ $proteinConsumed"
            else -> proteinConsumed.toString()
        }
        findViewById<TargetProgressRingView>(R.id.caloriesConsumedProteinProgressRing).apply {
            setTag(R.id.protein_target_tag, calories.proteinTargetG)
            setRingDimensions(
                resources.getDimensionPixelSize(R.dimen.home_macro_ring_size),
                resources.getDimensionPixelSize(R.dimen.home_macro_ring_thickness),
            )
            visibility = if (proteinTarget > 0) View.VISIBLE else View.GONE
            setProgress(proteinTarget, proteinConsumed ?: 0, proteinTarget > 0 && proteinConsumed != null)
        }
        findViewById<TargetProgressRingView>(R.id.caloriesRemainingProgressRing).apply {
            setRingDimensions(
                resources.getDimensionPixelSize(R.dimen.home_remaining_ring_size),
                resources.getDimensionPixelSize(R.dimen.home_remaining_ring_thickness),
            )
            visibility = if (target > 0) View.VISIBLE else View.GONE
            setProgress(target, if (calories.consumedKcal != null) target - calories.consumedKcal else 0, target > 0 && calories.consumedKcal != null)
        }
        caloriesConsumedText.visibility = if (target > 0) View.VISIBLE else View.GONE
        renderProgressHint(calories)
        findViewById<View>(R.id.caloriesSummaryBody).contentDescription = buildString {
            append("Metabolismo a riposo (BMR): ${kcal(calories.bmr)}. ")
            append("Metabolismo a riposo da BIA (BMR): ${kcal(calories.biaBmr)}. ")
            append("Consumo giornaliero (TDEE) ${kcal(calories.tdee)}. ")
            append("Obiettivo di oggi ${kcal(calories.target)}. ")
            if (planStale) append("${getString(R.string.target_source_plan_stale)}. ")
            append("Consumate ${calories.consumedKcal?.let { kcal(it) } ?: "nessuna registrazione"}. ")
            append("Proteine consumate ${calories.consumedProteinG?.let { NutritionEstimateFormatter.formatEstimatedMacro(it, "g") } ?: "non disponibili"}.")
        }
        // One target for the day: the plan's (adaptation and recovery included) when it exists, otherwise the
        // theoretical one, and the line always says which of the two is on screen.
        // The target is still the old plan's while the update is pending: say so and compare nothing with the new TDEE.
        val targetLine = when {
            planStale ->
                if (calories.theoreticalTarget != null && calories.theoreticalTarget != calories.target) {
                    getString(R.string.target_source_plan_stale_theoretical, kcal(calories.theoreticalTarget))
                } else {
                    getString(R.string.target_source_plan_stale)
                }
            calories.targetSource == DayTargetResolver.Source.PLAN_DAY ->
                if (calories.theoreticalTarget != null && calories.theoreticalTarget != calories.target) {
                    getString(R.string.target_source_plan_day_theoretical, kcal(calories.theoreticalTarget))
                } else {
                    getString(R.string.target_source_plan_day)
                }
            calories.targetSource == DayTargetResolver.Source.THEORETICAL -> getString(R.string.target_source_theoretical)
            calories.targetSource == DayTargetResolver.Source.PLAN_WEEK -> getString(R.string.target_source_plan_week)
            else -> getString(R.string.target_source_none)
        }
        findViewById<TextView>(R.id.caloriesTargetSourceText).text = targetLine
        findViewById<TextView>(R.id.caloriesModeText).text = TargetModeLine.text(
            goalLabel = calories.goalLabel,
            energyPercent = calories.energyPercent,
            planStale = planStale,
            staleLabel = getString(R.string.home_mode_plan_stale),
            unsetLabel = "Obiettivo non impostato",
        )
        listOf(
            R.id.caloriesBmrValue, R.id.caloriesBiaBmrValue, R.id.caloriesBaseNoSportValue, R.id.caloriesTdeeValue, R.id.caloriesTargetValue,
            R.id.caloriesConsumedText, R.id.caloriesConsumedProteinText, R.id.caloriesRemainingText,
        ).forEach { ValueUnitFormatter.apply(findViewById(it)) }
    }

    private fun renderProgressHint(calories: HomeViewModel.CalorieState) {
        val line = findViewById<TextView>(R.id.homeProgressHintText)
        when (HomeProgressHint.of(calories.target, calories.consumedCount, calories.consumedKcal)) {
            HomeProgressHint.Kind.ENCOURAGEMENT -> {
                line.setText(R.string.dashboard_subtitle)
                line.visibility = View.VISIBLE
            }
            HomeProgressHint.Kind.REGISTER_MEALS -> {
                line.setText(R.string.dashboard_hint_register_meals)
                line.visibility = View.VISIBLE
            }
            HomeProgressHint.Kind.HIDDEN -> line.visibility = View.GONE
        }
    }

    private fun showTdeeExplanation() {
        val c = currentCalories
        fun kcal(value: Int?) = value?.let { DisplayNumberFormatter.kcal(it) } ?: "-"
        val percentLine = when {
            c != null && TargetModeLine.isStale(isPlanUpdatePending(c), c.targetSource) -> getString(R.string.home_tdee_percent_plan_stale)
            else -> c?.energyPercent?.let { p ->
                when {
                    p > 0 -> "Surplus del +$p% rispetto al consumo, per favorire l'aumento di massa."
                    p < 0 -> "Deficit del $p% rispetto al consumo, per favorire la perdita di grasso."
                    else -> "Obiettivo in pari col consumo, per mantenere il peso."
                }
            } ?: "Imposta il tuo obiettivo nel profilo per calcolare le calorie di oggi."
        }
        val message = buildString {
            appendLine("• Metabolismo a riposo (BMR): ${kcal(c?.bmr)}")
            appendLine("  Stima usata dall'app per calcolare consumo giornaliero (TDEE) e obiettivo di oggi.")
            appendLine()
            appendLine("• Metabolismo a riposo da BIA (BMR): ${kcal(c?.biaBmr)}")
            appendLine("  Valore salvato dalla rilevazione, mostrato come riferimento.")
            appendLine()
            appendLine("• Consumo giornaliero (TDEE): ${kcal(c?.tdee)}")
            appendLine("  Metabolismo a riposo (BMR) moltiplicato per il livello di attività.")
            appendLine()
            appendLine("• Obiettivo di oggi: ${kcal(c?.target)}")
            append("  $percentLine")
        }
        showHelpCard("Come calcoliamo le calorie", message)
    }

    private fun renderNextMeal(next: HomeViewModel.NextMealState?, todayEpochDay: Long, records: List<FoodConsumptionEntity>) {
        currentNextMealId = next?.mealId
        findViewById<MealCardView>(R.id.nextMealCard).apply {
            if (next == null) {
                setTime("—")
                setTitle("Nessun pasto pianificato")
                setKcal("Apri il piano alimentare")
                setMeal(null, null, describe = false)
                setQuickConsume("", QuickConsumeRule.State.HIDDEN, null)
                contentDescription = "Nessun pasto pianificato. Apri il piano alimentare"
                return@apply
            }
            renderMeal(this, next)
            contentDescription = "Prossimo pasto: ${next.title}"
            // Today's (or past) meals only; the rest of the card still opens the detail.
            val consumed = records.any { it.mealId == next.mealId && it.status == FoodConsumptionStatus.CONSUMED.name }
            val quickState = QuickConsumeRule.stateOf(next.dateEpochDay, todayEpochDay, consumed)
            setQuickConsume(MealSlotIcons.label(next.type, next.timeMinutes), quickState) { onQuickConsume(next.mealId, consumed) }
        }
    }

    /**
     * Same shared logic as the meal detail and the Food list (MealStatusRecorder). The rings update by themselves:
     * Home already observes the consumption records. A meal already consumed is never cleared by the tap: only the snackbar action does.
     */
    private fun onQuickConsume(mealId: Long, alreadyConsumed: Boolean) {
        val recorder = data.mealStatusRecorder
        if (alreadyConsumed) {
            showMealSnackbar(R.string.meal_ux4_snackbar_already_consumed, R.string.meal_detail_ux4_undo_consumed) {
                lifecycleScope.launch { runCatching { recorder.tap(mealId, FoodConsumptionStatus.CONSUMED) } }
            }
            return
        }
        lifecycleScope.launch {
            val change = runCatching { recorder.markConsumed(mealId) }.getOrNull()
            when {
                change == null -> showMealSnackbar(R.string.meal_ux4_snackbar_error)
                change.changed -> showMealSnackbar(R.string.meal_ux4_snackbar_consumed, R.string.meal_ux4_snackbar_undo) {
                    lifecycleScope.launch { runCatching { recorder.restore(change) } }
                }
            }
        }
    }

    private fun showMealSnackbar(@androidx.annotation.StringRes message: Int, @androidx.annotation.StringRes action: Int? = null, onAction: (() -> Unit)? = null) {
        val snackbar = SnackbarAnchors.make(this, message)
        if (action != null && onAction != null) snackbar.setAction(action) { onAction() }
        snackbar.show()
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
        card.setMeal(meal.type, meal.timeMinutes, describe = true)
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

    private fun formatNumber(value: Float): String = DisplayNumberFormatter.decimal(value)

    private companion object {
        const val LANDSCAPE_GRID_TAG = "home_landscape_grid"
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
