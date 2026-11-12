package com.myfitai.app.ui.widgets

import java.util.Locale

/**
 * Funzioni pure per rendere leggibili i grafici senza vederli: descrizione sintetica, tabella dei valori,
 * legenda/unita' dell'asse, frase di spiegazione e stile delle serie. Nessuna dipendenza da Android: testabile in JVM.
 */
object ChartAccessibility {

    data class Point(val dateText: String, val value: Float)

    /**
     * Modelli (da strings.xml) della descrizione sintetica.
     * - empty: %1$s etichetta
     * - single: %1$s etichetta, %2$s data, %3$s valore con unita'
     * - multiple: %1$s etichetta, %2$d numero, %3$s dal, %4$s al, %5$s primo valore, %6$s ultimo valore con unita'
     */
    data class SummaryTemplates(val empty: String, val single: String, val multiple: String)

    /** Segmenti Peso / Grasso / Massa muscolare di Progresso e relativa unita'. */
    enum class EvolutionMetric(val unit: String) {
        WEIGHT("kg"), BODY_FAT("%"), MUSCLE("kg");

        companion object {
            fun fromIndex(index: Int): EvolutionMetric = entries.getOrElse(index) { WEIGHT }
        }
    }

    fun number(value: Float): String = String.format(Locale.ITALIAN, "%.1f", value)

    /** "80,5 kg", "23,0%": per la percentuale l'unita' resta attaccata al numero. */
    fun withUnit(value: Float, unit: String): String =
        if (unit == "%") "${number(value)}%" else "${number(value)} $unit"

    fun axisValue(value: Float, unit: String): String = withUnit(value, unit)

    fun legend(label: String, unit: String): String = "$label ($unit)"

    /** Punti in ordine cronologico; descrizione tipo "Peso, 3 rilevazioni dal 01/10/2026 al 12/11/2026, da 82,4 a 79,9 kg". */
    fun summary(templates: SummaryTemplates, label: String, unit: String, points: List<Point>): String = when (points.size) {
        0 -> String.format(Locale.ITALIAN, templates.empty, label)
        1 -> String.format(Locale.ITALIAN, templates.single, label, points[0].dateText, withUnit(points[0].value, unit))
        else -> String.format(
            Locale.ITALIAN, templates.multiple,
            label, points.size, points.first().dateText, points.last().dateText,
            number(points.first().value), withUnit(points.last().value, unit),
        )
    }

    /** Una riga per rilevazione, "data: valore unita'", nell'ordine del grafico. */
    fun tableRows(unit: String, points: List<Point>): List<String> =
        points.map { "${it.dateText}: ${withUnit(it.value, unit)}" }

    /** Dice se il grafico mostra valori reali o variazioni normalizzate. */
    fun explanation(normalized: Boolean, real: String, normalizedText: String, label: String, unit: String): String =
        String.format(Locale.ITALIAN, if (normalized) normalizedText else real, label, unit)
}

/** Aspetto di una serie oltre al colore: tratteggio e marcatore pieno/vuoto, a rotazione. */
object ChartSeriesStyle {
    data class Style(val dashed: Boolean, val hollowMarker: Boolean)

    private val cycle = listOf(
        Style(dashed = false, hollowMarker = false),
        Style(dashed = true, hollowMarker = true),
        Style(dashed = false, hollowMarker = true),
        Style(dashed = true, hollowMarker = false),
    )

    fun forIndex(index: Int): Style = cycle[Math.floorMod(index, cycle.size)]
}
