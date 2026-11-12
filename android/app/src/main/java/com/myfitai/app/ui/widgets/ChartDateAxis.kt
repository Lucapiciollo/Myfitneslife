package com.myfitai.app.ui.widgets

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Funzioni pure per le etichette dell'asse delle date dei grafici: ogni etichetta corrisponde a un punto reale,
 * senza ripetizioni e in ordine crescente. Con N punti si mostrano al massimo N etichette distinte.
 * Nessuna dipendenza da Android: testabile in JVM.
 */
object ChartDateAxis {

    data class Label(val index: Int, val text: String)

    private const val MILLIS_PER_DAY = 86_400_000L
    private const val DAYS_PER_YEAR = 365L

    /**
     * Sceglie gli indici dei punti da etichettare: al massimo min(N, [maxLabels]) indici distribuiti in modo uniforme
     * (primo e ultimo inclusi), crescenti, scartando quelli con testo uguale a quello gia' scelto.
     */
    fun selectIndices(labels: List<String>, maxLabels: Int): List<Int> {
        val count = labels.size
        if (count == 0 || maxLabels <= 0) return emptyList()
        val slots = minOf(count, maxLabels)
        val candidates = (0 until slots).map { slot ->
            if (slots == 1) 0 else (slot * (count - 1).toDouble() / (slots - 1)).roundToInt()
        }.distinct()
        val seen = HashSet<String>()
        return candidates.filter { seen.add(labels[it]) }
    }

    /** Testi delle date dei punti: dd/MM, oppure dd/MM/yy se tra primo e ultimo punto passa piu' di un anno. */
    fun formatTimestamps(timestampsMillis: List<Long>, zone: ZoneId): List<String> {
        if (timestampsMillis.isEmpty()) return emptyList()
        val spanDays = (timestampsMillis.max() - timestampsMillis.min()) / MILLIS_PER_DAY
        val pattern = if (spanDays > DAYS_PER_YEAR) "dd/MM/yy" else "dd/MM"
        val formatter = DateTimeFormatter.ofPattern(pattern, Locale.ITALIAN)
        return timestampsMillis.map { Instant.ofEpochMilli(it).atZone(zone).format(formatter) }
    }

    /** Etichette finali (indice del punto + testo) per i punti con le date indicate. */
    fun labels(timestampsMillis: List<Long>, zone: ZoneId, maxLabels: Int): List<Label> {
        val texts = formatTimestamps(timestampsMillis, zone)
        return selectIndices(texts, maxLabels).map { Label(it, texts[it]) }
    }
}
