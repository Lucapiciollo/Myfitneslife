package com.myfitai.app.domain.review

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

    fun validateBusiness(response: Response, expectedWeekStartEpochDay: Long): Result<Unit> = runCatching {
        require(response.weekStartEpochDay == expectedWeekStartEpochDay) { "WEEK_START_MISMATCH" }
        require(response.summary.isNotBlank()) { "SUMMARY_MISSING" }
        require(response.observations.isNotEmpty() && response.observations.all { it.isNotBlank() }) { "OBSERVATIONS_INVALID" }
        require(response.nextWeekGuidance.isNotEmpty() && response.nextWeekGuidance.all { it.isNotBlank() }) { "GUIDANCE_INVALID" }
        val allText = (listOf(response.summary) + response.observations + response.nextWeekGuidance).joinToString(" ").lowercase()
        require("diagnosi" !in allText && "diagnosis" !in allText) { "MEDICAL_CLAIM_NOT_ALLOWED" }
        require("causato" !in allText && "caused by" !in allText) { "CAUSAL_CLAIM_NOT_ALLOWED" }
    }
}
