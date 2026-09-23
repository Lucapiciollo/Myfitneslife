package com.myfitai.app.ui

import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.content.res.ColorStateList
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.myfitai.app.R
import com.myfitai.app.data.local.entity.BiaMeasurementEntity
import com.myfitai.app.domain.body.BiaMeasurementQualityEngine
import com.myfitai.app.domain.body.MeasurementTrendInterpreter
import com.myfitai.app.ui.widgets.BodyMeasurementTrendView
import com.myfitai.app.ui.widgets.SelectableSegmentView
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

class BiaTrendBinder(
    private val activity: BiaActivity,
    parent: LinearLayout,
    insertIndex: Int,
) {
    private enum class Metric(
        val label: String,
        val unit: String,
        val favorableDirection: MeasurementTrendInterpreter.Direction?,
        val stableThreshold: Float,
        val value: (BiaMeasurementEntity) -> Float?,
    ) {
        WEIGHT("Peso", "kg", null, 0.2f, { it.weightKg }),
        BODY_FAT("Grasso corporeo", "%", MeasurementTrendInterpreter.Direction.DOWN, 0.2f, { it.bodyFatPercent }),
        MUSCLE("Massa muscolare", "kg", MeasurementTrendInterpreter.Direction.UP, 0.2f, { it.muscleMassKg }),
        SKELETAL("Muscolo scheletrico", "kg", MeasurementTrendInterpreter.Direction.UP, 0.2f, { it.skeletalMuscleKg }),
        WATER("Acqua corporea", "%", null, 0.3f, { it.bodyWaterPercent }),
        VISCERAL("Grasso viscerale", "", MeasurementTrendInterpreter.Direction.DOWN, 0.2f, { it.visceralFatLevel }),
        BMR("BMR", "kcal", null, 20f, { it.bmrKcal }),
    }

    private var history: List<BiaMeasurementEntity> = emptyList()
    private var selectedMetric = Metric.WEIGHT
    private var selectedRangeIndex = 2

    val view = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        visibility = View.GONE
        setPadding(0, dp(16), 0, 0)
    }

    private val metricButton = MaterialButton(activity).apply {
        isAllCaps = false
        minHeight = dp(44)
        cornerRadius = dp(12)
        strokeWidth = dp(1)
        strokeColor = ColorStateList.valueOf(activity.getColor(R.color.divider))
        backgroundTintList = ColorStateList.valueOf(activity.getColor(R.color.white))
        setTextColor(activity.getColor(R.color.text_primary))
        setPadding(dp(12), 0, dp(12), 0)
    }
    private val currentText = text(15f, true)
    private val previousText = text(13f, false)
    private val periodText = text(13f, false)
    private val qualityText = text(12f, false)
    private val rangeSelector = SelectableSegmentView(activity)
    private val chart = BodyMeasurementTrendView(activity)
    private val trendNote = TextView(activity).apply {
        textSize = 13f
        setTextColor(activity.getColor(R.color.text_secondary))
        setBackgroundResource(R.drawable.bg_card)
        setPadding(dp(14), dp(12), dp(14), dp(12))
    }

    init {
        parent.addView(
            view,
            insertIndex,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT),
        )
        metricButton.setOnClickListener { showMetricChooser() }
        view.addView(metricButton, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(48)))
        view.addView(currentText, marginTop(14))
        view.addView(previousText, marginTop(6))
        view.addView(periodText, marginTop(4))
        view.addView(qualityText, marginTop(10))
        view.addView(rangeSelector, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(44)).apply { topMargin = dp(14) })
        view.addView(chart, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(240)).apply { topMargin = dp(12) })
        view.addView(trendNote, marginTop(10))

        rangeSelector.setSegments(listOf("1M", "3M", "6M", "1Y"), selectedRangeIndex)
        rangeSelector.setOnSegmentSelectedListener {
            selectedRangeIndex = it
            render()
        }
        render()
    }

    fun show() {
        view.visibility = View.VISIBLE
        render()
    }

    fun hide() {
        view.visibility = View.GONE
    }

    fun update(values: List<BiaMeasurementEntity>) {
        history = values
        render()
    }

    private fun showMetricChooser() {
        val metrics = Metric.entries
        val labels = metrics.map { metric ->
            "${metric.label}${if (metric == selectedMetric) " ✓" else ""}"
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
        currentText.text = current?.let { "Valore attuale: ${format(it.second, selectedMetric.unit)}" } ?: "Valore attuale: —"

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

        chart.setPoints(periodPoints.map { (item, value) ->
            BodyMeasurementTrendView.Point(
                Instant.ofEpochMilli(item.measuredAtEpochMillis)
                    .atZone(ZoneId.systemDefault())
                    .format(DateTimeFormatter.ofPattern("dd/MM", Locale.ITALIAN)),
                value,
            )
        })

        val latest = history.firstOrNull()
        val previous = history.drop(1).firstOrNull()
        val quality = latest?.let { BiaMeasurementQualityEngine.evaluate(it, previous) }
        qualityText.text = quality?.let {
            "Qualità confronto: ${BiaMeasurementQualityEngine.label(it.level)} (${it.score}/100)\n" +
                it.reasons.take(3).joinToString(" · ")
        } ?: "Qualità confronto: non valutabile"

        val interpretation = MeasurementTrendInterpreter.interpret(
            label = selectedMetric.label,
            delta = periodDelta,
            pointCount = periodPoints.size,
            favorableDirection = selectedMetric.favorableDirection,
            stableThreshold = selectedMetric.stableThreshold,
            qualityLabel = quality?.let { BiaMeasurementQualityEngine.label(it.level).lowercase(Locale.ITALIAN) },
        )
        trendNote.text = "${interpretation.title}\n${interpretation.message}"
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
