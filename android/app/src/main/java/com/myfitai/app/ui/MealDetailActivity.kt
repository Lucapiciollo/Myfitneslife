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
import com.myfitai.app.ui.motion.UiMotion
import com.google.android.material.card.MaterialCardView
import kotlinx.coroutines.launch
import java.util.Locale

class MealDetailActivity : BaseShellActivity() {

    private val data by lazy { AppDataContainer.get(this) }
    private val viewModel: MealDetailViewModel by viewModels {
        MealDetailViewModel.Factory(data.mealPlanRepository, data.foodConsumptionRepository, data.foodConsumptionService)
    }
    private var selectedDetailTab = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_meal_detail)
        bindBack()
        bindBottom(BottomNavBinder.Tab.FOOD)
        normalizeMealCards()
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
        bindConsumptionFeedback()

        val mealId = intent.getLongExtra(EXTRA_MEAL_ID, -1L)
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.state.collect(::render)
            }
        }
        viewModel.load(data.activeProfileStore.currentIdOrNull() ?: -1L, mealId)
    }

    private fun normalizeMealCards() {
        listOf(R.id.ingredientsCard, R.id.preparationCard).forEach { id ->
            findViewById<MaterialCardView>(id).apply {
                setCardBackgroundColor(getColor(R.color.white))
                strokeColor = getColor(R.color.divider)
                strokeWidth = resources.getDimensionPixelSize(R.dimen.space_1)
                cardElevation = 0f
            }
        }
    }

    private fun bindTabs() {
        val ingredientsCard = findViewById<View>(R.id.ingredientsCard)
        val preparationCard = findViewById<View>(R.id.preparationCard)
        findViewById<SelectableSegmentView>(R.id.detailSegment).apply {
            setSegments(listOf("Ingredienti", "Preparazione"), selectedIndex = 0)
            setOnSegmentMotionListener { selectedIndex, previousIndex ->
                val outgoingContainer = if (previousIndex == 0) {
                    findViewById<View>(R.id.ingredientsContainer)
                } else {
                    findViewById<View>(R.id.preparationContainer)
                }
                val incomingContainer = if (selectedIndex == 0) {
                    findViewById<View>(R.id.ingredientsContainer)
                } else {
                    findViewById<View>(R.id.preparationContainer)
                }
                UiMotion.crossfade(outgoingContainer, incomingContainer, showFirst = selectedIndex == 0)
            }
            setOnSegmentSelectedListener { index ->
                selectedDetailTab = index
            }
        }
    }

    private fun bindConsumptionFeedback() {
        listOf(R.id.consumedButton, R.id.skippedButton).forEach { id ->
            findViewById<View>(id).addOnLayoutChangeListener { view, _, _, _, _, _, _, _, _ ->
                val wasVisible = view.getTag(R.id.motionVisibilityTarget) as? Boolean ?: false
                val isVisible = view.visibility == View.VISIBLE
                if (wasVisible == isVisible) return@addOnLayoutChangeListener
                view.setTag(R.id.motionVisibilityTarget, isVisible)
                if (isVisible) UiMotion.selection(view, selected = true)
            }
        }
    }

    private fun render(state: MealDetailViewModel.State) {
        val errorView = findViewById<TextView>(R.id.mealError)
        if (state.loading) {
            errorView.visibility = View.VISIBLE
            errorView.text = "Caricamento pasto…"
            findViewById<View>(R.id.ingredientsCard).visibility = View.GONE
            findViewById<View>(R.id.preparationCard).visibility = View.GONE
            return
        }
        val meal = state.meal
        if (meal == null) {
            errorView.visibility = View.VISIBLE
            errorView.text = state.error ?: "Pasto non disponibile"
            findViewById<View>(R.id.ingredientsCard).visibility = View.GONE
            findViewById<View>(R.id.preparationCard).visibility = View.GONE
            return
        }
        errorView.visibility = View.GONE
        findViewById<View>(R.id.ingredientsCard).visibility = View.VISIBLE
        findViewById<View>(R.id.preparationCard).visibility = View.VISIBLE
        val ingredientsContent = findViewById<View>(R.id.ingredientsContainer)
        val preparationContent = findViewById<View>(R.id.preparationContainer)
        ingredientsContent.visibility = if (selectedDetailTab == 0) View.VISIBLE else View.GONE
        preparationContent.visibility = if (selectedDetailTab == 1) View.VISIBLE else View.GONE
        ingredientsContent.alpha = 1f
        preparationContent.alpha = 1f
        ingredientsContent.translationY = 0f
        preparationContent.translationY = 0f
        renderConsumption(state)

        findViewById<TextView>(R.id.mealTitle).text = meal.title
        findViewById<TextView>(R.id.mealSubtitle).text = listOfNotNull(
            displayMealType(meal.type),
            meal.timeMinutes?.let(::formatTime),
        ).joinToString(" · ")
        findViewById<TextView>(R.id.statKcal).text = NutritionEstimateFormatter.formatEstimatedKcal(meal.kcal)
        findViewById<TextView>(R.id.statProtein).text = NutritionEstimateFormatter.formatEstimatedMacro(meal.proteinG, "g")
        findViewById<TextView>(R.id.statCarbs).text = NutritionEstimateFormatter.formatEstimatedMacro(meal.carbsG, "g")
        findViewById<TextView>(R.id.statFat).text = NutritionEstimateFormatter.formatEstimatedMacro(meal.fatG, "g")
        findViewById<ImageView>(R.id.mealImage).setImageResource(imageFor(meal))

        val ingredientsContainer = findViewById<LinearLayout>(R.id.ingredientsContainer)
        ingredientsContainer.removeAllViews()
        if (meal.ingredients.isEmpty()) {
            ingredientsContainer.addView(TextView(this).apply {
                text = "Ingredienti non disponibili"
                setTextAppearance(R.style.Text_MyFitAI_BodyCompact)
                val verticalPadding = resources.getDimensionPixelSize(R.dimen.space_14)
                setPadding(0, verticalPadding, 0, verticalPadding)
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
        UiMotion.reveal(consumedButton, true, animateChange = false)
        UiMotion.reveal(skippedButton, true, animateChange = false)
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
    companion object {
        const val EXTRA_MEAL_ID = "meal_id"
    }
}
