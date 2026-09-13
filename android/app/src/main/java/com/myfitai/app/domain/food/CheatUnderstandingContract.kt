package com.myfitai.app.domain.food

import org.json.JSONObject

/**
 * Provider-neutral preview used before a deviation is persisted.
 * The user must see and confirm this interpretation before the app saves/adapts anything.
 */
object CheatUnderstandingContract {
    const val SCHEMA_NAME = "myfitai_cheat_understanding_v1"

    val schemaJson: String = JSONObject(
        """
        {
          "type":"object",
          "additionalProperties":false,
          "properties":{
            "understoodFood":{"type":"string"},
            "estimate":{
              "type":"object","additionalProperties":false,
              "properties":{
                "kcal":{"type":"integer"},
                "proteinG":{"type":"number"},
                "carbsG":{"type":"number"},
                "fatG":{"type":"number"},
                "confidence":{"type":"string"},
                "notes":{"type":"string"}
              },
              "required":["kcal","proteinG","carbsG","fatG","confidence","notes"]
            }
          },
          "required":["understoodFood","estimate"]
        }
        """.trimIndent()
    ).toString()

    data class Preview(
        val understoodFood: String,
        val estimate: CheatAdjustmentContract.Estimate,
    )

    fun parse(jsonText: String): Preview {
        val root = JSONObject(jsonText)
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
