package com.myfitai.app.domain.body

import com.myfitai.app.ai.AiCompactEnvelope
import com.myfitai.app.ai.AiRuntimeService
import com.myfitai.app.ai.AiStructuredRequest

class BiaAnalysisService(private val aiRuntime: AiRuntimeService) {
    data class Report(
        val current: Map<String, Float>,
        val previousDelta: Map<String, Float>,
        val measurementCount: Int,
    )

    data class Interpretation(
        val summary: String,
        val muscleStatus: String,
        val doingWell: List<String>,
        val improve: List<String>,
        val validation: String,
    )

    suspend fun analyze(report: Report): Interpretation {
        require(report.current.isNotEmpty()) { "INSUFFICIENT_DATA" }
        var parsed: Interpretation? = null
        val response = aiRuntime.execute(
            request = AiStructuredRequest(
                systemPrompt = SYSTEM_PROMPT,
                userPrompt = buildPrompt(report),
                schemaName = "myfitai_bia_analysis_pipe_v1",
                schemaJson = AiCompactEnvelope.schemaJson,
                maxOutputTokens = 500,
                thinkingBudget = 0,
            ),
            businessValidator = { json -> runCatching {
                parse(json).also { validate(it).getOrThrow(); parsed = it }
            } },
        )
        return parsed ?: parse(response.jsonText).also { validate(it).getOrThrow() }
    }

    private fun buildPrompt(report: Report): String = buildString {
        append("BIA|").append(report.measurementCount).appendLine()
        report.current.forEach { (key, value) -> appendLine("V|$key|$value") }
        report.previousDelta.forEach { (key, value) -> appendLine("D|$key|$value") }
    }

    private fun parse(json: String): Interpretation {
        val lines = AiCompactEnvelope.data(json).lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
        require(lines.firstOrNull() == "BA1") { "BIA_PIPE_INVALID" }
        var summary: String? = null
        var muscleStatus: String? = null
        val doingWell = mutableListOf<String>()
        val improve = mutableListOf<String>()
        var validation = ""
        lines.drop(1).forEach { line ->
            val parts = line.split('|')
            when (parts.firstOrNull()) {
                "S" -> { require(parts.size == 2 && summary == null); summary = parts[1].trim() }
                "M" -> { require(parts.size == 2 && muscleStatus == null); muscleStatus = parts[1].trim() }
                "O" -> { require(parts.size == 2); doingWell += parts[1].trim() }
                "I" -> { require(parts.size == 2); improve += parts[1].trim() }
                "V" -> { require(parts.size == 3 && parts[1] in setOf("0", "1")); validation = parts[2].trim() }
                else -> error("BIA_RECORD_INVALID")
            }
        }
        return Interpretation(requireNotNull(summary), requireNotNull(muscleStatus), doingWell, improve, validation)
    }

    private fun validate(value: Interpretation): Result<Unit> = runCatching {
        val text = (listOf(value.summary, value.muscleStatus) + value.doingWell + value.improve).joinToString(" ").lowercase()
        require(listOf("diagnosi", "patologia", "causa", "ideale perfetto").none(text::contains))
        require(value.summary.isNotBlank() && value.muscleStatus.isNotBlank())
        require(value.doingWell.size <= 4 && value.improve.size <= 4)
    }

    companion object {
        private const val SYSTEM_PROMPT = """
MyFitAI specialist in bioimpedance and sports body composition. Use only the supplied values and deltas. Read the data as a sports professional: explain muscle status, body-composition context, what is already positive, and where to improve through training, recovery, and nutrition. Distinguish measured facts from cautious interpretation. Never diagnose, infer causes, invent missing values, prescribe medical treatment, or compare with aesthetic ideals. A single measurement describes status, not a trend. Output ONLY JSON envelope with data:
BA1
S|brief overall summary
M|brief muscle status
O|positive observation or sporting strength (0..4 rows)
I|improvement priority with practical sport action (0..4 rows)
V|1_or_0|notes
Keep each text row <=20 words. Never use | or newline inside a text field.
"""
    }
}