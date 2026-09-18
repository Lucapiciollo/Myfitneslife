package com.myfitai.app.ui

import android.app.Activity
import android.content.Intent
import android.content.res.ColorStateList
import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.myfitai.app.R
import com.myfitai.app.data.AppDataContainer
import com.myfitai.app.domain.food.FoodMeal
import com.myfitai.app.domain.food.FoodPlanDay
import com.myfitai.app.domain.food.FoodPlanMetrics
import com.myfitai.app.domain.food.FoodPlanVersion
import com.myfitai.app.domain.food.FoodConsumptionMetrics
import com.myfitai.app.navigation.BottomNavBinder
import com.myfitai.app.ui.food.FoodPlanViewModel
import com.myfitai.app.ui.widgets.MealPlanRowView
import com.myfitai.app.ui.widgets.WeekDaySelectorView
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale

class FoodPlanActivity : BaseShellActivity() {

    private val data by lazy { AppDataContainer.get(this) }
    private val viewModel: FoodPlanViewModel by viewModels {
         FoodPlanViewModel.Factory(data.mealPlanRepository, data.activeProfileStore, data.nutritionPlanGenerationService, data.notificationScheduler, data.foodConsumptionRepository, data.aiJobScheduler)
    }

    private val mealAlternativeLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) recreate()
    }
    private val weekDaySelector by lazy { findViewById<WeekDaySelectorView>(R.id.weekDaySelector) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_food_plan)
        bindBottom(BottomNavBinder.Tab.FOOD)
        intent.takeIf { it.hasExtra(EXTRA_WEEK_START_EPOCH_DAY) }?.getLongExtra(EXTRA_WEEK_START_EPOCH_DAY, LocalDate.now().toEpochDay())?.let(viewModel::selectWeek)
        findViewById<View>(R.id.prevWeekButton).setOnClickListener { viewModel.previousWeek() }
        findViewById<View>(R.id.nextWeekButton).setOnClickListener { viewModel.nextWeek() }
        findViewById<View>(R.id.shoppingButton).setOnClickListener {
            startActivity(Intent(this, ShoppingListActivity::class.java).putExtra(ShoppingListActivity.EXTRA_WEEK_START_EPOCH_DAY, viewModel.state.value.weekStart.toEpochDay()))
        }
        findViewById<View>(R.id.cheatButton).setOnClickListener { go(CheatEntryActivity::class.java) }
        findViewById<View>(R.id.generatePlanButton).setOnClickListener { confirmPlanGeneration() }
        renderMealCountPreference()
        weekDaySelector.setOnDaySelectedListener(viewModel::selectDay)
        lifecycleScope.launch { repeatOnLifecycle(Lifecycle.State.STARTED) { viewModel.state.collect(::render) } }
    }

    private fun renderMealCountPreference() {
        val profileId = data.activeProfileStore.currentIdOrNull() ?: return
        findViewById<TextView>(R.id.mealCountHint).text = "${data.mealCountPreferences.get(profileId)} pasti al giorno"
    }

    private fun confirmPlanGeneration() {
        MaterialAlertDialogBuilder(this)
            .setTitle("Generare il piano con IA?")
            .setMessage(
                "La generazione invia una richiesta al provider IA e consuma la quota disponibile. " +
                    "Il costo effettivo dipende dal provider, dal modello e dal tuo piano di billing. " +
                    "La richiesta partirà solo dopo la tua conferma."
            )
            .setNegativeButton("Annulla", null)
            .setPositiveButton("Conferma e genera") { _, _ -> viewModel.generateCurrentWeek() }
            .show()
    }

    private fun render(state: FoodPlanViewModel.State) {
        val weekEnd = state.weekStart.plusDays(6)
        findViewById<TextView>(R.id.weekRangeLabel).text = formatWeekRange(state.weekStart, weekEnd)
        weekDaySelector.setDays((0..6).map { offset ->
            val date = state.weekStart.plusDays(offset.toLong())
            WeekDaySelectorView.Day(date.format(DateTimeFormatter.ofPattern("EEE", Locale.ITALIAN)).replaceFirstChar { it.uppercase() }.take(3), date.dayOfMonth.toString())
        }, state.selectedDayIndex)
        weekDaySelector.setOnDaySelectedListener(viewModel::selectDay)

        val versionLabel = findViewById<TextView>(R.id.planVersionLabel)
        val snapshot = state.snapshot
        if (snapshot != null) {
            versionLabel.visibility = View.VISIBLE
            versionLabel.text = "Piano v${snapshot.version.versionNumber}${snapshot.version.reason?.takeIf { it.isNotBlank() }?.let { " · $it" }.orEmpty()}"
        } else versionLabel.visibility = View.GONE

        val empty = findViewById<TextView>(R.id.emptyPlanText)
        val day = state.selectedDay
        empty.visibility = if (!state.hasPlan) View.VISIBLE else View.GONE
        if (state.hasPlan && day == null) { empty.visibility = View.VISIBLE; empty.text = "Nessun dato alimentare per il giorno selezionato." }
        else if (!state.hasPlan) empty.text = "Nessun piano per questa settimana. Genera un piano per vedere pasti, quantità e valori nutrizionali."

        renderGeneration(state)
        renderMeals(state.weekStart, day)
        renderTotals(day, state.snapshot?.version, state.consumptionRecords)
    }

    private fun renderGeneration(state: FoodPlanViewModel.State) {
        val button = findViewById<MaterialButton>(R.id.generatePlanButton)
        val statusContainer = findViewById<View>(R.id.generationStatusContainer)
        val progress = findViewById<ProgressBar>(R.id.generationProgress)
        val status = findViewById<TextView>(R.id.generationStatusText)
        val generation = state.generation
        button.isEnabled = !generation.running
        button.text = when { generation.running -> "Generazione in corso…"; state.hasPlan -> "Rigenera piano con IA"; else -> "Genera piano con IA" }
        if (state.hasPlan && !generation.running) {
            button.backgroundTintList = ColorStateList.valueOf(getColor(R.color.surface_primary))
            button.setTextColor(getColor(R.color.accent_green_dark))
            button.strokeWidth = dp(1)
            button.strokeColor = ColorStateList.valueOf(getColor(R.color.accent_green))
        } else {
            button.backgroundTintList = ColorStateList.valueOf(getColor(R.color.accent_green))
            button.setTextColor(getColor(R.color.white))
            button.strokeWidth = 0
        }
        val message = when {
            generation.running -> "Il piano viene generato e validato localmente prima del salvataggio."
            generation.error != null -> generation.error
            generation.successMessage != null -> listOfNotNull(generation.successMessage, generation.usageMessage).joinToString("\n")
            else -> null
        }
        statusContainer.visibility = if (message != null) View.VISIBLE else View.GONE
        progress.visibility = if (generation.running) View.VISIBLE else View.GONE
        status.text = message.orEmpty()
    }

    private fun renderMeals(weekStart: LocalDate, day: FoodPlanDay?) {
        val container = findViewById<LinearLayout>(R.id.mealsContainer)
        container.removeAllViews()
        day?.meals?.sortedBy { it.sortOrder }?.forEach { meal ->
            val changeEnabled = canChangeMeal(day.dateEpochDay, meal.timeMinutes) && meal.kcal != null
            val row = MealPlanRowView(this).apply {
                setTitle(displayMealType(meal.type)); setKcal(meal.kcal?.let { "$it kcal" } ?: "—"); setDescription(meal.title); setImage(imageFor(meal))
                setOnClickListener { openMeal(meal.id) }; setChangeEnabled(changeEnabled)
                if (changeEnabled) setOnChangeClickListener { openMealAlternatives(weekStart, day, meal) }
                contentDescription = "${displayMealType(meal.type)}: ${meal.title}"
            }
            container.addView(row, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })
        }
        day?.supplements?.takeIf { it.isNotEmpty() }?.let { supplements ->
            container.addView(infoRow("Integrazione", supplements.joinToString("\n") { s ->
                val timePrefix = s.timeMinutes?.let { "%02d:%02d · ".format(it / 60, it % 60) }.orEmpty()
                val dosePart = "${s.name} ${formatMacro(s.dose)} ${s.unit}"
                val energyPart = if (s.kcal > 0 || s.proteinG > 0f || s.carbsG > 0f || s.fatG > 0f) {
                    " · ${s.kcal} kcal · Proteine ${formatMacro(s.proteinG)} g · Carboidrati ${formatMacro(s.carbsG)} g · Grassi ${formatMacro(s.fatG)} g"
                } else {
                    ""
                }
                "$timePrefix$dosePart$energyPart"
            }))
        }
        day?.hydrationNote?.takeIf { it.isNotBlank() }?.let { container.addView(infoRow("Idratazione", it)) }
    }

    private fun infoRow(title: String, body: String) = TextView(this).apply {
        text = "$title\n$body"
        textSize = 15f
        setPadding(dp(16), dp(12), dp(16), dp(12))
        contentDescription = "$title: $body"
    }

    private fun renderTotals(day: FoodPlanDay?, version: FoodPlanVersion?, records: List<com.myfitai.app.data.local.entity.FoodConsumptionEntity>) {
        val totalContainer = findViewById<View>(R.id.dailyTotalContainer); val totalHeader = findViewById<View>(R.id.dailyTotalHeader)
        if (day == null) { totalContainer.visibility = View.GONE; totalHeader.visibility = View.GONE; return }
        val totals = FoodPlanMetrics.dayTotals(day)
        val dayRecords = records.filter { it.planVersionId == version?.id && it.plannedDateEpochDay == day.dateEpochDay }
        val consumed = FoodConsumptionMetrics.dayTotals(dayRecords)
        totalContainer.visibility = View.VISIBLE; totalHeader.visibility = View.VISIBLE
        val selectedDate = LocalDate.ofEpochDay(day.dateEpochDay)
        val dayLabel = selectedDate.format(DateTimeFormatter.ofPattern("EEE d MMM", Locale.ITALIAN))
            .replaceFirstChar { it.uppercase() }
        findViewById<TextView>(R.id.dailyTotalHeader).text = "Totale giornaliero · $dayLabel"
        findViewById<TextView>(R.id.dailyTotalLegend).text = "Target / piano / consumo registrato"
        findViewById<TextView>(R.id.totalKcalTarget).text = formatValue(version?.targetKcal, "kcal")
        findViewById<TextView>(R.id.totalKcalPlanned).text = formatValue(totals.kcal, "kcal")
        findViewById<TextView>(R.id.totalKcalConsumed).text = formatConsumed(consumed.kcal, dayRecords.isNotEmpty(), "kcal")
        findViewById<TextView>(R.id.totalProteinTarget).text = formatValue(version?.targetProteinG, "g")
        findViewById<TextView>(R.id.totalProteinPlanned).text = formatValue(totals.proteinG, "g")
        findViewById<TextView>(R.id.totalProteinConsumed).text = formatConsumed(consumed.proteinG, dayRecords.isNotEmpty(), "g")
        findViewById<TextView>(R.id.totalCarbsTarget).text = formatValue(version?.targetCarbsG, "g")
        findViewById<TextView>(R.id.totalCarbsPlanned).text = formatValue(totals.carbsG, "g")
        findViewById<TextView>(R.id.totalCarbsConsumed).text = formatConsumed(consumed.carbsG, dayRecords.isNotEmpty(), "g")
        findViewById<TextView>(R.id.totalFatTarget).text = formatValue(version?.targetFatG, "g")
        findViewById<TextView>(R.id.totalFatPlanned).text = formatValue(totals.fatG, "g")
        findViewById<TextView>(R.id.totalFatConsumed).text = formatConsumed(consumed.fatG, dayRecords.isNotEmpty(), "g")
        val expected = day.meals.size + day.supplements.size
        findViewById<TextView>(R.id.consumptionCoverage).text = if (dayRecords.isEmpty()) {
            "Consumo: nessuna registrazione"
        } else {
            "Registrati: ${consumed.recordedCount} di $expected elementi · consumati ${consumed.consumedCount}"
        }
    }

    private fun formatValue(value: Number?, unit: String): String = value?.let {
        if (unit == "kcal") "${it.toInt()} $unit" else "${formatMacro(it.toDouble())} $unit"
    } ?: "—"

    private fun formatConsumed(value: Double, hasRecords: Boolean, unit: String): String = if (!hasRecords) "—" else formatValue(value, unit)

    private fun openMeal(mealId: Long) = startActivity(Intent(this, MealDetailActivity::class.java).putExtra(MealDetailActivity.EXTRA_MEAL_ID, mealId))
    private fun openMealAlternatives(weekStart: LocalDate, day: FoodPlanDay, meal: FoodMeal) {
        mealAlternativeLauncher.launch(Intent(this, MealAlternativeActivity::class.java)
            .putExtra(MealAlternativeActivity.EXTRA_WEEK_START_EPOCH_DAY, weekStart.toEpochDay())
            .putExtra(MealAlternativeActivity.EXTRA_DAY_EPOCH_DAY, day.dateEpochDay)
            .putExtra(MealAlternativeActivity.EXTRA_MEAL_ID, meal.id)
            .putExtra(MealAlternativeActivity.EXTRA_MEAL_TITLE, meal.title)
            .putExtra(MealAlternativeActivity.EXTRA_MEAL_TYPE, displayMealType(meal.type))
            .putExtra(MealAlternativeActivity.EXTRA_MEAL_KCAL, meal.kcal ?: -1))
    }

    private fun canChangeMeal(dayEpochDay: Long, timeMinutes: Int?): Boolean {
        val date = LocalDate.ofEpochDay(dayEpochDay); val today = LocalDate.now()
        if (date.isBefore(today)) return false
        if (date.isAfter(today) || timeMinutes == null) return true
        val now = LocalTime.now().let { it.hour * 60 + it.minute }
        return timeMinutes > now
    }

    private fun imageFor(meal: FoodMeal): Int = when (meal.type.trim().lowercase(Locale.ROOT)) {
        "colazione", "breakfast" -> R.drawable.img_meal_breakfast; "spuntino", "snack" -> R.drawable.img_meal_snack; "pranzo", "lunch" -> R.drawable.img_meal_lunch; "pre-workout", "preworkout" -> R.drawable.img_meal_preworkout; "cena", "dinner" -> R.drawable.img_meal_dinner; else -> R.drawable.img_meal_lunch
    }
    private fun displayMealType(type: String): String = type.trim().ifBlank { "Pasto" }.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.ITALIAN) else it.toString() }
    private fun formatWeekRange(start: LocalDate, end: LocalDate): String {
        val monthFormatter = DateTimeFormatter.ofPattern("MMMM yyyy", Locale.ITALIAN)
        return if (start.month == end.month) "${start.dayOfMonth} – ${end.dayOfMonth} ${end.format(monthFormatter).replaceFirstChar { it.uppercase() }}" else "${start.format(DateTimeFormatter.ofPattern("d MMM", Locale.ITALIAN))} – ${end.format(DateTimeFormatter.ofPattern("d MMM", Locale.ITALIAN))} ${end.year}"
    }
    private fun formatMacro(value: Float): String = if (value % 1f == 0f) value.toInt().toString() else String.format(Locale.ITALIAN, "%.1f", value)
    private fun formatMacro(value: Double): String = if (value % 1.0 == 0.0) value.toInt().toString() else String.format(Locale.ITALIAN, "%.1f", value)
    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object { const val EXTRA_WEEK_START_EPOCH_DAY = "week_start_epoch_day" }
}
