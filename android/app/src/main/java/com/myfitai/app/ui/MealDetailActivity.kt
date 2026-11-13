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
import com.myfitai.app.ui.widgets.MealSlotIcons
import com.myfitai.app.data.AppDataContainer
import com.myfitai.app.domain.food.FoodConsumptionStatus
import com.myfitai.app.domain.food.FoodIngredient
import com.myfitai.app.domain.food.FoodMeal
import com.myfitai.app.domain.food.MealStatusChange
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
        MealDetailViewModel.Factory(data.mealPlanRepository, data.foodConsumptionRepository, data.mealStatusRecorder)
    }
    private var selectedDetailTab = 0
    private lateinit var ingredientsContainer: View
    private lateinit var preparationContainer: View

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_meal_detail)
        bindBack()
        bindBottom(BottomNavBinder.Tab.FOOD)
        normalizeMealCards()
        bindTabs()
        findViewById<View>(R.id.consumedButton).setOnClickListener { viewModel.tapStatus(FoodConsumptionStatus.CONSUMED) }
        findViewById<View>(R.id.skippedButton).setOnClickListener { viewModel.tapStatus(FoodConsumptionStatus.SKIPPED) }
        bindConsumptionFeedback()
        bindActionButtonsStacking()

        val mealId = intent.getLongExtra(EXTRA_MEAL_ID, -1L)
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { viewModel.state.collect(::render) }
                launch { viewModel.changes.collect(::showChangeFeedback) }
            }
        }
        viewModel.load(data.activeProfileStore.currentIdOrNull() ?: -1L, mealId)
    }

    /** Confirms the registration and offers to undo it; the snackbar sits above the fixed action bar. */
    private fun showChangeFeedback(change: MealStatusChange) {
        val message = when (change.kind) {
            MealStatusChange.Kind.CONSUMED -> R.string.meal_ux4_snackbar_consumed
            MealStatusChange.Kind.SKIPPED -> R.string.meal_ux4_snackbar_skipped
            MealStatusChange.Kind.CLEARED -> R.string.meal_ux4_snackbar_cleared
        }
        SnackbarAnchors.make(this, message, R.id.mealActionBar)
            .setAction(R.string.meal_ux4_snackbar_undo) { viewModel.undo(change) }
            .show()
    }

    private fun normalizeMealCards() {
        listOf(R.id.ingredientsCard, R.id.preparationCard).forEach { id ->
            findViewById<MaterialCardView>(id).apply {
                setCardBackgroundColor(getColor(R.color.white))
                strokeColor = getColor(R.color.divider)
                strokeWidth = resources.getDimensionPixelSize(R.dimen.border_width_default)
                cardElevation = resources.getDimension(R.dimen.elevation_none)
            }
        }
    }

    private fun bindTabs() {
        val ingredientsCard = findViewById<View>(R.id.ingredientsCard)
        val preparationCard = findViewById<View>(R.id.preparationCard)
        ingredientsContainer = ingredientsCard.findViewById(R.id.ingredientsContainer)
        preparationContainer = preparationCard.findViewById(R.id.preparationContainer)
        findViewById<SelectableSegmentView>(R.id.detailSegment).apply {
            setSegments(listOf("Ingredienti", "Preparazione"), selectedIndex = 0)
            setOnSegmentMotionListener { selectedIndex, previousIndex ->
                val outgoingContainer = if (previousIndex == 0) {
                    ingredientsContainer
                } else {
                    preparationContainer
                }
                val incomingContainer = if (selectedIndex == 0) {
                    ingredientsContainer
                } else {
                    preparationContainer
                }
                val outgoingCard = if (previousIndex == 0) ingredientsCard else preparationCard
                val incomingCard = if (selectedIndex == 0) ingredientsCard else preparationCard
                UiMotion.reveal(outgoingCard, false)
                UiMotion.reveal(incomingCard, true)
                UiMotion.crossfade(outgoingContainer, incomingContainer, showFirst = selectedIndex == 0)
            }
            setOnSegmentSelectedListener { index ->
                selectedDetailTab = index
            }
        }
        ingredientsCard.visibility = View.VISIBLE
        preparationCard.visibility = View.GONE
        ingredientsContainer.visibility = View.VISIBLE
        preparationContainer.visibility = View.GONE
    }

    /**
     * UX6: i due pulsanti stanno affiancati su una riga. Se con font molto grande un'etichetta supera
     * [MAX_ACTION_BUTTON_LINES] righe (testo che si taglierebbe) la riga passa a verticale, una sola volta
     * (si ripristina alla ricreazione dell'activity, p.es. dopo un cambio di dimensione carattere).
     */
    private fun bindActionButtonsStacking() {
        val row = findViewById<LinearLayout>(R.id.mealActionButtons)
        val buttons = listOf(findViewById<TextView>(R.id.consumedButton), findViewById<TextView>(R.id.skippedButton))
        row.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            if (row.orientation == LinearLayout.HORIZONTAL && buttons.any { it.lineCount > MAX_ACTION_BUTTON_LINES }) {
                row.post {
                    row.orientation = LinearLayout.VERTICAL
                    buttons.forEachIndexed { index, button ->
                        button.layoutParams = LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT,
                        ).apply { if (index > 0) topMargin = resources.getDimensionPixelSize(R.dimen.space_8) }
                    }
                }
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
            findViewById<View>(R.id.mealActionBar).visibility = View.GONE
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
            findViewById<View>(R.id.mealActionBar).visibility = View.GONE
            findViewById<View>(R.id.ingredientsCard).visibility = View.GONE
            findViewById<View>(R.id.preparationCard).visibility = View.GONE
            return
        }
        errorView.visibility = View.GONE
        findViewById<View>(R.id.ingredientsCard).visibility = if (selectedDetailTab == 0) View.VISIBLE else View.GONE
        findViewById<View>(R.id.preparationCard).visibility = if (selectedDetailTab == 1) View.VISIBLE else View.GONE
        val ingredientsContent = ingredientsContainer
        val preparationContent = preparationContainer
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
        findViewById<ImageView>(R.id.mealImage).apply {
            setImageResource(MealSlotIcons.iconRes(meal.type, meal.timeMinutes))
            contentDescription = MealSlotIcons.label(meal.type, meal.timeMinutes)
        }

        val ingredientsList = ingredientsContainer as LinearLayout
        ingredientsList.removeAllViews()
        if (meal.ingredients.isEmpty()) {
            ingredientsList.addView(TextView(this).apply {
                text = "Ingredienti non disponibili"
                setTextAppearance(R.style.Text_MyFitAI_BodyCompact)
                val verticalPadding = resources.getDimensionPixelSize(R.dimen.space_14)
                setPadding(0, verticalPadding, 0, verticalPadding)
            })
        } else {
            meal.ingredients.sortedBy { it.sortOrder }.forEach { ingredient ->
                ingredientsList.addView(IngredientRowView(this).apply {
                    setName(ingredient.name)
                    setQuantity(displayQuantity(ingredient))
                    setIcon(iconFor(ingredient))
                })
            }
        }

        (preparationContainer as TextView).text =
            meal.preparation?.takeIf { it.isNotBlank() } ?: "Preparazione non disponibile."
    }

    private fun renderConsumption(state: MealDetailViewModel.State) {
        val consumedButton = findViewById<com.google.android.material.button.MaterialButton>(R.id.consumedButton)
        val skippedButton = findViewById<com.google.android.material.button.MaterialButton>(R.id.skippedButton)
        val statusText = findViewById<TextView>(R.id.mealStatusText)
        val status = state.consumption?.status
        findViewById<View>(R.id.mealActionBar).visibility = View.VISIBLE
        UiMotion.reveal(consumedButton, true, animateChange = false)
        UiMotion.reveal(skippedButton, true, animateChange = false)
        when (status) {
            FoodConsumptionStatus.CONSUMED.name -> {
                consumedButton.setText(R.string.meal_detail_ux4_undo_consumed)
                skippedButton.setText(R.string.meal_detail_ux4_mark_skipped)
                statusText.setText(R.string.meal_detail_ux4_status_consumed)
                statusText.setTextColor(getColor(R.color.accent_green_dark))
            }
            FoodConsumptionStatus.SKIPPED.name -> {
                consumedButton.setText(R.string.meal_detail_ux4_mark_consumed)
                skippedButton.setText(R.string.meal_detail_ux4_undo_skipped)
                statusText.setText(R.string.meal_detail_ux4_status_skipped)
                statusText.setTextColor(getColor(R.color.semantic_warning))
            }
            else -> {
                consumedButton.setText(R.string.meal_detail_ux4_mark_consumed)
                skippedButton.setText(R.string.meal_detail_ux4_mark_skipped)
                statusText.setText(R.string.meal_detail_ux4_status_none)
                statusText.setTextColor(getColor(R.color.text_secondary))
            }
        }
        consumedButton.contentDescription = consumedButton.text
        skippedButton.contentDescription = skippedButton.text
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

    private fun displayMealType(type: String): String = type.trim().ifBlank { "Pasto" }
        .replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.ITALIAN) else it.toString() }

    private fun formatTime(minutes: Int): String = String.format(Locale.ITALIAN, "%02d:%02d", minutes / 60, minutes % 60)
    private fun formatMacro(value: Float): String = if (value % 1f == 0f) value.toInt().toString() else String.format(Locale.ITALIAN, "%.1f", value)
    companion object {
        const val EXTRA_MEAL_ID = "meal_id"
        private const val MAX_ACTION_BUTTON_LINES = 2
    }
}
