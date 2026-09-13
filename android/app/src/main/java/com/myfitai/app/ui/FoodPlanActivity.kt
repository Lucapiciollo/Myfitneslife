package com.myfitai.app.ui

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.button.MaterialButton
import com.myfitai.app.R
import com.myfitai.app.data.AppDataContainer
import com.myfitai.app.domain.food.FoodMeal
import com.myfitai.app.domain.food.FoodPlanDay
import com.myfitai.app.navigation.BottomNavBinder
import com.myfitai.app.ui.food.FoodPlanViewModel
import com.myfitai.app.ui.widgets.MealPlanRowView
import com.myfitai.app.ui.widgets.WeekDaySelectorView
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

class FoodPlanActivity : BaseShellActivity() {

    private val data by lazy { AppDataContainer.get(this) }
    private val viewModel: FoodPlanViewModel by viewModels {
        FoodPlanViewModel.Factory(
            repository = data.mealPlanRepository,
            activeProfileStore = data.activeProfileStore,
            generationService = data.nutritionPlanGenerationService,
            notificationScheduler = data.notificationScheduler,
        )
    }

    private val weekDaySelector by lazy { findViewById<WeekDaySelectorView>(R.id.weekDaySelector) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_food_plan)
        bindBottom(BottomNavBinder.Tab.FOOD)

