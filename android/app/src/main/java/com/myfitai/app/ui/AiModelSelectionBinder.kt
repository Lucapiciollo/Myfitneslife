package com.myfitai.app.ui

import android.widget.ArrayAdapter
import android.widget.TextView
import android.widget.Toast
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.MaterialAutoCompleteTextView
import com.myfitai.app.R
import com.myfitai.app.ai.AiModelConfig
import com.myfitai.app.ai.AiSettingsStore
import com.myfitai.app.ai.GeminiPricingStore
import com.myfitai.app.ai.OpenAiPricingStore

/** Exposed model combos. Each selection is applied only after explicit confirmation. */
object AiModelSelectionBinder {
    fun bind(activity: SettingsActivity, card: android.widget.LinearLayout, settings: AiSettingsStore) {
        val geminiPricing = GeminiPricingStore(activity)
        val openAiPricing = OpenAiPricingStore(activity)
        val geminiInput = activity.findViewById<MaterialAutoCompleteTextView>(R.id.geminiModelInput)
        val openAiInput = activity.findViewById<MaterialAutoCompleteTextView>(R.id.openAiModelInput)
        val geminiCost = activity.findViewById<TextView>(R.id.geminiModelCostText)
        val openAiCost = activity.findViewById<TextView>(R.id.openAiModelCostText)
        var pendingGemini = settings.selectedGeminiModel
        var pendingOpenAi = settings.selectedOpenAiModel
        fun comboLabel(model: String): String = "${AiModelConfig.ratingLabel(model)}  ${AiModelConfig.displayName(model)}"

        fun geminiPrice(model: String): String {
            val p = geminiPricing.pricingFor(model)
            return "Indice MyFitAI: ${AiModelConfig.ratingLabel(model)} · costo stimato: input \$${p.inputUsdPerMillion.stripTrailingZeros().toPlainString()} · cache \$${p.cachedInputUsdPerMillion.stripTrailingZeros().toPlainString()} · output \$${p.outputUsdPerMillion.stripTrailingZeros().toPlainString()} / 1M token"
        }

        fun openAiPrice(model: String): String {
            val p = openAiPricing.pricingFor(model)
            return "Indice MyFitAI: ${AiModelConfig.ratingLabel(model)} · costo stimato: input \$${p.inputUsdPerMillion.stripTrailingZeros().toPlainString()} · cache \$${p.cachedInputUsdPerMillion.stripTrailingZeros().toPlainString()} · output \$${p.outputUsdPerMillion.stripTrailingZeros().toPlainString()} / 1M token"
        }

        fun render() {
            geminiInput.setText(comboLabel(pendingGemini), false)
            openAiInput.setText(comboLabel(pendingOpenAi), false)
            geminiCost.text = geminiPrice(pendingGemini)
            openAiCost.text = openAiPrice(pendingOpenAi)
        }

        geminiInput.setAdapter(ArrayAdapter(activity, android.R.layout.simple_dropdown_item_1line, AiModelConfig.GEMINI_SELECTABLE.map(::comboLabel)))
        openAiInput.setAdapter(ArrayAdapter(activity, android.R.layout.simple_dropdown_item_1line, AiModelConfig.OPENAI_SELECTABLE.map(::comboLabel)))
        fun confirmSelection(
            title: String,
            model: String,
            price: String,
            previous: String,
            apply: () -> Unit,
            restore: () -> Unit,
        ) {
            val dialog = com.google.android.material.dialog.MaterialAlertDialogBuilder(activity)
                .setTitle("Confermare modello?")
                .setMessage("$title\n${AiModelConfig.displayName(model)}\n\n$price")
                .setNegativeButton("Annulla") { _, _ -> restore() }
                .setPositiveButton("Conferma") { _, _ ->
                    apply()
                    Toast.makeText(activity, "${AiModelConfig.displayName(model)} confermato", Toast.LENGTH_SHORT).show()
                }
                .create()
            dialog.setOnCancelListener { restore() }
            dialog.show()
        }

        geminiInput.setOnItemClickListener { _, _, position, _ ->
            val previous = settings.selectedGeminiModel
            pendingGemini = AiModelConfig.GEMINI_SELECTABLE[position]
            render()
            confirmSelection("Modello Gemini", pendingGemini, geminiPrice(pendingGemini), previous, {
                settings.selectedGeminiModel = pendingGemini
            }, {
                pendingGemini = previous
                render()
            })
        }
        openAiInput.setOnItemClickListener { _, _, position, _ ->
            val previous = settings.selectedOpenAiModel
            pendingOpenAi = AiModelConfig.OPENAI_SELECTABLE[position]
            render()
            confirmSelection("Modello OpenAI", pendingOpenAi, openAiPrice(pendingOpenAi), previous, {
                settings.selectedOpenAiModel = pendingOpenAi
            }, {
                pendingOpenAi = previous
                render()
            })
        }
        geminiInput.setOnClickListener { geminiInput.showDropDown() }
        openAiInput.setOnClickListener { openAiInput.showDropDown() }
        render()
    }
}
