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

/** Grafico riutilizzabile per l'andamento delle misure corporee. */
class BodyMeasurementTrendView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : FrameLayout(context, attrs) {

    data class Point(val label: String, val value: Float)

    private val chart = LineChart(context)
    private var points: List<Point> = emptyList()
    private var onPointsChanged: ((List<Point>) -> Unit)? = null

    init {
        addView(chart, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        chart.description.isEnabled = false
        chart.legend.isEnabled = false
        chart.axisRight.isEnabled = false
        chart.axisLeft.isEnabled = true
        chart.axisLeft.textColor = context.getColor(R.color.text_secondary)
        chart.axisLeft.isDrawAxisLine = false
        chart.axisLeft.isDrawGridLines = true
        chart.axisLeft.gridColor = context.getColor(R.color.divider)
        chart.xAxis.isEnabled = false
        chart.setTouchEnabled(true)
        chart.isPinchZoom = false
        chart.setBackgroundColor(Color.TRANSPARENT)
        chart.setDrawGridBackground(false)
        chart.setDrawBorders(false)
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
        val dataSet = LineDataSet<EntryFloat>(entries, "measurement").apply {
            color = context.getColor(R.color.accent_green_dark)
            lineWidth = 2.4f
            isDrawCircles = false
            isDrawValues = false
            isHighlight = true
            lineMode = LineDataSet.Mode.CUBIC_BEZIER
            isDrawFilled = true
            fillColor = context.getColor(R.color.accent_green)
            fillAlpha = 42
        }
        chart.data = LineData(dataSet)
        chart.invalidate()
        onPointsChanged?.invoke(points)
    }
}
