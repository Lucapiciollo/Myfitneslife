package com.myfitai.app.domain.food

import com.myfitai.app.ai.AiCompactEnvelope
import org.json.JSONObject

/** Preview used before a deviation is persisted. Runtime transport is compact; legacy JSON parsing stays test-compatible. */
object CheatUnderstandingContract {
    const val SCHEMA_NAME = "myfitai_cheat_understanding_pipe_v1"
    val schemaJson: String = AiCompactEnvelope.schemaJson(CheatUnderstandingCompactContract.PROTOCOL)

    data class Preview(
        val understoodFood: String,
        val estimate: CheatAdjustmentContract.Estimate,
    )

    fun parse(jsonText: String): Preview {
        val root = JSONObject(jsonText)
        if (root.has("data")) return CheatUnderstandingCompactContract.parse(jsonText)
        val estimate = root.getJSONObject("estimate")
        return Preview(
            understoodFood = root.getString("understoodFood").trim(),
            estimate = CheatAdjustmentContract.Estimate(
                kcal = estimate.getInt("kcal"),
                proteinG = estimate.getDouble("proteinG").toFloat(),
                carbsG = estimate.getDouble("carbsG").toFloat(),
                fatG = estimate.getDouble("fatG").toFloat(),
                confidence = estimate.getString("confidence").trim(),
                notes = estimate.getString("notes").trim(),
            ),
        )
    }

    fun validate(preview: Preview): Result<Unit> = runCatching {
        require(preview.understoodFood.isNotBlank()) { "UNDERSTANDING_MISSING" }
        require(preview.estimate.kcal > 0) { "CHEAT_ESTIMATE_INVALID" }
        require(preview.estimate.proteinG >= 0f && preview.estimate.carbsG >= 0f && preview.estimate.fatG >= 0f) { "CHEAT_MACROS_INVALID" }
        require(preview.estimate.confidence.lowercase() in setOf("low", "medium", "high")) { "CHEAT_CONFIDENCE_INVALID" }
    }
}
