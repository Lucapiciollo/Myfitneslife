package com.myfitai.app.ui

import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import com.myfitai.app.R
import com.myfitai.app.navigation.BottomNavBinder
import com.myfitai.app.ui.widgets.BodyMeasurementTrendView
import com.myfitai.app.ui.widgets.MeasurementRowView
import com.myfitai.app.ui.widgets.SelectableSegmentView

class BodyMeasuresActivity : BaseShellActivity() {

    private data class MeasureSeries(
        val label: String,
        val points: List<BodyMeasurementTrendView.Point>,
    ) {
        val current: Float get() = points.last().value
        val previousDelta: Float get() = if (points.size > 1) points.last().value - points[points.lastIndex - 1].value else 0f
    }

    private val series = listOf(
        MeasureSeries("Vita", points(93f, 92.2f, 91f, 89.5f, 88f, 86.8f, 86.2f, 85.2f, 84f)),
        MeasureSeries("Torace", points(99.6f, 99.8f, 100.4f, 100.8f, 101f, 101.2f, 101.4f, 101.6f, 102f)),
        MeasureSeries("Addome", points(92.4f, 91.8f, 90.7f, 90f, 89.6f, 89.2f, 89f, 88.8f, 88f)),
        MeasureSeries("Braccio", points(34.1f, 34.3f, 34.6f, 34.8f, 35f, 35.1f, 35.2f, 35.2f, 35.5f)),
        MeasureSeries("Coscia", points(56.4f, 56.5f, 56.8f, 57f, 57.1f, 57.1f, 57.2f, 57.3f, 57.5f)),
    )

    private var selectedMetricIndex = 0
    private var selectedRangeIndex = 2

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_body_measures)
        bindBottom(BottomNavBinder.Tab.PROGRESS)
        bindBack()

        findViewById<View>(R.id.saveButton).setOnClickListener { go(NewBodyMeasurementActivity::class.java) }

        val topSegment = findViewById<SelectableSegmentView>(R.id.measureSegment)
        val measureContent = findViewById<View>(R.id.measureContent)
        val trendContent = findViewById<View>(R.id.trendContent)
        val historyContent = findViewById<View>(R.id.historyContent)

        topSegment.setSegments(listOf("Misura", "Andamento", "Storico"), selectedIndex = 0)
        topSegment.setOnSegmentSelectedListener { index ->
            measureContent.visibility = if (index == 0) View.VISIBLE else View.GONE
            trendContent.visibility = if (index == 1) View.VISIBLE else View.GONE
            historyContent.visibility = if (index == 2) View.VISIBLE else View.GONE
        }

        val rows = listOf(
            R.id.rowChest to ("Torace" to "102 cm"),
            R.id.rowWaist to ("Vita" to "84 cm"),
            R.id.rowAbdomen to ("Addome" to "88 cm"),
            R.id.rowArmLeft to ("Braccio sx" to "36 cm"),
            R.id.rowArmRight to ("Braccio dx" to "35 cm"),
            R.id.rowThighLeft to ("Coscia sx" to "58 cm"),
            R.id.rowThighRight to ("Coscia dx" to "57 cm"),
            R.id.rowCalfLeft to ("Polpaccio sx" to "37 cm"),
            R.id.rowCalfRight to ("Polpaccio dx" to "37 cm"),
        )
        rows.forEach { (id, pair) ->
            findViewById<MeasurementRowView>(id).apply {
                setLabel(pair.first)
                setValue(pair.second)
            }
        }

        val metricSegment = findViewById<SelectableSegmentView>(R.id.trendMetricSegment)
        metricSegment.setSegments(series.map { it.label }, selectedIndex = selectedMetricIndex)
        metricSegment.setOnSegmentSelectedListener { index ->
            selectedMetricIndex = index
            renderTrend()
        }

        val rangeSegment = findViewById<SelectableSegmentView>(R.id.trendRangeSegment)
        rangeSegment.setSegments(listOf("1M", "3M", "6M", "1Y"), selectedIndex = selectedRangeIndex)
        rangeSegment.setOnSegmentSelectedListener { index ->
            selectedRangeIndex = index
            renderTrend()
        }

        renderTrend()
        renderHistory()
    }

    private fun renderTrend() {
        val item = series[selectedMetricIndex]
        val visiblePoints = filterPointsForRange(item.points, selectedRangeIndex)
        val periodDelta = if (visiblePoints.size > 1) visiblePoints.last().value - visiblePoints.first().value else 0f

        findViewById<TextView>(R.id.trendMetricLabel).text = item.label
        findViewById<TextView>(R.id.trendCurrentValue).text = formatCm(item.current)
        findViewById<TextView>(R.id.trendDeltaPrevious).text = "${formatSigned(item.previousDelta)} cm vs precedente"
        findViewById<TextView>(R.id.trendDeltaPeriod).text = "${formatSigned(periodDelta)} cm nel periodo"
        findViewById<BodyMeasurementTrendView>(R.id.bodyMeasurementTrendChart).setPoints(visiblePoints)
    }

    private fun filterPointsForRange(
        points: List<BodyMeasurementTrendView.Point>,
        rangeIndex: Int,
    ): List<BodyMeasurementTrendView.Point> {
        val maxPoints = when (rangeIndex) {
            0 -> 2   // circa 1 mese nel dataset mock
            1 -> 4   // circa 3 mesi
            2 -> 7   // circa 6 mesi
            else -> points.size
        }
        return points.takeLast(maxPoints.coerceAtMost(points.size))
    }

    private fun renderHistory() {
        val container = findViewById<LinearLayout>(R.id.historyList)
        container.removeAllViews()
        listOf(
            Triple("14 Set 2026", "Vita 84 cm · Torace 102 cm · Addome 88 cm", "-1,2 cm vita"),
            Triple("31 Ago 2026", "Vita 85,2 cm · Torace 101,6 cm · Addome 88,8 cm", "-1,0 cm vita"),
            Triple("10 Ago 2026", "Vita 86,2 cm · Torace 101,4 cm · Addome 89 cm", "-1,8 cm vita"),
            Triple("20 Lug 2026", "Vita 88 cm · Torace 101 cm · Addome 89,6 cm", "-1,5 cm vita"),
        ).forEach { (date, values, delta) ->
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(16), dp(12), dp(16), dp(12))
                background = getDrawable(R.drawable.bg_card)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply { bottomMargin = dp(8) }
            }
            card.addView(TextView(this).apply {
                text = date
                setTextColor(getColor(R.color.text_primary))
                textSize = 15f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
            })
            card.addView(TextView(this).apply {
                text = values
                setTextColor(getColor(R.color.text_secondary))
                textSize = 12f
                setPadding(0, dp(4), 0, 0)
            })
            card.addView(TextView(this).apply {
                text = delta
                setTextColor(getColor(R.color.semantic_positive))
                textSize = 12f
                setPadding(0, dp(6), 0, 0)
            })
            container.addView(card)
        }
    }

    private fun points(vararg values: Float): List<BodyMeasurementTrendView.Point> {
        val labels = listOf("Gen", "Feb", "Mar", "Apr", "Mag", "Giu", "Lug", "Ago", "Set")
        return values.mapIndexed { index, value -> BodyMeasurementTrendView.Point(labels[index], value) }
    }

    private fun formatCm(value: Float): String = if (value % 1f == 0f) "${value.toInt()} cm" else "${value.toString().replace('.', ',')} cm"
    private fun formatSigned(value: Float): String = (if (value > 0) "+" else "") + value.toString().replace('.', ',')
    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
