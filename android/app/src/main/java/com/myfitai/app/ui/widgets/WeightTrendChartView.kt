package com.myfitai.app.ui.widgets

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import com.myfitai.app.R

/** Grafico a linea dati-driven per l'andamento peso in Dashboard. */
class WeightTrendChartView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private var values: List<Float> = emptyList()

    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 5f
        color = context.getColor(R.color.accent_green_dark)
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f
        color = context.getColor(R.color.divider)
    }

    fun setData(newValues: List<Float>) {
        values = newValues
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (values.size < 2) return
        val w = width.toFloat()
        val h = height.toFloat()
        val min = values.min()
        val max = values.max()
        val range = (max - min).takeIf { it > 0f } ?: 1f
        val stepX = w / (values.size - 1)

        val gridLines = 4
        for (i in 0..gridLines) {
            val x = w * i / gridLines
            canvas.drawLine(x, 0f, x, h, gridPaint)
        }

        val linePath = Path()
        val fillPath = Path()
        values.forEachIndexed { index, value ->
            val x = index * stepX
            val y = h - ((value - min) / range) * h
            if (index == 0) {
                linePath.moveTo(x, y)
                fillPath.moveTo(x, h)
                fillPath.lineTo(x, y)
            } else {
                linePath.lineTo(x, y)
                fillPath.lineTo(x, y)
            }
        }
        fillPath.lineTo(w, h)
        fillPath.close()

        fillPaint.shader = LinearGradient(
            0f, 0f, 0f, h,
            context.getColor(R.color.chart_fill_start),
            context.getColor(R.color.chart_fill_end),
            Shader.TileMode.CLAMP,
        )
        canvas.drawPath(fillPath, fillPaint)
        canvas.drawPath(linePath, linePaint)
    }
}
