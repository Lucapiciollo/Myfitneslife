package com.myfitai.app.domain.progress

import com.myfitai.app.domain.ai.AiJobHandler
import com.myfitai.app.domain.ai.AiJobOutcome
import com.myfitai.app.domain.ai.AiJobWorker
import androidx.work.Data
import com.myfitai.app.data.profile.AiAutomationPreferences

class ProgressAnalysisAiJobHandler(
    private val service: ProgressAnalysisService,
    private val scheduler: ProgressAnalysisScheduler,
    private val automation: AiAutomationPreferences,
) : AiJobHandler {
    override suspend fun execute(profileId: Long, jobKey: String, params: androidx.work.Data): AiJobOutcome = try {
        val result = service.analyze(profileId)
        if (params.getBoolean(AiJobWorker.KEY_AUTOMATIC, false)) {
            automation.markRun(profileId, AiAutomationPreferences.Feature.PROGRESS_ANALYSIS, result.executedAtEpochMillis)
            scheduler.scheduleNextAfterAutomaticSuccess(profileId, result.executedAtEpochMillis)
        }
        AiJobOutcome.Success(Data.Builder().putString(AiJobWorker.KEY_PROVIDER, "${result.provider} · ${result.model}").build())
    } catch (error: ProgressAnalysisService.AnalysisException.NeedsInput) {
        AiJobOutcome.Failure(ProgressAnalysisRequirements.message(error.fields))
    } catch (error: ProgressAnalysisService.AnalysisException.InvalidAiOutput) {
        AiJobOutcome.Failure("Risultato IA non valido: nessun dato salvato")
    }
}
