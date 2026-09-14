package com.myfitai.app.domain.body

import com.myfitai.app.ai.AiCompactEnvelope
import com.myfitai.app.ai.AiRuntimeService
import com.myfitai.app.ai.AiStructuredRequest

/** Specialized AI interpreter for deterministic body-proportion results. */
class BodyProportionAnalysisService(
    private val aiRuntime: AiRuntimeService,
) {
    data class Interpretation(
        val summary: String,
        val observations: List<String>,
        val monitorNext: List<String>,
        val agentValidation: String,
    )

    suspend fun analyze(report: BodyProportionEngine.Report): Interpretation {
        require(report.availableMeasurements > 0) { "INSUFFICIENT_DATA" }
        var parsed: Interpretation? = null
        val response = aiRuntime.execute(
            request = AiStructuredRequest(
                systemPrompt = SYSTEM_PROMPT,
                userPrompt = buildPrompt(report),
                schemaName = "myfitai_body_proportion_pipe_v1",
                schemaJson = AiCompactEnvelope.schemaJson,
                maxOutputTokens = 500,
                thinkingBudget = 0,
            ),
            businessValidator = { json -> runCatching {
                parse(json).also { validateBusiness(it).getOrThrow(); parsed = it }
            } },
        )
        return parsed ?: parse(response.jsonText).also { validateBusiness(it).getOrThrow() }
    }

    private fun buildPrompt(report: BodyProportionEngine.Report): String = buildString {
        append("B|").append(report.status.name).append('|').append(report.availableMeasurements).append('|')
            .append(report.maxAsymmetryPercent ?: "?").appendLine()
        report.ratios.forEach { appendLine("R|${clean(it.label)}|${it.value}") }
        report.asymmetries.forEach { appendLine("A|${clean(it.label)}|${it.percent}|${clean(it.largerSide ?: "none")}") }
    }

    private fun parse(json: String): Interpretation {
        val lines = AiCompactEnvelope.data(json).lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
        require(lines.firstOrNull() == "BP1") { "BP_PIPE_INVALID" }
        var summary: String? = null
        val observations = mutableListOf<String>()
        val monitor = mutableListOf<String>()
        var validation = ""
        lines.drop(1).forEach { line ->
            val p = line.split('|')
            when (p.firstOrNull()) {
                "S" -> { require(p.size == 2 && summary == null); summary = p[1].trim() }
                "O" -> { require(p.size == 2); observations += p[1].trim() }
                "N" -> { require(p.size == 2); monitor += p[1].trim() }
                "V" -> { require(p.size == 3 && p[1] in setOf("0", "1")); validation = p[2].trim() }
                else -> error("BP_RECORD_INVALID")
            }
        }
        return Interpretation(requireNotNull(summary) { "BP_SUMMARY_MISSING" }, observations, monitor, validation)
    }

    private fun validateBusiness(value: Interpretation): Result<Unit> = runCatching {
        val forbidden = listOf("diagnosi", "diagnostica", "patologia", "postura scorretta", "causa", "ideale perfetto")
        val text = (listOf(value.summary) + value.observations + value.monitorNext).joinToString(" ").lowercase()
        require(forbidden.none(text::contains)) { "UNSUPPORTED_DIAGNOSTIC_OR_CAUSAL_CLAIM" }
        require(value.summary.isNotBlank()) { "EMPTY_SUMMARY" }
        require(value.observations.size <= 5) { "TOO_MANY_OBSERVATIONS" }
        require(value.monitorNext.size <= 5) { "TOO_MANY_MONITOR_ITEMS" }
    }

    private fun clean(value: String): String = AiCompactEnvelope.clean(value)

    companion object {
        private const val SYSTEM_PROMPT = """
MyFitAI body-measurement interpreter. App numbers are authoritative; explain only, never recalculate, diagnose, infer causes/posture/sex/age, or present aesthetic ideals. Output ONLY JSON envelope with `data` using:
BP1
S|summary
O|observation (0..5 rows)
N|monitorNext (0..5 rows)
V|1_or_0|notes
Keep every text row <=16 words. Never use | or newline inside a text field.
"""
    }
}
