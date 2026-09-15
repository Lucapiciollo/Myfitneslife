package com.myfitai.app.ui

import android.os.Bundle
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.myfitai.app.R
import com.myfitai.app.data.AppDataContainer
import com.myfitai.app.domain.food.FoodConsumptionStatus
import com.myfitai.app.domain.food.FoodIngredient
import com.myfitai.app.domain.food.FoodMeal
import com.myfitai.app.navigation.BottomNavBinder
import com.myfitai.app.ui.food.MealDetailViewModel
import com.myfitai.app.ui.widgets.IngredientRowView
import com.myfitai.app.ui.widgets.SelectableSegmentView
import kotlinx.coroutines.launch
import java.util.Locale

class MealDetailActivity : BaseShellActivity() {

    private val data by lazy { AppDataContainer.get(this) }
    private val viewModel: MealDetailViewModel by viewModels {
        MealDetailViewModel.Factory(data.mealPlanRepository, data.foodConsumptionRepository, data.foodConsumptionService)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_meal_detail)
        bindBack()
        bindBottom(BottomNavBinder.Tab.FOOD)
        bindTabs()
        findViewById<View>(R.id.consumedButton).setOnClickListener {
            if (viewModel.state.value.consumption == null) viewModel.setStatus(FoodConsumptionStatus.CONSUMED)
            else if (viewModel.state.value.consumption?.status == FoodConsumptionStatus.CONSUMED.name) viewModel.clearStatus()
            else viewModel.setStatus(FoodConsumptionStatus.CONSUMED)
        }
        findViewById<View>(R.id.skippedButton).setOnClickListener {
            if (viewModel.state.value.consumption?.status == FoodConsumptionStatus.SKIPPED.name) viewModel.clearStatus()
            else viewModel.setStatus(FoodConsumptionStatus.SKIPPED)
        }

        val mealId = intent.getLongExtra(EXTRA_MEAL_ID, -1L)
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.state.collect(::render)
            }
        }
        viewModel.load(data.activeProfileStore.currentIdOrNull() ?: -1L, mealId)
    }

    private fun bindTabs() {
        val ingredientsContainer = findViewById<View>(R.id.ingredientsContainer)
        val preparationContainer = findViewById<View>(R.id.preparationContainer)
        findViewById<SelectableSegmentView>(R.id.detailSegment).apply {
            setSegments(listOf("Ingredienti", "Preparazione"), selectedIndex = 0)
            setOnSegmentSelectedListener { index ->
                ingredientsContainer.visibility = if (index == 0) View.VISIBLE else View.GONE
                preparationContainer.visibility = if (index == 1) View.VISIBLE else View.GONE
            }
        }
    }

    private fun render(state: MealDetailViewModel.State) {
        val errorView = findViewById<TextView>(R.id.mealError)
        if (state.loading) {
            errorView.visibility = View.VISIBLE
            errorView.text = "Caricamento pasto…"
            return
        }
        val meal = state.meal
        if (meal == null) {
            errorView.visibility = View.VISIBLE
            errorView.text = state.error ?: "Pasto non disponibile"
            return
        }
        errorView.visibility = View.GONE
        renderConsumption(state)

        findViewById<TextView>(R.id.mealTitle).text = meal.title
        findViewById<TextView>(R.id.mealSubtitle).text = listOfNotNull(
            displayMealType(meal.type),
            meal.timeMinutes?.let(::formatTime),
        ).joinToString(" · ")
        findViewById<TextView>(R.id.statKcal).text = meal.kcal?.let { "$it kcal" } ?: "—"
        findViewById<TextView>(R.id.statProtein).text = meal.proteinG?.let { "${formatMacro(it)}g" } ?: "—"
        findViewById<TextView>(R.id.statCarbs).text = meal.carbsG?.let { "${formatMacro(it)}g" } ?: "—"
        findViewById<TextView>(R.id.statFat).text = meal.fatG?.let { "${formatMacro(it)}g" } ?: "—"
        findViewById<ImageView>(R.id.mealImage).setImageResource(imageFor(meal))

        val ingredientsContainer = findViewById<LinearLayout>(R.id.ingredientsContainer)
        ingredientsContainer.removeAllViews()
        if (meal.ingredients.isEmpty()) {
            ingredientsContainer.addView(TextView(this).apply {
                text = "Ingredienti non disponibili"
                setTextColor(getColor(R.color.text_secondary))
                textSize = 13f
                setPadding(0, dp(14), 0, dp(14))
            })
        } else {
            meal.ingredients.sortedBy { it.sortOrder }.forEach { ingredient ->
                ingredientsContainer.addView(IngredientRowView(this).apply {
                    setName(ingredient.name)
                    setQuantity(displayQuantity(ingredient))
                    setIcon(iconFor(ingredient))
                })
            }
        }

        findViewById<TextView>(R.id.preparationContainer).text =
            meal.preparation?.takeIf { it.isNotBlank() } ?: "Preparazione non disponibile."
    }

    private fun renderConsumption(state: MealDetailViewModel.State) {
        val consumedButton = findViewById<com.google.android.material.button.MaterialButton>(R.id.consumedButton)
        val skippedButton = findViewById<com.google.android.material.button.MaterialButton>(R.id.skippedButton)
        val status = state.consumption?.status
        consumedButton.visibility = View.VISIBLE
        skippedButton.visibility = View.VISIBLE
        when (status) {
            FoodConsumptionStatus.CONSUMED.name -> {
                consumedButton.text = "Annulla consumo"
                skippedButton.text = "Segna come saltato"
            }
            FoodConsumptionStatus.SKIPPED.name -> {
                consumedButton.text = "Segna come consumato"
                skippedButton.text = "Annulla pasto saltato"
            }
            else -> {
                consumedButton.text = "Segna come consumato"
                skippedButton.text = "Segna come saltato"
            }
        }
    }

    private fun displayQuantity(ingredient: FoodIngredient): String {
        ingredient.displayDose?.takeIf { it.isNotBlank() }?.let { return it }
        val quantity = if (ingredient.quantity % 1f == 0f) ingredient.quantity.toInt().toString()
        else String.format(Locale.ITALIAN, "%.1f", ingredient.quantity)
        return "$quantity ${ingredient.unit}".trim()
    }

    private fun iconFor(ingredient: FoodIngredient): Int = when (ingredient.category?.trim()?.lowercase(Locale.ROOT)) {
        "protein", "proteine" -> R.drawable.img_food_protein
        "carbs", "carboidrati", "cereali" -> R.drawable.img_food_carbs
        "vegetables", "verdure" -> R.drawable.img_food_vegetables
        "fats", "grassi" -> R.drawable.img_food_fats
        "condiments", "condimenti" -> R.drawable.img_food_condiments
        else -> R.drawable.img_food_carbs
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

    private fun formatTime(minutes: Int): String = String.format(Locale.ITALIAN, "%02d:%02d", minutes / 60, minutes % 60)
    private fun formatMacro(value: Float): String = if (value % 1f == 0f) value.toInt().toString() else String.format(Locale.ITALIAN, "%.1f", value)
    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        const val EXTRA_MEAL_ID = "meal_id"
    }
}
