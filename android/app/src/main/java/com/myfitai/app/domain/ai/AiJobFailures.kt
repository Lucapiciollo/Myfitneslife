package com.myfitai.app.domain.ai

import com.myfitai.app.ai.AiExecutionService
import com.myfitai.app.ai.AiTransportException
import com.myfitai.app.domain.food.CheatAdjustmentService
import com.myfitai.app.domain.food.NutritionAdviceService
import kotlinx.coroutines.CancellationException

/**
 * Single place that decides (a) whether an AI job error is worth a WorkManager retry and (b) which
 * message may leave the handler. Transient: no network, non-quota rate limit (429), provider 5xx.
 * Everything else (quota, auth, truncated output, invalid schema, business rejection) is permanent.
 *
 * Messages that reach WorkManager output, notifications and the UI never contain arbitrary exception
 * text: an exception message may embed model output (parser errors, `BIA_..._<value>`). Only
 * (1) typed exceptions with static messages, (2) a closed set of stable error codes and (3) a closed set of
 * static texts are forwarded; anything else becomes the handler's fixed fallback message.
 */
object AiJobFailures {
    private const val GENERIC_MESSAGE = "Operazione IA non riuscita"

    fun isTransient(error: Throwable): Boolean = (error as? AiTransportException)?.isTransient() == true

    fun toOutcome(error: Throwable): AiJobOutcome = when {
        isTransient(error) -> AiJobOutcome.Retry(
            if (error is AiTransportException.Network) "Provider non raggiungibile" else "Provider temporaneamente non disponibile",
        )
        else -> failure(error, GENERIC_MESSAGE)
    }

    fun failure(error: Throwable, fallback: String): AiJobOutcome.Failure = AiJobOutcome.Failure(safeMessage(error, fallback))

    /** Message safe to persist/show: never contains text copied from the model output. */
    fun safeMessage(error: Throwable, fallback: String): String = when (error) {
        is AiTransportException.NotConfigured -> "Provider IA non configurato"
        // Http/Network/InvalidResponse messages are built from our own constants and the numeric status.
        is AiTransportException -> error.message ?: fallback
        is AiExecutionService.Failure.BusinessRejected -> stableCode(error.reason) ?: fallback
        is AiExecutionService.Failure -> stableCode(error.message) ?: fallback
        // Typed exceptions of the cheat flow carry static texts and field names chosen by the app.
        is CheatAdjustmentService.AdjustmentException -> error.message ?: fallback
        else -> stableCode(error.message) ?: error.message?.takeIf { it in STATIC_MESSAGES } ?: fallback
    }

    /**
     * Stable prefix of an error code: up to the first ':' and, for the dynamic families, without the
     * variable tail (`BIA_RELIABILITY_INVALID_<model text>`, `BA2_L_ARITY_<n>` keeps only the numeric arity).
     * Returns null when the code is not in the closed [KNOWN_CODES] set.
     */
    fun stableCode(message: String?): String? {
        val head = message?.substringBefore(':')?.trim().orEmpty()
        if (head in KNOWN_CODES) return head
        if (ARITY_CODE.matches(head)) return head
        return DYNAMIC_PREFIXES.firstOrNull { head.startsWith("${it}_") }
    }

    /**
     * Call first inside a handler's `catch (error: Exception)`: cancellation and transient errors
     * must reach [AiJobWorker] so WorkManager can cancel or retry; the rest becomes a Failure.
     */
    fun rethrowIfRetryableOrCancelled(error: Throwable) {
        if (error is CancellationException || isTransient(error)) throw error
    }

    private val ARITY_CODE = Regex("^BA2_[A-Z]_ARITY_\\d{1,3}$")
    private val DYNAMIC_PREFIXES = listOf("BIA_RELIABILITY_INVALID")

    private val STATIC_MESSAGES = setOf(
        NutritionAdviceService.OUT_OF_SCOPE_MESSAGE,
        "Profilo non disponibile",
        "Nessuna rilevazione BIA con valori da analizzare",
    )

