package com.myfitai.app.ui

import android.text.InputType
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.LifecycleOwner
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.myfitai.app.R
import com.myfitai.app.ai.AiModelConfig
import com.myfitai.app.ai.AiSettingsStore
import com.myfitai.app.ai.OpenAiPricing
import com.myfitai.app.ai.OpenAiPricingStore
import com.myfitai.app.ai.OpenAiUsageTracker
import kotlinx.coroutines.launch
import java.math.BigDecimal
import java.math.RoundingMode

object OpenAiCostSettingsBinder {
    fun bind(activity: AppCompatActivity, card: LinearLayout, lifecycleOwner: LifecycleOwner = activity) {
        val tracker = OpenAiUsageTracker(activity)
        val pricingStore = OpenAiPricingStore(activity)
        val settings = AiSettingsStore(activity)
        val row = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            isClickable = true
            isFocusable = true
            setPadding(0, dp(activity, 4), 0, dp(activity, 12))
        }
        val title = TextView(activity).apply {
            text = "Costi OpenAI"
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

        fun currentModel() = settings.selectedOpenAiModel
        fun refresh() {
            lifecycleOwner.lifecycleScope.launch {
                val summary = runCatching { tracker.summary() }.getOrNull()
                val pricing = pricingStore.pricingFor(currentModel())
                value.text = if (summary == null) "Spesa non disponibile · ${pricingLabel(pricing)}"
                else "Oggi ${money(summary.todayUsd)} · mese ${money(summary.monthUsd)} · totale ${money(summary.totalUsd)}\n${summary.requestCount} richieste · ${pricingLabel(pricing)}"
            }
        }
        row.setOnClickListener { showCostDialog(activity, lifecycleOwner, tracker, pricingStore, currentModel(), ::refresh) }
        card.addView(row, 0)
        card.addView(View(activity).apply { setBackgroundColor(activity.getColor(R.color.divider)) }, 1, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1).apply { bottomMargin = dp(activity, 12) })
        refresh()
    }

    private fun showCostDialog(activity: AppCompatActivity, lifecycleOwner: LifecycleOwner, tracker: OpenAiUsageTracker, pricingStore: OpenAiPricingStore, model: String, refresh: () -> Unit) {
        lifecycleOwner.lifecycleScope.launch {
            val pricing = pricingStore.pricingFor(model)
            val summary = runCatching { tracker.summary() }.getOrNull()
            val message = buildString {
                append("Modello: ${AiModelConfig.displayName(model)}\n\n")
                if (summary != null) append("Oggi: ${money(summary.todayUsd)}\nQuesto mese: ${money(summary.monthUsd)}\nTotale: ${money(summary.totalUsd)}\nRichieste registrate: ${summary.requestCount}\n\n")
                append("Input: \$${pricing.inputUsdPerMillion.stripTrailingZeros().toPlainString()} / 1M token\n")
                append("Output: \$${pricing.outputUsdPerMillion.stripTrailingZeros().toPlainString()} / 1M token\n")
                append("Cache input: \$${pricing.cachedInputUsdPerMillion.stripTrailingZeros().toPlainString()} / 1M token\n")
                append("Fonte: ${if (pricingStore.isManual(model)) "manuale" else "listino incluso nell'app"}\nRiferimento listino: ${pricing.effectiveDate}\n\n")
                append("La spesa comprende solo le chiamate OpenAI effettuate da MyFitAI da quando il tracciamento è attivo; non è il saldo ufficiale OpenAI.")
            }
            MaterialAlertDialogBuilder(activity)
                .setTitle("Costi OpenAI")
                .setMessage(message)
                .setPositiveButton("Modifica prezzi") { _, _ -> showPricingEditor(activity, pricingStore, pricing, refresh) }
                .apply {
                    if (pricingStore.isManual(model)) setNeutralButton("Ripristina listino") { _, _ ->
                        pricingStore.restoreDefaults(model); refresh(); Toast.makeText(activity, "Prezzi OpenAI ripristinati", Toast.LENGTH_SHORT).show()
                    }
                }
                .setNegativeButton("Chiudi", null)
                .show()
        }
    }

    private fun showPricingEditor(activity: AppCompatActivity, pricingStore: OpenAiPricingStore, pricing: OpenAiPricing, refresh: () -> Unit) {
        val container = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(activity, 24), dp(activity, 8), dp(activity, 24), 0) }
        val input = decimalField(activity, "Input USD / 1M token", pricing.inputUsdPerMillion)
        val output = decimalField(activity, "Output USD / 1M token", pricing.outputUsdPerMillion)
        val cache = decimalField(activity, "Cache input USD / 1M token", pricing.cachedInputUsdPerMillion)
        container.addView(input); container.addView(output); container.addView(cache)
        val dialog = MaterialAlertDialogBuilder(activity).setTitle("Prezzi OpenAI manuali").setMessage("I costi già registrati non vengono ricalcolati.").setView(container).setNegativeButton("Annulla", null).setPositiveButton("Salva", null).create()
        dialog.setOnShowListener {
            dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val a = parseDecimal(input.text?.toString()); val b = parseDecimal(output.text?.toString()); val c = parseDecimal(cache.text?.toString())
                if (a == null || b == null || c == null || a.signum() < 0 || b.signum() < 0 || c.signum() < 0) { Toast.makeText(activity, "Inserisci prezzi validi", Toast.LENGTH_LONG).show(); return@setOnClickListener }
                pricingStore.setManual(pricing.model, a, b, c); dialog.dismiss(); refresh()
            }
        }
        dialog.show()
    }

    private fun decimalField(activity: AppCompatActivity, hint: String, value: BigDecimal) = EditText(activity).apply { this.hint = hint; inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL; setText(value.stripTrailingZeros().toPlainString()) }
    private fun parseDecimal(raw: String?) = raw?.trim()?.replace(',', '.')?.toBigDecimalOrNull()
    private fun pricingLabel(p: OpenAiPricing) = "${AiModelConfig.displayName(p.model)} · ${if (p.source == OpenAiPricingStore.SOURCE_MANUAL) "prezzi manuali" else "listino ${p.effectiveDate}"}"
    private fun money(value: BigDecimal) = "$" + value.setScale(4, RoundingMode.HALF_UP).toPlainString()
    private fun dp(activity: AppCompatActivity, value: Int) = (value * activity.resources.displayMetrics.density).toInt()
}
