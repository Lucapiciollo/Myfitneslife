package com.myfitai.app.ui

import android.os.Bundle
import android.content.Intent
import android.widget.TextView
import androidx.activity.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.myfitai.app.R
import com.myfitai.app.data.AppDataContainer
import com.myfitai.app.navigation.BottomNavBinder
import com.myfitai.app.ui.progress.PhysicalEvolutionState
import com.myfitai.app.ui.progress.PhysicalEvolutionViewModel
import com.myfitai.app.ui.progress.ProgressMetricState
import com.myfitai.app.ui.widgets.SelectableSegmentView
import com.myfitai.app.ui.widgets.TimeRangeSelectorView
import com.myfitai.app.ui.widgets.WeightTrendChartView
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

class PhysicalEvolutionActivity : BaseShellActivity() {
    private val data by lazy { AppDataContainer.get(this) }
    private val viewModel: PhysicalEvolutionViewModel by viewModels {
        PhysicalEvolutionViewModel.Factory(data.biaRepository, data.activeProfileStore)
    }
    private var metricIndex = 0
    private var rangeIndex = 1
    private var latestState = PhysicalEvolutionState()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_physical_evolution)
        bindBack()
        bindBottom(BottomNavBinder.Tab.PROGRESS)

        findViewById<WeightTrendChartView>(R.id.evolutionChart).showYAxisLabels()
        findViewById<SelectableSegmentView>(R.id.metricSegment).apply {
            setSegments(listOf("Peso", "Grasso", "Massa muscolare"), 0)
            setOnSegmentSelectedListener { metricIndex = it; render(latestState) }
        }
        findViewById<TimeRangeSelectorView>(R.id.timeRangeSelector).apply {
            setRanges(listOf("1M", "3M", "6M", "1Y"), 1)
            setOnRangeSelectedListener { rangeIndex = it; render(latestState) }
        }
        findViewById<android.view.View>(R.id.addBiaButton).setOnClickListener {
            startActivity(Intent(this, BiaActivity::class.java))
        }
        findViewById<android.view.View>(R.id.addBodyMeasurementButton).setOnClickListener {
            startActivity(Intent(this, BodyMeasuresActivity::class.java))
        }
        findViewById<android.view.View>(R.id.historyBiaButton).setOnClickListener {
            startActivity(Intent(this, BiaActivity::class.java).putExtra(BiaActivity.EXTRA_OPEN_HISTORY, true))
        }
        findViewById<android.view.View>(R.id.historyBodyButton).setOnClickListener {
            startActivity(Intent(this, BodyMeasuresActivity::class.java).putExtra(BodyMeasuresActivity.EXTRA_OPEN_HISTORY, true))
        }
        findViewById<android.view.View>(R.id.historyAllButton).setOnClickListener {
            startActivity(Intent(this, HistoryActivity::class.java))
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.state.collect { latestState = it; render(it) }
            }
        }
    }

    private fun render(state: PhysicalEvolutionState) {
        val selected = when (metricIndex) { 1 -> state.bodyFat; 2 -> state.muscle; else -> state.weight }
        val filtered = viewModel.filtered(selected, rangeIndex)
        val unit = if (metricIndex == 1) "%" else "kg"
        findViewById<TextView>(R.id.metricLabel).text = listOf("Peso", "Grasso corporeo", "Massa muscolare")[metricIndex]
        findViewById<TextView>(R.id.metricValue).text = filtered.value?.let { "${fmt(it)} $unit" } ?: "—"
        findViewById<TextView>(R.id.metricDelta).text = filtered.delta?.let { "${signed(it)} $unit nel periodo" } ?: "Dati insufficienti"
        findViewById<WeightTrendChartView>(R.id.evolutionChart).setData(filtered.series.map { it.value })
        renderDateLabels(filtered)
        renderSecondary(R.id.otherIndicatorFatValue, R.id.otherIndicatorFatDelta, state.bodyFat, "%")
        renderSecondary(R.id.otherIndicatorMuscleValue, R.id.otherIndicatorMuscleDelta, state.muscle, "kg")
        renderSecondary(R.id.otherIndicatorWaterValue, R.id.otherIndicatorWaterDelta, state.bodyWater, "%")
        findViewById<android.view.View>(R.id.visualComparisonSection).visibility = android.view.View.GONE
        findViewById<TextView>(R.id.progressSummaryTitle).text = if (filtered.series.size >= 2) "Trend basato sulle rilevazioni registrate" else "Servono più rilevazioni"
        findViewById<TextView>(R.id.progressSummaryText).text = if (filtered.series.size >= 2) "I valori mostrati derivano esclusivamente dallo storico BIA reale del profilo attivo." else "Aggiungi almeno due rilevazioni comparabili per visualizzare un andamento affidabile."
    }

    private fun renderSecondary(valueId: Int, deltaId: Int, metric: ProgressMetricState, unit: String) {
        findViewById<TextView>(valueId).text = metric.value?.let { "${fmt(it)} $unit" } ?: "—"
        findViewById<TextView>(deltaId).text = metric.delta?.let { signed(it) } ?: "—"
    }

    private fun renderDateLabels(metric: ProgressMetricState) {
        val ids = intArrayOf(R.id.dateLabel1, R.id.dateLabel2, R.id.dateLabel3, R.id.dateLabel4, R.id.dateLabel5)
        val points = metric.series
        ids.forEachIndexed { index, id ->
            val point = if (points.isEmpty()) null else points[((points.lastIndex * index) / 4).coerceIn(0, points.lastIndex)]
            findViewById<TextView>(id).text = point?.let {
                Instant.ofEpochMilli(it.timestamp).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("dd/MM"))
            } ?: "—"
        }
    }

    private fun fmt(v: Float) = String.format(Locale.ITALIAN, "%.1f", v)
    private fun signed(v: Float) = String.format(Locale.ITALIAN, "%+.1f", v)
}
