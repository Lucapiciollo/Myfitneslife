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
                userPrompt = "Estrai esclusivamente i valori numerici visibili nella foto della schermata o dello scontrino BIA. Non interpretare la salute dell'utente.",
                schemaName = BiaImportContract.SCHEMA_NAME,
                schemaJson = BiaImportContract.schemaJson,
                maxOutputTokens = 1_000,
                image = image,
            ),
            maxSchemaRetries = 1,
            businessValidator = { json -> runCatching { BiaImportContract.parse(json).also { BiaImportContract.validate(it).getOrThrow(); parsed = it } } },
        )
        val preview = parsed ?: BiaImportContract.parse(validated.jsonText)
        if (!preview.isBiaDocument) {
            throw NotBiaImage(preview.rejectionReason.ifBlank { "La foto non sembra una rilevazione BIA." })
        }
        return Result(preview, validated.provider.name, validated.model)
    }

    companion object {
        private const val SYSTEM_PROMPT = """
You are the MyFitAI BIA Import Agent. You have exactly one responsibility: parse a photo of a BIA/body-composition measurement report or scale result. Return only JSON matching the supplied schema.
- First classify the image. Set isBiaDocument=true only when the image clearly contains BIA/body-composition measurement context, such as weight, body fat, visceral fat, muscle mass, body water or BMR labels.
- For any other image (person, food, receipt, document, screenshot unrelated to BIA, landscape, or ambiguous image), set isBiaDocument=false, provide a concise Italian rejectionReason, leave every numeric field omitted, and do not provide interpretations.
- Never invent unreadable or missing values: omit fields that are not visible.
- Preserve units and distinguish percentages, kilograms and kcal.
- Extract a measurement timestamp only when it is clearly visible; otherwise omit it.
- confidence describes extraction quality, not medical certainty.
- notes must mention ambiguity, cropped fields or assumptions; never diagnose dehydration, disease or deficiency.
"""
    }
}
