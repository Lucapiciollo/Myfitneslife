package com.myfitai.app.domain.food

import com.myfitai.app.data.repository.BiaRepository
import com.myfitai.app.data.repository.BodyMeasurementRepository
import com.myfitai.app.data.repository.UserProfileRepository
import kotlinx.coroutines.flow.first

/**
 * Avvia il consiglio dell'obiettivo nutrizionale con i migliori dati disponibili.
 *
 * La BIA e le circonferenze aumentano la qualità/confidenza della raccomandazione,
 * ma non sono prerequisiti: un nuovo profilo deve poter ricevere un consiglio
 * anche usando solo dati anagrafici, peso/altezza e livello di attività.
 */
class NutritionPathTrigger(
    private val profiles: UserProfileRepository,
    private val bia: BiaRepository,
    private val body: BodyMeasurementRepository,
    private val scheduler: NutritionPathScheduler,
) {
    suspend fun maybeEnqueue(profileId: Long): String? {
        val profile = profiles.get(profileId) ?: return null
        val latestBia = bia.all(profileId).first().maxByOrNull { it.measuredAtEpochMillis }
        val latestBody = body.all(profileId).first().maxByOrNull { it.measuredAtEpochMillis }

        val key = buildString {
            append(profile.updatedAtEpochMillis)
            append('-').append(latestBia?.measuredAtEpochMillis ?: 0L)
            append('-').append(latestBody?.measuredAtEpochMillis ?: 0L)
        }
        scheduler.enqueue(profileId, key)
        return key
    }
}
