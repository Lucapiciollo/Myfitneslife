package com.myfitai.app.domain.body

import com.myfitai.app.ai.AiRuntimeService
import com.myfitai.app.ai.AiStructuredRequest
import org.json.JSONObject

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

        val request = AiStructuredRequest(
            systemPrompt = SYSTEM_PROMPT,
            userPrompt = buildPrompt(report),
            schemaName = "myfitai_body_proportion_analysis_v1",
            schemaJson = SCHEMA,
        )
        val response = aiRuntime.execute(
            request = request,
            businessValidator = ::validateBusiness,
        )
        return parse(response.jsonText)
    }

    private fun buildPrompt(report: BodyProportionEngine.Report): String = buildString {
        appendLine("Deterministic local body proportion report. Numerical values are authoritative.")
        appendLine("balanceStatus=${report.status.name}")
        appendLine("availableMeasurements=${report.availableMeasurements}")
        appendLine("maxAsymmetryPercent=${report.maxAsymmetryPercent ?: "null"}")
        appendLine("Ratios (descriptive only; not aesthetic ideals):")
        report.ratios.forEach { appendLine("- ${it.label}: ${it.value}") }
        appendLine("Measured left/right asymmetries:")
        report.asymmetries.forEach {
            appendLine("- ${it.label}: ${it.percent}% largerSide=${it.largerSide ?: "none"}")
        }
        appendLine("Engine note: ${report.note}")
    }

    private fun validateBusiness(json: String): Result<Unit> = runCatching {
        val root = JSONObject(json)
        val forbidden = listOf(
            "diagnosi", "diagnostica", "patologia", "postura scorretta", "causa", "ideale perfetto",
        )
        val text = buildString {
            append(root.optString("summary"))
            root.optJSONArray("observations")?.let { a -> for (i in 0 until a.length()) append(' ').append(a.optString(i)) }
            root.optJSONArray("monitorNext")?.let { a -> for (i in 0 until a.length()) append(' ').append(a.optString(i)) }
        }.lowercase()
        require(forbidden.none(text::contains)) { "UNSUPPORTED_DIAGNOSTIC_OR_CAUSAL_CLAIM" }
        require(root.getString("summary").isNotBlank()) { "EMPTY_SUMMARY" }
        require(root.getJSONArray("observations").length() <= 5) { "TOO_MANY_OBSERVATIONS" }
        require(root.getJSONArray("monitorNext").length() <= 5) { "TOO_MANY_MONITOR_ITEMS" }
    }

    private fun parse(json: String): Interpretation {
        val root = JSONObject(json)
        return Interpretation(
            summary = root.getString("summary"),
            observations = root.getJSONArray("observations").toStringList(),
            monitorNext = root.getJSONArray("monitorNext").toStringList(),
            agentValidation = root.getJSONObject("agentValidation").optString("notes"),
        )
    }

    private fun org.json.JSONArray.toStringList(): List<String> =
        (0 until length()).map { getString(it) }

    companion object {
        private const val SYSTEM_PROMPT = """
You are BodyCompositionAndProportionAgent for MyFitAI.
The app has already calculated all numerical ratios and asymmetries locally. Those values are authoritative.
Your role is ONLY to explain the supplied measurements in cautious, factual language.
Never invent missing measurements. Never infer sex, gender, age, posture, medical conditions or causes.
Never diagnose. Never present an aesthetic ideal as scientific truth.
Central ratios are descriptive only. Left/right asymmetry is a measured comparison, not a diagnosis.
Do not recalculate or replace numerical values. Do not claim that a training intervention will correct an asymmetry.
Prefer phrases such as "the measurements show", "relative to the recorded values", and "worth monitoring over time".
Return only JSON matching the schema.
"""

        private const val SCHEMA = """
{
  "type":"object",
  "additionalProperties":false,
  "properties":{
    "summary":{"type":"string"},
    "observations":{"type":"array","maxItems":5,"items":{"type":"string"}},
    "monitorNext":{"type":"array","maxItems":5,"items":{"type":"string"}},
    "agentValidation":{
      "type":"object",
      "additionalProperties":false,
      "properties":{
        "valid":{"type":"boolean"},
        "notes":{"type":"string"}
      },
      "required":["valid","notes"]
    }
  },
  "required":["summary","observations","monitorNext","agentValidation"]
}
"""
    }
}
