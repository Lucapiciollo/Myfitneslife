package com.myfitai.app.ui

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.TextView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ImageButton
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import com.myfitai.app.R
import com.myfitai.app.data.AppDataContainer
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.myfitai.app.domain.ai.AiJobState
import com.myfitai.app.domain.ai.AiJobType
import com.myfitai.app.domain.progress.ProgressAnalysisCompactContract
import com.myfitai.app.domain.calculation.WeeklyBodyExpectation
import com.myfitai.app.domain.progress.ProgressAnalysisService
import com.myfitai.app.ui.progress.PhysicalEvolutionState
import com.myfitai.app.ui.progress.PhysicalEvolutionViewModel
import com.myfitai.app.ui.widgets.SelectableSegmentView
import com.myfitai.app.ui.widgets.TimeRangeSelectorView
import com.myfitai.app.ui.widgets.WeightTrendChartView
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters
import com.myfitai.app.domain.food.FoodConsumptionMetrics
import java.util.Locale
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit

class PhysicalEvolutionFragment : Fragment(R.layout.activity_physical_evolution) {
    private val data by lazy { AppDataContainer.get(requireContext()) }
    private val viewModel: PhysicalEvolutionViewModel by viewModels { PhysicalEvolutionViewModel.Factory(data.biaRepository, data.activeProfileStore) }
    private var metricIndex = 0
    private var rangeIndex = 1
    private var latest = PhysicalEvolutionState()
    private var analysisDetailsExpanded = false
    private lateinit var analysisLastText: TextView
    private lateinit var analysisNextText: TextView
    private lateinit var analysisResultText: TextView
    private lateinit var analysisDetailsButton: MaterialButton
    private lateinit var analysisDetailsContainer: LinearLayout
    private lateinit var analysisProgress: ProgressBar
    private lateinit var analysisButton: MaterialButton

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        bindProgressAnalysisCard(view)
        observeAnalysisJob()
        bindWeeklyReviewCard(view)
        view.findViewById<WeightTrendChartView>(R.id.evolutionChart).showYAxisLabels()
        view.findViewById<View>(R.id.backButton).setOnClickListener { (activity as? TabHostActivity)?.showExitConfirmation() }
        view.findViewById<SelectableSegmentView>(R.id.metricSegment).apply {
            setSegments(listOf("Peso", "Grasso", "Massa muscolare"), 0)
            setOnSegmentSelectedListener { metricIndex = it; render(latest) }
        }
        view.findViewById<TimeRangeSelectorView>(R.id.timeRangeSelector).apply {
            setRanges(listOf("1M", "3M", "6M", "1Y"), 1)
            setOnRangeSelectedListener { rangeIndex = it; render(latest) }
        }
        view.findViewById<View>(R.id.progressSummaryTitle).setOnClickListener { startActivity(Intent(requireContext(), WeeklyReviewActivity::class.java)) }
        viewLifecycleOwner.lifecycleScope.launch { viewModel.state.collect { latest = it; render(it) } }
        loadWeeklyExpectation(view)
    }

    private fun render(state: PhysicalEvolutionState) {
        val view = view ?: return
        val metric = when (metricIndex) { 1 -> state.bodyFat; 2 -> state.muscle; else -> state.weight }
        val filtered = viewModel.filtered(metric, rangeIndex)
        val unit = if (metricIndex == 1) "%" else "kg"
        view.findViewById<TextView>(R.id.metricLabel).text = listOf("Peso", "Grasso corporeo", "Massa muscolare")[metricIndex]
        view.findViewById<TextView>(R.id.metricValue).text = filtered.value?.let { "${"%.1f".format(Locale.ITALIAN, it)} $unit" } ?: "—"
        view.findViewById<TextView>(R.id.metricDelta).text = filtered.delta?.let {
            String.format(Locale.ITALIAN, "%+.1f %s nel periodo", it, unit)
        } ?: "Dati insufficienti"
        view.findViewById<WeightTrendChartView>(R.id.evolutionChart).setData(filtered.series.map { it.value })
        renderDateLabels(view, filtered)
        renderSecondary(view, R.id.otherIndicatorFatValue, R.id.otherIndicatorFatDelta, state.bodyFat, "%")
        renderSecondary(view, R.id.otherIndicatorMuscleValue, R.id.otherIndicatorMuscleDelta, state.muscle, "kg")
        renderSecondary(view, R.id.otherIndicatorWaterValue, R.id.otherIndicatorWaterDelta, state.bodyWater, "%")
        view.findViewById<TextView>(R.id.progressSummaryTitle).text = if (filtered.series.size >= 2) "Trend basato sulle rilevazioni registrate" else "Servono più rilevazioni"
        view.findViewById<TextView>(R.id.progressSummaryText).text = if (filtered.series.size >= 2) "I valori mostrati derivano esclusivamente dallo storico BIA reale del profilo attivo." else "Aggiungi almeno due rilevazioni comparabili per visualizzare un andamento affidabile."
        renderWeeklyExpectation(view, state.weeklyExpectation)
    }

    private fun renderWeeklyExpectation(root: View, result: WeeklyBodyExpectation.Result) {
        val value = root.findViewById<TextView>(R.id.weeklyExpectationValue)
        val detail = root.findViewById<TextView>(R.id.weeklyExpectationDetail)
        if (!result.available) {
            value.text = "Aspettativa non disponibile"
            detail.text = result.caution
            return
        }
        val minFat = UiNumberFormat.decimal(result.expectedFatLossKgMin)
        val maxFat = UiNumberFormat.decimal(result.expectedFatLossKgMax)
        val minWeight = UiNumberFormat.decimal(result.expectedWeightChangeKgMin)
        val maxWeight = UiNumberFormat.decimal(result.expectedWeightChangeKgMax)
        value.text = "Grasso teorico: $minFat–$maxFat kg"
        detail.text = "Peso teorico: $minWeight–$maxWeight kg · Deficit ${result.plannedDeficitKcal} kcal\n${result.caution}"
    }

    private fun loadWeeklyExpectation(root: View) {
        viewLifecycleOwner.lifecycleScope.launch {
            val profileId = data.activeProfileStore.currentIdOrNull() ?: return@launch
            val monday = LocalDate.now().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
            val snapshot = data.mealPlanRepository.loadLatestSnapshot(profileId, monday.toEpochDay())
            val calculation = data.profileCalculationService.profileSnapshot(profileId)?.calculation
            val maintenance = calculation?.tdeeKcal?.toInt()
            val days = snapshot?.version?.days.orEmpty()
            val records = data.foodConsumptionRepository.all(profileId).first()
            val energy = data.workoutEnergyExpenditureRepository.forRange(profileId, monday.toEpochDay(), monday.plusDays(6).toEpochDay())
                .groupBy { it.exerciseDateEpochDay }
                .mapValues { (_, rows) -> rows.sumOf { it.caloriesKcal.coerceAtLeast(0) } }
            val planned = days.map { it.totalKcal }
            val consumed = days.map { day ->
                val rows = records.filter { it.planVersionId == snapshot?.version?.id && it.plannedDateEpochDay == day.dateEpochDay }
                if (rows.isEmpty()) null else FoodConsumptionMetrics.dayTotals(rows).kcal.toInt()
            }
            val result = WeeklyBodyExpectation.calculate(
                maintenanceKcalByDay = days.map { maintenance },
                plannedFoodKcalByDay = planned,
                exerciseKcalByDay = days.map { energy[it.dateEpochDay] ?: 0 },
                consumedFoodKcalByDay = consumed,
            )
            renderWeeklyExpectation(root, result)
        }
    }

    private fun bindProgressAnalysisCard(root: View) {
        val summary = root.findViewById<View>(R.id.progressSummaryTitle).parent as View
        val parent = summary.parent as LinearLayout
        val index = parent.indexOfChild(summary)
        val header = TextView(requireContext()).apply { text = "Analisi progressi IA"; textSize = 15f; setTextColor(requireContext().getColor(R.color.text_primary)); setTypeface(typeface, android.graphics.Typeface.BOLD) }
        parent.addView(header, index, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(24) })
        val card = LinearLayout(requireContext()).apply { orientation = LinearLayout.VERTICAL; setBackgroundResource(R.drawable.bg_card); setPadding(dp(16), dp(14), dp(16), dp(14)) }
        analysisLastText = bodyText(); analysisNextText = bodyText(); analysisResultText = bodyText().apply { visibility = View.GONE }
        analysisDetailsButton = MaterialButton(requireContext()).apply { text = "Mostra dettagli analisi"; isAllCaps = false; visibility = View.GONE; setOnClickListener { analysisDetailsExpanded = !analysisDetailsExpanded; renderDetailsVisibility() } }
        analysisDetailsContainer = LinearLayout(requireContext()).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE }
        analysisProgress = ProgressBar(requireContext()).apply { visibility = View.GONE }
        analysisButton = MaterialButton(requireContext()).apply { text = "Esegui analisi ora"; isAllCaps = false; setOnClickListener { confirmManualAnalysis() } }
        card.addView(analysisLastText); card.addView(analysisNextText, marginParams(4)); card.addView(analysisResultText, marginParams(10)); card.addView(analysisDetailsButton, LinearLayout.LayoutParams(-1, dp(44)).apply { topMargin = dp(8) }); card.addView(analysisDetailsContainer, marginParams(6)); card.addView(analysisProgress, LinearLayout.LayoutParams(dp(32), dp(32)).apply { topMargin = dp(10) }); card.addView(analysisButton, LinearLayout.LayoutParams(-1, dp(48)).apply { topMargin = dp(12) })
        parent.addView(card, index + 1, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        renderAnalysisStatus()
    }

    private fun bindWeeklyReviewCard(root: View) {
        val summary = root.findViewById<View>(R.id.progressSummaryTitle).parent as View
        val parent = summary.parent as LinearLayout
        val index = parent.indexOfChild(summary)
        parent.addView(TextView(requireContext()).apply { text = "Review settimanale"; textSize = 15f; setTextColor(requireContext().getColor(R.color.text_primary)); setTypeface(typeface, android.graphics.Typeface.BOLD) }, index, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(24) })
        parent.addView(LinearLayout(requireContext()).apply { orientation = LinearLayout.VERTICAL; setBackgroundResource(R.drawable.bg_card); setPadding(dp(16), dp(14), dp(16), dp(14)); setOnClickListener { startActivity(Intent(requireContext(), WeeklyReviewActivity::class.java)) }; addView(TextView(requireContext()).apply { text = "Gli ultimi 7 giorni, in un unico punto"; textSize = 14f; setTextColor(requireContext().getColor(R.color.text_primary)); setTypeface(typeface, android.graphics.Typeface.BOLD) }); addView(TextView(requireContext()).apply { text = "Controlla alimentazione, allenamenti, variazioni corporee e sintesi IA della settimana."; textSize = 13f; setTextColor(requireContext().getColor(R.color.text_secondary)) }, marginParams(6)); addView(MaterialButton(requireContext()).apply { text = "Apri review"; isAllCaps = false; setOnClickListener { startActivity(Intent(requireContext(), WeeklyReviewActivity::class.java)) } }, LinearLayout.LayoutParams(-1, dp(48)).apply { topMargin = dp(12) }) }, index + 1, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
    }

    private fun confirmManualAnalysis() { MaterialAlertDialogBuilder(requireContext()).setTitle("Eseguire l'analisi progressi con IA?").setMessage("L'analisi viene eseguita in background: puoi uscire dalla schermata e ricevere una notifica quando è pronta.").setNegativeButton("Annulla", null).setPositiveButton("Conferma ed esegui") { _, _ -> executeAnalysis() }.show() }

    /**
     * The analysis runs as a background job: leaving this screen, or the process being killed, no
     * longer throws the provider work away. The screen reattaches to the same job on return.
     */
    private fun executeAnalysis() {
        val profileId = data.activeProfileStore.currentIdOrNull() ?: return
        data.aiJobScheduler.enqueue(AiJobType.PROGRESS_ANALYSIS, profileId, analysisJobKey())
    }

    private fun observeAnalysisJob() {
        val profileId = data.activeProfileStore.currentIdOrNull() ?: return
        viewLifecycleOwner.lifecycleScope.launch {
            data.aiJobScheduler.observe(AiJobType.PROGRESS_ANALYSIS, profileId, analysisJobKey()).collect { state ->
                when (state) {
                    AiJobState.Idle -> renderAnalysisRunning(false)
                    AiJobState.Running -> renderAnalysisRunning(true)
                    is AiJobState.Succeeded -> {
                        data.aiJobScheduler.consume(state.id)
                        renderAnalysisRunning(false)
                        data.activeProfileStore.currentIdOrNull()?.let(data.progressAnalysisScheduler::reschedule)
                        renderAnalysisStatus()
                    }
                    is AiJobState.Failed -> {
                        data.aiJobScheduler.consume(state.id)
                        renderAnalysisRunning(false)
                        analysisResultText.visibility = View.VISIBLE
                        analysisResultText.text = state.message
                        analysisDetailsButton.visibility = View.GONE
                    }
                }
            }
        }
    }

    private fun renderAnalysisRunning(running: Boolean) {
        analysisProgress.visibility = if (running) View.VISIBLE else View.GONE
        analysisButton.isEnabled = !running
        analysisButton.text = if (running) "Analisi in corso…" else "Esegui analisi ora"
    }

    private fun analysisJobKey(): String = java.time.LocalDate.now().toEpochDay().toString()

    private fun renderAnalysisStatus(keepTransient: Boolean = false) { val id = data.activeProfileStore.currentIdOrNull(); if (id == null) { analysisLastText.text = "Ultima esecuzione: profilo non disponibile"; analysisNextText.text = "Esecuzione automatica: non pianificata"; analysisButton.isEnabled = false; return }; val prefs = data.progressAnalysisPreferences; val last = prefs.lastSuccessEpochMillis(id); analysisLastText.text = "Ultima esecuzione: ${last?.let(::formatDateTime) ?: "mai"}"; val next = prefs.nextDueEpochMillis(id); analysisNextText.text = if (next == null) "Automatica: dopo la prima analisi · frequenza ${prefs.intervalWeeks} sett." else "Prossima automatica: ${formatDateTime(next)}"; if (!keepTransient) prefs.lastSummary(id)?.let { analysisResultText.visibility = View.VISIBLE; analysisResultText.text = it; renderAnalysisDetails(prefs.lastPatterns(id)) } }
    private fun renderAnalysisDetails(patterns: List<ProgressAnalysisCompactContract.Pattern>) { analysisDetailsContainer.removeAllViews(); if (patterns.isEmpty()) { analysisDetailsButton.visibility = View.GONE; return }; analysisDetailsButton.visibility = View.VISIBLE; patterns.forEach { pattern -> analysisDetailsContainer.addView(TextView(requireContext()).apply { text = "${directionSymbol(pattern.direction)} ${pattern.code.name} · ${pattern.direction.name.lowercase()} · confidenza ${pattern.confidence.name.lowercase()}"; textSize = 13f; setTextColor(requireContext().getColor(R.color.text_secondary)); setPadding(0, dp(5), 0, dp(5)) }) }; renderDetailsVisibility() }
    private fun renderDetailsVisibility() { analysisDetailsContainer.visibility = if (analysisDetailsExpanded) View.VISIBLE else View.GONE; analysisDetailsButton.text = if (analysisDetailsExpanded) "Nascondi dettagli analisi" else "Mostra dettagli analisi" }
    private fun renderSecondary(root: View, valueId: Int, deltaId: Int, metric: com.myfitai.app.ui.progress.ProgressMetricState, unit: String) { root.findViewById<TextView>(valueId).text = metric.value?.let { "${fmt(it)} $unit" } ?: "—"; root.findViewById<TextView>(deltaId).text = metric.delta?.let { signed(it) } ?: "—" }
    private fun renderDateLabels(root: View, metric: com.myfitai.app.ui.progress.ProgressMetricState) { val ids = intArrayOf(R.id.dateLabel1, R.id.dateLabel2, R.id.dateLabel3, R.id.dateLabel4, R.id.dateLabel5); ids.forEachIndexed { index, id -> val point = metric.series.getOrNull(((metric.series.lastIndex * index) / 4).coerceAtLeast(0)); root.findViewById<TextView>(id).text = point?.let { Instant.ofEpochMilli(it.timestamp).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("dd/MM")) } ?: "—" } }
    private fun bodyText() = TextView(requireContext()).apply { textSize = 13f; setTextColor(requireContext().getColor(R.color.text_secondary)) }
    private fun marginParams(top: Int) = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(top) }
    private fun classificationLabel(value: ProgressAnalysisCompactContract.Classification) = value.name.replace('_', ' ').lowercase().replaceFirstChar { it.uppercase() }
    private fun confidenceLabel(value: ProgressAnalysisCompactContract.Confidence) = value.name.lowercase()
    private fun directionSymbol(value: ProgressAnalysisCompactContract.Direction) = when (value) { ProgressAnalysisCompactContract.Direction.FAVORABLE -> "✓"; ProgressAnalysisCompactContract.Direction.UNFAVORABLE -> "!"; ProgressAnalysisCompactContract.Direction.UNCERTAIN -> "•" }
    private fun formatDateTime(epoch: Long) = Instant.ofEpochMilli(epoch).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm", Locale.ITALIAN))
    private fun fmt(value: Float) = String.format(Locale.ITALIAN, "%.1f", value)
    private fun signed(value: Float) = String.format(Locale.ITALIAN, "%+.1f", value)
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
