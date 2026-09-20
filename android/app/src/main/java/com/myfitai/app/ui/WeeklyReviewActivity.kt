package com.myfitai.app.ui

import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.button.MaterialButton
import com.myfitai.app.R
import com.myfitai.app.data.AppDataContainer
import com.myfitai.app.domain.review.WeeklyReviewService
import com.myfitai.app.navigation.BottomNavBinder
import com.myfitai.app.ui.review.WeeklyReviewViewModel
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

class WeeklyReviewActivity : BaseShellActivity() {
    private val data by lazy { AppDataContainer.get(this) }
    private val viewModel: WeeklyReviewViewModel by viewModels {
         WeeklyReviewViewModel.Factory(data.weeklyReviewService, data.activeProfileStore, data.aiJobScheduler)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_weekly_review)
        bindBack()
        bindBottom(BottomNavBinder.Tab.PROGRESS)

        findViewById<View>(R.id.prevWeekButton).setOnClickListener { viewModel.previousWeek() }
        findViewById<View>(R.id.nextWeekButton).setOnClickListener { viewModel.nextWeek() }
        findViewById<View>(R.id.generateReviewButton).setOnClickListener {
            confirmAiRequest("La generazione della review nutrizionale settimanale") {
                viewModel.generate()
            }
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.state.collect(::render)
            }
        }
    }

    private fun render(state: WeeklyReviewViewModel.State) {
        findViewById<TextView>(R.id.weekLabel).text = formatWeek(state.weekStart)
        findViewById<View>(R.id.nextWeekButton).isEnabled = state.weekStart.plusWeeks(1).isBefore(currentMonday())

        val metrics = state.metrics
        findViewById<TextView>(R.id.adherenceValue).text = metrics?.adherencePercent?.let { "$it%" } ?: "n/d"
        findViewById<TextView>(R.id.cheatsValue).text = metrics?.cheatCount?.toString() ?: "—"
        findViewById<TextView>(R.id.workoutsValue).text = metrics?.let { "${it.workoutCount}" } ?: "—"
        findViewById<TextView>(R.id.trackingCoverageText).text = when {
            metrics == null -> "Piano seguito: percentuale dei pasti segnati come consumati rispetto a quelli previsti."
            metrics.trackingCoveragePercent == null -> "Nessun pasto consumato registrato: piano seguito n/d."
            else -> "Pasti registrati: ${metrics.trackingCoveragePercent}% degli elementi previsti · ${metrics.consumedMealCount} consumati · ${metrics.skippedMealCount} saltati."
        }

        renderNutrition(metrics)
        renderBodyChanges(metrics)

        val content = state.content
        findViewById<TextView>(R.id.reviewTitle).text = if (state.review != null) "Review disponibile" else "Review non ancora generata"
        findViewById<TextView>(R.id.reviewSummary).text = content?.summary
            ?: "I dati locali della settimana sono disponibili. La sintesi IA viene salvata nello storico solo dopo validazione."
        renderTextList(R.id.observationsContainer, content?.observations.orEmpty())
        renderTextList(R.id.guidanceContainer, content?.nextWeekGuidance.orEmpty())
        findViewById<View>(R.id.observationsHeader).visibility = if (content?.observations.isNullOrEmpty()) View.GONE else View.VISIBLE
        findViewById<View>(R.id.guidanceHeader).visibility = if (content?.nextWeekGuidance.isNullOrEmpty()) View.GONE else View.VISIBLE

        val button = findViewById<MaterialButton>(R.id.generateReviewButton)
        button.isEnabled = !state.loading && !state.generating
        button.text = when {
            state.generating -> "Generazione in corso…"
            state.review != null -> "Rigenera review"
            else -> "Genera review settimanale"
        }
        findViewById<ProgressBar>(R.id.reviewProgress).visibility = if (state.loading || state.generating) View.VISIBLE else View.GONE

        val status = findViewById<TextView>(R.id.statusText)
        val message = state.error ?: state.successMessage
        status.visibility = if (message.isNullOrBlank()) View.GONE else View.VISIBLE
        status.text = message.orEmpty()
    }

    private fun renderNutrition(metrics: WeeklyReviewService.LocalMetrics?) {
        if (metrics != null && !metrics.hasPlan) {
            findViewById<TextView>(R.id.avgKcalValue).text = "Nessun piano"
            findViewById<TextView>(R.id.targetKcalValue).text = "Nessun piano associato a questa settimana"
            findViewById<TextView>(R.id.avgProteinValue).text = "—"
            findViewById<TextView>(R.id.targetProteinValue).text = "Target: —"
            findViewById<TextView>(R.id.avgCarbsValue).text = "—"
            findViewById<TextView>(R.id.targetCarbsValue).text = "Target: —"
            findViewById<TextView>(R.id.avgFatValue).text = "—"
            findViewById<TextView>(R.id.targetFatValue).text = "Target: —"
            return
        }
        findViewById<TextView>(R.id.avgKcalValue).text = NutritionEstimateFormatter.formatEstimatedKcal(metrics?.plannedAverageKcal)
        findViewById<TextView>(R.id.targetKcalValue).text = "Target: ${metrics?.targetKcal ?: "—"}"
        findViewById<TextView>(R.id.avgProteinValue).text = NutritionEstimateFormatter.formatEstimatedMacro(metrics?.plannedAverageProteinG, "g")
        findViewById<TextView>(R.id.targetProteinValue).text = "Target: ${metrics?.targetProteinG?.let(::format1) ?: "—"}"
        findViewById<TextView>(R.id.avgCarbsValue).text = NutritionEstimateFormatter.formatEstimatedMacro(metrics?.plannedAverageCarbsG, "g")
        findViewById<TextView>(R.id.targetCarbsValue).text = "Target: ${metrics?.targetCarbsG?.let(::format1) ?: "—"}"
        findViewById<TextView>(R.id.avgFatValue).text = NutritionEstimateFormatter.formatEstimatedMacro(metrics?.plannedAverageFatG, "g")
        findViewById<TextView>(R.id.targetFatValue).text = "Target: ${metrics?.targetFatG?.let(::format1) ?: "—"}"
    }

    private fun renderBodyChanges(metrics: WeeklyReviewService.LocalMetrics?) {
        val values = buildList {
            metrics?.weightDeltaKg?.let { add("Peso ${signed(it)} kg") }
            metrics?.bodyFatDeltaPoints?.let { add("Grasso ${signed(it)} pp") }
            metrics?.muscleMassDeltaKg?.let { add("Massa muscolare ${signed(it)} kg") }
            metrics?.waistDeltaCm?.let { add("Vita ${signed(it)} cm") }
        }
        findViewById<TextView>(R.id.bodyChangesText).text = if (values.isEmpty()) {
            "Dati insufficienti per calcolare variazioni nella settimana. Servono almeno due rilevazioni della stessa metrica."
        } else values.joinToString(" · ") + "\nVariazioni osservate nello stesso periodo: non dimostrano causalità."
    }

    private fun renderTextList(containerId: Int, values: List<String>) {
        val container = findViewById<LinearLayout>(containerId)
        container.removeAllViews()
        values.forEach { value ->
            container.addView(TextView(this).apply {
                text = "• $value"
                textSize = 13f
                setTextColor(getColor(R.color.text_primary))
                setPadding(0, dp(6), 0, dp(6))
            })
        }
    }

    private fun formatWeek(start: LocalDate): String {
        val end = start.plusDays(6)
        val formatter = DateTimeFormatter.ofPattern("d MMM", Locale.ITALIAN)
        return "${start.format(formatter)} – ${end.format(formatter)} ${end.year}"
    }

    private fun currentMonday(): LocalDate {
        val today = LocalDate.now()
        return today.minusDays((today.dayOfWeek.value - 1).toLong())
    }

    private fun format1(value: Float): String = String.format(Locale.ITALIAN, "%.1f", value)
    private fun signed(value: Float): String = String.format(Locale.ITALIAN, "%+.1f", value)
    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
