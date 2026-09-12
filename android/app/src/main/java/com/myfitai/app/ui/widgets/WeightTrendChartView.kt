package com.myfitai.app.ui.widgets

import android.content.Context
import android.graphics.Color
import android.util.AttributeSet
import android.widget.FrameLayout
import com.myfitai.app.R
import info.appdev.charting.charts.LineChart
import info.appdev.charting.data.EntryFloat
import info.appdev.charting.data.LineData
import info.appdev.charting.data.LineDataSet

/** Grafico a linea dati-driven (MPAndroidChart/AppDevNext) per l'andamento peso in Dashboard. */
class WeightTrendChartView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : FrameLayout(context, attrs) {

    private val chart = LineChart(context)

    init {
        addView(chart, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        chart.description.isEnabled = false
        chart.legend.isEnabled = false
        chart.axisRight.isEnabled = false
        chart.axisLeft.isEnabled = false
        chart.xAxis.isEnabled = false
        chart.setTouchEnabled(false)
        chart.isPinchZoom = false
        chart.setBackgroundColor(Color.TRANSPARENT)
        chart.setDrawGridBackground(false)
        chart.setDrawBorders(false)
    }

    fun setData(values: List<Float>) {
        val entries = values.mapIndexed { index, value -> EntryFloat(index.toFloat(), value) }.toMutableList()
        val dataSet = LineDataSet<EntryFloat>(entries, "weight").apply {
            color = context.getColor(R.color.accent_green_dark)
            lineWidth = 2f
            isDrawCircles = false
            isDrawValues = false
            isHighlight = false
            lineMode = LineDataSet.Mode.CUBIC_BEZIER
            isDrawFilled = true
            fillColor = context.getColor(R.color.accent_green)
            fillAlpha = 60
        }
        chart.data = LineData(dataSet)
        chart.invalidate()
    }
}
