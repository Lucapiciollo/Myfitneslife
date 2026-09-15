package com.myfitai.app.ui

import android.text.InputType
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.myfitai.app.R
import com.myfitai.app.ai.AiModelConfig
import com.myfitai.app.ai.AiSettingsStore
import com.myfitai.app.ai.GeminiPricing
import com.myfitai.app.ai.GeminiPricingStore
import com.myfitai.app.ai.GeminiUsageTracker
import kotlinx.coroutines.launch
import java.math.BigDecimal
import java.math.RoundingMode

/** Programmatic Settings row so the existing approved settings XML does not need a visual rewrite. */
object GeminiCostSettingsBinder {
    fun bind(activity: SettingsActivity, card: LinearLayout, settings: AiSettingsStore) {
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
        row.addView(value, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(activity, 3)
        })

        fun currentModel(): String = settings.geminiVerifiedModel ?: AiModelConfig.GEMINI_PRIMARY

        fun refresh() {
            activity.lifecycleScope.launch {
                val summary = runCatching { tracker.summary() }.getOrNull()
                val pricing = pricingStore.pricingFor(currentModel())
                value.text = if (summary == null) {
                    "Spesa non disponibile · ${pricingLabel(pricing)}"
                } else {
                    "Oggi ${money(summary.todayUsd)} · mese ${money(summary.monthUsd)} · totale ${money(summary.totalUsd)}\n" +
                        "${summary.requestCount} richieste · ${pricingLabel(pricing)}"
                }
            }
        }

        row.setOnClickListener {
            showCostDialog(activity, tracker, pricingStore, currentModel(), ::refresh)
        }

        card.addView(row, 0)
        card.addView(
            View(activity).apply { setBackgroundColor(activity.getColor(R.color.divider)) },
            1,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1).apply { bottomMargin = dp(activity, 12) },
        )
        refresh()
    }

    private fun showCostDialog(
        activity: SettingsActivity,
        tracker: GeminiUsageTracker,
        pricingStore: GeminiPricingStore,
        model: String,
        refresh: () -> Unit,
    ) {
        activity.lifecycleScope.launch {
            val pricing = pricingStore.pricingFor(model)
            val summary = runCatching { tracker.summary() }.getOrNull()
            val message = buildString {
                append("Modello: ${AiModelConfig.displayName(model)}\n\n")
                if (summary != null) {
                    append("Oggi: ${money(summary.todayUsd)}\n")
                    append("Questo mese: ${money(summary.monthUsd)}\n")
                    append("Totale: ${money(summary.totalUsd)}\n")
                    append("Richieste registrate: ${summary.requestCount}\n\n")
                }
                append("Input: \$${pricing.inputUsdPerMillion.stripTrailingZeros().toPlainString()} / 1M token\n")
                append("Output + thinking: \$${pricing.outputUsdPerMillion.stripTrailingZeros().toPlainString()} / 1M token\n")
                append("Cache input: \$${pricing.cachedInputUsdPerMillion.stripTrailingZeros().toPlainString()} / 1M token\n")
                append("Fonte: ${if (pricingStore.isManual(model)) "manuale" else "listino incluso nell'app"}\n")
                append("Riferimento listino: ${pricing.effectiveDate}\n\n")
                append("La spesa comprende solo le chiamate Gemini effettuate da MyFitAI da quando il tracciamento è attivo; non è il saldo ufficiale Google.")
            }
            MaterialAlertDialogBuilder(activity)
                .setTitle("Costi Gemini")
                .setMessage(message)
                .setPositiveButton("Modifica prezzi") { _, _ ->
                    showPricingEditor(activity, pricingStore, pricing, refresh)
                }
                .apply {
                    if (pricingStore.isManual(model)) {
                        setNeutralButton("Ripristina listino") { _, _ ->
                            pricingStore.restoreDefaults(model)
                            refresh()
                            Toast.makeText(activity, "Prezzi Gemini ripristinati", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
                .setNegativeButton("Chiudi", null)
                .show()
        }
    }

    private fun showPricingEditor(
        activity: SettingsActivity,
        pricingStore: GeminiPricingStore,
        pricing: GeminiPricing,
        refresh: () -> Unit,
    ) {
        val container = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(activity, 24), dp(activity, 8), dp(activity, 24), 0)
        }
        val input = decimalField(activity, "Input USD / 1M token", pricing.inputUsdPerMillion)
        val output = decimalField(activity, "Output + thinking USD / 1M token", pricing.outputUsdPerMillion)
        val cache = decimalField(activity, "Cache input USD / 1M token", pricing.cachedInputUsdPerMillion)
        container.addView(input)
        container.addView(output)
        container.addView(cache)

        val dialog = MaterialAlertDialogBuilder(activity)
            .setTitle("Prezzi Gemini manuali")
            .setMessage("Usa il listino Standard per il modello selezionato. I costi già registrati non vengono ricalcolati.")
            .setView(container)
            .setNegativeButton("Annulla", null)
            .setPositiveButton("Salva", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val inputPrice = parseDecimal(input.text?.toString())
                val outputPrice = parseDecimal(output.text?.toString())
                val cachePrice = parseDecimal(cache.text?.toString())
                if (inputPrice == null || outputPrice == null || cachePrice == null ||
                    inputPrice.signum() < 0 || outputPrice.signum() < 0 || cachePrice.signum() < 0
                ) {
                    Toast.makeText(activity, "Inserisci prezzi validi maggiori o uguali a zero", Toast.LENGTH_LONG).show()
                    return@setOnClickListener
                }
                pricingStore.setManual(pricing.model, inputPrice, outputPrice, cachePrice)
                dialog.dismiss()
                refresh()
                Toast.makeText(activity, "Prezzi Gemini aggiornati", Toast.LENGTH_SHORT).show()
            }
        }
        dialog.show()
    }

    private fun decimalField(activity: SettingsActivity, hint: String, value: BigDecimal): EditText = EditText(activity).apply {
        this.hint = hint
        inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
        setText(value.stripTrailingZeros().toPlainString())
        setSelectAllOnFocus(true)
    }

    private fun parseDecimal(raw: String?): BigDecimal? = raw?.trim()?.replace(',', '.')?.toBigDecimalOrNull()

    private fun pricingLabel(pricing: GeminiPricing): String =
        "${AiModelConfig.displayName(pricing.model)} · ${if (pricing.source == GeminiPricingStore.SOURCE_MANUAL) "prezzi manuali" else "listino ${pricing.effectiveDate}"}"

    private fun money(value: BigDecimal): String = "$" + value.setScale(4, RoundingMode.HALF_UP).toPlainString()

    private fun dp(activity: SettingsActivity, value: Int): Int =
        (value * activity.resources.displayMetrics.density).toInt()
}
