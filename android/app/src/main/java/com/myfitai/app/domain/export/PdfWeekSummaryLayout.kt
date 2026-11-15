package com.myfitai.app.domain.export

import kotlin.math.ceil
import kotlin.math.max

/**
 * Layout puro (senza Canvas) del riquadro "Settimana"/"Periodo" nella copertina del PDF del piano.
 *
 * L'altezza del riquadro dipende solo dal numero di righe: nessuno spazio vuoto fisso.
 * Se le righe non entrano nella pagina in una colonna sola, vengono distribuite su piu colonne,
 * cosi il riquadro non supera mai il limite inferiore della pagina.
 */
internal object PdfWeekSummaryLayout {
    /** Distanza dal bordo alto del riquadro alla prima riga (include il titolo del riquadro). */
    const val FIRST_ROW_OFFSET = 65f
    const val ROW_PITCH = 30f
    const val BOTTOM_PADDING = 30f
    private const val COLUMN_GAP = 16f

    data class Cell(val column: Int, val row: Int, val left: Float, val right: Float, val baselineOffset: Float)

    data class Result(val columns: Int, val rowsPerColumn: Int, val height: Float, val cells: List<Cell>)

    fun maxRowsPerColumn(top: Float, pageBottomLimit: Float): Int =
        max(1, ((pageBottomLimit - top - FIRST_ROW_OFFSET - BOTTOM_PADDING) / ROW_PITCH).toInt() + 1)

    fun layout(rowCount: Int, top: Float, pageBottomLimit: Float, innerLeft: Float, innerRight: Float): Result {
        val maxRows = maxRowsPerColumn(top, pageBottomLimit)
        val columns = max(1, ceil(rowCount / maxRows.toFloat()).toInt())
        val rowsPerColumn = if (rowCount == 0) 0 else ceil(rowCount / columns.toFloat()).toInt()
        val height = if (rowsPerColumn == 0) FIRST_ROW_OFFSET else FIRST_ROW_OFFSET + ROW_PITCH * rowsPerColumn
        val columnWidth = (innerRight - innerLeft - COLUMN_GAP * (columns - 1)) / columns
        val cells = (0 until rowCount).map { index ->
            val column = index / rowsPerColumn
            val row = index % rowsPerColumn
            val left = innerLeft + column * (columnWidth + COLUMN_GAP)
            Cell(column, row, left, left + columnWidth, FIRST_ROW_OFFSET + row * ROW_PITCH)
        }
        return Result(columns, rowsPerColumn, height, cells)
    }
}
