package com.myfitai.app.ui

import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.myfitai.app.R
import com.myfitai.app.ai.AiModelConfig
import com.myfitai.app.ai.AiSettingsStore
import com.myfitai.app.ai.GeminiPricingStore
import com.myfitai.app.ai.OpenAiPricingStore

object AiModelSelectionBinder {
    fun bind(activity: SettingsActivity, card: LinearLayout, settings: AiSettingsStore) {
        val geminiPricing = GeminiPricingStore(activity)
        val openAiPricing = OpenAiPricingStore(activity)

        addModelRow(
            activity = activity,
            card = card,
            title = "Modello Gemini",
            models = AiModelConfig.GEMINI_SELECTABLE,
            current = { settings.selectedGeminiModel },
            priceLabel = { model ->
                val p = geminiPricing.pricingFor(model)
                "input \$${p.inputUsdPerMillion.stripTrailingZeros().toPlainString()} · cache \$${p.cachedInputUsdPerMillion.stripTrailingZeros().toPlainString()} · output \$${p.outputUsdPerMillion.stripTrailingZeros().toPlainString()} / 1M"
            },
            select = { settings.selectedGeminiModel = it },
        )

        addModelRow(
            activity = activity,
            card = card,
            title = "Modello OpenAI",
            models = AiModelConfig.OPENAI_SELECTABLE,
            current = { settings.selectedOpenAiModel },
            priceLabel = { model ->
                val p = openAiPricing.pricingFor(model)
                "input \$${p.inputUsdPerMillion.stripTrailingZeros().toPlainString()} · cache \$${p.cachedInputUsdPerMillion.stripTrailingZeros().toPlainString()} · output \$${p.outputUsdPerMillion.stripTrailingZeros().toPlainString()} / 1M"
            },
            select = { settings.selectedOpenAiModel = it },
        )
    }

    private fun addModelRow(
        activity: SettingsActivity,
        card: LinearLayout,
        title: String,
        models: List<String>,
        current: () -> String,
        priceLabel: (String) -> String,
        select: (String) -> Unit,
    ) {
        val row = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            val verticalPadding = activity.resources.getDimensionPixelSize(R.dimen.space_8)
            setPadding(0, verticalPadding, 0, verticalPadding)
            isClickable = true
            isFocusable = true
        }
        val modelView = TextView(activity).apply {
            setTextAppearance(R.style.Text_MyFitAI_Body)
        }
        val pricingView = TextView(activity).apply {
            setTextAppearance(R.style.Text_MyFitAI_StatusValue)
        }
        row.addView(modelView, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        row.addView(pricingView, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            topMargin = activity.resources.getDimensionPixelSize(R.dimen.space_2)
        })
        fun render() {
            val model = current()
            modelView.text = "$title: ${AiModelConfig.displayName(model)}"
            pricingView.text = priceLabel(model)
            row.contentDescription = "$title: ${AiModelConfig.displayName(model)}, ${priceLabel(model)}"
        }
        row.setOnClickListener {
            val selectedModel = current()
            val labels = models.map { model ->
                val marker = if (model == selectedModel) " ✓" else ""
                "${AiModelConfig.displayName(model)}$marker\n${priceLabel(model)}"
            }.toTypedArray()
            MaterialAlertDialogBuilder(activity)
                .setTitle(title)
                .setSingleChoiceItems(labels, models.indexOf(selectedModel)) { dialog, which ->
                    val model = models[which]
                    select(model)
                    dialog.dismiss()
                    render()
                    Toast.makeText(activity, "${AiModelConfig.displayName(model)} sarà usato dalla prossima richiesta", Toast.LENGTH_LONG).show()
                }
                .setNegativeButton("Annulla", null)
                .show()
        }
        card.addView(row, 0)
        card.addView(View(activity).apply { setBackgroundColor(activity.getColor(R.color.divider)) }, 1, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, activity.resources.getDimensionPixelSize(R.dimen.space_1)).apply { bottomMargin = activity.resources.getDimensionPixelSize(R.dimen.space_8) })
        render()
    }
}
