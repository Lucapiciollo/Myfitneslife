package com.myfitai.app.ui

import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.myfitai.app.R
import com.myfitai.app.ai.AiModelConfig
import com.myfitai.app.ai.AiSettingsStore
import com.myfitai.app.ai.GeminiPricingStore
import com.myfitai.app.ai.OpenAiPricingStore

object AiModelSelectionBinder {
    fun bind(activity: AppCompatActivity, card: LinearLayout, settings: AiSettingsStore) {
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
        activity: AppCompatActivity,
        card: LinearLayout,
        title: String,
        models: List<String>,
        current: () -> String,
        priceLabel: (String) -> String,
        select: (String) -> Unit,
    ) {
        val row = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            isClickable = true
            isFocusable = true
            setPadding(0, dp(activity, 4), 0, dp(activity, 12))
        }
        val titleView = TextView(activity).apply {
            text = title
            textSize = 15f
            setTextColor(activity.getColor(R.color.text_primary))
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }
        val valueView = TextView(activity).apply {
            textSize = 12f
            setTextColor(activity.getColor(R.color.text_secondary))
        }
        fun render() {
            val model = current()
            valueView.text = "${AiModelConfig.displayName(model)} · ${priceLabel(model)}"
        }
        row.addView(titleView)
        row.addView(valueView, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(activity, 3) })
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
        card.addView(View(activity).apply { setBackgroundColor(activity.getColor(R.color.divider)) }, 1, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1).apply { bottomMargin = dp(activity, 12) })
        render()
    }

    private fun dp(activity: AppCompatActivity, value: Int) = (value * activity.resources.displayMetrics.density).toInt()
}
