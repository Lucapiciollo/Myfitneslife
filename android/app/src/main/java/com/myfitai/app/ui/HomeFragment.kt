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
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.myfitai.app.R
import com.myfitai.app.data.AppDataContainer
import com.myfitai.app.domain.calculation.EnergyTargetPresentation
import com.myfitai.app.domain.calculation.LocalCalculationEngine
import com.myfitai.app.domain.calculation.DailyCalorieProgress
import com.myfitai.app.notifications.NotificationPreferences
import com.myfitai.app.ui.home.HomeViewModel
import com.myfitai.app.ui.widgets.MealCardView
import com.myfitai.app.ui.widgets.MetricCardView
import com.myfitai.app.ui.widgets.TimeRangeSelectorView
import com.myfitai.app.ui.widgets.WeightTrendChartView
import com.myfitai.app.ui.widgets.WorkoutCardView
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.time.DayOfWeek
import java.time.temporal.TemporalAdjusters
import kotlin.math.abs
import kotlin.math.roundToInt

class HomeFragment : Fragment(R.layout.activity_home) {
    private val data by lazy { AppDataContainer.get(requireContext()) }
    private val viewModel: HomeViewModel by viewModels {
        HomeViewModel.Factory(data.userProfileRepository, data.biaRepository, data.bodyMeasurementRepository,
            data.workoutRepository, data.mealPlanRepository, data.activeProfileStore, data.profileCalculationService,
            data.nutritionRecoveryRepository, data.foodConsumptionRepository)
    }
    private var nextMealId: Long? = null
    private val mealAlternativeLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { }
    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) lifecycleScope.launch { runCatching { data.notificationScheduler.refresh() } }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        view.findViewById<View>(R.id.profileButton).setOnClickListener { startActivity(Intent(requireContext(), ProfileActivity::class.java)) }
        view.findViewById<View>(R.id.nextMealCard).setOnClickListener {
            if (!com.myfitai.app.ai.AiProviderAccess.requireConfigured(requireActivity())) return@setOnClickListener
            nextMealId?.let { startActivity(Intent(requireContext(), MealDetailActivity::class.java).putExtra(MealDetailActivity.EXTRA_MEAL_ID, it)) }
                ?: (activity as? TabHostActivity)?.selectTab(com.myfitai.app.navigation.BottomNavBinder.Tab.FOOD)
        }
        (view.findViewById<MealCardView>(R.id.nextMealCard)).setOnChangeClickListener {
            val meal = viewModel.state.value.nextMeal ?: return@setOnChangeClickListener
            if (!com.myfitai.app.ai.AiProviderAccess.requireConfigured(requireActivity())) return@setOnChangeClickListener
            MaterialAlertDialogBuilder(requireContext())
                .setTitle("Cambiare questo pasto?")
                .setMessage("Genererò un'alternativa della stessa categoria e dello stesso orario, senza modificare i pasti successivi.")
                .setNegativeButton("Annulla", null)
                .setPositiveButton("Genera alternativa") { _, _ ->
                    val weekStart = LocalDate.ofEpochDay(meal.dateEpochDay).with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
                    mealAlternativeLauncher.launch(Intent(requireContext(), MealAlternativeActivity::class.java).apply {
                        putExtra(MealAlternativeActivity.EXTRA_WEEK_START_EPOCH_DAY, weekStart.toEpochDay())
                        putExtra(MealAlternativeActivity.EXTRA_DAY_EPOCH_DAY, meal.dateEpochDay)
                        putExtra(MealAlternativeActivity.EXTRA_MEAL_ID, meal.mealId)
                        putExtra(MealAlternativeActivity.EXTRA_MEAL_TITLE, meal.title)
                        putExtra(MealAlternativeActivity.EXTRA_MEAL_TYPE, meal.type)
                        putExtra(MealAlternativeActivity.EXTRA_MEAL_KCAL, meal.kcal ?: -1)
                    })
                }.show()
        }
        view.findViewById<View>(R.id.nextWorkoutCard).setOnClickListener { startActivity(Intent(requireContext(), WorkoutsActivity::class.java)) }
        view.findViewById<View>(R.id.todayWorkoutButton).setOnClickListener { startActivity(Intent(requireContext(), WorkoutsActivity::class.java)) }
        view.findViewById<View>(R.id.measurementsButton).setOnClickListener { startActivity(Intent(requireContext(), MeasurementsActivity::class.java)) }
        view.findViewById<View>(R.id.todayMenuButton).setOnClickListener { showTodayMenu(viewModel.state.value.todayMenu) }
        view.findViewById<TextView>(R.id.todayLabel).text = LocalDate.now().format(DateTimeFormatter.ofPattern("EEEE d MMMM", Locale.ITALIAN)).replaceFirstChar { it.uppercase(Locale.ITALIAN) }
        view.findViewById<MetricCardView>(R.id.metricWeight).setLabel(getString(R.string.dashboard_metric_weight))
        view.findViewById<MetricCardView>(R.id.metricFat).setLabel(getString(R.string.dashboard_metric_fat))
        view.findViewById<MetricCardView>(R.id.metricMuscle).setLabel(getString(R.string.dashboard_metric_muscle))
        view.findViewById<WeightTrendChartView>(R.id.weightTrendChart).showYAxisLabels()
        view.findViewById<TimeRangeSelectorView>(R.id.timeRangeSelector).apply {
            setRanges(listOf("1W", "1M", "3M", "1Y"), 1)
            setOnRangeSelectedListener(viewModel::selectRange)
        }
        viewLifecycleOwner.lifecycleScope.launch { viewModel.state.collect { render(it) } }
        requestNotificationPermission()
    }

    private fun showTodayMenu(menu: List<HomeViewModel.NextMealState>) {
        val content = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(4), dp(20), dp(4))
        }
        if (menu.isEmpty()) {
            content.addView(TextView(requireContext()).apply { text = "Nessun menu pianificato per oggi."; textSize = 14f; setTextColor(requireContext().getColor(R.color.text_secondary)) })
        } else {
            menu.forEach { meal ->
                content.addView(TextView(requireContext()).apply {
                    val time = meal.timeMinutes?.let { "%02d:%02d".format(it / 60, it % 60) } ?: "Orario non indicato"
                    text = "$time · ${meal.type}\n${meal.title} · ${meal.kcal ?: "—"} kcal"
                    textSize = 14f
                    setTextColor(requireContext().getColor(R.color.text_primary))
                    setPadding(0, dp(10), 0, dp(10))
                })
            }
        }
        MaterialAlertDialogBuilder(requireContext()).setTitle("Menu di oggi").setView(content).setPositiveButton("Chiudi", null).show()
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) return
        val prefs = NotificationPreferences(requireContext())
        if (!prefs.permissionPrompted) { prefs.permissionPrompted = true; permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) }
    }

    private fun render(state: HomeViewModel.DashboardState) {
        val view = view ?: return
        val firstName = state.profileName?.trim()?.substringBefore(' ')?.takeIf { it.isNotBlank() }
        view.findViewById<TextView>(R.id.greetingText).text = firstName?.let { "Ciao $it 👋" } ?: "Ciao 👋"
        renderMetric(view.findViewById(R.id.metricWeight), state.weight.value, state.weight.deltaFromPrevious, "kg", MetricCardView.DeltaState.NEUTRAL)
        renderMetric(view.findViewById(R.id.metricFat), state.bodyFat.value, state.bodyFat.deltaFromPrevious, "%", deltaState(state.bodyFat.deltaFromPrevious, downPositive = true))
        renderMetric(view.findViewById(R.id.metricMuscle), state.muscleMass.value, state.muscleMass.deltaFromPrevious, "kg", deltaState(state.muscleMass.deltaFromPrevious, downPositive = false))
        view.findViewById<WeightTrendChartView>(R.id.weightTrendChart).setSeries(state.trendSeries.map { it.label to it.values })
        view.findViewById<TextView>(R.id.recompositionStateText).text = trendStatus(state.recompositionState)
        view.findViewById<TextView>(R.id.trendExplanationText).text = "Confronta grasso corporeo e massa muscolare tra le rilevazioni disponibili. Non è una diagnosi: serve a capire la direzione dei cambiamenti nel tempo."
        renderEnergy(view, state.energy)
        renderCalorieProgress(view, state)
        renderTodaySummary(view, state)
        renderWeeklyExpectation(view, state)
        nextMealId = state.nextMeal?.mealId
        view.findViewById<MealCardView>(R.id.nextMealCard).apply {
            val meal = state.nextMeal
            val date = meal?.dateEpochDay?.let(LocalDate::ofEpochDay)
            val dayLabel = when (date) {
                LocalDate.now() -> "Oggi"
                LocalDate.now().plusDays(1) -> "Domani"
                null -> "—"
                else -> date.format(DateTimeFormatter.ofPattern("EEE d MMM", Locale.ITALIAN))
            }
            setTime(listOfNotNull(dayLabel, meal?.timeMinutes?.let { "%02d:%02d".format(it / 60, it % 60) }).joinToString(" "))
            setTitle(meal?.title ?: "Nessun pasto pianificato")
            setKcal(meal?.kcal?.let { "$it kcal" } ?: "Apri il piano alimentare")
            setImage(R.drawable.img_next_meal)
            setChangeEnabled(meal != null && meal.dateEpochDay >= LocalDate.now().toEpochDay())
        }
        view.findViewById<WorkoutCardView>(R.id.nextWorkoutCard).apply {
            val workout = state.workoutToday ?: state.nextWorkout
            setTitle(workout?.title ?: if (state.restDayToday) "Oggi riposo" else "Nessun allenamento pianificato")
            setTime(state.workoutToday?.let { java.time.Instant.ofEpochMilli(it.startedAtEpochMillis).atZone(java.time.ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("HH:mm", Locale.ITALIAN)) } ?: if (state.restDayToday) "Riposo" else "—")
            setImage(R.drawable.img_next_workout)
        }
        view.findViewById<TextView>(R.id.secondMealSummary).text = state.upcomingMeals.meals.getOrNull(1)?.let { meal ->
            "${meal.timeMinutes?.let { "%02d:%02d".format(it / 60, it % 60) } ?: "—"} · ${meal.title} · ${meal.kcal ?: "—"} kcal"
        } ?: "Nessun altro pasto imminente"
    }

    private fun renderWeeklyExpectation(view: View, state: HomeViewModel.DashboardState) {
        val result = state.weeklyExpectation
        view.findViewById<TextView>(R.id.weeklyExpectationStatus).text = when (result.goalStatus) {
            com.myfitai.app.domain.calculation.WeeklyBodyExpectation.GoalStatus.REACHED -> "Obiettivo in linea con gli ultimi dati"
            com.myfitai.app.domain.calculation.WeeklyBodyExpectation.GoalStatus.REVIEW_REQUIRED -> "Andamento da rivedere con più dati"
            com.myfitai.app.domain.calculation.WeeklyBodyExpectation.GoalStatus.IN_PROGRESS -> "Obiettivo in corso"
            else -> "Servono più rilevazioni"
        }
        view.findViewById<TextView>(R.id.weeklyExpectationDetail).text = if (result.available) "Atteso dal piano: ${UiNumberFormat.decimal(result.expectedFatLossKgMin)}–${UiNumberFormat.decimal(result.expectedFatLossKgMax)} kg di grasso teorico. ${result.caution}" else result.caution
    }

    private fun renderTodaySummary(view: View, state: HomeViewModel.DashboardState) {
        val progress = DailyCalorieProgress.calculate(state.energy.effectiveTargetKcal, state.consumedKcalToday)
        val calories = view.findViewById<TextView>(R.id.todayCaloriesSummary)
        calories.text = when {
            progress.targetKcal == null -> "Calorie: target non disponibile"
            progress.status == DailyCalorieProgress.Status.EMPTY -> "Calorie: nessun alimento registrato"
            progress.exceededKcal > 0 -> "Calorie: sopra il target di ${progress.exceededKcal} kcal"
            else -> "Calorie: ${progress.consumedKcal} / ${progress.targetKcal} kcal registrate"
        }
        calories.setTextColor(requireContext().getColor(if (progress.exceededKcal > 0) R.color.semantic_error else if (progress.status == DailyCalorieProgress.Status.COMPLETE) R.color.accent_green_dark else R.color.text_primary))
        view.findViewById<TextView>(R.id.todayMealsSummary).text = if (state.plannedMealsToday > 0) "Pasti: ${state.consumedMealsToday} di ${state.plannedMealsToday} registrati" else "Pasti: nessun piano per oggi"
        view.findViewById<TextView>(R.id.todayWorkoutSummary).text = when {
            state.workoutToday != null -> "Allenamento: ${state.workoutToday.title}"
            state.restDayToday -> "Allenamento: oggi è previsto riposo"
            else -> "Allenamento: nessuno registrato oggi"
        }
    }

    private fun renderCalorieProgress(view: View, state: HomeViewModel.DashboardState) {
        val progress = DailyCalorieProgress.calculate(state.energy.effectiveTargetKcal, state.consumedKcalToday)
        val calorieBar = view.findViewById<ProgressBar>(R.id.calorieProgressBar)
        calorieBar.progress = progress.percent
        calorieBar.progressTintList = ColorStateList.valueOf(getColorForCalorieStatus(progress.status))
        view.findViewById<TextView>(R.id.calorieProgressValue).text = when {
            progress.targetKcal == null -> "Completa i dati per calcolare il target."
            progress.status == DailyCalorieProgress.Status.EMPTY -> "Nessun alimento registrato · la pila è vuota"
            progress.exceededKcal > 0 -> "${progress.consumedKcal} / ${progress.targetKcal} kcal · sopra il target di ${progress.exceededKcal} kcal"
            progress.percent >= 100 -> "${progress.consumedKcal} / ${progress.targetKcal} kcal · target raggiunto"
            else -> "${progress.consumedKcal} / ${progress.targetKcal} kcal · ${progress.percent}% completato"
        }
        val recovery = state.energy.recovery
        val recoveryBar = view.findViewById<ProgressBar>(R.id.recoveryTankProgressBar)
        val recoveryValue = view.findViewById<TextView>(R.id.recoveryTankValue)
        if (recovery == null || recovery.budgetBeforeKcal <= 0) {
            recoveryBar.progress = 0
            recoveryValue.text = "Nessun recovery attivo."
        } else {
            recoveryBar.progress = DailyCalorieProgress.recoveryRemainingPercent(recovery)
            recoveryValue.text = "${recovery.budgetAfterPlannedKcal} kcal residue · ${recovery.exerciseKcal} kcal esercizio già considerate · ${recoveryBar.progress}% dell'eccedenza"
        }
        view.findViewById<View>(R.id.energyRecoveryDetails).visibility = View.VISIBLE
    }

    private fun getColorForCalorieStatus(status: DailyCalorieProgress.Status): Int = when (status) {
        DailyCalorieProgress.Status.NO_TARGET -> requireContext().getColor(R.color.text_muted)
        DailyCalorieProgress.Status.EMPTY -> requireContext().getColor(R.color.semantic_warning)
        DailyCalorieProgress.Status.IN_PROGRESS, DailyCalorieProgress.Status.COMPLETE -> requireContext().getColor(R.color.accent_green)
        DailyCalorieProgress.Status.EXCEEDED -> requireContext().getColor(R.color.semantic_error)
    }

    private fun renderMetric(view: MetricCardView, value: Float?, delta: Float?, unit: String, deltaState: MetricCardView.DeltaState) {
        view.setValue(value?.let {
            val formatted = if (it % 1f == 0f) it.toInt().toString() else String.format(Locale.ITALIAN, "%.1f", it)
            "$formatted $unit"
        } ?: "—")
        view.setDelta(delta?.let { String.format(Locale.ITALIAN, "%+.1f %s", it, unit) } ?: "Dati insufficienti", deltaState)
    }

    private fun deltaState(delta: Float?, downPositive: Boolean): MetricCardView.DeltaState = when {
        delta == null || delta == 0f -> MetricCardView.DeltaState.NEUTRAL
        downPositive && delta < 0f || !downPositive && delta > 0f -> MetricCardView.DeltaState.POSITIVE
        else -> MetricCardView.DeltaState.NEGATIVE
    }

    private fun renderEnergy(view: View, state: EnergyTargetPresentation.State) {
        view.findViewById<TextView>(R.id.energyGoalLabel).text = goalLabel(state.goal)
        view.findViewById<TextView>(R.id.energyGoalDescription).text = goalDescription(state.goal)
        val percent = state.goal?.let { ((LocalCalculationEngine.goalEnergyFactor(it) - 1.0) * 100.0).roundToInt() }
        view.findViewById<TextView>(R.id.energyModeLabel).text = percent?.let { if (it > 0) "+$it%" else if (it < 0) "−${abs(it)}%" else "0%" } ?: "—"
        view.findViewById<TextView>(R.id.energyTargetCaption).text = if (state.mode == EnergyTargetPresentation.Mode.RECOVERY_ADJUSTED) "Target effettivo di oggi" else "Target di oggi"
        view.findViewById<TextView>(R.id.energyTargetValue).text = state.effectiveTargetKcal?.let { "$it kcal" } ?: "Non disponibile"
        view.findViewById<TextView>(R.id.energyMaintenanceValue).text = "${UiNumberFormat.decimal(state.tdeeKcal)} kcal"
        view.findViewById<TextView>(R.id.energyDifferenceValue).text = state.differenceKcal?.let { if (it < 0) "−${abs(it)} kcal" else "+${it} kcal" } ?: "—"
        val exerciseSummary = if (state.exerciseKcal > 0) " · Base ${UiNumberFormat.decimal(state.baseTdeeKcal)} + esercizio ${state.exerciseKcal} kcal" else ""
        view.findViewById<TextView>(R.id.energyTargetSubline).text = when (state.mode) {
            EnergyTargetPresentation.Mode.DEFICIT -> "Deficit previsto rispetto al mantenimento"
            EnergyTargetPresentation.Mode.MAINTENANCE -> "Target allineato al mantenimento"
            EnergyTargetPresentation.Mode.SURPLUS -> "Surplus previsto rispetto al mantenimento"
            EnergyTargetPresentation.Mode.RECOVERY_ADJUSTED -> "Riduzione temporanea, senza compensazioni punitive"
            EnergyTargetPresentation.Mode.INSUFFICIENT_DATA -> "Completa profilo, peso e rilevazioni"
        } + exerciseSummary
        view.findViewById<View>(R.id.energyRecoveryDetails).visibility = View.VISIBLE
        view.findViewById<TextView>(R.id.energyRecoveryValue).text = state.recovery?.let { "Normale ${state.normalTargetKcal ?: "—"} kcal  •  Eccedenza residua ${it.budgetAfterPlannedKcal} kcal  •  Confermato ${it.confirmedRecoveryKcal} kcal" }.orEmpty()
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

    private fun todaySummary(state: HomeViewModel.DashboardState): String = listOf(
        state.energy.effectiveTargetKcal?.let { "Target $it kcal" } ?: "Target non disponibile",
        if (state.plannedMealsToday > 0) "Pasti registrati ${state.consumedMealsToday}/${state.plannedMealsToday}" else "Nessun piano per oggi",
        state.nextMeal?.let { "Pasto ${it.title}" } ?: "Nessun prossimo pasto",
        state.nextWorkout?.let { "Allenamento ${it.title}" } ?: "Nessun allenamento imminente",
        freshnessLabel("BIA", state.latestBiaAgeDays),
        freshnessLabel("Misure", state.latestBodyMeasurementAgeDays),
    ).joinToString("\n")

    private fun freshnessLabel(label: String, ageDays: Long?): String = when (ageDays) {
        null -> "$label: nessun dato"
        0L -> "$label: oggi"
        1L -> "$label: ieri"
        else -> "$label: ${ageDays} giorni fa"
    }

    private fun recompositionText(state: LocalCalculationEngine.RecompositionState): String = when (state) {
        LocalCalculationEngine.RecompositionState.FAVORABLE -> "Trend: grasso in calo e massa muscolare in aumento."
        LocalCalculationEngine.RecompositionState.FAT_LOSS_WITH_STABLE_MUSCLE -> "Trend: grasso in calo con massa muscolare stabile."
        LocalCalculationEngine.RecompositionState.MUSCLE_GAIN_WITH_STABLE_FAT -> "Trend: massa muscolare in aumento con grasso stabile."
        LocalCalculationEngine.RecompositionState.STABLE -> "Trend corporeo sostanzialmente stabile."
        LocalCalculationEngine.RecompositionState.MIXED -> "Trend misto: servono più rilevazioni."
        LocalCalculationEngine.RecompositionState.NOT_ENOUGH_DATA -> "Aggiungi rilevazioni per vedere il trend corporeo."
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
}
