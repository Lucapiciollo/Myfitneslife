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
 * vince l'id piu alto e, a ulteriore parita, la misura corporea sulla BIA; senza rilevazioni
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
                .thenBy { it.id }
                .thenBy { if (it.source == WeightSource.MISURA) 1 else 0 },
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

    fun resolve(
        bia: List<BiaMeasurementEntity>,
        body: List<BodyMeasurementEntity>,
        profileWeightKg: Float?,
        profileId: Long? = null,
    ): WeightReading = resolve(observations(bia, body, profileId), profileWeightKg)

    private fun valid(value: Float?): Float? = value?.takeIf { it.isFinite() && it > 0f }
}
