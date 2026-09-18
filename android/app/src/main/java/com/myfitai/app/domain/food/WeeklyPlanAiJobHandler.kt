package com.myfitai.app.domain.food

import androidx.work.Data
import com.myfitai.app.domain.ai.AiJobHandler
import com.myfitai.app.domain.ai.AiJobOutcome
import com.myfitai.app.domain.ai.AiJobWorker
import com.myfitai.app.notifications.NotificationScheduler
import java.time.LocalDate

class WeeklyPlanAiJobHandler(
    private val service: NutritionPlanGenerationService,
    private val notifications: NotificationScheduler,
) : AiJobHandler {
    override suspend fun execute(profileId: Long, jobKey: String, params: androidx.work.Data): AiJobOutcome = try {
        val weekStart = jobKey.toLongOrNull()?.let(LocalDate::ofEpochDay)
            ?: return AiJobOutcome.Failure("Settimana non valida")
        val result = service.generateWeek(profileId, weekStart)
        runCatching { notifications.refresh() }
        AiJobOutcome.Success(Data.Builder().putString(AiJobWorker.KEY_PROVIDER, "${result.provider} · ${result.model}").build())
    } catch (error: NutritionPlanGenerationService.GenerationException.NeedsInput) {
        AiJobOutcome.Failure("Completa prima: ${error.fields.joinToString()}")
    } catch (error: NutritionPlanGenerationService.GenerationException.PastWeek) {
        AiJobOutcome.Failure("Le settimane concluse sono storico in sola lettura.")
    }
}
