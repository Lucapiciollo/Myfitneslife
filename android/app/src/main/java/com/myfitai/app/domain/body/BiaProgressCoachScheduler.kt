package com.myfitai.app.domain.body

import androidx.work.Data
import com.myfitai.app.data.profile.AiAutomationPreferences
import com.myfitai.app.data.profile.ActiveProfileStore
import com.myfitai.app.domain.ai.AiJobScheduler
import com.myfitai.app.domain.ai.AiJobType

/**
 * Pianifica l'analisi BIA automatica con una **chiave deterministica** per (profilo, scadenza):
 * `auto-<scadenza>`, dove la scadenza è l'ultima esecuzione riuscita + frequenza (0 se non c'è mai
 * stata un'esecuzione). Riavviare l'app N volte senza successo accoda quindi un solo job
 * (`ExistingWorkPolicy.KEEP` deduplica sul nome); prima la chiave conteneva l'ora corrente e ogni
 * avvio ne creava uno nuovo. La decisione se spendere o no la chiamata IA è di [BiaAnalysisPolicy].
 */
class BiaProgressCoachScheduler(
    private val automation: AiAutomationPreferences,
    private val activeProfileStore: ActiveProfileStore,
    private val jobs: AiJobScheduler,
) {
    fun ensureScheduled(profileId: Long, nowEpochMillis: Long = System.currentTimeMillis()) {
        val config = automation.get(profileId, AiAutomationPreferences.Feature.BIA_PROGRESS_COACH)
        if (!config.enabled) {
            jobs.cancelAll(AiJobType.BIA_ANALYSIS, profileId)
            return
        }
        val due = automation.nextDue(profileId, AiAutomationPreferences.Feature.BIA_PROGRESS_COACH)
        jobs.enqueue(
            AiJobType.BIA_ANALYSIS,
            profileId,
            jobKey(due),
            initialDelayMillis = delayMillis(due, nowEpochMillis),
            params = Data.Builder().putBoolean(BiaAnalysisAiJobHandler.KEY_AUTOMATIC, true).build(),
        )
    }

    /**
     * Da chiamare dopo un cambio di frequenza: annulla soltanto il vecchio job `auto-<scadenza precedente>`
     * (le analisi manuali in corso non vengono toccate) e ne accoda uno nuovo sulla nuova scadenza.
     *
     * @param previousDue scadenza calcolata PRIMA di cambiare la frequenza ([AiAutomationPreferences.nextDue]).
     */
    fun onFrequencyChanged(profileId: Long, previousDue: Long?, nowEpochMillis: Long = System.currentTimeMillis()) {
        jobs.cancel(AiJobType.BIA_ANALYSIS, profileId, jobKey(previousDue))
        ensureScheduled(profileId, nowEpochMillis)
    }

    fun onSuccess(profileId: Long, atEpochMillis: Long = System.currentTimeMillis()) {
        automation.markRun(profileId, AiAutomationPreferences.Feature.BIA_PROGRESS_COACH, atEpochMillis)
        ensureScheduled(profileId, atEpochMillis)
    }

    /**
     * Un job automatico terminato in errore definitivo non deve ripartire a ogni avvio dell'app:
     * il prossimo tentativo slitta di una frequenza (i retry transitori li gestisce già il worker).
     */
    fun deferAfterFailure(profileId: Long, atEpochMillis: Long = System.currentTimeMillis()) = onSuccess(profileId, atEpochMillis)

    fun cancel(profileId: Long) = jobs.cancelAll(AiJobType.BIA_ANALYSIS, profileId)

    companion object {
        /** Chiave del job automatico per una scadenza; stessa scadenza = stessa chiave, qualunque sia l'ora di avvio. */
        fun jobKey(dueEpochMillis: Long?): String = "auto-${dueEpochMillis ?: 0L}"

        fun delayMillis(dueEpochMillis: Long?, nowEpochMillis: Long): Long =
            ((dueEpochMillis ?: 0L) - nowEpochMillis).coerceAtLeast(0L)
    }
}
