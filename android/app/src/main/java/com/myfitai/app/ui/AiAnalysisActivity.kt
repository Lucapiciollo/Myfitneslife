package com.myfitai.app.ui

import android.os.Bundle
import android.widget.TextView
import androidx.lifecycle.lifecycleScope
import com.myfitai.app.R
import com.myfitai.app.data.AppDataContainer
import com.myfitai.app.domain.personalization.PersonalResponseEngine
import com.myfitai.app.navigation.BottomNavBinder
import com.myfitai.app.ui.widgets.SelectableSegmentView
import kotlinx.coroutines.launch
import java.util.Locale

class AiAnalysisActivity : BaseShellActivity() {
    private val data by lazy { AppDataContainer.get(this) }
    private var lookbackDays = 30

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_ai_analysis)
        bindBottom(BottomNavBinder.Tab.MORE)
        bindBack()

        findViewById<SelectableSegmentView>(R.id.analysisRangeSegment).apply {
            setSegments(listOf("7 giorni", "30 giorni", "90 giorni"), 1)
            setOnSegmentSelectedListener { index ->
                lookbackDays = when (index) { 0 -> 7; 2 -> 90; else -> 30 }
                load()
            }
        }
        findViewById<android.view.View>(R.id.deepenButton).setOnClickListener { go(WeeklyReviewActivity::class.java) }
        load()
    }

    private fun load() {
        lifecycleScope.launch {
            val summary = data.personalResponseService.summarizeActiveProfile(lookbackDays = lookbackDays)
            render(summary)
        }
    }

    private fun render(summary: PersonalResponseEngine.Summary?) {
        if (summary == null) {
            findViewById<TextView>(R.id.analysisSummaryTitle).text = "Nessun profilo attivo"
            findViewById<TextView>(R.id.analysisSummaryText).text = "Seleziona un profilo per analizzare lo storico."
            findViewById<TextView>(R.id.analysisPatternsText).text = "—"
            return
        }
        findViewById<TextView>(R.id.analysisSummaryTitle).text = "Ultimi ${summary.lookbackDays} giorni"
        findViewById<TextView>(R.id.analysisSummaryText).text = buildString {
            append("Allenamenti: ${summary.workoutCount}; riposi: ${summary.restDayCount}; sgarri registrati: ${summary.cheatCount}.")
            metric(" Peso", summary.weightDeltaKg, "kg")
            metric(" Grasso", summary.bodyFatDeltaPoints, "punti")
            metric(" Massa muscolare", summary.muscleMassDeltaKg, "kg")
            metric(" Vita", summary.waistDeltaCm, "cm")
        }
        findViewById<TextView>(R.id.analysisPatternsText).text = if (summary.patterns.isEmpty()) {
            "Non ci sono ancora abbastanza evidenze per individuare pattern ricorrenti."
        } else {
            summary.patterns.joinToString("\n\n") { pattern -> "• ${pattern.text} (n=${pattern.evidenceCount})" }
        }
    }

    private fun StringBuilder.metric(label: String, value: Double?, unit: String) {
        if (value != null) append("$label: ${String.format(Locale.ITALIAN, "%+.2f", value)} $unit.")
    }
}
