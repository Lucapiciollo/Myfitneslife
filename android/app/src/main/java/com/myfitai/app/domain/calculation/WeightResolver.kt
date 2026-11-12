package com.myfitai.app.domain.calculation

import com.myfitai.app.data.local.entity.BiaMeasurementEntity
import com.myfitai.app.data.local.entity.BodyMeasurementEntity

/** Provenienza del peso effettivamente usato da motore locale, UI e contesto IA. */
enum class WeightSource { BIA, MISURA, PROFILO, NESSUNO }

/**
 * Peso effettivo con fonte esplicita. [measuredAtEpochMillis] esiste solo per BIA/MISURA:
 * il fallback sul profilo non ha una data di rilevazione e non ne riceve una inventata.
 */
data class WeightReading(
    val valueKg: Float?,
    val measuredAtEpochMillis: Long?,
    val source: WeightSource,
)

/**
 * Unica regola di scelta del peso. Il motore (ProfileCalculationService) e le schermate leggono
 * lo stesso risultato: nessuna duplicazione della logica nelle Activity/ViewModel.
 *
 * Regole: valori non finiti o <= 0 ignorati; vince la rilevazione piu recente; a pari timestamp
 * vince la misura corporea sulla BIA (gli id di tabelle diverse non sono confrontabili) e, tra righe
 * della stessa tabella, l'id piu alto; senza rilevazioni
 * valide si ripiega sul peso del profilo (mai sovrascritto) e infine su NESSUNO.
 */
object WeightResolver {

    data class Observation(
        val measuredAtEpochMillis: Long,
        val id: Long,
        val valueKg: Float,
        val source: WeightSource,
    )

    /** Rilevazioni valide ordinate dalla piu vecchia alla piu recente secondo le regole di pari-data. */
    fun observations(
        bia: List<BiaMeasurementEntity>,
        body: List<BodyMeasurementEntity>,
        profileId: Long? = null,
    ): List<Observation> = (
        bia.filter { profileId == null || it.profileId == profileId }
            .mapNotNull { row -> valid(row.weightKg)?.let { Observation(row.measuredAtEpochMillis, row.id, it, WeightSource.BIA) } } +
            body.filter { profileId == null || it.profileId == profileId }
                .mapNotNull { row -> valid(row.weightKg)?.let { Observation(row.measuredAtEpochMillis, row.id, it, WeightSource.MISURA) } }
        ).sortedWith(
            compareBy<Observation> { it.measuredAtEpochMillis }
                .thenBy { if (it.source == WeightSource.MISURA) 1 else 0 }
                .thenBy { it.id },
        )

    fun resolve(
        observations: List<Observation>,
        profileWeightKg: Float?,
    ): WeightReading {
        observations.lastOrNull()?.let {
            return WeightReading(it.valueKg, it.measuredAtEpochMillis, it.source)
        }
        valid(profileWeightKg)?.let { return WeightReading(it, null, WeightSource.PROFILO) }
        return WeightReading(null, null, WeightSource.NESSUNO)
    }

    /** Serie per grafici/delta: stessi punti e stesso ordine da cui deriva il valore corrente. */
    data class Series(
        val reading: WeightReading,
        val points: List<Pair<Long, Float>>,
        val deltaFromPrevious: Float?,
    )

    /**
     * [filter] restringe i punti (es. intervallo del grafico). Il delta e valorizzato solo se l'ultimo punto
     * filtrato e proprio la rilevazione corrente, cosi valore, fonte e delta non possono divergere.
     */
    fun series(
        observations: List<Observation>,
        profileWeightKg: Float?,
        filter: (List<Pair<Long, Float>>) -> List<Pair<Long, Float>> = { it },
    ): Series {
        val reading = resolve(observations, profileWeightKg)
        // A pari timestamp resta solo la lettura risolta (l'ultima nell'ordine): il delta e contro il punto precedente nel tempo.
        val points = filter(
            observations.groupBy { it.measuredAtEpochMillis }
                .map { (timestamp, sameTime) -> timestamp to sameTime.last().valueKg },
        )
        val last = points.lastOrNull()
        val delta = if (last != null && points.size >= 2 && last.first == reading.measuredAtEpochMillis && last.second == reading.valueKg) {
            last.second - points[points.lastIndex - 1].second
        } else null
        return Series(reading, points, delta)
    }

    fun resolve(
        bia: List<BiaMeasurementEntity>,
        body: List<BodyMeasurementEntity>,
        profileWeightKg: Float?,
        profileId: Long? = null,
    ): WeightReading = resolve(observations(bia, body, profileId), profileWeightKg)

    private fun valid(value: Float?): Float? = value?.takeIf { it.isFinite() && it > 0f }
}
