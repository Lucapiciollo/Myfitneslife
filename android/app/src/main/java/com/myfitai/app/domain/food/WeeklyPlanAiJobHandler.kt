package com.myfitai.app.domain.food

import androidx.work.Data
import com.myfitai.app.data.repository.MealPlanRepository
import com.myfitai.app.domain.ai.AiJobHandler
import com.myfitai.app.domain.ai.AiJobOutcome
import com.myfitai.app.domain.ai.AiJobWorker
import com.myfitai.app.notifications.NotificationScheduler
import java.time.LocalDate

class WeeklyPlanAiJobHandler(
    private val service: NutritionPlanGenerationService,
    private val notifications: NotificationScheduler,
    private val plans: MealPlanRepository,
    private val automaticScheduler: NutritionPlanScheduler,
) : AiJobHandler {
    override suspend fun execute(profileId: Long, jobKey: String, params: Data): AiJobOutcome {
        val automatic = params.getBoolean(KEY_AUTOMATIC, false)
        return try {
            val weekEpochDay = params.getLong(KEY_WEEK_START_EPOCH_DAY, Long.MIN_VALUE)
                .takeIf { it != Long.MIN_VALUE }
                ?: jobKey.toLongOrNull()
                ?: return AiJobOutcome.Failure("Settimana non valida")
            val weekStart = LocalDate.ofEpochDay(weekEpochDay)

            if (automatic && plans.getPlanForWeek(profileId, weekStart.toEpochDay()) != null) {
                return AiJobOutcome.Success(
                    Data.Builder().putString("message", "Piano già presente: generazione automatica non necessaria.").build()
                )
            }

            val result = service.generateWeek(profileId, weekStart)
            runCatching { notifications.refresh() }
            AiJobOutcome.Success(
                Data.Builder()
                    .putString(AiJobWorker.KEY_PROVIDER, "${result.provider} · ${result.model}")
                    .build()
            )
        } catch (error: NutritionPlanGenerationService.GenerationException.NeedsInput) {
            AiJobOutcome.Failure("Completa prima: ${error.fields.joinToString()}")
        } catch (error: NutritionPlanGenerationService.GenerationException.PastWeek) {
            AiJobOutcome.Failure("Le settimane concluse sono storico in sola lettura.")
        } catch (error: NutritionPlanGenerationService.GenerationException.InvalidAiOutput) {
            AiJobOutcome.Failure(humanReadableValidationError(error.message.orEmpty()))
        } finally {
            if (automatic) automaticScheduler.scheduleNextAfterAutomaticRun(profileId)
        }
    }

    private fun humanReadableValidationError(message: String): String {
        if (!message.startsWith("NUTRITION_INTEGRITY_INVALID:")) {
            return "Il piano generato non ha superato la validazione: $message"
        }
        val labels = message.substringAfter(':')
            .split(',')
            .filter { it.isNotBlank() }
            .distinct()
            .map { code ->
                when (code) {
                    "TARGET_DAY_OUT_OF_RANGE" -> "target giornaliero fuori dal ±3%"
                    "MEAL_COUNT_INVALID" -> "numero di pasti non corretto"
                    "DAY_MEALS_KCAL_MISMATCH" -> "somma kcal dei pasti diversa dal totale del giorno"
                    "DAY_MEALS_PROTEIN_MISMATCH" -> "somma proteine diversa dal totale del giorno"
                    "DAY_MEALS_CARBS_MISMATCH" -> "somma carboidrati diversa dal totale del giorno"
                    "DAY_MEALS_FAT_MISMATCH" -> "somma grassi diversa dal totale del giorno"
                    "MACRO_CALORIE_INCONSISTENCY" -> "kcal non coerenti con i macro del pasto"
                    else -> code
                }
            }
        return "Piano rifiutato dal validatore locale: ${labels.joinToString("; ")}."
    }

    companion object {
        const val KEY_WEEK_START_EPOCH_DAY = "week_start_epoch_day"
        const val KEY_AUTOMATIC = "automatic_generation"
    }
}
