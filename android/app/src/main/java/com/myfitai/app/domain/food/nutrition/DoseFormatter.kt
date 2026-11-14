package com.myfitai.app.domain.food.nutrition

import kotlin.math.abs
import kotlin.math.floor

/**
 * Equivalenza NOTA tra un'unità pratica e i grammi (es. cucchiaio = 13,5 g di olio, uovo = 50 g).
 *
 * @param name nome singolare ("cucchiaino", "uovo").
 * @param gramsPerUnit grammi di una unità; deve venire da una fonte nota (catalogo), mai stimato.
 * @param pluralName plurale ("cucchiaini", "uova"); default = [name].
 * @param qualifier suffisso fisso dopo l'unità, per le confezioni: "da 30 g" -> "1 bustina da 30 g".
 */
data class UnitEquivalent(
    val name: String,
    val gramsPerUnit: Double,
    val pluralName: String = name,
    val qualifier: String? = null,
)

/**
 * Formattazione deterministica di displayDose a partire dai grammi.
 *
 * Usa SOLO le equivalenze fornite in ingresso: se nessuna rappresenta i grammi entro la tolleranza con una
 * quantità leggibile (interi, mezzi, quarti sotto l'unità) il risultato è solo in grammi ("150 g").
 * Non inventa mai pesi per le unità.
 */
object DoseFormatter {
    /** Scarto relativo massimo tra grammi reali e grammi dell'unità arrotondata alla quantità leggibile. */
    const val DEFAULT_TOLERANCE = 0.03

    private const val MAX_UNITS = 12.0
    private const val EPS = 1e-9

    fun format(
        grams: Double,
        equivalents: List<UnitEquivalent> = emptyList(),
        tolerance: Double = DEFAULT_TOLERANCE,
    ): String {
        if (grams <= 0.0) return formatGrams(0.0)
        var best: Candidate? = null
        for (eq in equivalents) {
            if (eq.gramsPerUnit <= 0.0 || eq.name.isBlank()) continue
            val c = candidate(grams, eq, tolerance) ?: continue
            if (best == null || c.isBetterThan(best)) best = c
        }
        return best?.render() ?: formatGrams(grams)
    }

    /** "150 g"; sotto i 10 g un decimale con la virgola ("2,5 g"). */
    fun formatGrams(grams: Double): String {
        val g = if (grams < 0.0) 0.0 else grams
        if (g >= 10.0) return "${Math.round(g)} g"
        val tenths = Math.round(g * 10.0)
        val whole = tenths / 10
        val dec = (tenths % 10).toInt()
        return if (dec == 0) "$whole g" else "$whole,$dec g"
    }

    private class Candidate(
        val eq: UnitEquivalent,
        val quarters: Int,
        val relDev: Double,
    ) {
        val complexity: Int = when {
            quarters % 4 == 0 -> 0
            quarters % 2 == 0 -> 1
            else -> 2
        }

        fun isBetterThan(o: Candidate): Boolean {
            if (complexity != o.complexity) return complexity < o.complexity
            if (abs(relDev - o.relDev) > EPS) return relDev < o.relDev
            return eq.gramsPerUnit > o.eq.gramsPerUnit
        }

        fun render(): String {
            val whole = quarters / 4
            val frac = when (quarters % 4) {
                1 -> "1/4"
                2 -> "1/2"
                3 -> "3/4"
                else -> null
            }
            val qty = when {
                frac == null -> "$whole"
                whole == 0 -> frac
                else -> "$whole e $frac"
            }
            val singular = quarters <= 4
            val unit = if (singular) eq.name else eq.pluralName
            return buildString {
                append(qty).append(' ').append(unit)
                eq.qualifier?.takeIf { it.isNotBlank() }?.let { append(' ').append(it) }
            }
        }
    }

    private fun candidate(grams: Double, eq: UnitEquivalent, tolerance: Double): Candidate? {
        val count = grams / eq.gramsPerUnit
        if (count < 0.25 - EPS || count > MAX_UNITS) return null
        // Griglia leggibile: quarti sotto 1, mezzi da 1 a 5, interi oltre.
        val grid = when {
            count < 1.0 -> 0.25
            count < 5.0 -> 0.5
            else -> 1.0
        }
        val q = floor(count / grid + 0.5) * grid
        if (q < 0.25 - EPS) return null
        val relDev = abs(q * eq.gramsPerUnit - grams) / grams
        if (relDev > tolerance + EPS) return null
        return Candidate(eq, Math.round(q * 4.0).toInt(), relDev)
    }
}
