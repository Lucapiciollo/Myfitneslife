package com.myfitai.app.domain.progress

import com.myfitai.app.domain.ai.AiJobHandler
import com.myfitai.app.domain.ai.AiJobOutcome
import com.myfitai.app.domain.ai.AiJobWorker
import androidx.work.Data

class ProgressAnalysisAiJobHandler(private val service: ProgressAnalysisService) : AiJobHandler {
    override suspend fun execute(profileId: Long, jobKey: String, params: androidx.work.Data): AiJobOutcome = try {
        val result = service.analyze(profileId)
        AiJobOutcome.Success(Data.Builder().putString(AiJobWorker.KEY_PROVIDER, "${result.provider} · ${result.model}").build())
    } catch (error: ProgressAnalysisService.AnalysisException.NeedsInput) {
        AiJobOutcome.Failure(ProgressAnalysisRequirements.message(error.fields))
    } catch (error: ProgressAnalysisService.AnalysisException.InvalidAiOutput) {
        AiJobOutcome.Failure("Risultato IA non valido: nessun dato salvato")
    }
}
