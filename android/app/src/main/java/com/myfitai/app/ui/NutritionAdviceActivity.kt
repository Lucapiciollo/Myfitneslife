package com.myfitai.app.ui

import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.myfitai.app.R
import com.myfitai.app.data.AppDataContainer
import com.myfitai.app.domain.food.NutritionAdviceContract
import com.myfitai.app.navigation.BottomNavBinder
import com.myfitai.app.ui.food.NutritionAdviceViewModel
import kotlinx.coroutines.launch
import java.util.Locale

class NutritionAdviceActivity : BaseShellActivity() {

    private val data by lazy { AppDataContainer.get(this) }
    private val viewModel: NutritionAdviceViewModel by viewModels {
        NutritionAdviceViewModel.Factory(
            adviceService = data.nutritionAdviceService,
            cheatService = data.cheatAdjustmentService,
            notificationScheduler = data.notificationScheduler,
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_nutrition_advice)
        bindBack()
        bindBottom(BottomNavBinder.Tab.FOOD)

        findViewById<View>(R.id.askButton).setOnClickListener {
            viewModel.ask(findViewById<EditText>(R.id.questionInput).text?.toString().orEmpty())
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.state.collect(::render)
            }
        }
    }

    private fun render(state: NutritionAdviceViewModel.State) {
        val busy = state.running || state.accepting
        findViewById<View>(R.id.askButton).isEnabled = !busy
        findViewById<View>(R.id.loadingRow).visibility = if (busy) View.VISIBLE else View.GONE

        val answerCard = findViewById<View>(R.id.answerCard)
        val answer = state.answer
        answerCard.visibility = if (answer != null) View.VISIBLE else View.GONE
        if (answer != null) {
            findViewById<TextView>(R.id.answerText).text = answer
            findViewById<TextView>(R.id.providerText).apply {
                text = state.providerLabel.orEmpty()
                visibility = if (state.providerLabel.isNullOrBlank()) View.GONE else View.VISIBLE
            }
            findViewById<TextView>(R.id.assumptionsText).apply {
                text = state.assumptions
                visibility = if (state.assumptions.isBlank()) View.GONE else View.VISIBLE
            }
        }

        renderSuggestions(state.suggestions, enabled = !busy)

        findViewById<TextView>(R.id.statusText).apply {
            val message = when {
                state.accepting -> "Registro il suggerimento e ricalcolo i pasti futuri…"
                state.error != null -> state.error
                else -> null
            }
            visibility = if (message == null) View.GONE else View.VISIBLE
            text = message.orEmpty()
        }

        val result = state.acceptedResult ?: return
        viewModel.consumeAcceptedResult()
        startActivity(
            Intent(this, AdjustedPlanActivity::class.java)
                .putExtra(AdjustedPlanActivity.EXTRA_DESCRIPTION, "Suggerimento alimentare accettato")
                .putExtra(AdjustedPlanActivity.EXTRA_ESTIMATE, result.estimateSummary)
                .putExtra(AdjustedPlanActivity.EXTRA_ADAPTED, result.adapted)
                .putExtra(AdjustedPlanActivity.EXTRA_SUMMARY, result.adaptationSummary)
                .putStringArrayListExtra(AdjustedPlanActivity.EXTRA_MODIFIED_MEALS, ArrayList(result.modifiedMeals))
        )
        finish()
    }

    private fun renderSuggestions(suggestions: List<NutritionAdviceContract.Suggestion>, enabled: Boolean) {
        val container = findViewById<LinearLayout>(R.id.suggestionsContainer)
        container.removeAllViews()
        if (suggestions.isEmpty()) return

        container.addView(tableHeader())

        suggestions.forEach { suggestion ->
            val card = MaterialCardView(this).apply {
                radius = dp(12).toFloat()
                setCardBackgroundColor(getColor(R.color.surface_primary))
                strokeColor = getColor(R.color.divider)
                strokeWidth = dp(1)
            }
            val body = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(12), dp(10), dp(12), dp(10))
            }

            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            row.addView(cell(suggestion.title, 2.4f, bold = true, gravity = Gravity.START))
            row.addView(cell(suggestion.estimatedKcal.toString(), 0.9f, bold = true))
            row.addView(cell(String.format(Locale.ITALIAN, "%.0f", suggestion.proteinG), 0.7f))
            row.addView(cell(String.format(Locale.ITALIAN, "%.0f", suggestion.carbsG), 0.7f))
            row.addView(cell(String.format(Locale.ITALIAN, "%.0f", suggestion.fatG), 0.7f))
            body.addView(row)

            body.addView(TextView(this).apply {
                text = suggestion.reason
                setTextColor(getColor(R.color.text_secondary))
                textSize = 11f
                maxLines = 2
                setPadding(0, dp(6), 0, 0)
            })

            body.addView(MaterialButton(this).apply {
                text = "Accetta"
                isAllCaps = false
                isEnabled = enabled
                setOnClickListener { viewModel.acceptSuggestion(suggestion) }
            }, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(42),
            ).apply { topMargin = dp(8) })

            card.addView(body)
            container.addView(card, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = dp(8) })
        }
    }

    private fun tableHeader(): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(12), 0, dp(12), dp(6))
        addView(cell("Opzione", 2.4f, bold = true, gravity = Gravity.START, secondary = true))
        addView(cell("kcal", 0.9f, bold = true, secondary = true))
        addView(cell("P", 0.7f, bold = true, secondary = true))
        addView(cell("C", 0.7f, bold = true, secondary = true))
        addView(cell("F", 0.7f, bold = true, secondary = true))
    }

    private fun cell(
        value: String,
        weight: Float,
        bold: Boolean = false,
        gravity: Int = Gravity.CENTER,
        secondary: Boolean = false,
    ): TextView = TextView(this).apply {
        text = value
        setTextColor(getColor(if (secondary) R.color.text_secondary else R.color.text_primary))
        textSize = if (secondary) 11f else 12f
        this.gravity = gravity
        maxLines = 2
        if (bold) setTypeface(typeface, Typeface.BOLD)
        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, weight)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
