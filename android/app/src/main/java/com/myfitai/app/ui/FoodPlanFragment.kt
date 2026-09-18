package com.myfitai.app.ui

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.myfitai.app.R
import com.myfitai.app.data.AppDataContainer
import com.myfitai.app.domain.calculation.EnergyTargetPresentation
import com.myfitai.app.domain.calculation.LocalCalculationEngine
import com.myfitai.app.domain.food.*
import com.myfitai.app.ui.food.FoodPlanViewModel
import com.myfitai.app.ui.widgets.MealPlanRowView
import com.myfitai.app.ui.widgets.WeekDaySelectorView
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

class FoodPlanFragment : Fragment(R.layout.activity_food_plan) {
    private val data by lazy { AppDataContainer.get(requireContext()) }
    private val viewModel: FoodPlanViewModel by viewModels {
        FoodPlanViewModel.Factory(data.mealPlanRepository, data.userProfileRepository, data.activeProfileStore,
            data.nutritionPlanGenerationService, data.profileCalculationService, data.notificationScheduler,
             data.foodConsumptionRepository, data.cheatEntryRepository, data.nutritionRecoveryRepository, data.workoutEnergyExpenditureRepository, data.aiJobScheduler)
    }
    private val mealAlternativeLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { }

    fun selectWeek(epochDay: Long) { if (isAdded) viewModel.selectWeek(epochDay) }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        view.findViewById<View>(R.id.prevWeekButton).setOnClickListener { viewModel.previousWeek() }
        view.findViewById<View>(R.id.nextWeekButton).setOnClickListener { viewModel.nextWeek() }
        view.findViewById<View>(R.id.shoppingButton).setOnClickListener {
            viewModel.state.value.takeIf { it.hasPlan }?.let { startActivity(Intent(requireContext(), ShoppingListActivity::class.java).putExtra(ShoppingListActivity.EXTRA_WEEK_START_EPOCH_DAY, it.weekStart.toEpochDay())) }
        }
        view.findViewById<View>(R.id.cheatButton).setOnClickListener { if (viewModel.state.value.hasPlan) startActivity(Intent(requireContext(), CheatEntryActivity::class.java)) }
        view.findViewById<View>(R.id.generatePlanButton).setOnClickListener { confirmGeneration() }
        view.findViewById<WeekDaySelectorView>(R.id.weekDaySelector).setOnDaySelectedListener(viewModel::selectDay)
        arrangeFoodSections(view)
        viewLifecycleOwner.lifecycleScope.launch { viewModel.state.collect(::render) }
    }

    private fun arrangeFoodSections(root: View) {
        val content = root.findViewById<WeekDaySelectorView>(R.id.weekDaySelector).parent as? LinearLayout ?: return
        val orderedIds = listOf(
            R.id.energyTargetCard,
            R.id.mealCountHint,
            R.id.recoverySummary,
            R.id.emptyPlanText,
            R.id.generationStatusContainer,
            R.id.mealsContainer,
            R.id.supplementsCard,
            R.id.dailyTotalContainer,
            R.id.weeklyActionsCard,
            R.id.generatePlanButton,
        )
        val views = orderedIds.mapNotNull(content::findViewById)
        views.forEach(content::removeView)
        views.forEach(content::addView)
    }

    private fun confirmGeneration() {
        MaterialAlertDialogBuilder(requireContext()).setTitle("Generare il piano con IA?")
            .setMessage("La generazione invia una richiesta al provider IA e valida il piano localmente prima del salvataggio.")
            .setNegativeButton("Annulla", null)
            .setPositiveButton("Conferma e genera") { _, _ -> viewModel.generateCurrentWeek() }.show()
    }

    private fun render(state: FoodPlanViewModel.State) {
        val root = view ?: return
        root.findViewById<TextView>(R.id.weekRangeLabel).text = formatWeekRange(state.weekStart, state.weekStart.plusDays(6))
        root.findViewById<WeekDaySelectorView>(R.id.weekDaySelector).setDays((0..6).map { offset ->
            val date = state.weekStart.plusDays(offset.toLong())
            WeekDaySelectorView.Day(date.format(DateTimeFormatter.ofPattern("EEE", Locale.ITALIAN)).replaceFirstChar { it.uppercase() }.take(3), date.dayOfMonth.toString())
        }, state.selectedDayIndex)
        root.findViewById<TextView>(R.id.planVersionLabel).apply {
            visibility = if (state.snapshot == null) View.GONE else View.VISIBLE
            text = state.snapshot?.let { "Piano v${it.version.versionNumber}${it.version.reason?.let { reason -> " · $reason" }.orEmpty()}" }.orEmpty()
        }
        root.findViewById<TextView>(R.id.mealCountHint).text = "${data.mealCountPreferences.get(data.activeProfileStore.currentIdOrNull() ?: 0L)} pasti al giorno"
        root.findViewById<View>(R.id.shoppingButton).isEnabled = state.hasPlan && !state.generation.running
        root.findViewById<View>(R.id.cheatButton).isEnabled = state.hasPlan && !state.generation.running
        val empty = root.findViewById<TextView>(R.id.emptyPlanText)
        empty.visibility = if (state.hasPlan && state.selectedDay != null) View.GONE else View.VISIBLE
        empty.text = if (state.hasPlan) "Nessun dato alimentare per il giorno selezionato." else "Nessun piano per questa settimana. Genera un piano per vedere pasti, quantità e valori nutrizionali."
        renderEnergy(root, state.energy)
        renderRecovery(root, state)
        renderGeneration(root, state)
        renderMeals(root, state)
        renderSupplements(root, state.selectedDay)
        renderTotals(root, state)
    }

    private fun renderMeals(root: View, state: FoodPlanViewModel.State) {
        val container = root.findViewById<LinearLayout>(R.id.mealsContainer)
        container.removeAllViews()
        val day = state.selectedDay ?: return
        day.meals.sortedBy { it.sortOrder }.forEach { meal ->
            val changeEnabled = canChangeMeal(day.dateEpochDay, meal.timeMinutes) && meal.kcal != null
            container.addView(MealPlanRowView(requireContext()).apply {
                setTitle(displayMealType(meal.type)); setDescription(meal.title); setKcal(meal.kcal?.let { "$it kcal" } ?: "—"); setImage(imageFor(meal))
                val record = state.consumptionRecords.firstOrNull { it.itemKey == FoodConsumptionKeys.meal(meal.id) }
                val past = LocalDate.ofEpochDay(day.dateEpochDay).isBefore(LocalDate.now()) || (LocalDate.ofEpochDay(day.dateEpochDay) == LocalDate.now() && (meal.timeMinutes ?: Int.MAX_VALUE) <= LocalTime.now().hour * 60 + LocalTime.now().minute)
                setStatus(when (record?.status) { FoodConsumptionStatus.CONSUMED.name -> "Registrato da te"; FoodConsumptionStatus.SKIPPED.name -> "Saltato"; else -> if (past) "Pasto concluso · da registrare" else "Da registrare" }, record?.status == FoodConsumptionStatus.CONSUMED.name)
                setOnClickListener { startActivity(Intent(requireContext(), MealDetailActivity::class.java).putExtra(MealDetailActivity.EXTRA_MEAL_ID, meal.id)) }
                setChangeEnabled(changeEnabled)
                if (changeEnabled) setOnChangeClickListener { mealAlternativeLauncher.launch(Intent(requireContext(), MealAlternativeActivity::class.java).apply {
                    putExtra(MealAlternativeActivity.EXTRA_WEEK_START_EPOCH_DAY, state.weekStart.toEpochDay()); putExtra(MealAlternativeActivity.EXTRA_DAY_EPOCH_DAY, day.dateEpochDay); putExtra(MealAlternativeActivity.EXTRA_MEAL_ID, meal.id); putExtra(MealAlternativeActivity.EXTRA_MEAL_TITLE, meal.title); putExtra(MealAlternativeActivity.EXTRA_MEAL_TYPE, displayMealType(meal.type)); putExtra(MealAlternativeActivity.EXTRA_MEAL_KCAL, meal.kcal ?: -1)
                }) }
                contentDescription = "${displayMealType(meal.type)}: ${meal.title}"
            }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        }
        day.hydrationNote?.takeIf { it.isNotBlank() }?.let { container.addView(infoRow("Idratazione", it)) }
    }

    private fun renderSupplements(root: View, day: FoodPlanDay?) {
        val card = root.findViewById<View>(R.id.supplementsCard)
        val container = root.findViewById<LinearLayout>(R.id.supplementsContainer)
        container.removeAllViews()
        val supplements = day?.supplements.orEmpty()
        card.visibility = if (supplements.isEmpty()) View.GONE else View.VISIBLE
        supplements.forEach { supplement ->
            val timing = supplement.timeMinutes?.let { "%02d:%02d · ".format(it / 60, it % 60) }.orEmpty()
            container.addView(infoRow(
                "${timing}${supplement.name} · dose ${supplement.dose} ${supplement.unit}",
                "${supplement.kcal} kcal · Proteine ${supplement.proteinG} g · Carboidrati ${supplement.carbsG} g · Grassi ${supplement.fatG} g${supplement.notes?.let { "\nNote: $it" }.orEmpty()}",
            ))
        }
    }

    private fun renderTotals(root: View, state: FoodPlanViewModel.State) {
        val day = state.selectedDay ?: run { root.findViewById<View>(R.id.dailyTotalContainer).visibility = View.GONE; root.findViewById<View>(R.id.dailyTotalHeader).visibility = View.GONE; return }
        root.findViewById<View>(R.id.dailyTotalContainer).visibility = View.VISIBLE; root.findViewById<View>(R.id.dailyTotalHeader).visibility = View.VISIBLE
        val totals = FoodPlanMetrics.dayTotals(day)
        val version = state.snapshot?.version
        val records = state.consumptionRecords.filter { it.planVersionId == version?.id && it.plannedDateEpochDay == day.dateEpochDay }
        val consumed = FoodConsumptionMetrics.dayTotals(records)
        val exerciseKcal = state.exerciseKcalByDay[day.dateEpochDay] ?: 0
        root.findViewById<TextView>(R.id.dailyTotalHeader).text = "Totale giornaliero · ${LocalDate.ofEpochDay(day.dateEpochDay).format(DateTimeFormatter.ofPattern("EEE d MMM", Locale.ITALIAN)).replaceFirstChar { it.uppercase() }}"
        root.findViewById<TextView>(R.id.dailyTotalLegend).text = if (exerciseKcal > 0) "Registrato da te = cibo segnato. Esercizio = kcal allenamento. Bilancio netto = cibo registrato − esercizio." else "Registrato da te = cibo segnato. Il bilancio netto mostra il cibo registrato meno l'esercizio."
        root.findViewById<TextView>(R.id.totalKcalTarget).text = formatValue(version?.targetKcal, "kcal"); root.findViewById<TextView>(R.id.totalKcalPlanned).text = formatValue(totals.kcal, "kcal"); root.findViewById<TextView>(R.id.totalKcalConsumed).text = formatValue(consumed.kcal, "kcal")
        root.findViewById<TextView>(R.id.exerciseKcalValue).text = if (exerciseKcal > 0) "+$exerciseKcal kcal" else "Nessun allenamento"
        root.findViewById<TextView>(R.id.netKcalValue).text = if (exerciseKcal > 0) "${(consumed.kcal - exerciseKcal).toInt()} kcal" else formatValue(consumed.kcal, "kcal")
        root.findViewById<TextView>(R.id.totalProteinTarget).text = formatValue(version?.targetProteinG, "g"); root.findViewById<TextView>(R.id.totalProteinPlanned).text = formatValue(totals.proteinG, "g"); root.findViewById<TextView>(R.id.totalProteinConsumed).text = formatValue(consumed.proteinG, "g")
        root.findViewById<TextView>(R.id.totalCarbsTarget).text = formatValue(version?.targetCarbsG, "g"); root.findViewById<TextView>(R.id.totalCarbsPlanned).text = formatValue(totals.carbsG, "g"); root.findViewById<TextView>(R.id.totalCarbsConsumed).text = formatValue(consumed.carbsG, "g")
        root.findViewById<TextView>(R.id.totalFatTarget).text = formatValue(version?.targetFatG, "g"); root.findViewById<TextView>(R.id.totalFatPlanned).text = formatValue(totals.fatG, "g"); root.findViewById<TextView>(R.id.totalFatConsumed).text = formatValue(consumed.fatG, "g")
        root.findViewById<TextView>(R.id.consumptionCoverage).text = if (records.isEmpty()) "Consumo registrato da te: nessun alimento segnato" else "Consumo registrato da te: ${records.size} elementi segnati"
    }

    private fun renderGeneration(root: View, state: FoodPlanViewModel.State) {
        val button = root.findViewById<MaterialButton>(R.id.generatePlanButton); val progress = root.findViewById<ProgressBar>(R.id.generationProgress); val statusContainer = root.findViewById<View>(R.id.generationStatusContainer); val status = root.findViewById<TextView>(R.id.generationStatusText)
        button.isEnabled = !state.generation.running; button.text = if (state.generation.running) "Generazione in corso…" else if (state.hasPlan) "Rigenera piano con IA" else "Genera piano con IA"; progress.visibility = if (state.generation.running) View.VISIBLE else View.GONE
        val message = state.generation.error ?: listOfNotNull(state.generation.successMessage, state.generation.usageMessage).joinToString("\n").takeIf { it.isNotBlank() }; statusContainer.visibility = if (state.generation.running || message != null) View.VISIBLE else View.GONE; status.text = message.orEmpty()
    }

    private fun renderRecovery(root: View, state: FoodPlanViewModel.State) { root.findViewById<TextView>(R.id.recoverySummary).apply { visibility = if (state.recovery?.budgetBeforeKcal ?: 0 > 0) View.VISIBLE else View.GONE; text = state.recovery?.let { "Riequilibrio attivo\nKcal in eccesso prima: ${it.budgetBeforeKcal} · Oggi da riequilibrare: −${it.plannedRecoveryKcal} · Eccedenza residua: ${it.budgetAfterPlannedKcal}\nConfermato: ${it.confirmedRecoveryKcal} kcal" }.orEmpty() } }
    private fun renderEnergy(root: View, state: EnergyTargetPresentation.State) {
        root.findViewById<TextView>(R.id.energyGoalLabel).text = state.goal?.name?.lowercase()?.replace('_', ' ')?.replaceFirstChar { it.uppercase() } ?: "Obiettivo energetico"
        root.findViewById<TextView>(R.id.energyGoalDescription).text = goalDescription(state.goal)
        val percent = state.goal?.let { ((LocalCalculationEngine.goalEnergyFactor(it) - 1) * 100).roundToInt() }
        root.findViewById<TextView>(R.id.energyModeLabel).text = percent?.let { "${if (it >= 0) "+" else "−"}${abs(it)}% vs mantenimento" } ?: "—"
        root.findViewById<TextView>(R.id.energyTargetValue).text = state.effectiveTargetKcal?.let { "$it kcal" } ?: "Non disponibile"
        root.findViewById<TextView>(R.id.energyMaintenanceValue).text = "${UiNumberFormat.decimal(state.tdeeKcal)} kcal"
        root.findViewById<TextView>(R.id.energyDifferenceValue).text = state.differenceKcal?.let { "${if (it < 0) "−" else "+"}${abs(it)} kcal" } ?: "—"
        val exerciseSummary = if (state.exerciseKcal > 0) " · Base ${UiNumberFormat.decimal(state.baseTdeeKcal)} + esercizio ${state.exerciseKcal} kcal" else ""
        val explanation = when (state.mode) {
            EnergyTargetPresentation.Mode.DEFICIT -> "Segui questo valore per assumere meno calorie del mantenimento."
            EnergyTargetPresentation.Mode.MAINTENANCE -> "Segui questo valore per restare vicino al mantenimento."
            EnergyTargetPresentation.Mode.SURPLUS -> "Segui questo valore per sostenere un aumento controllato."
            EnergyTargetPresentation.Mode.RECOVERY_ADJUSTED -> "Valore ridotto temporaneamente per riequilibrare la giornata."
            EnergyTargetPresentation.Mode.INSUFFICIENT_DATA -> "Completa profilo, peso e rilevazioni per calcolarlo."
        }
        root.findViewById<TextView>(R.id.energyTargetSubline).text = explanation + exerciseSummary
        root.findViewById<View>(R.id.energyRecoveryDetails).visibility = if (state.mode == EnergyTargetPresentation.Mode.RECOVERY_ADJUSTED) View.VISIBLE else View.GONE
    }
    private fun formatWeekRange(start: LocalDate, end: LocalDate) = if (start.month == end.month) "${start.dayOfMonth} – ${end.dayOfMonth} ${end.format(DateTimeFormatter.ofPattern("MMMM yyyy", Locale.ITALIAN)).replaceFirstChar { it.uppercase() }}" else "${start.format(DateTimeFormatter.ofPattern("d MMM", Locale.ITALIAN))} – ${end.format(DateTimeFormatter.ofPattern("d MMM", Locale.ITALIAN))} ${end.year}"
    private fun goalDescription(goal: LocalCalculationEngine.Goal?): String = when (goal) {
        LocalCalculationEngine.Goal.RECOMPOSITION -> "Ridurre gradualmente il grasso e mantenere o aumentare la massa muscolare."
        LocalCalculationEngine.Goal.WEIGHT_LOSS -> "Creare un deficit calorico controllato per ridurre il peso nel tempo."
        LocalCalculationEngine.Goal.MAINTENANCE -> "Mantenere il peso attuale con un apporto vicino al consumo giornaliero."
        LocalCalculationEngine.Goal.MUSCLE_GAIN -> "Favorire l'aumento della massa muscolare con un apporto energetico adeguato."
        LocalCalculationEngine.Goal.PERFORMANCE -> "Sostenere allenamenti e recupero con energia sufficiente."
        null -> "Seleziona un obiettivo per interpretare il target calorico."
    }
    private fun displayMealType(type: String) = type.trim().ifBlank { "Pasto" }.replaceFirstChar { it.titlecase(Locale.ITALIAN) }
    private fun imageFor(meal: FoodMeal) = when (meal.type.lowercase(Locale.ROOT)) { "colazione", "breakfast" -> R.drawable.img_meal_breakfast; "spuntino", "snack" -> R.drawable.img_meal_snack; "pranzo", "lunch" -> R.drawable.img_meal_lunch; "cena", "dinner" -> R.drawable.img_meal_dinner; else -> R.drawable.img_meal_lunch }
    private fun canChangeMeal(day: Long, time: Int?) = !LocalDate.ofEpochDay(day).isBefore(LocalDate.now()) && (LocalDate.ofEpochDay(day).isAfter(LocalDate.now()) || time == null || time > LocalTime.now().hour * 60 + LocalTime.now().minute)
    private fun infoRow(title: String, body: String) = TextView(requireContext()).apply { text = "$title\n$body"; textSize = 15f; setPadding(dp(16), dp(12), dp(16), dp(12)) }
    private fun formatValue(value: Number?, unit: String) = value?.let { "${if (unit == "kcal") it.toInt() else it.toString()} $unit" } ?: "—"
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
