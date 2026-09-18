package com.myfitai.app.domain.review

import androidx.work.Data
import com.myfitai.app.domain.ai.AiJobHandler
import com.myfitai.app.domain.ai.AiJobOutcome
import com.myfitai.app.domain.ai.AiJobWorker
import java.time.LocalDate

class WeeklyReviewAiJobHandler(private val service: WeeklyReviewService) : AiJobHandler {
    override suspend fun execute(profileId: Long, jobKey: String): AiJobOutcome = try {
        val week = jobKey.toLongOrNull()?.let(LocalDate::ofEpochDay)
            ?: return AiJobOutcome.Failure("Settimana non valida")
        val result = service.generate(profileId, week)
        AiJobOutcome.Success(Data.Builder().putString(AiJobWorker.KEY_PROVIDER, "${result.provider} · ${result.model}").build())
    } catch (error: WeeklyReviewService.ReviewException.NeedsInput) {
        AiJobOutcome.Failure("Completa prima: ${error.fields.joinToString()}")
    } catch (error: WeeklyReviewService.ReviewException.WeekNotCompleted) {
        AiJobOutcome.Failure("La review è disponibile solo dopo la chiusura della settimana.")
    } catch (error: WeeklyReviewService.ReviewException.InvalidAiOutput) {
        AiJobOutcome.Failure("Risultato IA non valido: nessuna review salvata")
    }
}
