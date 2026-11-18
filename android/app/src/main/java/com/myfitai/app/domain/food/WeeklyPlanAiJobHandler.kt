package com.myfitai.app.domain.food

import androidx.work.Data
import com.myfitai.app.data.repository.MealPlanRepository
import com.myfitai.app.domain.ai.AiJobHandler
import com.myfitai.app.domain.ai.AiJobOutcome
import com.myfitai.app.domain.ai.AiJobWorker
import com.myfitai.app.domain.safety.SafetyDecision
import com.myfitai.app.domain.safety.SafetyRuleCode
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
            val periodWeeks = params.getInt(KEY_PERIOD_WEEKS, 1).coerceIn(1, 4)

            if (automatic && plans.getPlanForWeek(profileId, weekStart.toEpochDay()) != null) {
                return AiJobOutcome.Success(
                    Data.Builder().putString("message", "Piano già presente: generazione automatica non necessaria.").build()
                )
            }

            val result = service.generatePeriod(profileId, weekStart, periodWeeks)
            runCatching { notifications.refresh() }
            AiJobOutcome.Success(
                Data.Builder()
                    .putString("notice", listOfNotNull(result.safetyNotice, result.varietyNotice).distinct().joinToString(" ").ifBlank { null })
                    .putString(AiJobWorker.KEY_PROVIDER, "${result.provider} · ${result.model}")
                    .build()
            )
        } catch (error: NutritionPlanGenerationService.GenerationException.NeedsInput) {
            AiJobOutcome.Failure(PlanPrerequisites.message(error.prerequisites))
        } catch (error: NutritionPlanGenerationService.GenerationException.PastWeek) {
            AiJobOutcome.Failure("Le settimane concluse sono storico in sola lettura.")
        } catch (error: NutritionPlanGenerationService.GenerationException.ConstraintsUnreadable) {
            unreadableConstraintsOutcome(error)
        } catch (error: NutritionPlanGenerationService.GenerationException.SafetyBlocked) {
            safetyBlockedOutcome(error.decision)
        } catch (_: NutritionPlanGenerationService.GenerationException.StalePlan) {
            AiJobOutcome.Retry("Il piano è stato aggiornato durante la generazione. La richiesta verrà riprovata sulla versione più recente.")
        } catch (_: NutritionPlanGenerationService.GenerationException.SafetyChanged) {
            AiJobOutcome.Failure("Il profilo è cambiato durante la generazione. Controlla i dati e riprova.")
        } finally {
            if (automatic) automaticScheduler.scheduleNextAfterAutomaticRun(profileId)
        }
    }

    companion object {
        /**
         * Unreadable saved constraints are a final, user-fixable error: a plain [AiJobOutcome.Failure] (never
         * Retry, so WorkManager does not re-run it) with the clear user text and no machine code. The failure
         * notification id is derived from profile and week, so repeated runs replace it instead of piling up.
         */
        fun unreadableConstraintsOutcome(error: NutritionPlanGenerationService.GenerationException.ConstraintsUnreadable): AiJobOutcome.Failure =
            AiJobOutcome.Failure(error.message ?: DietaryProfile.UNREADABLE_MESSAGE)

        /** Fixed user copy only; rule codes and stored declaration answers are never written to WorkManager output. */
        fun safetyBlockedOutcome(decision: SafetyDecision): AiJobOutcome {
            val message = when {
                SafetyRuleCode.AGE_UNDER_18 in decision.rules ->
                    "La generazione IA di piani è disponibile dai 18 anni; per indicazioni alimentari rivolgiti a un professionista sanitario."
                SafetyRuleCode.HEALTH_DECLARATION_INCOMPLETE in decision.rules ->
                    "Completa la dichiarazione di sicurezza nel profilo prima di generare un piano."
                SafetyRuleCode.PREGNANCY_OR_LACTATION_DECLARED in decision.rules ||
                    SafetyRuleCode.CLINICIAN_MANAGED_CONDITION_DECLARED in decision.rules ->
                    "Con l'obiettivo attuale non è possibile generare un piano. L'obiettivo salvato nel profilo non è stato modificato."
                else -> "I dati e l'obiettivo salvati non consentono di generare un piano entro i limiti di sicurezza. Rivedi il profilo prima di riprovare."
            }
            return AiJobOutcome.Failure(message)
        }

        const val KEY_WEEK_START_EPOCH_DAY = "week_start_epoch_day"
        const val KEY_AUTOMATIC = "automatic_generation"
        const val KEY_PERIOD_WEEKS = "period_weeks"
    }
}
