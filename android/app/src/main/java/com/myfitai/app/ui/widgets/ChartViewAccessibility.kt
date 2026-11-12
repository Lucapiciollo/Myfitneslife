package com.myfitai.app.ui.widgets

import android.content.Context
import android.view.View
import androidx.core.view.ViewCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/**
 * Alternativa testuale di un grafico: descrizione sintetica come contentDescription e, se disponibile,
 * azione di accessibilita' "Mostra tabella dei valori". Il grafico disegnato non viene letto dai servizi
 * (e' solo una resa visiva): l'informazione sta nella descrizione e nella tabella.
 */
class ChartAccessibilityBinder(private val chartView: View) {
    private var actionId: Int = View.NO_ID

    fun bind(summary: String, showTableLabel: String, onShowTable: (() -> Unit)?) {
        chartView.contentDescription = summary
        chartView.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        if (actionId != View.NO_ID) {
            ViewCompat.removeAccessibilityAction(chartView, actionId)
            actionId = View.NO_ID
        }
        if (onShowTable != null) {
            actionId = ViewCompat.addAccessibilityAction(chartView, showTableLabel) { _, _ ->
                onShowTable()
                true
            }
        }
    }
}

/** Tabella dei valori: elenco scorrevole "data: valore", leggibile voce per voce da TalkBack. */
object ChartValuesDialog {
    fun show(context: Context, title: String, rows: List<String>, emptyMessage: String, closeLabel: String) {
        val builder = MaterialAlertDialogBuilder(context).setTitle(title).setPositiveButton(closeLabel, null)
        if (rows.isEmpty()) builder.setMessage(emptyMessage) else builder.setItems(rows.toTypedArray(), null)
        builder.show()
    }
}

/**
 * Distingue le serie anche senza il colore: tratteggio e marcatore pieno/vuoto da [ChartSeriesStyle].
 * Misure da token (dimens): lunghezza/spazio del tratteggio in px, raggio del foro in dp come gli altri raggi.
 */
internal fun info.appdev.charting.data.LineDataSet<info.appdev.charting.data.EntryFloat>.applySeriesStyle(
    index: Int,
    context: Context,
) {
    val style = ChartSeriesStyle.forIndex(index)
    val resources = context.resources
    if (style.dashed) {
        enableDashedLine(
            resources.getDimension(com.myfitai.app.R.dimen.chart_dash_length),
            resources.getDimension(com.myfitai.app.R.dimen.chart_dash_gap),
            0f,
        )
    }
    isDrawCircles = true
    circleRadius = resources.getDimension(com.myfitai.app.R.dimen.chart_point_radius) / resources.displayMetrics.density
    isDrawCircleHoleEnabled = style.hollowMarker
    if (style.hollowMarker) {
        circleHoleRadius = resources.getDimension(com.myfitai.app.R.dimen.chart_point_hole_radius) / resources.displayMetrics.density
        circleHoleColor = context.getColor(com.myfitai.app.R.color.white)
    }
}
