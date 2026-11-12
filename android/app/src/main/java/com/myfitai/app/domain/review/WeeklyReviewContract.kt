package com.myfitai.app.domain.review

import com.myfitai.app.domain.ai.UnsupportedClaimGuard
import org.json.JSONObject

/** Provider-neutral structured contract for a weekly review. */
object WeeklyReviewContract {
    const val SCHEMA_NAME = WeeklyReviewCompactContract.SCHEMA_NAME
    val schemaJson: String get() = WeeklyReviewCompactContract.schemaJson

    data class AgentValidation(val valid: Boolean, val notes: String)
    data class Response(
        val weekStartEpochDay: Long,
        val summary: String,
        val observations: List<String>,
        val nextWeekGuidance: List<String>,
        val agentValidation: AgentValidation,
    )

    fun parse(jsonText: String): Response {
        val root = JSONObject(jsonText)
        if (root.has("data")) return WeeklyReviewCompactContract.parse(jsonText)
        fun strings(name: String): List<String> {
            val array = root.getJSONArray(name)
            return buildList { for (i in 0 until array.length()) add(array.getString(i).trim()) }
        }
        val validation = root.getJSONObject("agentValidation")
        return Response(
            weekStartEpochDay = root.getLong("weekStartEpochDay"),
            summary = root.getString("summary").trim(),
            observations = strings("observations"),
            nextWeekGuidance = strings("nextWeekGuidance"),
            agentValidation = AgentValidation(validation.getBoolean("valid"), validation.getString("notes").trim()),
        )
    }

    /** Limits declared to the model in the weekly review prompt and enforced here (single source). */
    const val MAX_SUMMARY_WORDS = 25
    const val MAX_ITEM_WORDS = 18
    const val MIN_OBSERVATIONS = 1
    const val MAX_OBSERVATIONS = 6
    const val MIN_GUIDANCE = 1
    const val MAX_GUIDANCE = 5

    private fun words(text: String): Int = text.trim().split(Regex("\\s+")).count { it.isNotEmpty() }

    fun validateBusiness(response: Response, expectedWeekStartEpochDay: Long): Result<Unit> = runCatching {
        require(response.weekStartEpochDay == expectedWeekStartEpochDay) { "WEEK_START_MISMATCH" }
        require(response.summary.isNotBlank()) { "SUMMARY_MISSING" }
        require(response.observations.isNotEmpty() && response.observations.all { it.isNotBlank() }) { "OBSERVATIONS_INVALID" }
        require(response.nextWeekGuidance.isNotEmpty() && response.nextWeekGuidance.all { it.isNotBlank() }) { "GUIDANCE_INVALID" }
        require(words(response.summary) <= MAX_SUMMARY_WORDS) { "SUMMARY_TOO_LONG" }
        require(response.observations.size in MIN_OBSERVATIONS..MAX_OBSERVATIONS) { "OBSERVATIONS_COUNT_INVALID" }
        require(response.nextWeekGuidance.size in MIN_GUIDANCE..MAX_GUIDANCE) { "GUIDANCE_COUNT_INVALID" }
        require((response.observations + response.nextWeekGuidance).all { words(it) <= MAX_ITEM_WORDS }) { "ITEM_TOO_LONG" }
        val fields = listOf(response.summary) + response.observations + response.nextWeekGuidance
        require(!UnsupportedClaimGuard.containsAffirmed(fields, listOf("diagnosi", "diagnosis"))) { "MEDICAL_CLAIM_NOT_ALLOWED" }
        require(!UnsupportedClaimGuard.containsAffirmed(fields, UnsupportedClaimGuard.CAUSAL_ASSERTIONS)) { "CAUSAL_CLAIM_NOT_ALLOWED" }
        require(!UnsupportedClaimGuard.containsAffirmed(fields, UnsupportedClaimGuard.BODY_OVERCLAIMS)) { "BODY_OVERCLAIM_NOT_ALLOWED" }
    }
}
