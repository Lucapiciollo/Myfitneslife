package com.myfitai.app.domain.body

import com.myfitai.app.ai.AiCompactEnvelope
import com.myfitai.app.ai.AiRuntimeGateway
import com.myfitai.app.ai.AiStructuredRequest
import com.myfitai.app.domain.ai.UnsupportedClaimGuard
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
                schemaName = SCHEMA_NAME,
                schemaJson = AiCompactEnvelope.schemaJson,
                maxOutputTokens = MAX_OUTPUT_TOKENS,
                thinkingBudget = 0,
            ),
            businessValidator = { json -> runCatching {
                parse(json).also { validateBusiness(it, report).getOrThrow(); parsed = it }
            } },
        )
        return parsed ?: parse(response.jsonText).also { validateBusiness(it, report).getOrThrow() }
    }

    private fun buildPrompt(report: BodyProportionEngine.Report): String = buildString {
        append("B|").append(report.status.name).append('|').append(report.availableMeasurements).append('|')
            .append(report.maxAsymmetryPercent ?: "?").appendLine()
        appendLine("T|${clean(report.note)}")
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

    /** Same check used by the screen: see [validate]. [source] adds the comparison grounding checks. */
    internal fun validateBusiness(value: Interpretation, source: BodyProportionEngine.Report? = null): Result<Unit> =
        validate(value, source)

    private fun clean(value: String): String = AiCompactEnvelope.clean(value)

    companion object {
        const val SCHEMA_NAME = "myfitai_body_proportion_pipe_v1"

        /** Text limits declared in the prompt and enforced by [validate]. */
        const val MAX_SUMMARY_CHARS = 500
        const val MAX_COMPARISON_CHARS = 400
        const val MAX_ITEM_CHARS = 240
        const val MAX_OBSERVATIONS = 5
        const val MAX_MONITOR_ITEMS = 5
        private const val MAX_NOTES_CHARS = 120

        /** "valid=1 · " prepended by the parser to the model notes. */
        private const val VALIDATION_PREFIX_CHARS = 10

        /** Output budget; must stay equal to the BP1 entry of AiContractRegistry (checked by a test). */
        const val MAX_OUTPUT_TOKENS = 1_500

        /** Characters of the longest output [validate] accepts (records + separators, before JSON escaping). */
        internal const val WORST_CASE_OUTPUT_CHARS =
            4 + (2 + MAX_SUMMARY_CHARS) + (2 + MAX_COMPARISON_CHARS) +
                MAX_OBSERVATIONS * (2 + MAX_ITEM_CHARS) + MAX_MONITOR_ITEMS * (2 + MAX_ITEM_CHARS) + (4 + MAX_NOTES_CHARS)

        private val forbidden = listOf("diagnosi", "diagnostica", "patologia", "postura scorretta", "causa certa", "ideale perfetto")

        /**
         * The ONE business validation of a body-proportion interpretation (service and screen).
         * Claims use [UnsupportedClaimGuard] (negations such as "non è una diagnosi" are accepted). With a [source]
         * report the comparison must be grounded in the app comparison: it may say that a single indicator cannot be
         * evaluated, but it must not call insufficient an indicator the app did compare.
         */
        fun validate(value: Interpretation, source: BodyProportionEngine.Report? = null): Result<Unit> = runCatching {
            val fields = listOf(value.summary, value.comparison) + value.observations + value.monitorNext
            require(!UnsupportedClaimGuard.containsAffirmed(fields, forbidden)) { "UNSUPPORTED_DIAGNOSTIC_OR_CAUSAL_CLAIM" }
            require(value.summary.isNotBlank()) { "EMPTY_SUMMARY" }
            require(value.summary.length <= MAX_SUMMARY_CHARS) { "SUMMARY_TOO_LONG" }
            require(value.comparison.isNotBlank() && value.comparison.length <= MAX_COMPARISON_CHARS) { "BODY_COMPARISON_INVALID" }
            require(value.observations.size <= MAX_OBSERVATIONS) { "TOO_MANY_OBSERVATIONS" }
            require(value.monitorNext.size <= MAX_MONITOR_ITEMS) { "TOO_MANY_MONITOR_ITEMS" }
            require((value.observations + value.monitorNext).all { it.isNotBlank() && it.length <= MAX_ITEM_CHARS }) { "BODY_PROPORTION_TEXT_INVALID" }
            require(value.agentValidation.isNotBlank()) { "BODY_VALIDATION_MISSING" }
            require(value.agentValidation.length <= VALIDATION_PREFIX_CHARS + MAX_NOTES_CHARS) { "BODY_VALIDATION_TOO_LONG" }
            if (source != null) validateComparison(value.comparison, source)
        }

        private fun validateComparison(comparison: String, source: BodyProportionEngine.Report) {
            val text = comparison.lowercase(Locale.ROOT)
            if (source.comparison.isEmpty()) {
                require("insufficient" in text || "non è possibile" in text || "non e possibile" in text) { "BODY_MISSING_COMPARISON_UNCERTAINTY" }
                return
            }
            val labels = source.comparison.map { it.label.lowercase(Locale.ROOT) }
            val falseInsufficient = text.split(';', '.', '\n').any { segment ->
                "insufficient" in segment && labels.any { it in segment }
            }
            require(!falseInsufficient) { "BODY_FALSE_INSUFFICIENT_COMPARISON" }
            require(labels.any { it in text }) { "BODY_COMPARISON_NOT_GROUNDED" }
        }

        internal val SYSTEM_PROMPT = """
MyFitAI body-measurement interpreter. App numbers are authoritative; explain only, never recalculate, diagnose, infer causes/posture/sex/age, or present aesthetic ideals. ${UnsupportedClaimGuard.PROMPT_SAFETY_RULES} Interpret only the latest body measurement and the app-computed comparisons with the immediately previous measurement supplied in APP_COMPARISON. Do not infer a trend from a single comparison, and do not calculate a difference yourself. If APP_COMPARISON is ?, explicitly state that there is no prior measurement to compare. A measured delta is not proof of fat or muscle change; note ordinary measurement variation when appropriate.
Input records: B|status|availableMeasurements|maxAsymmetryPercent. T|app note. CURRENT_DATE|latest measurement date. PREVIOUS_DATE|previous measurement date or ?. APP_COMPARISON|label:previous:current:difference:unit entries separated by ; (or ?). R|label|ratio value. A|label|asymmetryPercent|largerSide (left/right/none).
Asymmetry thresholds are descriptive app thresholds, not evidence of pathological imbalance: the dominant limb can differ naturally; never call a difference a disorder or a defect.
Output ONLY JSON envelope with `data` using:
BP1
S|summary (<=$MAX_SUMMARY_CHARS chars)
C|brief interpretation of APP_COMPARISON (<=$MAX_COMPARISON_CHARS chars) without changing or recalculating its values; say insufficient only for an indicator that APP_COMPARISON does not contain, or for everything when it is ?
O|observation (0..$MAX_OBSERVATIONS rows)
N|monitorNext (0..$MAX_MONITOR_ITEMS rows)
V|1_or_0|notes (<=$MAX_NOTES_CHARS chars)
Keep O/N rows non-empty and <=$MAX_ITEM_CHARS chars. Never use | or newline inside a text field. Validation V is the agent's self-assessment only; appValidation is computed locally.
"""
    }
}
