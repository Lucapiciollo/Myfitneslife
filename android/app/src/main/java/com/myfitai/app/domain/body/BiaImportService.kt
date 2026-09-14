package com.myfitai.app.domain.body

import com.myfitai.app.ai.AiImageInput
import com.myfitai.app.ai.AiRuntimeGateway
import com.myfitai.app.ai.AiStructuredRequest

class BiaImportService(private val aiRuntime: AiRuntimeGateway) {
    data class Result(
        val preview: BiaImportContract.Preview,
        val provider: String,
        val model: String,
    )

    class NotBiaImage(message: String) : IllegalArgumentException(message)

    suspend fun import(image: AiImageInput): Result {
        var parsed: BiaImportContract.Preview? = null
        val validated = aiRuntime.execute(
            request = AiStructuredRequest(
                systemPrompt = SYSTEM_PROMPT,
                userPrompt = "Leggi solo i valori visibili. Campi assenti=?; testo senza | o newline.",
                schemaName = BiaImportCompactContract.SCHEMA_NAME,
                schemaJson = BiaImportCompactContract.schemaJson,
                maxOutputTokens = 350,
                image = image,
                thinkingBudget = 0,
            ),
            maxSchemaRetries = 1,
            businessValidator = { json -> runCatching {
                BiaImportCompactContract.parseEnvelope(json).also {
                    BiaImportContract.validate(it).getOrThrow()
                    parsed = it
                }
            } },
        )
        val preview = parsed ?: BiaImportCompactContract.parseEnvelope(validated.jsonText)
        if (!preview.isBiaDocument) {
            throw NotBiaImage(preview.rejectionReason.ifBlank { "La foto non sembra una rilevazione BIA." })
        }
        return Result(preview, validated.provider.name, validated.model)
    }

    companion object {
        private const val SYSTEM_PROMPT = """
MyFitAI BIA Import Agent. Output ONLY the JSON envelope; `data` contains exactly:
BIA1
B|0_or_1|timestamp_or_?|weight_or_?|bodyFat_or_?|visceral_or_?|muscle_or_?|skeletal_or_?|water_or_?|bmr_or_?|HIGH_MEDIUM_LOW|rejectionReason|notes
Classify 1 only for a clear BIA/body-composition report. Otherwise 0, all numeric fields ?, concise Italian rejectionReason. Never invent unreadable values. Timestamp only if visible. Confidence is extraction quality. Notes only ambiguity/crop/assumption, max 12 words. No diagnosis. Never use | or newline inside text fields.
"""
    }
}