    internal val KNOWN_CODES: Set<String> = setOf(
        "ADAPTATION_FALSE_WITHOUT_REASON",
        "ADAPTED_DAY_OUTSIDE_TARGETS",
        "ASSUMPTIONS_TOO_LONG",
        "BA2_B_INVALID",
        "BA2_C_INVALID",
        "BA2_D_INVALID",
        "BA2_F_INVALID",
        "BA2_K_INVALID",
        "BA2_N_INVALID",
        "BA2_O_INVALID",
        "BA2_P_INVALID",
        "BA2_S_INVALID",
        "BA2_V_INVALID",
        "BIA_CALORIES_MISSING",
        "BIA_CLASSIFICATION_INVALID",
        "BIA_CLASSIFICATION_MISSING",
        "BIA_FAT_LOSS_MISSING",
        "BIA_NEXT_CHECK_MISSING",
        "BIA_OBJECTIVE_MISSING",
        "BIA_PIPE_FIELDS_INVALID",
        "BIA_PIPE_FLAG_INVALID",
        "BIA_PIPE_INVALID",
        "BIA_PIPE_NUMBER_INVALID",
        "BIA_PIPE_TIMESTAMP_INVALID",
        "BIA_PROGRESS_PIPE_INVALID",
        "BIA_RAW_CONFIDENCE_INVALID",
        "BIA_RAW_FLAG_INVALID",
        "BIA_RAW_HEADER_INVALID",
        "BIA_RAW_LABEL_INVALID",
        "BIA_RAW_MEASUREMENT_INVALID",
        "BIA_RAW_NUMBER_INVALID",
        "BIA_RAW_PIPE_INVALID",
        "BIA_RAW_REJECTION_WITH_VALUES",
        "BIA_RELIABILITY_INVALID",
        "BIA_RELIABILITY_MISSING",
        "BIA_RELIABILITY_REASON_MISSING",
        "BIA_SUMMARY_MISSING",
        "BIA_VERDICT_MISSING",
        "BODY_COMPARISON_INVALID",
        "BODY_PROPORTION_TEXT_INVALID",
        "BP_COMPARISON_MISSING",
        "BP_PIPE_INVALID",
        "BP_RECORD_INVALID",
        "BP_SUMMARY_MISSING",
        "BP_VALIDATION_MISSING",
        "BUSINESS_VALIDATION_FAILED",
        "CA_ADAPTATION_MISSING",
        "CA_C",
        "CA_CONF",
        "CA_ESTIMATE_MISSING",
        "CA_F",
        "CA_FLAG",
        "CA_IDOSE",
        "CA_INAME",
        "CA_INGREDIENTS_REQUIRED",
        "CA_IQTY",
        "CA_IUNIT",
        "CA_KCAL",
        "CA_P",
        "CA_RC",
        "CA_RECORD_INVALID",
        "CA_RF",
        "CA_RKCAL",
        "CA_RP",
        "CA_SORT",
        "CA_TIME",
        "CA_TITLE",
        "CA_TYPE",
        "CA_VALIDATION_MISSING",
        "CA1_INVALID",
        "CHEAT_CONFIDENCE_INVALID",
        "CHEAT_ESTIMATE_INVALID",
        "CHEAT_MACROS_INVALID",
        "COMPARISON_RELIABILITY_INVALID",
        "CONFIRMED_CARBS_CHANGED",
        "CONFIRMED_FAT_CHANGED",
        "CONFIRMED_KCAL_CHANGED",
        "CONFIRMED_PROTEIN_CHANGED",
        "CU_C_INVALID",
        "CU_CONF_INVALID",
        "CU_COUNT_INVALID",
        "CU_F_INVALID",
        "CU_FOOD_INVALID",
        "CU_KCAL_INVALID",
        "CU_P_INVALID",
        "CU1_INVALID",
        "EMPTY_ACTION",
        "EMPTY_BIA_ANALYSIS",
        "EMPTY_SUMMARY",
        "EXPECTED_FIVE_SUGGESTIONS",
        "FOOD_CONSTRAINT_VIOLATION",
        "FOODS_REQUIRED_FOR_CONSTRAINT_VALIDATION",
        "FUTURE_MEAL_COUNT_MISMATCH",
        "FUTURE_MEAL_SET_MISMATCH",
        "INGREDIENT_DOSE_MISSING",
        "INGREDIENT_INVALID",
        "INGREDIENT_METADATA_MISSING",
        "INSUFFICIENT_DATA",
        "INVALID_COMPACT_ANSWER",
        "INVALID_KCAL",
        "INVALID_MACROS",
        "INVALID_SCHEMA",
        "INVALID_SUGGESTION_REASON",
        "INVALID_SUGGESTION_TITLE",
        "LATEST_MEASUREMENT_DATE_MISSING",
        "MA_C",
        "MA_DOSE",
        "MA_F",
        "MA_INAME",
        "MA_INGREDIENTS_REQUIRED",
        "MA_KCAL",
        "MA_P",
        "MA_QTY",
        "MA_RECORD_INVALID",
        "MA_TITLE",
        "MA_UNIT",
        "MA_VALIDATION_MISSING",
        "MA1_INVALID",
        "MEAL_LABEL_MISSING",
        "MEAL_MACROS_INVALID",
        "MEAL_TIME_MUST_NOT_MOVE",
        "MEAL_WITHOUT_INGREDIENTS",
        "NA_ANSWER_MISSING",
        "NA_ASSUMPTIONS_MISSING",
        "NA_C",
        "NA_F",
        "NA_KCAL",
        "NA_P",
        "NA_REASON",
        "NA_RECORD_INVALID",
        "NA_SCOPE",
        "NA_SCOPE_MISSING",
        "NA_TITLE",
        "NA_VALIDATION_MISSING",
        "NA1_INVALID",
        "NO_FUTURE_MEALS",
        "OUT_OF_SCOPE_WITH_SUGGESTIONS",
        "OUTPUT_TRUNCATED",
        "PUNITIVE_ADAPTATION_BLOCKED",
        "PUNITIVE_MEAL_TOO_SMALL",
        "REPLACEMENTS_NOT_ALLOWED",
        "SAFETY_NOTE_REQUIRED",
        "SCENARIO_RELIABILITY_INVALID",
        "SUMMARY_TOO_LONG",
        "THREE_ACTIONS_REQUIRED",
        "INSUFFICIENT_DATA_MAX_TWO_ACTIONS",
        "PROFILE_CONSTRAINTS_UNREADABLE",
        "TOO_MANY_COMPARISONS",
        "TOO_MANY_HISTORICAL_PERIODS",
        "TOO_MANY_MONITOR_ITEMS",
        "TOO_MANY_OBSERVATIONS",
        "TOO_MANY_POSITIVES",
        "TOO_MANY_SCENARIOS",
        "TRANSIENT_AFTER_PAID_RETRY",
        "UNDERSTANDING_MISSING",
        "UNSUPPORTED_DIAGNOSTIC_OR_CAUSAL_CLAIM",
        "UNSUPPORTED_MEDICAL_OR_CAUSAL_CLAIM",
    )
}