package com.myfitai.app.domain.body

import androidx.work.Data
import com.myfitai.app.domain.ai.AiJobHandler
import com.myfitai.app.domain.ai.AiJobOutcome
import com.myfitai.app.domain.ai.AiJobWorker
import org.json.JSONObject

class BodyProportionsAiJobHandler(private val service: BodyProportionAnalysisService) : AiJobHandler {
    override suspend fun execute(profileId: Long, jobKey: String, params: Data): AiJobOutcome = try {
        val report = decode(params.getString(KEY_REPORT).orEmpty())
        val result = service.analyze(report)
        val payload = JSONObject().put("summary", result.summary).put("observations", org.json.JSONArray(result.observations)).put("monitorNext", org.json.JSONArray(result.monitorNext)).put("provider", result.agentValidation).toString()
        AiJobOutcome.Success(Data.Builder().putString(KEY_PAYLOAD, payload).putString(AiJobWorker.KEY_PROVIDER, result.agentValidation).build())
    } catch (error: Exception) { AiJobOutcome.Failure(error.message ?: "Interpretazione misure non disponibile") }

    private fun decode(json: String): BodyProportionEngine.Report {
        val root = JSONObject(json)
        val ratios = root.getJSONArray("ratios").let { array -> buildList { for (i in 0 until array.length()) { val v = array.getJSONObject(i); add(BodyProportionEngine.Ratio(v.getString("key"), v.getString("label"), v.getDouble("value").toFloat(), v.getString("description"))) } } }
        val asym = root.getJSONArray("asymmetries").let { array -> buildList { for (i in 0 until array.length()) { val v = array.getJSONObject(i); add(BodyProportionEngine.Asymmetry(v.getString("key"), v.getString("label"), v.getDouble("percent").toFloat(), v.optString("largerSide").takeIf { it.isNotBlank() })) } } }
        return BodyProportionEngine.Report(BodyProportionEngine.BalanceStatus.valueOf(root.getString("status")), root.optDouble("maxAsymmetry", Double.NaN).takeIf { !it.isNaN() }?.toFloat(), ratios, asym, root.getInt("availableMeasurements"), root.getString("note"))
    }
    companion object { const val KEY_REPORT = "report"; const val KEY_PAYLOAD = "payload" }
}
