package com.myfitai.app.ui.widgets

import android.content.Context
import android.util.AttributeSet
import android.widget.FrameLayout
import com.myfitai.app.R
import info.appdev.charting.charts.LineChart
import info.appdev.charting.data.EntryFloat
import info.appdev.charting.data.LineData
import info.appdev.charting.data.LineDataSet
import info.appdev.charting.interfaces.datasets.ILineDataSet

/** Grafico comparativo per peso, grasso corporeo e massa muscolare nella Dashboard. */
class WeightTrendChartView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : FrameLayout(context, attrs) {

    private val chart = LineChart(context)

    init {
        addView(chart, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        chart.description.isEnabled = false
        chart.legend.isEnabled = true
        chart.legend.textColor = context.getColor(R.color.text_secondary)
        chart.legend.textSize = chartDp(R.dimen.chart_legend_text_size)
        chart.axisRight.isEnabled = false
        chart.axisLeft.isEnabled = false
        chart.xAxis.isEnabled = false
        chart.setTouchEnabled(false)
        chart.isPinchZoom = false
        chart.setBackgroundColor(context.getColor(R.color.chart_transparent))
        chart.setDrawGridBackground(false)
        chart.setDrawBorders(false)
    }

    /** Mostra l'asse Y con i valori numerici (nascosto di default, usato in Home). */
    fun showYAxisLabels() {
        chart.axisLeft.isEnabled = true
        chart.axisLeft.textColor = context.getColor(R.color.text_muted)
        chart.axisLeft.isDrawGridLines = false
        chart.axisLeft.isDrawAxisLine = false
    }

    fun setData(values: List<Float>) = setSeries(listOf("Peso" to values))

    fun setSeries(series: List<Pair<String, List<Float>>>) {
        val colors = context.resources.obtainTypedArray(R.array.chart_series_palette)
        val dataSets = series.mapIndexedNotNull { index, (label, values) ->
            if (values.isEmpty()) return@mapIndexedNotNull null
            val baseline = values.firstOrNull()?.takeIf { it != 0f } ?: return@mapIndexedNotNull null
            val entries = values.mapIndexed { pointIndex, value ->
                EntryFloat(
                    pointIndex.toFloat(),
                    ((value / baseline) - 1f) * context.resources.getInteger(R.integer.chart_percent_multiplier),
                )
            }.toMutableList()
            LineDataSet<EntryFloat>(entries, label).apply {
                color = colors.getColor(index % colors.length(), context.getColor(R.color.text_primary))
                lineWidth = chartDp(R.dimen.chart_line_width)
                isDrawCircles = false
                isDrawValues = false
                isHighlight = false
                lineMode = LineDataSet.Mode.CUBIC_BEZIER
                isDrawFilled = false
            }
        }
        colors.recycle()
        chart.data = LineData(dataSets.toMutableList() as MutableList<ILineDataSet<EntryFloat>>)
        chart.invalidate()
    }

    private fun chartDp(dimenRes: Int): Float =
        resources.getDimension(dimenRes) / resources.displayMetrics.density
}
