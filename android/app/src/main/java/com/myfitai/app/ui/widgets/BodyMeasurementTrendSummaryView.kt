package com.myfitai.app.ui.widgets

import android.content.Context
import android.util.AttributeSet
import androidx.appcompat.widget.AppCompatTextView
import com.myfitai.app.R
import com.myfitai.app.domain.body.MeasurementTrendInterpreter

/** Deterministic note shown under the body-measurement trend chart. */
class BodyMeasurementTrendSummaryView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : AppCompatTextView(context, attrs) {

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        post {
            val chart = rootView.findViewById<BodyMeasurementTrendView>(R.id.bodyMeasurementTrendChart)
            chart?.setOnPointsChangedListener(::render)
        }
    }

    private fun render(points: List<BodyMeasurementTrendView.Point>) {
        val label = rootView.findViewById<android.widget.TextView>(R.id.trendMetricLabel)?.text?.toString().orEmpty().ifBlank { "La misura" }
        val delta = if (points.size >= 2) points.last().value - points.first().value else null
        val result = MeasurementTrendInterpreter.interpret(
            label = label,
            delta = delta,
            pointCount = points.size,
            favorableDirection = null,
            stableThreshold = 0.2f,
        )
        text = "${result.title}\n${result.message}"
    }
}
