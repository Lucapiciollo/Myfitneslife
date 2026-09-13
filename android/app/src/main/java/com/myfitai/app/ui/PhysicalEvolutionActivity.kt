package com.myfitai.app.ui

import android.os.Bundle
import com.myfitai.app.R
import com.myfitai.app.navigation.BottomNavBinder
import com.myfitai.app.ui.widgets.SelectableSegmentView
import com.myfitai.app.ui.widgets.TimeRangeSelectorView
import com.myfitai.app.ui.widgets.WeightTrendChartView

class PhysicalEvolutionActivity : BaseShellActivity() {

    private data class MetricData(val label: String, val value: String, val delta: String, val trend: List<Float>)

    private val metrics = listOf(
        MetricData("Peso", "78,4 kg", "-3,6 kg (-4,4%)", listOf(82f, 81.2f, 80.5f, 80.1f, 79.4f, 79f, 78.6f, 78.9f, 78.4f, 78.7f, 78.2f, 78.4f)),
        MetricData("Grasso corporeo", "14,2 %", "-2,8 % (-16,5%)", listOf(17f, 16.6f, 16.2f, 15.8f, 15.5f, 15.1f, 14.8f, 14.9f, 14.6f, 14.4f, 14.3f, 14.2f)),
        MetricData("Massa muscolare", "66,8 kg", "+0,6 kg (+0,9%)", listOf(66.2f, 66.3f, 66.1f, 66.4f, 66.5f, 66.3f, 66.6f, 66.5f, 66.7f, 66.6f, 66.8f, 66.8f)),
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_physical_evolution)
        bindBack()
        bindBottom(BottomNavBinder.Tab.PROGRESS)

        val chart = findViewById<WeightTrendChartView>(R.id.evolutionChart)
        chart.showYAxisLabels()
        val labelView = findViewById<android.widget.TextView>(R.id.metricLabel)
        val valueView = findViewById<android.widget.TextView>(R.id.metricValue)
        val deltaView = findViewById<android.widget.TextView>(R.id.metricDelta)

        fun applyMetric(index: Int) {
            val metric = metrics[index]
            labelView.text = metric.label
            valueView.text = metric.value
            deltaView.text = metric.delta
            chart.setData(metric.trend)
        }
        applyMetric(0)

        findViewById<SelectableSegmentView>(R.id.metricSegment).apply {
            setSegments(listOf("Peso", "Grasso", "Massa muscolare"), selectedIndex = 0)
            setOnSegmentSelectedListener { index -> applyMetric(index) }
        }

        findViewById<TimeRangeSelectorView>(R.id.timeRangeSelector).setRanges(listOf("1M", "3M", "6M", "1Y"), selectedIndex = 1)
    }
}
