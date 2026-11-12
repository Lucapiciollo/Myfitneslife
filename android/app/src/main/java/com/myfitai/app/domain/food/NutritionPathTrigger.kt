package com.myfitai.app.domain.food

import com.myfitai.app.data.profile.AiAutomationPreferences
import com.myfitai.app.data.repository.BiaRepository
import com.myfitai.app.data.repository.BodyMeasurementRepository
import com.myfitai.app.data.repository.UserProfileRepository
import com.myfitai.app.ai.AiRuntimeService
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
    private val aiRuntime: AiRuntimeService? = null,
    private val automation: AiAutomationPreferences? = null,
) {
    /** Cosa fare dopo il salvataggio di una BIA o di una misura. */
    enum class Action { NONE, SUGGEST, ENQUEUE }

    /** Esito per la UI: la schermata decide come mostrarlo, mai una navigazione automatica. */
    sealed interface Advice {
        /** Nessun job accodato: mostrare un invito non invasivo ("Vuoi un consiglio sull'obiettivo con i nuovi dati?") con la conferma IA standard. */
        object Suggest : Advice

        /** L'utente ha attivato "Consiglio obiettivo dopo nuove misure": il job è accodato (chiave idempotente). */
        data class Queued(val jobKey: String) : Advice
    }

    /**
     * Dopo nuove misure: nessun job IA a pagamento senza consenso. Solo se l'utente ha attivato
     * l'automazione esplicita [AiAutomationPreferences.Feature.NUTRITION_GOAL_AFTER_MEASURES]
     * (spenta di default) il job viene accodato; altrimenti si propone soltanto l'invito.
     */
    suspend fun onMeasurementsChanged(profileId: Long): Advice? {
        val providerConfigured = aiRuntime?.selectedProviderType() != com.myfitai.app.ai.AiProviderType.NOT_CONFIGURED
        val automationEnabled = automation
            ?.get(profileId, AiAutomationPreferences.Feature.NUTRITION_GOAL_AFTER_MEASURES)?.enabled == true
        return when (decide(providerConfigured, automationEnabled)) {
            Action.NONE -> null
            Action.SUGGEST -> Advice.Suggest
            Action.ENQUEUE -> maybeEnqueue(profileId)?.let { Advice.Queued(it) }
        }
    }

    suspend fun maybeEnqueue(profileId: Long): String? {
        if (aiRuntime?.selectedProviderType() == com.myfitai.app.ai.AiProviderType.NOT_CONFIGURED) return null
        val profile = profiles.get(profileId) ?: return null
        val latestBia = bia.all(profileId).first()
            .maxWithOrNull(compareBy({ it.measuredAtEpochMillis }, { it.id }))
        val latestBody = body.all(profileId).first()
            .maxWithOrNull(compareBy({ it.measuredAtEpochMillis }, { it.id }))

        val key = buildString {
            append(profile.updatedAtEpochMillis)
            append('-').append(latestBia?.measuredAtEpochMillis ?: 0L)
            append('-').append(latestBia?.id ?: 0L)
            append('-').append(latestBody?.measuredAtEpochMillis ?: 0L)
            append('-').append(latestBody?.id ?: 0L)
        }
        scheduler.enqueue(profileId, key)
        return key
    }

    companion object {
        /** Regola pura e testabile: nessun consenso esplicito = nessun job a pagamento. */
        fun decide(providerConfigured: Boolean, automationEnabled: Boolean): Action = when {
            !providerConfigured -> Action.NONE
            automationEnabled -> Action.ENQUEUE
            else -> Action.SUGGEST
        }
    }
}
