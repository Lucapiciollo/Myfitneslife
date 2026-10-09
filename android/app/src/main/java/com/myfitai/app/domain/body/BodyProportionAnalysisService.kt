package com.myfitai.app.domain.body

import com.myfitai.app.ai.AiCompactEnvelope
import com.myfitai.app.ai.AiRuntimeGateway
import com.myfitai.app.ai.AiStructuredRequest
import java.util.Locale

/** Specialized AI interpreter for deterministic body-proportion results. */
class BodyProportionAnalysisService(
    private val aiRuntime: AiRuntimeGateway,
) {
    data class Interpretation(
        val summary: String,
        val observations: List<String>,
        val monitorNext: List<String>,
        val agentValidation: String,
        val comparison: String = "",
    )

    suspend fun analyze(report: BodyProportionEngine.Report): Interpretation {
        require(report.availableMeasurements > 0) { "INSUFFICIENT_DATA" }
        require(report.currentDate != null) { "LATEST_MEASUREMENT_DATE_MISSING" }
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
        appendLine("T|${clean(report.note)}")
        appendLine("CURRENT_DATE|${report.currentDate}")
        appendLine("PREVIOUS_DATE|${report.previousDate ?: "?"}")
        appendLine("CURRENT_DATE|${report.currentDate}")
        appendLine("PREVIOUS_DATE|${report.previousDate ?: "?"}")
        appendLine("APP_COMPARISON|${report.comparison.joinToString(";") { "${clean(it.label)}:${it.previous}:${it.current}:${it.difference}:${it.unit}" }.ifBlank { "?" }}")
        report.ratios.forEach { appendLine("R|${clean(it.label)}|${it.value}") }
        report.asymmetries.forEach { appendLine("A|${clean(it.label)}|${it.percent}|${clean(it.largerSide ?: "none")}") }
    }

    private fun parse(json: String): Interpretation {
        val lines = AiCompactEnvelope.data(json).lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
        require(lines.firstOrNull() == "BP1") { "BP_PIPE_INVALID" }
        var summary: String? = null
        var comparison: String? = null
        val observations = mutableListOf<String>()
        val monitor = mutableListOf<String>()
        var validation: String? = null
        lines.drop(1).forEach { line ->
            val p = line.split('|')
            when (p.firstOrNull()) {
                "S" -> { require(p.size == 2 && summary == null); summary = p[1].trim() }
                "C" -> { require(p.size == 2 && comparison == null); comparison = p[1].trim() }
                "T" -> require(p.size == 2)
                "O" -> { require(p.size == 2); observations += p[1].trim() }
                "N" -> { require(p.size == 2); monitor += p[1].trim() }
                "V" -> { require(p.size == 3 && validation == null && p[1] in setOf("0", "1")); validation = "valid=${p[1]} · ${p[2].trim()}" }
                else -> error("BP_RECORD_INVALID")
            }
        }
        return Interpretation(
            requireNotNull(summary) { "BP_SUMMARY_MISSING" }, observations, monitor,
            requireNotNull(validation) { "BP_VALIDATION_MISSING" }, requireNotNull(comparison) { "BP_COMPARISON_MISSING" },
        )
    }

    internal fun validateBusiness(value: Interpretation): Result<Unit> = runCatching {
        val forbidden = listOf("diagnosi", "diagnostica", "patologia", "postura scorretta", "causa certa", "ideale perfetto")
        val text = (listOf(value.summary, value.comparison) + value.observations + value.monitorNext).joinToString(" ").lowercase(Locale.ROOT)
        require(forbidden.none(text::contains)) { "UNSUPPORTED_DIAGNOSTIC_OR_CAUSAL_CLAIM" }
        require(value.summary.isNotBlank()) { "EMPTY_SUMMARY" }
        require(value.summary.length <= 500) { "SUMMARY_TOO_LONG" }
        require(value.comparison.isNotBlank() && value.comparison.length <= 400) { "BODY_COMPARISON_INVALID" }
        require(value.observations.size <= 5) { "TOO_MANY_OBSERVATIONS" }
        require(value.monitorNext.size <= 5) { "TOO_MANY_MONITOR_ITEMS" }
        require((value.observations + value.monitorNext).all { it.isNotBlank() && it.length <= 240 }) { "BODY_PROPORTION_TEXT_INVALID" }
    }

    private fun clean(value: String): String = AiCompactEnvelope.clean(value)

    companion object {
        private const val SYSTEM_PROMPT = """
MyFitAI body-measurement interpreter. App numbers are authoritative; explain only, never recalculate, diagnose, infer causes/posture/sex/age, or present aesthetic ideals. Interpret only the latest body measurement and the app-computed comparisons with the immediately previous measurement supplied in APP_COMPARISON. Do not infer a trend from a single comparison, and do not calculate a difference yourself. If APP_COMPARISON is ?, explicitly state that there is no prior measurement to compare. A measured delta is not proof of fat or muscle change; note ordinary measurement variation when appropriate.
Output ONLY JSON envelope with `data` using:
BP1
S|summary (<=500 chars)
C|brief interpretation of APP_COMPARISON without changing or recalculating its values; say insufficient when ?
O|observation (0..5 rows)
N|monitorNext (0..5 rows)
V|1_or_0|notes
Keep O/N rows non-empty and <=240 chars. Never use | or newline inside a text field. Validation V is the agent's self-assessment only; appValidation is computed locally.
"""
    }
}
