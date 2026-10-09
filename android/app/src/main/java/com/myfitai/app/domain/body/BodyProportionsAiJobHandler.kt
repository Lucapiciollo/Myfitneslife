package com.myfitai.app.domain.body

import androidx.work.Data
import com.myfitai.app.domain.ai.AiJobHandler
import com.myfitai.app.domain.ai.AiJobOutcome
import com.myfitai.app.domain.ai.AiJobWorker
import java.util.Locale
import org.json.JSONObject

class BodyProportionsAiJobHandler(private val service: BodyProportionAnalysisService) : AiJobHandler {
    override suspend fun execute(profileId: Long, jobKey: String, params: Data): AiJobOutcome = try {
        val report = decode(params.getString(KEY_REPORT).orEmpty())
        val result = service.analyze(report)
        val payload = JSONObject()
            .put("summary", result.summary)
            // The comparison displayed to the user is calculated by the app, never supplied by the model.
            .put("appComparison", canonicalComparison(report))
            .put("comparison", result.comparison)
            .put("observations", org.json.JSONArray(result.observations))
            .put("monitorNext", org.json.JSONArray(result.monitorNext))
            .put("agentValidation", result.agentValidation)
            .toString()
        AiJobOutcome.Success(Data.Builder().putString(KEY_PAYLOAD, payload).putString(AiJobWorker.KEY_PROVIDER, result.agentValidation).build())
    } catch (error: Exception) { AiJobOutcome.Failure(error.message ?: "Interpretazione misure non disponibile") }

    private fun decode(json: String): BodyProportionEngine.Report {
        val root = JSONObject(json)
        val ratios = root.getJSONArray("ratios").let { array -> buildList { for (i in 0 until array.length()) { val v = array.getJSONObject(i); add(BodyProportionEngine.Ratio(v.getString("key"), v.getString("label"), v.getDouble("value").toFloat(), v.getString("description"))) } } }
        val asym = root.getJSONArray("asymmetries").let { array -> buildList { for (i in 0 until array.length()) { val v = array.getJSONObject(i); add(BodyProportionEngine.Asymmetry(v.getString("key"), v.getString("label"), v.getDouble("percent").toFloat(), v.optString("largerSide").takeIf { it.isNotBlank() })) } } }
        val comparison = root.optJSONArray("comparison")?.let { array -> buildList {
            for (i in 0 until array.length()) {
                val v = array.getJSONObject(i)
                add(BodyProportionEngine.MeasurementComparison(
                    key = v.getString("key"), label = v.getString("label"),
                    previous = v.getDouble("previous").toFloat(), current = v.getDouble("current").toFloat(),
                    difference = v.getDouble("difference").toFloat(), unit = v.optString("unit", "cm"),
                ))
            }
        } }.orEmpty()
        return BodyProportionEngine.Report(
            status = BodyProportionEngine.BalanceStatus.valueOf(root.getString("status")),
            maxAsymmetryPercent = root.optDouble("maxAsymmetry", Double.NaN).takeIf { !it.isNaN() }?.toFloat(),
            ratios = ratios,
            asymmetries = asym,
            availableMeasurements = root.getInt("availableMeasurements"),
            note = root.getString("note"),
            comparison = comparison,
            currentDate = root.optString("currentDate").takeIf { it.isNotBlank() && it != "?" },
            previousDate = root.optString("previousDate").takeIf { it.isNotBlank() && it != "?" },
        )
    }

    private fun canonicalComparison(report: BodyProportionEngine.Report): String {
        if (report.previousDate == null || report.comparison.isEmpty()) {
            return "Una sola rilevazione: non è possibile confrontare l'andamento."
        }
        return buildString {
            append("Precedente ").append(report.previousDate).append(" → ultima ").append(report.currentDate ?: "?").append('\n')
            report.comparison.forEach { item ->
                append(item.label).append(": ")
                    .append(String.format(Locale.ITALIAN, "%.1f", item.previous)).append(' ').append(item.unit)
                    .append(" → ").append(String.format(Locale.ITALIAN, "%.1f", item.current)).append(' ').append(item.unit)
                    .append(" (variazione ").append(String.format(Locale.ITALIAN, "%+.1f", item.difference)).append(' ').append(item.unit).append(")\n")
            }
        }.trim()
    }
    companion object { const val KEY_REPORT = "report"; const val KEY_PAYLOAD = "payload" }
}
