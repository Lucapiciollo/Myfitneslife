package com.myfitai.app.ui.widgets

import android.content.Context
import android.util.AttributeSet
import android.widget.FrameLayout
import com.myfitai.app.R
import info.appdev.charting.charts.LineChart
import info.appdev.charting.data.EntryFloat
import info.appdev.charting.data.LineData
import info.appdev.charting.data.LineDataSet
import info.appdev.charting.components.AxisBase
import info.appdev.charting.formatter.IAxisValueFormatter
import java.util.Locale

/** Grafico riutilizzabile per l'andamento delle misure corporee. */
class BodyMeasurementTrendView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : FrameLayout(context, attrs) {

    data class Point(val label: String, val value: Float)
    data class Series(val label: String, val points: List<Point>)

    private val chart = LineChart(context)
    private val accessibilityBinder = ChartAccessibilityBinder(this)
    private var points: List<Point> = emptyList()
    private var onPointsChanged: ((List<Point>) -> Unit)? = null

    /** True once the first real-series render has been requested, including a legitimate empty state. */
    fun hasReceivedRealSeries(): Boolean = chart.xAxis.isEnabled

    fun hasRealSeriesData(): Boolean = chart.data?.dataSets?.any { it.entryCount > 0 } == true

    init {
        addView(chart, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        // Il grafico disegnato non e' leggibile dai servizi di accessibilita': vale la descrizione del contenitore.
        chart.importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        chart.description.isEnabled = false
        chart.legend.isEnabled = true
        chart.legend.textColor = context.getColor(R.color.text_secondary)
        chart.legend.textSize = chartDp(R.dimen.chart_axis_text_size)
        chart.axisRight.isEnabled = false
        chart.axisLeft.isEnabled = true
        chart.axisLeft.textColor = context.getColor(R.color.text_secondary)
        chart.axisLeft.isDrawAxisLine = false
        chart.axisLeft.isDrawGridLines = true
        chart.axisLeft.gridColor = context.getColor(R.color.divider)
        chart.xAxis.isEnabled = false
        chart.setTouchEnabled(true)
        chart.isPinchZoom = false
        chart.setBackgroundColor(context.getColor(R.color.chart_transparent))
        chart.setDrawGridBackground(false)
        chart.setDrawBorders(false)
        chart.setNoDataTextColor(context.getColor(R.color.chart_no_data_text))
    }

    fun setRealSeries(series: Series?, unit: String) {
        chart.xAxis.isEnabled = true
        chart.xAxis.textColor = context.getColor(R.color.text_secondary)
        chart.xAxis.isDrawGridLines = false
        chart.xAxis.labelRotationAngle = context.resources.getInteger(R.integer.chart_x_axis_label_rotation_degrees).toFloat()
        chart.xAxis.granularity = context.resources.getInteger(R.integer.chart_x_axis_granularity).toFloat()
        applyDateAxis(series?.points.orEmpty())
        chart.axisLeft.valueFormatter = object : IAxisValueFormatter {
            override fun getFormattedValue(value: Float, axis: AxisBase?): String =
                if (unit == "%") resources.getString(R.string.chart_percent_value_format, value)
                else resources.getString(R.string.chart_decimal_value_format, value)
        }
        if (series == null || series.points.isEmpty()) {
            chart.clear()
            chart.invalidate()
            return
        }
        val entries = series.points.mapIndexed { index, point -> EntryFloat(index.toFloat(), point.value) }.toMutableList()
        val dataSet = LineDataSet<EntryFloat>(entries, "$unit").apply {
            color = context.getColor(R.color.accent_green_dark)
            lineWidth = chartDp(R.dimen.chart_line_width_emphasis)
            isDrawCircles = true
            circleRadius = chartDp(R.dimen.chart_point_radius)
            isDrawValues = false
            isHighlight = true
            lineMode = LineDataSet.Mode.CUBIC_BEZIER
            isDrawFilled = true
            fillColor = context.getColor(R.color.accent_green)
            fillAlpha = resources.getInteger(R.integer.chart_area_fill_alpha_value)
        }
        chart.data = LineData(dataSet)
        chart.invalidate()
    }

    fun setOnPointsChangedListener(listener: (List<Point>) -> Unit) {
        onPointsChanged = listener
        listener(points)
    }

    fun setPoints(points: List<Point>) {
        this.points = points
        if (points.isEmpty()) {
            chart.clear()
            chart.invalidate()
            onPointsChanged?.invoke(points)
            return
        }
        val entries = points.mapIndexed { index, point -> EntryFloat(index.toFloat(), point.value) }.toMutableList()
        val dataSet = LineDataSet<EntryFloat>(entries, context.getString(R.string.chart_series_default_label)).apply {
            color = context.getColor(R.color.accent_green_dark)
            lineWidth = chartDp(R.dimen.chart_line_width_emphasis)
            isDrawCircles = true
            circleRadius = chartDp(R.dimen.chart_point_radius)
            isDrawValues = false
            isHighlight = true
            lineMode = LineDataSet.Mode.CUBIC_BEZIER
            isDrawFilled = true
            fillColor = context.getColor(R.color.accent_green)
            fillAlpha = resources.getInteger(R.integer.chart_area_fill_alpha_value)
        }
        chart.data = LineData(dataSet)
        chart.invalidate()
        onPointsChanged?.invoke(points)
    }

    /** Descrizione sintetica e azione "Mostra tabella dei valori" per i servizi di accessibilita'. */
    fun bindAccessibility(summary: String, showTableLabel: String, onShowTable: (() -> Unit)?) =
        accessibilityBinder.bind(summary, showTableLabel, onShowTable)

    /**
     * Una sola metrica con valori reali: legenda = metrica scelta con unita', asse Y con unita', asse delle date visibile.
     * Le altre metriche restano fuori dal grafico.
     */
    fun setMetricSeries(label: String, unit: String, metricPoints: List<Point>) {
        this.points = metricPoints
        chart.xAxis.isEnabled = true
        chart.xAxis.textColor = context.getColor(R.color.text_secondary)
        chart.xAxis.isDrawGridLines = false
        chart.xAxis.position = info.appdev.charting.components.XAxis.XAxisPosition.BOTTOM
        chart.xAxis.labelRotationAngle = context.resources.getInteger(R.integer.chart_x_axis_label_rotation_degrees).toFloat()
        chart.xAxis.granularity = context.resources.getInteger(R.integer.chart_x_axis_granularity).toFloat()
        applyDateAxis(metricPoints)
        chart.axisLeft.valueFormatter = object : IAxisValueFormatter {
            override fun getFormattedValue(value: Float, axis: AxisBase?): String = ChartAccessibility.axisValue(value, unit)
        }
        if (metricPoints.isEmpty()) {
            chart.clear()
            chart.invalidate()
            onPointsChanged?.invoke(metricPoints)
            return
        }
        val entries = metricPoints.mapIndexed { index, point -> EntryFloat(index.toFloat(), point.value) }.toMutableList()
        val dataSet = LineDataSet<EntryFloat>(entries, ChartAccessibility.legend(label, unit)).apply {
            color = context.getColor(R.color.accent_green_dark)
            lineWidth = chartDp(R.dimen.chart_line_width_emphasis)
            isDrawValues = false
            isHighlight = true
            lineMode = LineDataSet.Mode.CUBIC_BEZIER
            isDrawFilled = false
            applySeriesStyle(0, context)
        }
        chart.data = LineData(dataSet)
        chart.invalidate()
        onPointsChanged?.invoke(metricPoints)
    }

    fun setNormalizedSeries(series: List<Series>) {        val colors = resources.obtainTypedArray(R.array.chart_series_palette)
        val dataSets = series.mapIndexedNotNull { index, item ->
            val baseline = item.points.firstOrNull()?.value?.takeIf { it != 0f } ?: return@mapIndexedNotNull null
            val entries = item.points.mapIndexed { pointIndex, point ->
                EntryFloat(
                    pointIndex.toFloat(),
                    ((point.value / baseline) - 1f) * resources.getInteger(R.integer.chart_percent_multiplier),
                )
            }.toMutableList()
            LineDataSet<EntryFloat>(entries, item.label).apply {
                color = colors.getColor(index % colors.length(), context.getColor(R.color.text_primary))
                lineWidth = chartDp(R.dimen.chart_line_width)
                isDrawValues = false
                isHighlight = false
                lineMode = LineDataSet.Mode.CUBIC_BEZIER
                isDrawFilled = false
                applySeriesStyle(index, context)
            }
        }
        colors.recycle()
        chart.data = LineData(dataSets.toMutableList() as MutableList<info.appdev.charting.interfaces.datasets.ILineDataSet<EntryFloat>>)
        chart.invalidate()
    }

    /** Asse delle date: solo etichette di punti reali, distinte e in ordine (al massimo una per punto), mai ripetute. */
    private fun applyDateAxis(axisPoints: List<Point>) {
        val shownLabels = ChartDateAxis.selectIndices(
            axisPoints.map { it.label },
            context.resources.getInteger(R.integer.chart_x_axis_label_count),
        ).associateWith { axisPoints[it].label }
        chart.xAxis.setLabelCount(shownLabels.size.coerceAtLeast(1), false)
        chart.xAxis.valueFormatter = object : IAxisValueFormatter {
            override fun getFormattedValue(value: Float, axis: AxisBase?): String {
                val index = Math.round(value)
                return if (kotlin.math.abs(value - index) < AXIS_INDEX_EPSILON) shownLabels[index].orEmpty() else ""
            }
        }
    }

    private fun chartDp(dimenRes: Int): Float =
        resources.getDimension(dimenRes) / resources.displayMetrics.density

    private companion object {
        const val AXIS_INDEX_EPSILON = 0.001f
    }
}
