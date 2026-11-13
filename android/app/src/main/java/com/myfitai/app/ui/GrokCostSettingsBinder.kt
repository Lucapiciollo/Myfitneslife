package com.myfitai.app.ui

import android.view.View
import android.widget.LinearLayout
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.myfitai.app.R
import com.myfitai.app.ai.AiModelConfig
import com.myfitai.app.ai.AiSettingsStore
import com.myfitai.app.ai.GrokPricing
import com.myfitai.app.ai.GrokPricingStore
import com.myfitai.app.ai.GrokUsageTracker
import com.myfitai.app.ui.widgets.KeyValueRowView
import kotlinx.coroutines.launch
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Costi e prezzi del provider sperimentale Grok (xAI), con lo stesso schema di [OpenAiCostSettingsBinder].
 * Aggiunge riga e divisore in coda al gruppo costi e li restituisce, cosi' la schermata li mostra solo se Grok e' in uso.
 */
object GrokCostSettingsBinder {
    fun bind(activity: BaseShellActivity, card: LinearLayout): List<View> {
        val tracker = GrokUsageTracker(activity)
        val pricingStore = GrokPricingStore(activity)
        val settings = AiSettingsStore(activity)
        val row = KeyValueRowView(activity).apply {
            isClickable = true
            isFocusable = true
            minimumHeight = activity.resources.getDimensionPixelSize(R.dimen.control_min_height)
        }
        row.setKey(activity.getString(R.string.ai_grok_cost_title))
        row.setValue(activity.getString(R.string.ai_grok_cost_loading))

        fun currentModel() = settings.selectedGrokModel
        fun refresh() {
            activity.lifecycleScope.launch {
                val summary = runCatching { tracker.summary() }.getOrNull()
                val pricing = pricingStore.pricingFor(currentModel())
                row.setValue(
                    if (summary == null) {
                        activity.getString(R.string.ai_grok_cost_unavailable, pricingLabel(activity, pricingStore, pricing))
                    } else {
                        activity.getString(R.string.ai_grok_cost_month, money(summary.monthUsd))
                    },
                )
            }
        }
        row.setOnClickListener { showCostDialog(activity, tracker, pricingStore, currentModel(), ::refresh) }
        val divider = View(activity).apply { setBackgroundColor(activity.getColor(R.color.divider)) }
        card.addView(row)
        card.addView(
            divider,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                activity.resources.getDimensionPixelSize(R.dimen.divider_thickness),
            ).apply { bottomMargin = activity.resources.getDimensionPixelSize(R.dimen.space_8) },
        )
        refresh()
        return listOf(row, divider)
    }

    private fun showCostDialog(
        activity: BaseShellActivity,
        tracker: GrokUsageTracker,
        pricingStore: GrokPricingStore,
        model: String,
        refresh: () -> Unit,
    ) {
        activity.lifecycleScope.launch {
            val pricing = pricingStore.pricingFor(model)
            val summary = runCatching { tracker.summary() }.getOrNull()
            val message = buildString {
                append(activity.getString(R.string.ai_grok_cost_dialog_model, AiModelConfig.displayName(model))).append("\n\n")
                if (summary != null) {
                    append(
                        activity.getString(
                            R.string.ai_grok_cost_dialog_usage,
                            money(summary.todayUsd),
                            money(summary.monthUsd),
                            money(summary.totalUsd),
                            summary.requestCount,
                        ),
                    ).append("\n\n")
                }
                append(
                    activity.getString(
                        R.string.ai_grok_cost_dialog_rates,
                        usd(pricing.inputUsdPerMillion),
                        usd(pricing.outputUsdPerMillion),
                        usd(pricing.cachedInputUsdPerMillion),
                    ),
                ).append("\n")
                val source = activity.getString(
                    if (pricingStore.isManual(model)) R.string.ai_grok_cost_source_manual else R.string.ai_grok_cost_source_bundled,
                )
                append(activity.getString(R.string.ai_grok_cost_dialog_source, source, pricing.effectiveDate)).append("\n\n")
                append(activity.getString(R.string.ai_grok_cost_dialog_note))
            }
            MaterialAlertDialogBuilder(activity)
                .setTitle(R.string.ai_grok_cost_title)
                .setMessage(message)
                .setPositiveButton(R.string.ai_grok_cost_edit) { _, _ -> showPricingEditor(activity, pricingStore, pricing, refresh) }
                .apply {
                    if (pricingStore.isManual(model)) {
                        setNeutralButton(R.string.ai_grok_cost_restore) { _, _ ->
                            pricingStore.restoreDefaults(model)
                            refresh()
                            Toast.makeText(activity, R.string.ai_grok_cost_restored, Toast.LENGTH_SHORT).show()
                        }
                    }
                }
                .setNegativeButton(R.string.ai_grok_cost_close, null)
                .show()
        }
    }

    private fun showPricingEditor(
        activity: BaseShellActivity,
        pricingStore: GrokPricingStore,
        pricing: GrokPricing,
        refresh: () -> Unit,
    ) {
        val content = activity.layoutInflater.inflate(R.layout.dialog_pricing_editor, null, false)
        val input = content.findViewById<TextInputEditText>(R.id.pricingInput)
        val output = content.findViewById<TextInputEditText>(R.id.pricingOutput)
        val cache = content.findViewById<TextInputEditText>(R.id.pricingCache)
        val inputLayout = content.findViewById<TextInputLayout>(R.id.pricingInputLayout)
        val outputLayout = content.findViewById<TextInputLayout>(R.id.pricingOutputLayout)
        val cacheLayout = content.findViewById<TextInputLayout>(R.id.pricingCacheLayout)

        outputLayout.hint = activity.getString(R.string.ai_grok_pricing_output_hint)
        input.setText(pricing.inputUsdPerMillion.stripTrailingZeros().toPlainString())
        output.setText(pricing.outputUsdPerMillion.stripTrailingZeros().toPlainString())
        cache.setText(pricing.cachedInputUsdPerMillion.stripTrailingZeros().toPlainString())

        val dialog = MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.ai_grok_pricing_title)
            .setMessage(R.string.ai_grok_pricing_message)
            .setView(activity.normalizeRuntimeDialogContent(content))
            .setNegativeButton(R.string.ai_grok_pricing_cancel, null)
            .setPositiveButton(R.string.ai_grok_pricing_save, null)
            .create()
        dialog.setOnShowListener {
            dialog.window?.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
            dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val a = parseDecimal(input.text?.toString())
                val b = parseDecimal(output.text?.toString())
                val c = parseDecimal(cache.text?.toString())
                val invalid = activity.getString(R.string.ai_grok_pricing_invalid)
                inputLayout.error = if (a == null || a.signum() < 0) invalid else null
                outputLayout.error = if (b == null || b.signum() < 0) invalid else null
                cacheLayout.error = if (c == null || c.signum() < 0) invalid else null
                if (a == null || b == null || c == null || a.signum() < 0 || b.signum() < 0 || c.signum() < 0) {
                    return@setOnClickListener
                }
                pricingStore.setManual(pricing.model, a, b, c)
                dialog.dismiss()
                refresh()
            }
        }
        dialog.show()
    }

    private fun parseDecimal(raw: String?) = raw?.trim()?.replace(',', '.')?.toBigDecimalOrNull()

    private fun usd(value: BigDecimal) = "$" + value.stripTrailingZeros().toPlainString()

    private fun pricingLabel(activity: BaseShellActivity, store: GrokPricingStore, p: GrokPricing): String {
        val source = if (store.isManual(p.model)) {
            activity.getString(R.string.ai_grok_cost_pricing_manual)
        } else {
            activity.getString(R.string.ai_grok_cost_pricing_listing, p.effectiveDate)
        }
        return activity.getString(R.string.ai_grok_cost_pricing_label, AiModelConfig.displayName(p.model), source)
    }

    private fun money(value: BigDecimal) = "$" + value.setScale(4, RoundingMode.HALF_UP).toPlainString()
}
