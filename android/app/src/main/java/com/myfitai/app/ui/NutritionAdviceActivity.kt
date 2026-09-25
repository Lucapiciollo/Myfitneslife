package com.myfitai.app.ui

import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.View
import com.google.android.material.textfield.TextInputEditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.textfield.TextInputLayout
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
            aiJobScheduler = data.aiJobScheduler,
            activeProfileStore = data.activeProfileStore,
            pendingJobKey = intent.getStringExtra(EXTRA_JOB_KEY),
        )
    }

    companion object { const val EXTRA_JOB_KEY = "nutrition_advice_job_key" }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_nutrition_advice)
        normalizeAdviceSurfaces()
        bindBack()
        bindBottom(BottomNavBinder.Tab.FOOD)

        findViewById<View>(R.id.askButton).setOnClickListener {
            val question = findViewById<TextInputEditText>(R.id.questionInput).text?.toString().orEmpty()
            confirmAiRequest("La richiesta di un consiglio nutrizionale") {
                viewModel.ask(question)
            }
        }
        findViewById<View>(R.id.aiConfigurationNoticeButton).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.state.collect(::render)
            }
        }
    }

    private fun render(state: NutritionAdviceViewModel.State) {
        val busy = state.running || state.accepting
        val providerConfigured = aiProviderConfigured
        setAiActionEnabled(findViewById(R.id.askButton), !busy)
        findViewById<View>(R.id.aiConfigurationNoticeCard).visibility =
            if (providerConfigured) View.GONE else View.VISIBLE
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

        suggestions.forEach { suggestion ->
            val card = MaterialCardView(this).apply {
                radius = resources.getDimension(R.dimen.radius_medium)
                setCardBackgroundColor(getColor(R.color.white))
                strokeColor = getColor(R.color.divider)
                strokeWidth = resources.getDimensionPixelSize(R.dimen.space_1)
                cardElevation = 0f
            }
            val body = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                val horizontalPadding = resources.getDimensionPixelSize(R.dimen.card_content_padding_compact)
                setPadding(horizontalPadding, resources.getDimensionPixelSize(R.dimen.space_10), horizontalPadding, resources.getDimensionPixelSize(R.dimen.space_12))
            }

            body.addView(TextView(this).apply {
                text = suggestion.title
                setTextAppearance(R.style.Text_MyFitAI_KeyValueValue)
                includeFontPadding = false
            })

            val metrics = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, resources.getDimensionPixelSize(R.dimen.space_8), 0, 0)
            }
            metrics.addView(metric("kcal", NutritionEstimateFormatter.formatEstimatedKcal(suggestion.estimatedKcal), 1.1f))
            metrics.addView(metric("Proteine", NutritionEstimateFormatter.formatEstimatedMacro(suggestion.proteinG, "g"), 1f))
            metrics.addView(metric("Carboidrati", NutritionEstimateFormatter.formatEstimatedMacro(suggestion.carbsG, "g"), 1.1f))
            metrics.addView(metric("Grassi", NutritionEstimateFormatter.formatEstimatedMacro(suggestion.fatG, "g"), 0.9f))
            body.addView(metrics)

            body.addView(TextView(this).apply {
                text = suggestion.reason
                setTextAppearance(R.style.Text_MyFitAI_Body)
                includeFontPadding = false
                maxLines = 2
                ellipsize = android.text.TextUtils.TruncateAt.END
                setPadding(0, resources.getDimensionPixelSize(R.dimen.space_10), 0, 0)
            })

            body.addView(MaterialButton(this).apply {
                text = "Accetta"
                isAllCaps = false
                setAiActionEnabled(this, enabled)
                minHeight = resources.getDimensionPixelSize(R.dimen.button_primary_min_height)
                setTextColor(getColor(R.color.white))
                setTypeface(typeface, Typeface.BOLD)
                backgroundTintList = ColorStateList.valueOf(getColor(R.color.accent_green))
                cornerRadius = resources.getDimensionPixelSize(R.dimen.radius_field)
                stateListAnimator = null
                setOnClickListener {
                    confirmAiRequest("L'applicazione del suggerimento e il ricalcolo dei pasti futuri") {
                        viewModel.acceptSuggestion(suggestion)
                    }
                }
            }, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                resources.getDimensionPixelSize(R.dimen.button_primary_min_height),
            ).apply { topMargin = resources.getDimensionPixelSize(R.dimen.space_12) })

            card.addView(body)
            container.addView(card, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = resources.getDimensionPixelSize(R.dimen.space_8) })
        }
    }

    private fun metric(label: String, value: String, weight: Float): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, weight)
        addView(TextView(this@NutritionAdviceActivity).apply {
            text = label
            setTextAppearance(R.style.Text_MyFitAI_Micro)
            setTextColor(getColor(R.color.text_secondary))
            gravity = Gravity.CENTER
            maxLines = 2
            minHeight = resources.getDimensionPixelSize(R.dimen.space_28)
            includeFontPadding = false
        })
        addView(TextView(this@NutritionAdviceActivity).apply {
            text = value
            setTextAppearance(R.style.Text_MyFitAI_KeyValueValue)
            gravity = Gravity.CENTER
            includeFontPadding = false
        })
    }

    private fun normalizeAdviceSurfaces() {
        fun visit(view: View) {
            when (view) {
                is MaterialCardView -> {
                    view.setCardBackgroundColor(getColor(R.color.white))
                    view.strokeColor = getColor(R.color.divider)
                    view.strokeWidth = resources.getDimensionPixelSize(R.dimen.space_1)
                    view.cardElevation = 0f
                }
                is TextInputLayout -> {
                    view.boxBackgroundColor = getColor(R.color.white)
                    view.boxStrokeColor = getColor(R.color.myfitai_input_stroke)
                    view.boxStrokeWidth = resources.getDimensionPixelSize(R.dimen.space_1)
                    view.boxStrokeWidthFocused = resources.getDimensionPixelSize(R.dimen.space_2)
                    view.hintTextColor = android.content.res.ColorStateList.valueOf(getColor(R.color.myfitai_input_hint))
                }
            }
            if (view is android.view.ViewGroup) {
                for (index in 0 until view.childCount) visit(view.getChildAt(index))
            }
        }
        visit(findViewById(android.R.id.content))
    }
}
