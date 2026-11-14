package com.myfitai.app.ai

import kotlinx.coroutines.CancellationException

/**
 * Closed taxonomy of everything that can go wrong in an AI call. It is the ONLY classification of errors:
 * [AiTransportException.isTransient], [AiExecutionService] and [com.myfitai.app.domain.ai.AiJobFailures] all
 * ask [classify]; there is no second table of codes or retry rules.
 *
 * Every category has
 * - a stable [code] (safe to persist, put in WorkManager output and show as a technical detail),
 * - a [retryable] flag: `true` only when repeating the SAME request later may succeed (network, rate limit,
 *   provider 5xx). Everything else is permanent for the current request,
 * - a [userMessage] taken from this closed table. It never contains model output, provider error text, keys,
 *   prompts or numbers coming from the response.
 *
 * Retry rules (plan §7): a transient error before any response was paid is retried by the job; once a response
 * was already consumed, a later transient error becomes [PaidRetryBudgetConsumed] (never restarted).
 */
sealed class AiFailure(
    val code: String,
    val retryable: Boolean,
    val userMessage: String,
    /** How [com.myfitai.app.domain.ai.AiJobFailures] surfaces this failure to handlers/UI: the static text or the code. */
    val surface: Surface,
    /** Code forwarded when [surface] is [Surface.CODE]. Differs from [code] only to stay compatible with UI mappings. */
    val surfaceCode: String = code,
) {
    enum class Surface { MESSAGE, CODE }

    /** Why the provider declined to produce the answer. Informational: it never changes the user text. */
    enum class RefusalReason { MODEL_REFUSAL, SAFETY, PROMPT_BLOCKED, CONTENT_FILTER }

    // ---- Transport: where the request itself did not complete ----

    /** No connectivity or timeout. Transient. */
    object Network : AiFailure("NETWORK", true, "Provider non raggiungibile", Surface.MESSAGE)

    /** 429 that is not a quota problem. Transient. */
    object RateLimited : AiFailure("RATE_LIMITED", true, "Provider temporaneamente non disponibile", Surface.MESSAGE)

    /** Provider 5xx. Transient. */
    object ProviderUnavailable : AiFailure("PROVIDER_UNAVAILABLE", true, "Provider temporaneamente non disponibile", Surface.MESSAGE)

    /** Quota/billing exhausted. Permanent until the user acts. */
    object Quota : AiFailure("QUOTA_EXHAUSTED", false, "Quota del provider IA esaurita", Surface.MESSAGE)

    /** Invalid, expired or unauthorized credential / auth method. */
    object Auth : AiFailure("AUTH", false, "Chiave API del provider IA non valida o non autorizzata", Surface.MESSAGE)

    object NotConfigured : AiFailure("NOT_CONFIGURED", false, "Provider IA non configurato", Surface.MESSAGE)

    /** The selected model is not accessible with this credential. */
    object ModelUnavailable : AiFailure("MODEL_UNAVAILABLE", false, "Modello IA non accessibile con questa chiave", Surface.MESSAGE)

    /** The provider answered, but not in a way the app can interpret (bad envelope, unknown finish reason, bad request). */
    object ProviderProtocol : AiFailure("PROVIDER_PROTOCOL", false, "Risposta provider non interpretabile", Surface.MESSAGE)

    // ---- Output: the provider answered but the answer is not usable ----

    /** Refusal / safety / blocked prompt / content filter. The provider text is never read or forwarded. */
    data class Refused(val reason: RefusalReason) :
        AiFailure("REFUSED", false, "Il provider IA ha rifiutato di rispondere a questa richiesta", Surface.CODE)

    /** finishReason MAX_TOKENS / length / max_output_tokens: an incomplete answer is never a success. */
    object Truncated : AiFailure("OUTPUT_TRUNCATED", false, "Risposta IA interrotta prima del completamento", Surface.CODE)

    /** HTTP success but no text (empty, only thoughts, no candidates). */
    object EmptyOutput : AiFailure("EMPTY_OUTPUT", false, "Il provider IA non ha restituito alcun contenuto", Surface.CODE)

    /** The text is not parseable JSON. Surfaced with the legacy `INVALID_SCHEMA` code the UI already maps. */
    object MalformedJson : AiFailure(
        "MALFORMED_JSON", false, "Risposta IA non in formato JSON valido", Surface.CODE, surfaceCode = "INVALID_SCHEMA",
    )

    /** Parseable JSON that violates the contract schema, after the allowed retries. */
    object SchemaInvalid : AiFailure("INVALID_SCHEMA", false, "Risposta IA non conforme al formato atteso", Surface.CODE)

    /** An app-side business validator rejected the answer. [ruleCode] is only ever a member of [BUSINESS_CODES]. */
    data class BusinessRuleViolated(val ruleCode: String?) :
        AiFailure("BUSINESS_VALIDATION_FAILED", false, "Risposta IA respinta dai controlli dell'app", Surface.CODE)

    // ---- App ----

    /** Programming error: a request used a schemaName that is not in [AiContractRegistry]. Never a silent fallback. */
    object ContractNotRegistered :
        AiFailure("CONTRACT_NOT_REGISTERED", false, "Contratto IA non registrato", Surface.CODE)

    /** A transient error arrived after a paid response was consumed: the job must not restart. */
    object PaidRetryBudgetConsumed :
        AiFailure("TRANSIENT_AFTER_PAID_RETRY", false, "Operazione IA interrotta dopo un tentativo già consumato", Surface.CODE)

    object Cancelled : AiFailure("CANCELLED", false, "Operazione IA annullata", Surface.MESSAGE)

    object Unexpected : AiFailure("UNEXPECTED", false, "Operazione IA non riuscita", Surface.MESSAGE)

    /** Exception carrying this failure, used when a failure is detected by a pure parser/check. */
    fun toException(): Exception = when (this) {
        Truncated -> AiExecutionService.Failure.OutputTruncated()
        is Refused -> AiExecutionService.Failure.Refused(reason)
        EmptyOutput -> AiExecutionService.Failure.EmptyOutput()
        MalformedJson -> AiExecutionService.Failure.MalformedJson()
        SchemaInvalid -> AiExecutionService.Failure.InvalidSchema(emptyList())
        ContractNotRegistered -> AiExecutionService.Failure.ContractNotRegistered("")
        is BusinessRuleViolated -> AiExecutionService.Failure.BusinessRejected(ruleCode.orEmpty())
        Network -> AiTransportException.Network()
        ProviderProtocol -> AiTransportException.InvalidResponse()
        else -> IllegalStateException(code)
    }

    companion object {
        /**
         * Every parameterless category (used by tests to check uniqueness and the retry table). A getter, not a
         * stored value: building the list during the companion's static initialisation would run while the
         * objects (subclasses) are still being initialised and capture nulls.
         */
        val ALL_STATIC: List<AiFailure>
            get() = listOf(
                Network, RateLimited, ProviderUnavailable, Quota, Auth, NotConfigured, ModelUnavailable, ProviderProtocol,
                Truncated, EmptyOutput, MalformedJson, SchemaInvalid, ContractNotRegistered, PaidRetryBudgetConsumed,
                Cancelled, Unexpected,
            )

        /** The one place that maps any [Throwable] to a category. Cancellation is classified, never swallowed. */
        fun classify(error: Throwable): AiFailure = when (error) {
            is CancellationException -> Cancelled
            is AiTransportException.NotConfigured -> NotConfigured
            is AiTransportException.Network -> Network
            is AiTransportException.InvalidResponse -> ProviderProtocol
            is AiTransportException.Http -> fromHttp(error.failureKind, error.statusCode)
            is AiExecutionService.Failure.InvalidSchema -> SchemaInvalid
            is AiExecutionService.Failure.OutputTruncated -> Truncated
            is AiExecutionService.Failure.Refused -> Refused(error.reason)
            is AiExecutionService.Failure.EmptyOutput -> EmptyOutput
            is AiExecutionService.Failure.MalformedJson -> MalformedJson
            is AiExecutionService.Failure.ContractNotRegistered -> ContractNotRegistered
            is AiExecutionService.Failure.PaidRetryBudgetConsumed -> PaidRetryBudgetConsumed
            is AiExecutionService.Failure.BusinessRejected -> BusinessRuleViolated(stableBusinessCode(error.reason))
            // Validators of the contracts throw plain exceptions whose message is a stable code.
            else -> stableBusinessCode(error.message)?.let { BusinessRuleViolated(it) } ?: Unexpected
        }

        fun fromHttp(kind: AiTransportFailureKind, statusCode: Int): AiFailure = when (kind) {
            AiTransportFailureKind.INVALID_API_KEY, AiTransportFailureKind.UNSUPPORTED_AUTH_METHOD -> Auth
            AiTransportFailureKind.QUOTA_EXHAUSTED -> Quota
            AiTransportFailureKind.RATE_LIMITED -> RateLimited
            AiTransportFailureKind.PROVIDER_UNAVAILABLE -> ProviderUnavailable
            AiTransportFailureKind.MODEL_UNAVAILABLE, AiTransportFailureKind.MODEL_REQUIRES_INTERACTIONS -> ModelUnavailable
            AiTransportFailureKind.SCHEMA -> ProviderProtocol
            AiTransportFailureKind.NETWORK, AiTransportFailureKind.UNKNOWN ->
                if (statusCode in 500..599) ProviderUnavailable else ProviderProtocol
        }

        /**
         * Classifies a provider finish reason (Gemini `finishReason`, Interactions `status`, OpenAI chat `finish_reason`).
         * Returns null only for a normal end of generation; anything else is a failure, so an abnormal end can
         * never be accepted as a success by accident.
         */
        fun fromFinishReason(reason: String?): AiFailure? = when (reason?.trim()?.lowercase().orEmpty()) {
            "", "stop", "finish_reason_unspecified", "completed", "end_turn" -> null
            "max_tokens", "max_output_tokens", "length", "incomplete" -> Truncated
            "safety", "blocklist", "prohibited_content", "spii", "image_safety", "recitation" -> Refused(RefusalReason.SAFETY)
            "content_filter" -> Refused(RefusalReason.CONTENT_FILTER)
            "refusal" -> Refused(RefusalReason.MODEL_REFUSAL)
            else -> ProviderProtocol
        }

        /**
         * Stable prefix of a business error code: up to the first ':' and, for the dynamic families, without the
         * variable tail (`BIA_RELIABILITY_INVALID_<model text>`; `BA2_L_ARITY_<n>` keeps only the numeric arity).
         * Returns null when the code is not in the closed [BUSINESS_CODES] set.
         */
        fun stableBusinessCode(message: String?): String? {
            val head = message?.substringBefore(':')?.trim().orEmpty()
            if (head in BUSINESS_CODES) return head
            if (ARITY_CODE.matches(head)) return head
            return DYNAMIC_PREFIXES.firstOrNull { head.startsWith("${it}_") }
        }

        private val ARITY_CODE = Regex("^BA2_[A-Z]_ARITY_\\d{1,3}$")
        private val DYNAMIC_PREFIXES = listOf("BIA_RELIABILITY_INVALID")

        /** Closed set of codes emitted by the contract/business validators (moved verbatim from AiJobFailures). */
        val BUSINESS_CODES: Set<String> = setOf(
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
        "BIA_CALORIES_TEXT_HAS_FIGURES",
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
        "BIA_PLACEHOLDER_TEXT",
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
        "BIA_TEXT_ENUM_TAIL",
        "BIA_TEXT_TOO_SHORT",
        "BIA_VERDICT_EQUALS_CLASSIFICATION",
        "BIA_VERDICT_MISSING",
        "BODY_COMPARISON_INVALID",
        "BODY_FLAG_CODE_NOT_PROVIDED",
        "BODY_FLAG_FORBIDDEN_TERM",
        "BODY_FLAG_SIGNAL_NOT_PROVIDED",
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
        "NUMBER_NOT_IN_CONTEXT",
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
}
