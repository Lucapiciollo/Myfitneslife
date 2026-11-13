package com.myfitai.app.ai

/**
 * Single registry of every AI contract the app can send to a provider, keyed by the EXACT `schemaName`.
 *
 * It replaces substring matching (`contains("_pipe_")`, `contains("weekly_nutrition")`, ...) and the old
 * `COMPACT_PROTOCOLS` table: a name that is not registered is never guessed. [AiExecutionService] and
 * [AiRuntimeService] call [require], which fails with [AiExecutionService.Failure.ContractNotRegistered]
 * (category [AiFailure.ContractNotRegistered]) before any prompt is built or any paid call is made.
 * A test checks that every schemaName declared by the contracts is present.
 *
 * `maxOutputTokens` are the prudent caps already applied to the short compact agents (unchanged values, now
 * sent to BOTH providers: Gemini generateContent used to ignore them). Reasoning/thinking tokens may count toward
 * the cap on some models, so a cap that is too small ends as [AiFailure.Truncated], never as a silent success.
 * `null` = no application cap (the weekly plan has a variable length).
 */
object AiContractRegistry {
    enum class Format {
        /** `{"data": "<pipe protocol>"}` envelope; the local parser/validators are authoritative. */
        COMPACT_PIPE,

        /** Typed JSON object validated by the canonical schema (reserved for the planned contract v2). */
        NATIVE_JSON,
    }

    data class Spec(
        val schemaName: String,
        val format: Format,
        /** First line of the compact protocol (`B2`, `MFP1`, ...); required for [Format.COMPACT_PIPE], used by retry hints. */
        val protocolHeader: String?,
        /** Recommended output budget in tokens, or null for no application cap. */
        val maxOutputTokens: Int?,
        /** True when the validated result is stored (informational in C1; read by the validated-output layer later). */
        val persisted: Boolean,
        /** False when Gemini rejects the contract as a native `responseSchema` (JSON-only mode + local validation). */
        val geminiNativeSchema: Boolean = true,
        /**
         * Extra instruction for the business retry, keyed by the stable rule code (the part of the rejection reason before `:`).
         * Fixed text written by the app: it names the record to fix and never echoes anything the model wrote.
         */
        val fixHints: Map<String, String> = emptyMap(),
    ) {
        init {
            require(format != Format.COMPACT_PIPE || !protocolHeader.isNullOrBlank()) { "$schemaName: compact contract needs a protocol header" }
        }

        /** Retry hint naming the exact protocol header of this contract. */
        fun retryRule(): String = when {
            protocolHeader.isNullOrBlank() -> "The data value must follow its requested compact protocol, one record per line."
            else -> "The data value must begin with $protocolHeader and follow its requested pipe records, one record per line."
        }

        /** Contract-specific instruction for a business rejection, or null (the generic retry text is used as is). */
        fun fixHint(reason: String): String? = fixHints[reason.substringBefore(':').trim()]
    }

    private const val CLASSIFICATIONS = "POSITIVE, PROBABLY_POSITIVE, STABLE, MONITOR, NEGATIVE, INSUFFICIENT_DATA"
    private const val SENTENCE_RECORDS = "Y, M and X records must be short Italian sentences of at least 3 words, never codes or capitalised names."

    /** BIA Coach (BA2): the retry names the record to write and its format, so the model does not have to guess. */
    private val BIA_COACH_FIX_HINTS: Map<String, String> = mapOf(
        "BIA_CLASSIFICATION_MISSING" to "The B record is missing: add the record B|<CLASSIFICATION> as its own line right after BA2, with CLASSIFICATION one of $CLASSIFICATIONS; S|<short Italian sentence> is a separate record.",
        "BIA_CLASSIFICATION_INVALID" to "The B record must be B|<CLASSIFICATION> as its own line right after BA2, with CLASSIFICATION exactly one of $CLASSIFICATIONS.",
        "BIA_VERDICT_MISSING" to "The S record is missing: add the record S|<short Italian sentence> (never a classification name) after the B record.",
        "BIA_VERDICT_EQUALS_CLASSIFICATION" to "S must be a short Italian sentence, not the classification: write B|<CLASSIFICATION> right after BA2 and S|<short Italian sentence> as a separate record.",
        "BIA_PLACEHOLDER_TEXT" to "A text field holds a code in capitals instead of a sentence. $SENTENCE_RECORDS The classification goes only in the B record.",
        "BIA_TEXT_TOO_SHORT" to SENTENCE_RECORDS,
        "BIA_TEXT_ENUM_TAIL" to "A G or R comment ends with a reliability value or a separator. Write the comment as plain Italian text and put HIGH, MEDIUM or LOW only as the last field of the record, never inside the comment.",
        "BIA_RELIABILITY_MISSING" to "Add the record L|<HIGH, MEDIUM or LOW>|<short reason>.",
        "THREE_ACTIONS_REQUIRED" to "Write exactly three X records (priority actions), each a short Italian sentence.",
    )

    val all: List<Spec> = listOf(
        Spec("myfitai_bia_raw_pipe_v2", Format.COMPACT_PIPE, "B2", 350, persisted = false),
        Spec("myfitai_bia_pipe_v1", Format.COMPACT_PIPE, "BIA1", 350, persisted = false),
        Spec("myfitai_bia_progress_coach_pipe_v2", Format.COMPACT_PIPE, "BA2", 2_200, persisted = true, fixHints = BIA_COACH_FIX_HINTS),
        Spec("myfitai_progress_analysis_pipe_v1", Format.COMPACT_PIPE, "PA1", 1_500, persisted = true),
        Spec("myfitai_body_proportion_pipe_v1", Format.COMPACT_PIPE, "BP1", 1_500, persisted = true),
        Spec("myfitai_weekly_review_pipe_v1", Format.COMPACT_PIPE, "WR1", 700, persisted = true),
        Spec("myfitai_meal_alternatives_pipe_v1", Format.COMPACT_PIPE, "MA1", 2_200, persisted = false, geminiNativeSchema = false),
        Spec("myfitai_nutrition_advice_pipe_v1", Format.COMPACT_PIPE, "NA1", 900, persisted = false),
        Spec("myfitai_cheat_understanding_pipe_v1", Format.COMPACT_PIPE, "CU1", 500, persisted = false),
        Spec("myfitai_cheat_adjustment_pipe_v1", Format.COMPACT_PIPE, "CA1", 2_500, persisted = true),
        Spec("myfitai_plan_review_pipe_v1", Format.COMPACT_PIPE, "PR1", 1_500, persisted = true),
        Spec("myfitai_nutrition_path_pipe_v1", Format.COMPACT_PIPE, "NP1", 1_500, persisted = true),
        Spec("myfitai_weekly_nutrition_pipe_v1", Format.COMPACT_PIPE, "MFP1", null, persisted = true, geminiNativeSchema = false),
    )

    private val bySchemaName: Map<String, Spec> = all.associateBy { it.schemaName }.also {
        check(it.size == all.size) { "duplicate schemaName in AiContractRegistry" }
    }

    /** Exact lookup. Null for an unregistered name (no prefix/substring matching). */
    fun find(schemaName: String): Spec? = bySchemaName[schemaName]

    /** Exact lookup that fails loudly: an unregistered contract is a programming error, not a fallback case. */
    fun require(schemaName: String): Spec =
        find(schemaName) ?: throw AiExecutionService.Failure.ContractNotRegistered(schemaName)
}
