package com.myfitai.app.ui

import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.myfitai.app.R
import com.myfitai.app.ai.AiModelConfig
import com.myfitai.app.ai.AiSettingsStore
import com.myfitai.app.ai.GeminiPricing
import com.myfitai.app.ai.GeminiPricingStore
import com.myfitai.app.ai.GeminiUsageTracker
import kotlinx.coroutines.launch
import java.math.BigDecimal
import java.math.RoundingMode

object GeminiCostSettingsBinder {
    fun bind(activity: SettingsActivity, card: LinearLayout, settings: AiSettingsStore) {
        OpenAiCostSettingsBinder.bind(activity, card)
        AiModelSelectionBinder.bind(activity, card, settings)
        val tracker = GeminiUsageTracker(activity)
        val pricingStore = GeminiPricingStore(activity)
        val row = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            isClickable = true
            isFocusable = true
            setPadding(0, dp(activity, 4), 0, dp(activity, 12))
        }
        val title = TextView(activity).apply {
            text = "Costi Gemini"
            textSize = 15f
            setTextColor(activity.getColor(R.color.text_primary))
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }
        val value = TextView(activity).apply {
            text = "Calcolo della spesa registrata da MyFitAI…"
            textSize = 12f
            setTextColor(activity.getColor(R.color.text_secondary))
        }
        row.addView(title)
        row.addView(value, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(activity, 3) })

        fun currentModel(): String = settings.selectedGeminiModel
        fun refresh() {
            activity.lifecycleScope.launch {
                val summary = runCatching { tracker.summary() }.getOrNull()
                val pricing = pricingStore.pricingFor(currentModel())
                value.text = if (summary == null) "Spesa non disponibile · ${pricingLabel(pricing)}"
                else "Oggi ${money(summary.todayUsd)} · mese ${money(summary.monthUsd)} · totale ${money(summary.totalUsd)}\n${summary.requestCount} richieste · ${pricingLabel(pricing)}"
            }
        }
        row.setOnClickListener { showCostDialog(activity, tracker, pricingStore, currentModel(), ::refresh) }
        card.addView(row, 0)
        card.addView(View(activity).apply { setBackgroundColor(activity.getColor(R.color.divider)) }, 1, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1).apply { bottomMargin = dp(activity, 12) })
        refresh()
    }

    private fun showCostDialog(activity: SettingsActivity, tracker: GeminiUsageTracker, pricingStore: GeminiPricingStore, model: String, refresh: () -> Unit) {
        activity.lifecycleScope.launch {
            val pricing = pricingStore.pricingFor(model)
            val summary = runCatching { tracker.summary() }.getOrNull()
            val message = buildString {
                append("Modello: ${AiModelConfig.displayName(model)}\n\n")
                if (summary != null) append("Oggi: ${money(summary.todayUsd)}\nQuesto mese: ${money(summary.monthUsd)}\nTotale: ${money(summary.totalUsd)}\nRichieste registrate: ${summary.requestCount}\n\n")
                append("Input: \$${pricing.inputUsdPerMillion.stripTrailingZeros().toPlainString()} / 1M token\n")
                append("Output + thinking: \$${pricing.outputUsdPerMillion.stripTrailingZeros().toPlainString()} / 1M token\n")
                append("Cache input: \$${pricing.cachedInputUsdPerMillion.stripTrailingZeros().toPlainString()} / 1M token\n")
                append("Fonte: ${if (pricingStore.isManual(model)) "manuale" else "listino incluso nell'app"}\nRiferimento listino: ${pricing.effectiveDate}\n\n")
                append("La spesa comprende solo le chiamate Gemini effettuate da MyFitAI da quando il tracciamento è attivo; non è il saldo ufficiale Google.")
            }
            MaterialAlertDialogBuilder(activity)
                .setTitle("Costi Gemini")
                .setMessage(message)
                .setPositiveButton("Modifica prezzi") { _, _ -> showPricingEditor(activity, pricingStore, pricing, refresh) }
                .apply {
                    if (pricingStore.isManual(model)) setNeutralButton("Ripristina listino") { _, _ ->
                        pricingStore.restoreDefaults(model); refresh(); Toast.makeText(activity, "Prezzi Gemini ripristinati", Toast.LENGTH_SHORT).show()
                    }
                }
                .setNegativeButton("Chiudi", null)
                .show()
        }
    }

    private fun showPricingEditor(activity: SettingsActivity, pricingStore: GeminiPricingStore, pricing: GeminiPricing, refresh: () -> Unit) {
        val content = activity.layoutInflater.inflate(R.layout.dialog_pricing_editor, null, false)
        val input = content.findViewById<TextInputEditText>(R.id.pricingInput)
        val output = content.findViewById<TextInputEditText>(R.id.pricingOutput)
        val cache = content.findViewById<TextInputEditText>(R.id.pricingCache)
        val inputLayout = content.findViewById<TextInputLayout>(R.id.pricingInputLayout)
        val outputLayout = content.findViewById<TextInputLayout>(R.id.pricingOutputLayout)
        val cacheLayout = content.findViewById<TextInputLayout>(R.id.pricingCacheLayout)

        outputLayout.hint = "Output + thinking USD / 1M token"
        input.setText(pricing.inputUsdPerMillion.stripTrailingZeros().toPlainString())
        output.setText(pricing.outputUsdPerMillion.stripTrailingZeros().toPlainString())
        cache.setText(pricing.cachedInputUsdPerMillion.stripTrailingZeros().toPlainString())

        val dialog = MaterialAlertDialogBuilder(activity)
            .setTitle("Prezzi Gemini manuali")
            .setMessage("I costi già registrati non vengono ricalcolati.")
            .setView(content)
            .setNegativeButton("Annulla", null)
            .setPositiveButton("Salva", null)
            .create()
        dialog.setOnShowListener {
            dialog.window?.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
            val scroll = content.findViewById<androidx.core.widget.NestedScrollView>(R.id.pricingEditorScroll)
            val maxHeight = (activity.resources.displayMetrics.heightPixels * 0.45f).toInt()
            scroll.layoutParams = scroll.layoutParams.apply { height = minOf(dp(activity, 320), maxHeight) }
            dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val a = parseDecimal(input.text?.toString())
                val b = parseDecimal(output.text?.toString())
                val c = parseDecimal(cache.text?.toString())
                inputLayout.error = if (a == null || a.signum() < 0) "Inserisci un prezzo valido" else null
                outputLayout.error = if (b == null || b.signum() < 0) "Inserisci un prezzo valido" else null
                cacheLayout.error = if (c == null || c.signum() < 0) "Inserisci un prezzo valido" else null
                if (a == null || b == null || c == null ||
                    a.signum() < 0 || b.signum() < 0 || c.signum() < 0
                ) return@setOnClickListener
                pricingStore.setManual(pricing.model, a, b, c)
                dialog.dismiss()
                refresh()
            }
        }
        dialog.show()
    }

    private fun parseDecimal(raw: String?) = raw?.trim()?.replace(',', '.')?.toBigDecimalOrNull()
    private fun pricingLabel(p: GeminiPricing) = "${AiModelConfig.displayName(p.model)} · ${if (p.source == GeminiPricingStore.SOURCE_MANUAL) "prezzi manuali" else "listino ${p.effectiveDate}"}"
    private fun money(value: BigDecimal) = "$" + value.setScale(4, RoundingMode.HALF_UP).toPlainString()
    private fun dp(activity: SettingsActivity, value: Int) = (value * activity.resources.displayMetrics.density).toInt()
}
