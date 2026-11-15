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
 * Usa SOLO le equivalenze fornite in ingresso. Una dose e' "naturale" se e' un numero INTERO o MEZZO di unita'
 * (1 cucchiaino, 1/2 cucchiaio, 1 e 1/2 uova) entro la tolleranza sul peso: i quarti (1/4, 3/4) non si producono
 * mai. Tra piu' unita' valide vince quella con il numero intero, poi il mezzo; a parita' lo scarto minore, poi
 * l'unita' piu' grande. Cosi' 9 g diventano "2 cucchiaini" (non 3/4 di cucchiaio) e, se nessuna unita' funziona,
 * il risultato e' solo in grammi ("150 g"). Non inventa mai pesi per le unita'.
 */
object DoseFormatter {
    /** Scarto relativo massimo tra grammi reali e grammi dell'unita' arrotondata alla quantita' leggibile. */
    const val DEFAULT_TOLERANCE = 0.03

    private const val MAX_UNITS = 12.0
    private const val MIN_UNITS = 0.5
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

    /** [halves] = quantita' in mezzi (2 = una unita', 3 = una e mezza). */
    private class Candidate(
        val eq: UnitEquivalent,
        val halves: Int,
        val relDev: Double,
    ) {
        /** 0 = numero intero, 1 = con il mezzo. */
        val complexity: Int = if (halves % 2 == 0) 0 else 1

        fun isBetterThan(o: Candidate): Boolean {
            if (complexity != o.complexity) return complexity < o.complexity
            if (abs(relDev - o.relDev) > EPS) return relDev < o.relDev
            return eq.gramsPerUnit > o.eq.gramsPerUnit
        }

        fun render(): String {
            val whole = halves / 2
            val qty = when {
                halves % 2 == 0 -> "$whole"
                whole == 0 -> "1/2"
                else -> "$whole e 1/2"
            }
            val singular = halves <= 2
            val unit = if (singular) eq.name else eq.pluralName
            return buildString {
                append(qty).append(' ').append(unit)
                eq.qualifier?.takeIf { it.isNotBlank() }?.let { append(' ').append(it) }
            }
        }
    }

    private fun candidate(grams: Double, eq: UnitEquivalent, tolerance: Double): Candidate? {
        val count = grams / eq.gramsPerUnit
        if (count < MIN_UNITS / 2.0 - EPS || count > MAX_UNITS) return null
        // Griglia leggibile: mezzi fino a 5 unita', interi oltre.
        val grid = if (count < 5.0) 0.5 else 1.0
        val q = floor(count / grid + 0.5) * grid
        if (q < MIN_UNITS - EPS) return null
        val relDev = abs(q * eq.gramsPerUnit - grams) / grams
        if (relDev > tolerance + EPS) return null
        return Candidate(eq, Math.round(q * 2.0).toInt(), relDev)
    }
}