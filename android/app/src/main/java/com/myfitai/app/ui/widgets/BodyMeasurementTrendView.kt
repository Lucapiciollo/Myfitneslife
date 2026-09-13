package com.myfitai.app.ui.widgets

import android.content.Context
import android.graphics.Color
import android.util.AttributeSet
import android.widget.FrameLayout
import com.github.mikephil.charting.charts.LineChart
import com.github.mikephil.charting.components.XAxis
import com.github.mikephil.charting.data.Entry
import com.github.mikephil.charting.data.LineData
import com.github.mikephil.charting.data.LineDataSet
import com.github.mikephil.charting.formatter.ValueFormatter
import com.myfitai.app.R

/**
 * Grafico riutilizzabile per l'andamento delle misure corporee.
 * Incapsula AndroidChart/MPAndroidChart e mantiene lo stile MyFitAI centralizzato.
 */
class BodyMeasurementTrendView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : FrameLayout(context, attrs) {

    data class Point(val label: String, val value: Float)

    private val chart = LineChart(context)

    init {
        addView(chart, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        configureChart()
    }

    private fun configureChart() = with(chart) {
        description.isEnabled = false
        legend.isEnabled = false
        setTouchEnabled(true)
        isDragEnabled = true
        setScaleEnabled(false)
        setPinchZoom(false)
        setDrawGridBackground(false)
        setNoDataText("Nessuna misurazione disponibile")
        setNoDataTextColor(context.getColor(R.color.text_secondary))
        setBackgroundColor(Color.TRANSPARENT)
        extraBottomOffset = 6f
        extraTopOffset = 10f

        axisRight.isEnabled = false
        axisLeft.apply {
            textColor = context.getColor(R.color.text_secondary)
            textSize = 10f
            setDrawAxisLine(false)
            setDrawGridLines(true)
            gridColor = context.getColor(R.color.divider)
            gridLineWidth = 0.6f
        }
        xAxis.apply {
            position = XAxis.XAxisPosition.BOTTOM
            textColor = context.getColor(R.color.text_secondary)
            textSize = 10f
            setDrawAxisLine(false)
            setDrawGridLines(false)
            granularity = 1f
        }
    }

    fun setPoints(points: List<Point>, unit: String = "cm") {
        if (points.isEmpty()) {
            chart.clear()
            chart.invalidate()
            return
        }

        val entries = points.mapIndexed { index, point -> Entry(index.toFloat(), point.value) }
        val dataSet = LineDataSet(entries, "").apply {
            color = context.getColor(R.color.accent_green)
            lineWidth = 2.4f
            mode = LineDataSet.Mode.CUBIC_BEZIER
            cubicIntensity = 0.18f
            setDrawCircles(true)
            circleRadius = 3.2f
            setCircleColor(context.getColor(R.color.accent_green))
            setDrawCircleHole(true)
            circleHoleRadius = 1.5f
            circleHoleColor = context.getColor(R.color.white)
            setDrawValues(false)
            setDrawFilled(true)
            fillColor = context.getColor(R.color.accent_green)
            fillAlpha = 28
            highLightColor = context.getColor(R.color.accent_green_dark)
            highlightLineWidth = 1f
        }

        chart.xAxis.valueFormatter = object : ValueFormatter() {
            override fun getFormattedValue(value: Float): String {
                val index = value.toInt()
                return points.getOrNull(index)?.label.orEmpty()
            }
        }
        chart.axisLeft.valueFormatter = object : ValueFormatter() {
            override fun getFormattedValue(value: Float): String = "${value.toInt()} $unit"
        }

        chart.data = LineData(dataSet)
        chart.notifyDataSetChanged()
        chart.invalidate()
    }
}
