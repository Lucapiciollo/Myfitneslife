package com.myfitai.app.domain.review

import com.myfitai.app.ai.AiCompactEnvelope

object WeeklyReviewCompactContract {
    const val SCHEMA_NAME = "myfitai_weekly_review_pipe_v1"
    const val PROTOCOL = """WR1
W|weekStartEpochDay
S|summary
O|observation
G|nextWeekGuidance
V|1_or_0|notes"""
    val schemaJson: String = AiCompactEnvelope.schemaJson(PROTOCOL)

    fun parse(json: String): WeeklyReviewContract.Response {
        val lines = AiCompactEnvelope.data(json).lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
        require(lines.firstOrNull() == "WR1") { "WR_PIPE_INVALID" }
        var week: Long? = null
        var summary: String? = null
        val observations = mutableListOf<String>()
        val guidance = mutableListOf<String>()
        var validation = WeeklyReviewContract.AgentValidation(false, "")
        lines.drop(1).forEach { line ->
            val p = line.split('|')
            when (p.firstOrNull()) {
                "W" -> { require(p.size == 2 && week == null); week = p[1].toLongOrNull() ?: error("WR_WEEK_INVALID") }
                "S" -> { require(p.size == 2 && summary == null); summary = p[1].trim() }
                "O" -> { require(p.size == 2); observations += p[1].trim() }
                "G" -> { require(p.size == 2); guidance += p[1].trim() }
                "V" -> { require(p.size == 3 && p[1] in setOf("0", "1")); validation = WeeklyReviewContract.AgentValidation(p[1] == "1", p[2].trim()) }
                else -> error("WR_RECORD_INVALID")
            }
        }
        return WeeklyReviewContract.Response(requireNotNull(week) { "WR_WEEK_MISSING" }, requireNotNull(summary) { "WR_SUMMARY_MISSING" }, observations, guidance, validation)
    }
}
