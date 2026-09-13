package com.myfitai.app.ui

import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
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
                state.accepting -> "Sto registrando il suggerimento e ricalcolando solo i pasti futuri…"
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
        suggestions.forEach { suggestion ->
            val card = MaterialCardView(this).apply {
                radius = dp(14).toFloat()
                setCardBackgroundColor(getColor(R.color.surface_primary))
                strokeColor = getColor(R.color.divider)
                strokeWidth = dp(1)
            }
            val body = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(16), dp(14), dp(16), dp(14))
            }
            body.addView(TextView(this).apply {
                text = suggestion.title
                setTextColor(getColor(R.color.text_primary))
                textSize = 16f
                setTypeface(typeface, Typeface.BOLD)
            })
            body.addView(TextView(this).apply {
                text = suggestion.reason
                setTextColor(getColor(R.color.text_secondary))
                textSize = 13f
                setPadding(0, dp(6), 0, 0)
            })
            body.addView(TextView(this).apply {
                text = String.format(
                    Locale.ITALIAN,
                    "≈ %d kcal · P %.0fg · C %.0fg · F %.0fg",
                    suggestion.estimatedKcal,
                    suggestion.proteinG,
                    suggestion.carbsG,
                    suggestion.fatG,
                )
                setTextColor(getColor(R.color.accent_green_dark))
                textSize = 13f
                setTypeface(typeface, Typeface.BOLD)
                setPadding(0, dp(8), 0, 0)
            })
            body.addView(MaterialButton(this).apply {
                text = "Accetta suggerimento"
                isAllCaps = false
                isEnabled = enabled
                setOnClickListener { viewModel.acceptSuggestion(suggestion) }
            }, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(48),
            ).apply { topMargin = dp(12) })
            card.addView(body)
            container.addView(card, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = dp(10) })
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
