package com.myfitai.app.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.TextView
import android.widget.ProgressBar
import android.content.res.ColorStateList
import android.widget.LinearLayout
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.myfitai.app.R
import com.myfitai.app.data.AppDataContainer
import com.myfitai.app.domain.calculation.LocalCalculationEngine
import com.myfitai.app.domain.calculation.DailyCalorieProgress
import com.myfitai.app.domain.calculation.EnergyTargetPresentation
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
import kotlin.math.abs
import kotlin.math.roundToInt

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
            profileCalculationService = data.profileCalculationService,
            recoveryRepository = data.nutritionRecoveryRepository,
            foodConsumptionRepository = data.foodConsumptionRepository,
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
        findViewById<android.view.View>(R.id.todayMenuButton).setOnClickListener { showTodayMenu(viewModel.state.value.todayMenu) }
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
        lifecycleScope.launch { viewModel.state.collect(::renderDashboard) }
    }

    private fun showTodayMenu(menu: List<HomeViewModel.NextMealState>) {
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), dp(12))
        }

        content.addView(TextView(this).apply {
            text = todayLabel()
            textSize = 12f
            setTextColor(getColor(R.color.text_secondary))
            setPadding(0, 0, 0, dp(8))
        })

        if (menu.isEmpty()) {
            val emptyCard = com.google.android.material.card.MaterialCardView(this).apply {
                radius = dp(14).toFloat()
                cardElevation = 0f
                setCardBackgroundColor(getColor(R.color.surface_secondary))
                strokeColor = getColor(R.color.divider)
                strokeWidth = dp(1)
                addView(TextView(this@HomeActivity).apply {
                    text = "Nessun pasto pianificato per oggi."
                    textSize = 14f
                    setTextColor(getColor(R.color.text_secondary))
                    setPadding(dp(16), dp(16), dp(16), dp(16))
                })
            }
            content.addView(emptyCard, LinearLayout.LayoutParams(-1, -2))
        } else {
            menu.sortedWith(compareBy<HomeViewModel.NextMealState> { it.timeMinutes ?: Int.MAX_VALUE }.thenBy { it.mealId })
                .forEachIndexed { index, meal ->
                    val card = com.google.android.material.card.MaterialCardView(this).apply {
                        radius = dp(14).toFloat()
                        cardElevation = 0f
                        setCardBackgroundColor(getColor(R.color.surface_primary))
                        strokeColor = getColor(R.color.divider)
                        strokeWidth = dp(1)
                        isClickable = true
                        isFocusable = true
                    }

                    val row = LinearLayout(this).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = android.view.Gravity.CENTER_VERTICAL
                        setPadding(dp(14), dp(12), dp(14), dp(12))
                    }

                    val timeBadge = TextView(this).apply {
                        val time = meal.timeMinutes?.let { "%02d:%02d".format(it / 60, it % 60) } ?: "—"
                        text = time
                        gravity = android.view.Gravity.CENTER
                        textSize = 12f
                        typeface = android.graphics.Typeface.DEFAULT_BOLD
                        setTextColor(getColor(R.color.accent_green_dark))
                        setBackgroundResource(R.drawable.bg_positive_soft)
                        setPadding(dp(9), dp(7), dp(9), dp(7))
                    }
                    row.addView(timeBadge, LinearLayout.LayoutParams(dp(62), -2))

                    row.addView(LinearLayout(this).apply {
                        orientation = LinearLayout.VERTICAL
                        setPadding(dp(12), 0, dp(8), 0)
                        addView(TextView(this@HomeActivity).apply {
                            text = meal.type.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.ITALIAN) else it.toString() }
                            textSize = 11f
                            typeface = android.graphics.Typeface.DEFAULT_BOLD
                            setTextColor(getColor(R.color.text_muted))
                        })
                        addView(TextView(this@HomeActivity).apply {
                            text = meal.title
                            textSize = 14f
                            typeface = android.graphics.Typeface.DEFAULT_BOLD
                            setTextColor(getColor(R.color.text_primary))
                            setPadding(0, dp(2), 0, 0)
                        })
                        addView(TextView(this@HomeActivity).apply {
                            text = meal.kcal?.let { "$it kcal" } ?: "Calorie non indicate"
                            textSize = 12f
                            setTextColor(getColor(R.color.text_secondary))
                            setPadding(0, dp(3), 0, 0)
                        })
                    }, LinearLayout.LayoutParams(0, -2, 1f))

                    row.addView(TextView(this).apply {
                        text = "›"
                        textSize = 24f
                        setTextColor(getColor(R.color.text_muted))
                        gravity = android.view.Gravity.CENTER
                    }, LinearLayout.LayoutParams(dp(24), dp(40)))

                    card.addView(row)
                    card.setOnClickListener {
                        startActivity(Intent(this@HomeActivity, MealDetailActivity::class.java).putExtra(MealDetailActivity.EXTRA_MEAL_ID, meal.mealId))
                    }

                    content.addView(card, LinearLayout.LayoutParams(-1, -2).apply {
                        if (index > 0) topMargin = dp(8)
                    })
                }
        }

        val scroll = android.widget.ScrollView(this).apply {
            isFillViewport = true
            overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
            addView(content, android.widget.ScrollView.LayoutParams(-1, -2))
        }

        MaterialAlertDialogBuilder(this)
            .setTitle("Pasti di oggi")
            .setView(scroll)
            .setNegativeButton("Chiudi", null)
            .show()
    }

    private fun renderDashboard(state: HomeViewModel.DashboardState) {
        val firstName = state.profileName?.trim()?.substringBefore(' ')?.takeIf { it.isNotBlank() }
        findViewById<TextView>(R.id.greetingText).text = firstName?.let { "Ciao $it 👋" } ?: "Ciao 👋"

        renderMetric(findViewById(R.id.metricWeight), state.weight.value, state.weight.deltaFromPrevious, "kg", DeltaSemantic.NEUTRAL)
        renderMetric(findViewById(R.id.metricFat), state.bodyFat.value, state.bodyFat.deltaFromPrevious, "%", DeltaSemantic.DOWN_IS_POSITIVE)
        renderMetric(findViewById(R.id.metricMuscle), state.muscleMass.value, state.muscleMass.deltaFromPrevious, "kg", DeltaSemantic.UP_IS_POSITIVE)
        renderEnergy(state.energy)
        renderCalorieProgress(state)

        findViewById<WeightTrendChartView>(R.id.weightTrendChart).setSeries(
            state.trendSeries.map { it.label to it.values },
        )
        findViewById<TextView>(R.id.recompositionStateText).text = trendStatus(state.recompositionState)
        findViewById<TextView>(R.id.trendExplanationText).text = "Confronta grasso corporeo e massa muscolare tra le rilevazioni disponibili. Non è una diagnosi: serve a capire la direzione dei cambiamenti nel tempo."
        renderTodaySummary(state)
        renderWeeklyExpectation(state)
        renderNextMeal(state.nextMeal)
        renderNextWorkout(state.nextWorkout)
    }

    private fun renderWeeklyExpectation(state: HomeViewModel.DashboardState) {
        val status = findViewById<TextView>(R.id.weeklyExpectationStatus)
        val detail = findViewById<TextView>(R.id.weeklyExpectationDetail)
        val result = state.weeklyExpectation
        status.text = when (result.goalStatus) {
            com.myfitai.app.domain.calculation.WeeklyBodyExpectation.GoalStatus.REACHED -> "Obiettivo in linea con gli ultimi dati"
            com.myfitai.app.domain.calculation.WeeklyBodyExpectation.GoalStatus.REVIEW_REQUIRED -> "Andamento da rivedere con più dati"
            com.myfitai.app.domain.calculation.WeeklyBodyExpectation.GoalStatus.IN_PROGRESS -> "Obiettivo in corso"
            com.myfitai.app.domain.calculation.WeeklyBodyExpectation.GoalStatus.NEEDS_MORE_DATA -> "Servono più rilevazioni"
        }
        detail.text = if (result.available) "Atteso dal piano: ${UiNumberFormat.decimal(result.expectedFatLossKgMin)}–${UiNumberFormat.decimal(result.expectedFatLossKgMax)} kg di grasso teorico. ${result.caution}" else "${result.caution}"
    }

    private fun renderTodaySummary(state: HomeViewModel.DashboardState) {
        val progress = DailyCalorieProgress.calculate(state.energy.effectiveTargetKcal, state.consumedKcalToday)
        val calories = findViewById<TextView>(R.id.todayCaloriesSummary)
        calories.text = when {
            progress.targetKcal == null -> "Calorie: target non disponibile"
            progress.status == DailyCalorieProgress.Status.EMPTY -> "Calorie: nessun alimento registrato"
            progress.exceededKcal > 0 -> "Calorie: sopra il target di ${progress.exceededKcal} kcal"
            else -> "Calorie: ${progress.consumedKcal} / ${progress.targetKcal} kcal registrate"
        }
        calories.setTextColor(getColor(if (progress.exceededKcal > 0) R.color.semantic_error else if (progress.status == DailyCalorieProgress.Status.COMPLETE) R.color.accent_green_dark else R.color.text_primary))
        findViewById<TextView>(R.id.todayMealsSummary).text = if (state.plannedMealsToday > 0) "Pasti: ${state.consumedMealsToday} di ${state.plannedMealsToday} registrati" else "Pasti: nessun piano per oggi"
        findViewById<TextView>(R.id.todayWorkoutSummary).text = when {
            state.workoutToday != null -> "Allenamento: ${state.workoutToday.title}"
            state.restDayToday -> "Allenamento: oggi è previsto riposo"
            else -> "Allenamento: nessuno registrato oggi"
        }
    }

    private fun renderCalorieProgress(state: HomeViewModel.DashboardState) {
        val progress = DailyCalorieProgress.calculate(state.energy.effectiveTargetKcal, state.consumedKcalToday)
        val calorieBar = findViewById<ProgressBar>(R.id.calorieProgressBar)
        calorieBar.progress = progress.percent
        calorieBar.progressTintList = ColorStateList.valueOf(getColorForCalorieStatus(progress.status))
        findViewById<TextView>(R.id.calorieProgressValue).text = when {
            progress.targetKcal == null -> "Completa i dati per calcolare il target."
            progress.status == DailyCalorieProgress.Status.EMPTY -> "Nessun alimento registrato · la pila è vuota"
            progress.exceededKcal > 0 -> "${progress.consumedKcal} / ${progress.targetKcal} kcal · sopra il target di ${progress.exceededKcal} kcal"
            progress.percent >= 100 -> "${progress.consumedKcal} / ${progress.targetKcal} kcal · target raggiunto"
            else -> "${progress.consumedKcal} / ${progress.targetKcal} kcal · ${progress.percent}% completato"
        }
        val recovery = state.energy.recovery
        findViewById<ProgressBar>(R.id.recoveryTankProgressBar).progress = DailyCalorieProgress.recoveryRemainingPercent(recovery)
        findViewById<TextView>(R.id.recoveryTankValue).text = if (recovery == null || recovery.budgetBeforeKcal <= 0) {
            "Nessun recovery attivo."
        } else {
            "${recovery.budgetAfterPlannedKcal} kcal residue · ${recovery.exerciseKcal} kcal esercizio già considerate · ${DailyCalorieProgress.recoveryRemainingPercent(recovery)}% dell'eccedenza"
        }
        findViewById<View>(R.id.energyRecoveryDetails).visibility = View.VISIBLE
    }

    private fun getColorForCalorieStatus(status: DailyCalorieProgress.Status): Int = when (status) {
        DailyCalorieProgress.Status.NO_TARGET -> getColor(R.color.text_muted)
        DailyCalorieProgress.Status.EMPTY -> getColor(R.color.semantic_warning)
        DailyCalorieProgress.Status.IN_PROGRESS, DailyCalorieProgress.Status.COMPLETE -> getColor(R.color.accent_green)
        DailyCalorieProgress.Status.EXCEEDED -> getColor(R.color.semantic_error)
    }

    private fun todaySummary(state: HomeViewModel.DashboardState): String {
        val target = state.energy.effectiveTargetKcal?.let { "Target $it kcal" } ?: "Target non disponibile"
        val meal = state.nextMeal?.let { meal ->
            val time = meal.timeMinutes?.let { String.format(Locale.ITALIAN, "%02d:%02d", it / 60, it % 60) }
            listOfNotNull("Pasto", time, meal.title).joinToString(" · ")
        } ?: "Nessun prossimo pasto"
        val workout = state.nextWorkout?.let { workout ->
            val time = Instant.ofEpochMilli(workout.startedAtEpochMillis)
                .atZone(ZoneId.systemDefault())
                .format(DateTimeFormatter.ofPattern("HH:mm", Locale.ITALIAN))
            "Allenamento $time · ${workout.title}"
        } ?: "Nessun allenamento imminente"
        return listOf(target, meal, workout).joinToString("\n")
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

    private fun renderEnergy(state: com.myfitai.app.domain.calculation.EnergyTargetPresentation.State) {
        val modeLabel = findViewById<TextView>(R.id.energyModeLabel)
        val goalLabel = findViewById<TextView>(R.id.energyGoalLabel)
        val targetCaption = findViewById<TextView>(R.id.energyTargetCaption)
        val targetValue = findViewById<TextView>(R.id.energyTargetValue)
        val subline = findViewById<TextView>(R.id.energyTargetSubline)
        val maintenanceValue = findViewById<TextView>(R.id.energyMaintenanceValue)
        val differenceValue = findViewById<TextView>(R.id.energyDifferenceValue)
        val recoveryDetails = findViewById<View>(R.id.energyRecoveryDetails)
        val recoveryValue = findViewById<TextView>(R.id.energyRecoveryValue)

        goalLabel.text = goalLabel(state.goal)
        findViewById<TextView>(R.id.energyGoalDescription).text = goalDescription(state.goal)
        modeLabel.text = when (state.mode) {
            EnergyTargetPresentation.Mode.DEFICIT -> "Deficit"
            EnergyTargetPresentation.Mode.MAINTENANCE -> "Mantenimento"
            EnergyTargetPresentation.Mode.SURPLUS -> "Surplus"
            EnergyTargetPresentation.Mode.RECOVERY_ADJUSTED -> "Recovery"
            EnergyTargetPresentation.Mode.INSUFFICIENT_DATA -> "Da completare"
        }
        val goalPercent = state.goal?.let { ((LocalCalculationEngine.goalEnergyFactor(it) - 1.0) * 100.0).roundToInt() }
        modeLabel.text = goalPercent?.let { formatGoalPercent(it) } ?: modeLabel.text
        targetCaption.text = if (state.mode == EnergyTargetPresentation.Mode.RECOVERY_ADJUSTED) "Target effettivo di oggi" else "Target di oggi"
        targetValue.text = state.effectiveTargetKcal?.let { "$it kcal" } ?: "Non disponibile"
        maintenanceValue.text = "${UiNumberFormat.decimal(state.tdeeKcal)} kcal"
        differenceValue.text = when (state.mode) {
            EnergyTargetPresentation.Mode.DEFICIT -> state.differenceKcal?.let { "−${abs(it)} kcal" } ?: "—"
            EnergyTargetPresentation.Mode.SURPLUS -> state.differenceKcal?.let { "+${abs(it)} kcal" } ?: "—"
            EnergyTargetPresentation.Mode.MAINTENANCE -> "In linea"
            EnergyTargetPresentation.Mode.RECOVERY_ADJUSTED -> state.recovery?.plannedRecoveryKcal?.let { "−$it kcal" } ?: "—"
            EnergyTargetPresentation.Mode.INSUFFICIENT_DATA -> "—"
        }
        val exerciseSummary = if (state.exerciseKcal > 0) " · Base ${UiNumberFormat.decimal(state.baseTdeeKcal)} + esercizio ${state.exerciseKcal} kcal" else ""
        subline.text = when (state.mode) {
            EnergyTargetPresentation.Mode.DEFICIT -> "Deficit previsto rispetto al mantenimento"
            EnergyTargetPresentation.Mode.MAINTENANCE -> "Target allineato al mantenimento"
            EnergyTargetPresentation.Mode.SURPLUS -> "Surplus previsto rispetto al mantenimento"
            EnergyTargetPresentation.Mode.RECOVERY_ADJUSTED -> "Riduzione temporanea, senza compensazioni punitive"
            EnergyTargetPresentation.Mode.INSUFFICIENT_DATA -> "Completa profilo, peso e rilevazioni"
        } + exerciseSummary
        recoveryDetails.visibility = View.VISIBLE
        recoveryValue.text = state.recovery?.let {
            "Normale ${state.normalTargetKcal ?: "—"} kcal  •  Eccedenza residua ${it.budgetAfterPlannedKcal} kcal  •  Confermato ${it.confirmedRecoveryKcal} kcal"
        } ?: "Nessun recovery attivo."
    }

    private fun goalLabel(goal: LocalCalculationEngine.Goal?): String = when (goal) {
        LocalCalculationEngine.Goal.RECOMPOSITION -> "Ricomposizione"
        LocalCalculationEngine.Goal.WEIGHT_LOSS -> "Dimagrimento"
        LocalCalculationEngine.Goal.MAINTENANCE -> "Mantenimento"
        LocalCalculationEngine.Goal.MUSCLE_GAIN -> "Aumento massa"
        LocalCalculationEngine.Goal.PERFORMANCE -> "Performance"
        null -> "Obiettivo energetico"
    }

    private fun goalDescription(goal: LocalCalculationEngine.Goal?): String = when (goal) {
        LocalCalculationEngine.Goal.RECOMPOSITION -> "Ridurre gradualmente il grasso e mantenere o aumentare la massa muscolare."
        LocalCalculationEngine.Goal.WEIGHT_LOSS -> "Creare un deficit calorico controllato per ridurre il peso nel tempo."
        LocalCalculationEngine.Goal.MAINTENANCE -> "Mantenere il peso attuale con un apporto vicino al consumo giornaliero."
        LocalCalculationEngine.Goal.MUSCLE_GAIN -> "Favorire l'aumento della massa muscolare con un apporto energetico adeguato."
        LocalCalculationEngine.Goal.PERFORMANCE -> "Sostenere allenamenti e recupero con energia sufficiente."
        null -> "Seleziona un obiettivo per interpretare il target calorico."
    }

    private fun formatGoalPercent(percent: Int): String = when {
        percent > 0 -> "+$percent%"
        percent < 0 -> "−${abs(percent)}%"
        else -> "0%"
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

    private fun trendStatus(state: LocalCalculationEngine.RecompositionState): String = when (state) {
        LocalCalculationEngine.RecompositionState.FAVORABLE -> "Direzione favorevole: grasso in calo, massa muscolare in aumento."
        LocalCalculationEngine.RecompositionState.FAT_LOSS_WITH_STABLE_MUSCLE -> "Grasso in calo, massa muscolare stabile."
        LocalCalculationEngine.RecompositionState.MUSCLE_GAIN_WITH_STABLE_FAT -> "Massa muscolare in aumento, grasso stabile."
        LocalCalculationEngine.RecompositionState.STABLE -> "Trend stabile: non emergono cambiamenti rilevanti."
        LocalCalculationEngine.RecompositionState.MIXED -> "Trend misto: i dati non mostrano una direzione unica."
        LocalCalculationEngine.RecompositionState.NOT_ENOUGH_DATA -> "Dati insufficienti per leggere una direzione."
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun formatNumber(value: Float): String = if (value % 1f == 0f) value.toInt().toString() else String.format(Locale.ITALIAN, "%.1f", value)
    private fun formatSigned(value: Float): String = String.format(Locale.ITALIAN, "%+.1f", value)
}
