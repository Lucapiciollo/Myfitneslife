package com.myfitai.app.ui

import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.button.MaterialButton
import com.myfitai.app.R
import com.myfitai.app.data.local.entity.BiaMeasurementEntity
import com.myfitai.app.domain.body.BiaMeasurementQualityEngine
import com.myfitai.app.ui.widgets.BodyMeasurementTrendView
import com.myfitai.app.ui.widgets.SelectableSegmentView
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

class BiaTrendBinder(
    private val activity: BiaActivity,
    private val segment: SelectableSegmentView,
    private val newMeasurementContainer: View,
    private val historyContainer: View,
) {
    private enum class Metric(
        val label: String,
        val unit: String,
        val value: (BiaMeasurementEntity) -> Float?,
    ) {
        WEIGHT("Peso", "kg", { it.weightKg }),
        BODY_FAT("Grasso corporeo", "%", { it.bodyFatPercent }),
        MUSCLE("Massa muscolare", "kg", { it.muscleMassKg }),
        SKELETAL("Muscolo scheletrico", "kg", { it.skeletalMuscleKg }),
        WATER("Acqua corporea", "%", { it.bodyWaterPercent }),
        VISCERAL("Grasso viscerale", "", { it.visceralFatLevel }),
        BMR("BMR", "kcal", { it.bmrKcal }),
    }

    private var history: List<BiaMeasurementEntity> = emptyList()
    private var selectedMetric = Metric.WEIGHT
    private var selectedRangeIndex = 2
    private val trendContainer = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        visibility = View.GONE
        setPadding(0, dp(16), 0, 0)
    }
    private val metricButton = MaterialButton(activity).apply { isAllCaps = false }
    private val currentText = text(15f, true)
    private val previousText = text(13f, false)
    private val periodText = text(13f, false)
    private val qualityText = text(12f, false)
    private val rangeSelector = SelectableSegmentView(activity)
    private val chart = BodyMeasurementTrendView(activity)

    init {
        val parent = historyContainer.parent as LinearLayout
        val index = parent.indexOfChild(historyContainer)
        parent.addView(trendContainer, index + 1, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ))

        metricButton.setOnClickListener { showMetricChooser() }
        trendContainer.addView(metricButton, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            dp(48),
        ))
        trendContainer.addView(currentText, marginTop(14))
        trendContainer.addView(previousText, marginTop(6))
        trendContainer.addView(periodText, marginTop(4))
        trendContainer.addView(qualityText, marginTop(10))
        trendContainer.addView(rangeSelector, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            dp(44),
        ).apply { topMargin = dp(14) })
        trendContainer.addView(chart, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            dp(240),
        ).apply { topMargin = dp(12) })

        rangeSelector.setSegments(listOf("1M", "3M", "6M", "1Y"), selectedRangeIndex)
        rangeSelector.setOnSegmentSelectedListener {
            selectedRangeIndex = it
            render()
        }

        segment.setSegments(listOf("Nuova misurazione", "Andamento", "Storico"), 0)
        segment.setOnSegmentSelectedListener { index ->
            newMeasurementContainer.visibility = if (index == 0) View.VISIBLE else View.GONE
            trendContainer.visibility = if (index == 1) View.VISIBLE else View.GONE
            historyContainer.visibility = if (index == 2) View.VISIBLE else View.GONE
            if (index == 1) render()
        }
        render()
    }

    fun update(values: List<BiaMeasurementEntity>) {
        history = values
        render()
    }

    private fun showMetricChooser() {
        val metrics = Metric.entries
        val labels = metrics.map { metric ->
            val mark = if (metric == selectedMetric) " ✓" else ""
            "${metric.label}$mark"
        }.toTypedArray()
        MaterialAlertDialogBuilder(activity)
            .setTitle("Metrica BIA")
            .setSingleChoiceItems(labels, metrics.indexOf(selectedMetric)) { dialog, which ->
                selectedMetric = metrics[which]
                dialog.dismiss()
                render()
            }
            .setNegativeButton("Annulla", null)
            .show()
    }

    private fun render() {
        metricButton.text = "Metrica: ${selectedMetric.label}"
        val points = history.asReversed().mapNotNull { item -> selectedMetric.value(item)?.let { item to it } }
        val current = points.lastOrNull()
        currentText.text = if (current == null) {
            "Valore attuale: —"
        } else {
            "Valore attuale: ${format(current.second, selectedMetric.unit)}"
        }

        val previousDelta = if (points.size >= 2) points.last().second - points[points.lastIndex - 1].second else null
        previousText.text = previousDelta?.let {
            "${signed(it)} ${selectedMetric.unit} rispetto alla precedente disponibile"
        } ?: "Confronto precedente: dati insufficienti"

        val anchorDate = history.firstOrNull()?.let(::dateOf) ?: LocalDate.now()
        val from = when (selectedRangeIndex) {
            0 -> anchorDate.minusMonths(1)
            1 -> anchorDate.minusMonths(3)
            2 -> anchorDate.minusMonths(6)
            else -> anchorDate.minusYears(1)
        }
        val periodPoints = points.filter { !dateOf(it.first).isBefore(from) }
        val periodDelta = if (periodPoints.size >= 2) periodPoints.last().second - periodPoints.first().second else null
        periodText.text = periodDelta?.let {
            "${signed(it)} ${selectedMetric.unit} nel periodo selezionato"
        } ?: "Trend periodo: dati insufficienti"

        val chartPoints = periodPoints.map { (item, value) ->
            BodyMeasurementTrendView.Point(
                Instant.ofEpochMilli(item.measuredAtEpochMillis)
                    .atZone(ZoneId.systemDefault())
                    .format(DateTimeFormatter.ofPattern("dd/MM", Locale.ITALIAN)),
                value,
            )
        }
        chart.setPoints(chartPoints)

        val latest = history.firstOrNull()
        val previous = history.drop(1).firstOrNull()
        val quality = latest?.let { BiaMeasurementQualityEngine.evaluate(it, previous) }
        qualityText.text = quality?.let {
            "Qualità confronto: ${BiaMeasurementQualityEngine.label(it.level)} (${it.score}/100)\n" +
                it.reasons.take(3).joinToString(" · ")
        } ?: "Qualità confronto: non valutabile"
    }

    private fun dateOf(item: BiaMeasurementEntity): LocalDate =
        Instant.ofEpochMilli(item.measuredAtEpochMillis).atZone(ZoneId.systemDefault()).toLocalDate()

    private fun format(value: Float, unit: String): String {
        val number = if (value % 1f == 0f) value.toInt().toString() else String.format(Locale.ITALIAN, "%.1f", value)
        return if (unit.isBlank()) number else "$number $unit"
    }

    private fun signed(value: Float): String = String.format(Locale.ITALIAN, "%+.1f", value)

    private fun text(size: Float, bold: Boolean) = TextView(activity).apply {
        textSize = size
        setTextColor(activity.getColor(if (bold) R.color.text_primary else R.color.text_secondary))
        if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
    }

    private fun marginTop(value: Int) = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT,
        LinearLayout.LayoutParams.WRAP_CONTENT,
    ).apply { topMargin = dp(value) }

    private fun dp(value: Int) = (value * activity.resources.displayMetrics.density).toInt()
}
