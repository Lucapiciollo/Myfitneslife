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
        val current: Float,
        val previousDelta: Float,
        val periodDelta: Float,
        val points: List<BodyMeasurementTrendView.Point>,
    )

    private val series = listOf(
        MeasureSeries("Vita", 84f, -1.2f, -3.4f, points(91f, 89.5f, 88f, 86.2f, 85.2f, 84f)),
        MeasureSeries("Torace", 102f, 0.4f, 1.6f, points(100.4f, 100.8f, 101f, 101.4f, 101.6f, 102f)),
        MeasureSeries("Addome", 88f, -0.8f, -2.7f, points(90.7f, 90f, 89.6f, 89f, 88.8f, 88f)),
        MeasureSeries("Braccio", 35.5f, 0.3f, 0.9f, points(34.6f, 34.8f, 35f, 35.2f, 35.2f, 35.5f)),
        MeasureSeries("Coscia", 57.5f, 0.2f, 0.7f, points(56.8f, 57f, 57.1f, 57.2f, 57.3f, 57.5f)),
    )

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
        metricSegment.setSegments(series.map { it.label }, selectedIndex = 0)
        metricSegment.setOnSegmentSelectedListener { renderTrend(it) }

        findViewById<SelectableSegmentView>(R.id.trendRangeSegment).setSegments(
            listOf("1M", "3M", "6M", "1Y"),
            selectedIndex = 2,
        )
        renderTrend(0)
        renderHistory()
    }

    private fun renderTrend(index: Int) {
        val item = series[index]
        findViewById<TextView>(R.id.trendMetricLabel).text = item.label
        findViewById<TextView>(R.id.trendCurrentValue).text = formatCm(item.current)
        findViewById<TextView>(R.id.trendDeltaPrevious).text = "${formatSigned(item.previousDelta)} cm vs precedente"
        findViewById<TextView>(R.id.trendDeltaPeriod).text = "${formatSigned(item.periodDelta)} cm nel periodo"
        findViewById<BodyMeasurementTrendView>(R.id.bodyMeasurementTrendChart).setPoints(item.points)
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
        val labels = listOf("20 Lug", "3 Ago", "10 Ago", "24 Ago", "31 Ago", "14 Set")
        return values.mapIndexed { index, value -> BodyMeasurementTrendView.Point(labels[index], value) }
    }

    private fun formatCm(value: Float): String = if (value % 1f == 0f) "${value.toInt()} cm" else "${value.toString().replace('.', ',')} cm"
    private fun formatSigned(value: Float): String = (if (value > 0) "+" else "") + value.toString().replace('.', ',')
    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