        findViewById<View>(R.id.prevWeekButton).setOnClickListener { viewModel.previousWeek() }
        findViewById<View>(R.id.nextWeekButton).setOnClickListener { viewModel.nextWeek() }
        findViewById<View>(R.id.shoppingButton).setOnClickListener {
            startActivity(
                Intent(this, ShoppingListActivity::class.java)
                    .putExtra(ShoppingListActivity.EXTRA_WEEK_START_EPOCH_DAY, viewModel.state.value.weekStart.toEpochDay())
            )
        }
        findViewById<View>(R.id.cheatButton).setOnClickListener { go(CheatEntryActivity::class.java) }
        findViewById<View>(R.id.generatePlanButton).setOnClickListener { viewModel.generateCurrentWeek() }
        weekDaySelector.setOnDaySelectedListener(viewModel::selectDay)

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.state.collect(::render)
            }
        }
    }

    private fun render(state: FoodPlanViewModel.State) {
        val weekEnd = state.weekStart.plusDays(6)
        findViewById<TextView>(R.id.weekRangeLabel).text = formatWeekRange(state.weekStart, weekEnd)
        weekDaySelector.setDays(
            (0..6).map { offset ->
                val date = state.weekStart.plusDays(offset.toLong())
                WeekDaySelectorView.Day(
                    abbreviation = date.format(DateTimeFormatter.ofPattern("EEE", Locale.ITALIAN)).replaceFirstChar { it.uppercase() }.take(3),
                    dayNumber = date.dayOfMonth.toString(),
                )
            },
            selectedIndex = state.selectedDayIndex,
        )
        weekDaySelector.setOnDaySelectedListener(viewModel::selectDay)

        val versionLabel = findViewById<TextView>(R.id.planVersionLabel)
        val snapshot = state.snapshot
        if (snapshot != null) {
            versionLabel.visibility = View.VISIBLE
            versionLabel.text = "Piano v${snapshot.version.versionNumber}${snapshot.version.reason?.takeIf { it.isNotBlank() }?.let { " · $it" }.orEmpty()}"
        } else {
            versionLabel.visibility = View.GONE
        }

        val empty = findViewById<TextView>(R.id.emptyPlanText)
        val day = state.selectedDay
        empty.visibility = if (!state.hasPlan) View.VISIBLE else View.GONE
        if (state.hasPlan && day == null) {
            empty.visibility = View.VISIBLE
            empty.text = "Nessun dato alimentare per il giorno selezionato."
        } else if (!state.hasPlan) {
            empty.text = "Nessun piano alimentare disponibile per questa settimana."
        }

        renderGeneration(state)
        renderMeals(day)
        renderTotals(day)
    }

    private fun renderGeneration(state: FoodPlanViewModel.State) {
        val button = findViewById<MaterialButton>(R.id.generatePlanButton)
        val statusContainer = findViewById<View>(R.id.generationStatusContainer)
        val progress = findViewById<ProgressBar>(R.id.generationProgress)
        val status = findViewById<TextView>(R.id.generationStatusText)
        val generation = state.generation

        button.isEnabled = !generation.running
        button.text = when {
            generation.running -> "Generazione in corso…"
            state.hasPlan -> "Rigenera piano con IA"
            else -> "Genera piano con IA"
        }

        val message = when {
            generation.running -> "Il piano viene generato e validato localmente prima del salvataggio."
            generation.error != null -> generation.error
            generation.successMessage != null -> generation.successMessage
            else -> null
        }
        statusContainer.visibility = if (message != null) View.VISIBLE else View.GONE
        progress.visibility = if (generation.running) View.VISIBLE else View.GONE
        status.text = message.orEmpty()
    }

    private fun renderMeals(day: FoodPlanDay?) {
        val container = findViewById<LinearLayout>(R.id.mealsContainer)
        container.removeAllViews()
        day?.meals?.sortedBy { it.sortOrder }?.forEach { meal ->
            val row = MealPlanRowView(this).apply {
                setTitle(displayMealType(meal.type))
                setKcal(meal.kcal?.let { "$it kcal" } ?: "—")
                setDescription(meal.title)
                setImage(imageFor(meal))
                setOnClickListener { openMeal(meal.id) }
                contentDescription = "${displayMealType(meal.type)}: ${meal.title}"
            }
            container.addView(
                row,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply { topMargin = dp(8) },
            )
        }
    }

    private fun renderTotals(day: FoodPlanDay?) {
        val totalContainer = findViewById<View>(R.id.dailyTotalContainer)
        val totalHeader = findViewById<View>(R.id.dailyTotalHeader)
        if (day == null) {
            totalContainer.visibility = View.GONE
            totalHeader.visibility = View.GONE
            return
        }
        totalContainer.visibility = View.VISIBLE
        totalHeader.visibility = View.VISIBLE

        val kcal = day.totalKcal ?: day.meals.mapNotNull { it.kcal }.takeIf { it.isNotEmpty() }?.sum()
        val protein = day.proteinG ?: sumOrNull(day.meals.map { it.proteinG })
        val carbs = day.carbsG ?: sumOrNull(day.meals.map { it.carbsG })
        val fat = day.fatG ?: sumOrNull(day.meals.map { it.fatG })

        findViewById<TextView>(R.id.totalKcal).text = kcal?.let { "$it kcal" } ?: "— kcal"
        findViewById<TextView>(R.id.totalProtein).text = protein?.let { "P ${formatMacro(it)}g" } ?: "P —"
        findViewById<TextView>(R.id.totalCarbs).text = carbs?.let { "C ${formatMacro(it)}g" } ?: "C —"
        findViewById<TextView>(R.id.totalFat).text = fat?.let { "F ${formatMacro(it)}g" } ?: "F —"
    }

    private fun openMeal(mealId: Long) {
        startActivity(Intent(this, MealDetailActivity::class.java).putExtra(MealDetailActivity.EXTRA_MEAL_ID, mealId))
    }

    private fun imageFor(meal: FoodMeal): Int = when (meal.type.trim().lowercase(Locale.ROOT)) {
        "colazione", "breakfast" -> R.drawable.img_meal_breakfast
        "spuntino", "snack" -> R.drawable.img_meal_snack
        "pranzo", "lunch" -> R.drawable.img_meal_lunch
        "pre-workout", "preworkout" -> R.drawable.img_meal_preworkout
        "cena", "dinner" -> R.drawable.img_meal_dinner
        else -> R.drawable.img_meal_lunch
    }

    private fun displayMealType(type: String): String = type.trim().ifBlank { "Pasto" }
        .replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.ITALIAN) else it.toString() }

    private fun formatWeekRange(start: LocalDate, end: LocalDate): String {
        val monthFormatter = DateTimeFormatter.ofPattern("MMMM yyyy", Locale.ITALIAN)
        return if (start.month == end.month) {
            "${start.dayOfMonth} – ${end.dayOfMonth} ${end.format(monthFormatter).replaceFirstChar { it.uppercase() }}"
        } else {
            val short = DateTimeFormatter.ofPattern("d MMM", Locale.ITALIAN)
            "${start.format(short)} – ${end.format(short)} ${end.year}"
        }
    }

    private fun sumOrNull(values: List<Float?>): Float? {
        val present = values.filterNotNull()
        return present.takeIf { it.isNotEmpty() }?.sum()
    }

    private fun formatMacro(value: Float): String = if (value % 1f == 0f) value.toInt().toString() else String.format(Locale.ITALIAN, "%.1f", value)
    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
