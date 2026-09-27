package com.myfitai.app.domain.body

import androidx.work.Data
import com.myfitai.app.data.profile.AiAutomationPreferences
import com.myfitai.app.data.profile.ActiveProfileStore
import com.myfitai.app.domain.ai.AiJobScheduler
import com.myfitai.app.domain.ai.AiJobType
import java.util.concurrent.TimeUnit

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
        val due = automation.nextDue(profileId, AiAutomationPreferences.Feature.BIA_PROGRESS_COACH) ?: nowEpochMillis
        jobs.enqueue(
            AiJobType.BIA_ANALYSIS,
            profileId,
            "auto-$due",
            initialDelayMillis = (due - nowEpochMillis).coerceAtLeast(0L),
            params = Data.Builder().putBoolean(BiaAnalysisAiJobHandler.KEY_AUTOMATIC, true).build(),
        )
    }

    fun onSuccess(profileId: Long, atEpochMillis: Long = System.currentTimeMillis()) {
        automation.markRun(profileId, AiAutomationPreferences.Feature.BIA_PROGRESS_COACH, atEpochMillis)
        ensureScheduled(profileId, atEpochMillis)
    }

    fun cancel(profileId: Long) = jobs.cancelAll(AiJobType.BIA_ANALYSIS, profileId)
}
