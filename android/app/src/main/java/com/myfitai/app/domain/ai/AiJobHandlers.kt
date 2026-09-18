package com.myfitai.app.domain.ai

import androidx.work.Data
import com.myfitai.app.domain.body.BodyProportionAnalysisService
import com.myfitai.app.domain.body.BodyProportionEngine
import com.myfitai.app.domain.food.NutritionAdviceService
import com.myfitai.app.domain.food.NutritionAdviceContract
import com.myfitai.app.domain.food.NutritionPlanGenerationService
import com.myfitai.app.domain.food.CheatAdjustmentContract
import com.myfitai.app.domain.food.CheatAdjustmentService
import com.myfitai.app.domain.food.MealAlternativeContract
import com.myfitai.app.domain.food.MealAlternativeService
import com.myfitai.app.domain.food.NutritionPathContract
import com.myfitai.app.domain.progress.ProgressAnalysisService
import com.myfitai.app.domain.review.WeeklyReviewService
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate

/** Weekly plan generation. [jobKey] is the week start epoch day. */
class WeeklyPlanAiJobHandler(
    private val service: NutritionPlanGenerationService,
) : AiJobHandler {
    override suspend fun execute(profileId: Long, jobKey: String, params: Data): AiJobOutcome {
        val weekStart = jobKey.toLongOrNull()?.let(LocalDate::ofEpochDay)
            ?: return AiJobOutcome.Failure("Settimana non valida")
        return try {
            val result = service.generateWeek(weekStart)
            // The plan itself is already persisted as a new version; the payload would duplicate it.
            AiJobOutcome.Success(payloadJson = null, provider = "${result.provider} · ${result.model}")
        } catch (error: NutritionPlanGenerationService.GenerationException.NeedsInput) {
            AiJobOutcome.Failure("Completa prima: ${error.fields.joinToString()}")
        } catch (error: NutritionPlanGenerationService.GenerationException.PastWeek) {
            AiJobOutcome.Failure("La settimana selezionata è già conclusa")
        }
    }
}

class NutritionPathAiJobHandler(
    private val aiRuntime: com.myfitai.app.ai.AiRuntimeService,
    private val contextProvider: suspend (Long) -> String,
) : AiJobHandler {
    override suspend fun execute(profileId: Long, jobKey: String, params: Data): AiJobOutcome {
        val request = com.myfitai.app.ai.AiStructuredRequest(
            systemPrompt = """
                NutritionPathAgent. Scope: nutrition pathway selection only. Return compact JSON only.
                Recommend one path and at most two alternatives. Do not diagnose, do not prescribe training,
                do not calculate or change calories/macros, do not invent missing values. The recommendation is
                advisory; the user and app make the final choice. All user-facing text must be Italian.
                Protocol: ${NutritionPathContract.PROTOCOL}
            """.trimIndent(),
            userPrompt = contextProvider(profileId),
            schemaName = NutritionPathContract.SCHEMA_NAME,
            schemaJson = NutritionPathContract.schemaJson,
            maxOutputTokens = 650,
            thinkingBudget = 0,
        )
        var parsed: NutritionPathContract.Response? = null
        val validated = aiRuntime.execute(request, maxSchemaRetries = 2) { json -> runCatching {
            NutritionPathContract.parse(json).also { NutritionPathContract.validateBusiness(it).getOrThrow(); parsed = it }
        } }
        val response = parsed ?: NutritionPathContract.parse(validated.jsonText)
        return AiJobOutcome.Success(
            payloadJson = JSONObject().put("recommendation", JSONObject().put("path", response.recommendation.path).put("confidence", response.recommendation.confidence).put("reason", response.recommendation.reason))
                .put("alternatives", JSONArray().apply { response.alternatives.forEach { put(JSONObject().put("path", it.path).put("confidence", it.confidence).put("reason", it.reason)) } })
                .put("code", response.code).put("explanation", response.explanation).put("agentValid", response.agentValid).toString(),
            provider = "${validated.provider.name} · ${validated.model}",
        )
    }
}

/** Progress analysis. The service already persists its own result for the screen to read back. */
class ProgressAnalysisAiJobHandler(
    private val service: ProgressAnalysisService,
) : AiJobHandler {
    override suspend fun execute(profileId: Long, jobKey: String, params: Data): AiJobOutcome = try {
        val result = service.analyze(profileId)
        AiJobOutcome.Success(payloadJson = null, provider = "${result.provider} · ${result.model}")
    } catch (error: ProgressAnalysisService.AnalysisException.NeedsInput) {
        AiJobOutcome.Failure("Completa prima: ${error.fields.joinToString()}")
    } catch (error: ProgressAnalysisService.AnalysisException.InvalidAiOutput) {
        AiJobOutcome.Failure("Risultato IA non valido: nessun dato salvato")
    }
}

/** Weekly review. [jobKey] is the week start epoch day; the service persists its own entity. */
class WeeklyReviewAiJobHandler(
    private val service: WeeklyReviewService,
) : AiJobHandler {
    override suspend fun execute(profileId: Long, jobKey: String, params: Data): AiJobOutcome {
        val weekStart = jobKey.toLongOrNull()?.let(LocalDate::ofEpochDay)
            ?: return AiJobOutcome.Failure("Settimana non valida")
        return try {
            val result = service.generate(weekStart)
            AiJobOutcome.Success(payloadJson = null, provider = "${result.provider} · ${result.model}")
        } catch (error: WeeklyReviewService.ReviewException.NeedsInput) {
            AiJobOutcome.Failure("Completa prima: ${error.fields.joinToString()}")
        } catch (error: WeeklyReviewService.ReviewException.WeekNotCompleted) {
            AiJobOutcome.Failure("La settimana non è ancora conclusa")
        } catch (error: WeeklyReviewService.ReviewException.InvalidAiOutput) {
            AiJobOutcome.Failure("Risultato IA non valido: nessuna review salvata")
        }
    }
}

/**
 * Body proportion interpretation. The service returns a value object, so the payload is persisted
 * here: it is what the screen reattaches to after the notification.
 */
class BodyProportionsAiJobHandler(
    private val service: BodyProportionAnalysisService,
    private val reportProvider: suspend (Long) -> BodyProportionEngine.Report?,
) : AiJobHandler {
    override suspend fun execute(profileId: Long, jobKey: String, params: Data): AiJobOutcome {
        val report = reportProvider(profileId) ?: return AiJobOutcome.Failure("Misure corporee non disponibili")
        val interpretation = service.analyze(report)
        return AiJobOutcome.Success(payloadJson = encode(interpretation), provider = null)
    }

    companion object {
        fun encode(value: BodyProportionAnalysisService.Interpretation): String = JSONObject()
            .put("summary", value.summary)
            .put("observations", JSONArray(value.observations))
            .put("monitorNext", JSONArray(value.monitorNext))
            .toString()

        fun decode(json: String): BodyProportionAnalysisService.Interpretation {
            val root = JSONObject(json)
            return BodyProportionAnalysisService.Interpretation(
                summary = root.optString("summary"),
                observations = root.optJSONArray("observations").toStringList(),
                monitorNext = root.optJSONArray("monitorNext").toStringList(),
                agentValidation = "",
            )
        }
    }
}

/** Nutrition advice. [jobKey] is a stable hash of the question so the screen can reattach to it. */
class NutritionAdviceAiJobHandler(
    private val service: NutritionAdviceService,
) : AiJobHandler {
    override suspend fun execute(profileId: Long, jobKey: String, params: Data): AiJobOutcome {
        val question = params.getString(KEY_QUESTION).orEmpty()
        if (question.isBlank()) return AiJobOutcome.Failure("Domanda mancante")
        val result = service.ask(question)
        return AiJobOutcome.Success(payloadJson = encode(question, result), provider = result.providerLabel)
    }

    companion object {
        const val KEY_QUESTION = "nutrition_advice_question"

        fun jobKeyFor(question: String): String = (question.trim().hashCode() and 0x7fffffff).toString()

        fun encode(question: String, result: NutritionAdviceService.Result): String = JSONObject()
            .put("question", question)
            .put("accepted", result.accepted)
            .put("answer", result.answer)
            .put("assumptions", result.assumptions)
            .put("providerLabel", result.providerLabel)
            .put("suggestions", JSONArray().apply {
                result.suggestions.forEach { suggestion ->
                    put(JSONObject()
                        .put("title", suggestion.title)
                        .put("reason", suggestion.reason)
                        .put("estimatedKcal", suggestion.estimatedKcal)
                        .put("proteinG", suggestion.proteinG)
                        .put("carbsG", suggestion.carbsG)
                        .put("fatG", suggestion.fatG))
                }
            })
            .toString()

        fun decode(json: String): NutritionAdviceService.Result {
            val root = JSONObject(json)
            val suggestionsJson = root.optJSONArray("suggestions") ?: JSONArray()
            val suggestions = buildList<NutritionAdviceContract.Suggestion> {
                for (index in 0 until suggestionsJson.length()) {
                    val item = suggestionsJson.getJSONObject(index)
                    add(NutritionAdviceContract.Suggestion(
                        title = item.optString("title"),
                        reason = item.optString("reason"),
                        estimatedKcal = item.optInt("estimatedKcal"),
                        proteinG = item.optDouble("proteinG").toFloat(),
                        carbsG = item.optDouble("carbsG").toFloat(),
                        fatG = item.optDouble("fatG").toFloat(),
                    ))
                }
            }
            return NutritionAdviceService.Result(
                accepted = root.optBoolean("accepted"),
                answer = root.optString("answer"),
                suggestions = suggestions,
                assumptions = root.optString("assumptions"),
                providerLabel = root.optString("providerLabel").takeIf { it.isNotBlank() && it != "null" },
            )
        }

        fun decodeQuestion(json: String): String = JSONObject(json).optString("question")
    }
}

/** First phase of the deviation flow: understand and estimate, but do not persist anything. */
class CheatUnderstandingAiJobHandler(
    private val service: CheatAdjustmentService,
    private val imageStore: AiImageJobStore? = null,
) : AiJobHandler {
    override suspend fun execute(profileId: Long, jobKey: String, params: Data): AiJobOutcome = try {
        val input = decodeInput(params.getString(KEY_INPUT_JSON).orEmpty()).let { original ->
            val imagePath = params.getString(KEY_IMAGE_PATH)
            if (imagePath.isNullOrBlank() || imageStore == null) original
            else original.copy(labelImage = imageStore.read(imagePath))
        }
        val understanding = service.analyze(input)
        AiJobOutcome.Success(encodePreview(input, understanding), "${understanding.provider} · ${understanding.model}")
    } catch (error: CheatAdjustmentService.AdjustmentException.NeedsInput) {
        AiJobOutcome.Failure("Completa prima: ${error.fields.joinToString()}")
    } catch (error: CheatAdjustmentService.AdjustmentException.NoPlanForWeek) {
        AiJobOutcome.Failure("Genera prima un piano alimentare per la settimana dello sgarro.")
    }

    companion object {
        const val KEY_INPUT_JSON = "cheat_input_json"
        const val KEY_IMAGE_PATH = "cheat_image_path"
        const val KEY_CONFIRMED_JSON = "cheat_confirmed_json"

        fun jobKeyFor(input: CheatAdjustmentService.Input): String = fingerprint(input).hashCode().toUInt().toString()

        fun encodeInput(input: CheatAdjustmentService.Input): String = JSONObject()
            .put("description", input.description)
            .put("quantityText", input.quantityText)
            .put("notes", input.notes)
            .put("occurredAtEpochMillis", input.occurredAtEpochMillis)
            .toString()

        fun decodeInput(json: String): CheatAdjustmentService.Input {
            val root = JSONObject(json)
            return CheatAdjustmentService.Input(
                description = root.optString("description"),
                quantityText = root.optString("quantityText").takeIf { it.isNotBlank() && it != "null" },
                notes = root.optString("notes").takeIf { it.isNotBlank() && it != "null" },
                occurredAtEpochMillis = root.optLong("occurredAtEpochMillis"),
            )
        }

        fun encodePreview(input: CheatAdjustmentService.Input, value: CheatAdjustmentService.Understanding): String = JSONObject()
            .put("input", JSONObject(encodeInput(input)))
            .put("understoodFood", value.understoodFood)
            .put("estimate", JSONObject()
                .put("kcal", value.estimate.kcal)
                .put("proteinG", value.estimate.proteinG)
                .put("carbsG", value.estimate.carbsG)
                .put("fatG", value.estimate.fatG)
                .put("confidence", value.estimate.confidence)
                .put("notes", value.estimate.notes))
            .put("provider", value.provider)
            .put("model", value.model)
            .toString()

        fun decodePreview(json: String): Pair<CheatAdjustmentService.Input, CheatAdjustmentService.Understanding> {
            val root = JSONObject(json)
            val estimate = root.getJSONObject("estimate")
            val input = decodeInput(root.getJSONObject("input").toString())
            val value = CheatAdjustmentService.Understanding(
                understoodFood = root.optString("understoodFood"),
                estimate = CheatAdjustmentContract.Estimate(
                    kcal = estimate.getInt("kcal"),
                    proteinG = estimate.getDouble("proteinG").toFloat(),
                    carbsG = estimate.getDouble("carbsG").toFloat(),
                    fatG = estimate.getDouble("fatG").toFloat(),
                    confidence = estimate.optString("confidence"),
                    notes = estimate.optString("notes"),
                ),
                provider = root.optString("provider"),
                model = root.optString("model"),
                inputFingerprint = fingerprint(input),
            )
            return input to value
        }

        private fun fingerprint(input: CheatAdjustmentService.Input): String = listOf(
            input.description.trim(), input.quantityText?.trim().orEmpty(),
            input.notes?.trim().orEmpty(), input.occurredAtEpochMillis.toString(),
        ).joinToString("|")
    }
}

/** Second phase: runs only after the user confirms the persisted preview. */
class CheatAdjustmentAiJobHandler(
    private val service: CheatAdjustmentService,
) : AiJobHandler {
    override suspend fun execute(profileId: Long, jobKey: String, params: Data): AiJobOutcome = try {
        val (input, understanding) = CheatUnderstandingAiJobHandler.decodePreview(
            params.getString(CheatUnderstandingAiJobHandler.KEY_CONFIRMED_JSON).orEmpty()
        )
        val result = service.registerAndAdapt(input, understanding)
        AiJobOutcome.Success(encodeResult(result), null)
    } catch (error: CheatAdjustmentService.AdjustmentException.PreviewStale) {
        AiJobOutcome.Failure(error.message ?: "La preview non è più valida")
    } catch (error: CheatAdjustmentService.AdjustmentException.NeedsInput) {
        AiJobOutcome.Failure("Completa prima: ${error.fields.joinToString()}")
    }

    companion object {
        fun jobKeyFor(previewJobKey: String): String = "$previewJobKey-confirmed"

        fun encodeResult(result: CheatAdjustmentService.Result): String = JSONObject()
            .put("cheatId", result.cheatId)
            .put("adapted", result.adapted)
            .put("newVersionId", result.newVersionId)
            .put("estimatedKcal", result.estimatedKcal)
            .put("estimateSummary", result.estimateSummary)
            .put("adaptationSummary", result.adaptationSummary)
            .put("modifiedMeals", JSONArray(result.modifiedMeals))
            .toString()
    }
}

/** Generates alternatives in the background; applying one remains an explicit UI action. */
class MealAlternativesAiJobHandler(
    private val service: MealAlternativeService,
) : AiJobHandler {
    override suspend fun execute(profileId: Long, jobKey: String, params: Data): AiJobOutcome = try {
        val result = service.generate(
            weekStartEpochDay = params.getLong(KEY_WEEK_START, Long.MIN_VALUE),
            dayEpochDay = params.getLong(KEY_DAY, Long.MIN_VALUE),
            mealId = params.getLong(KEY_MEAL_ID, -1L),
        )
        AiJobOutcome.Success(encode(result), "${result.provider} · ${result.model}")
    } catch (error: MealAlternativeService.AlternativeException) {
        AiJobOutcome.Failure(error.message ?: "Alternative non disponibili")
    }

    companion object {
        const val KEY_WEEK_START = "meal_alt_week_start"
        const val KEY_DAY = "meal_alt_day"
        const val KEY_MEAL_ID = "meal_alt_id"

        fun jobKeyFor(weekStart: Long, day: Long, mealId: Long): String = "$weekStart-$day-$mealId"

        fun encode(value: MealAlternativeService.Alternatives): String = JSONObject()
            .put("planId", value.planId).put("sourceVersionId", value.sourceVersionId)
            .put("weekStartEpochDay", value.weekStartEpochDay).put("dayEpochDay", value.dayEpochDay)
            .put("mealId", value.mealId).put("mealType", value.mealType)
            .put("mealTimeMinutes", value.mealTimeMinutes).put("targetKcal", value.targetKcal)
            .put("provider", value.provider).put("model", value.model)
            .put("items", JSONArray().apply { value.items.forEach { put(encodeAlternative(it)) } })
            .toString()

        fun decode(json: String): MealAlternativeService.Alternatives {
            val root = JSONObject(json)
            val items = root.getJSONArray("items")
            return MealAlternativeService.Alternatives(
                planId = root.getLong("planId"), sourceVersionId = root.getLong("sourceVersionId"),
                weekStartEpochDay = root.getLong("weekStartEpochDay"), dayEpochDay = root.getLong("dayEpochDay"),
                mealId = root.getLong("mealId"), mealType = root.getString("mealType"),
                mealTimeMinutes = if (root.isNull("mealTimeMinutes")) null else root.optInt("mealTimeMinutes"),
                targetKcal = root.getInt("targetKcal"),
                items = (0 until items.length()).map { decodeAlternative(items.getJSONObject(it)) },
                provider = root.getString("provider"), model = root.getString("model"),
            )
        }

        private fun encodeAlternative(value: MealAlternativeContract.Alternative): JSONObject = JSONObject()
            .put("title", value.title).put("kcal", value.kcal).put("proteinG", value.proteinG)
            .put("carbsG", value.carbsG).put("fatG", value.fatG).put("preparation", value.preparation)
            .put("reason", value.reason).put("ingredients", JSONArray().apply {
                value.ingredients.forEach { ingredient -> put(JSONObject()
                    .put("name", ingredient.name).put("quantity", ingredient.quantity)
                    .put("unit", ingredient.unit).put("displayDose", ingredient.displayDose)
                    .put("weightState", ingredient.weightState).put("nutritionConfidence", ingredient.nutritionConfidence)
                    .put("category", ingredient.category)) }
            })

        private fun decodeAlternative(value: JSONObject): MealAlternativeContract.Alternative {
            val ingredients = value.getJSONArray("ingredients")
            return MealAlternativeContract.Alternative(
                title = value.getString("title"), kcal = value.getInt("kcal"),
                proteinG = value.getDouble("proteinG").toFloat(), carbsG = value.getDouble("carbsG").toFloat(),
                fatG = value.getDouble("fatG").toFloat(), preparation = value.getString("preparation"),
                reason = value.getString("reason"), ingredients = (0 until ingredients.length()).map { index ->
                    val item = ingredients.getJSONObject(index)
                    MealAlternativeContract.Ingredient(item.getString("name"), item.getDouble("quantity").toFloat(), item.getString("unit"), item.getString("displayDose"), item.getString("weightState"), item.getString("nutritionConfidence"), item.getString("category"))
                },
            )
        }
    }
}

/** Reads and validates one BIA document from the private image file owned by the job. */
class BiaImportAiJobHandler(
    private val service: com.myfitai.app.domain.body.BiaImportService,
    private val imageStore: AiImageJobStore,
) : AiJobHandler {
    override suspend fun execute(profileId: Long, jobKey: String, params: Data): AiJobOutcome = try {
        val imagePath = params.getString(AiJobWorker.KEY_IMAGE_PATH)
            ?: return AiJobOutcome.Failure("Foto BIA non disponibile")
        val result = service.import(imageStore.read(imagePath))
        AiJobOutcome.Success(encode(result), "${result.provider} · ${result.model}")
    } catch (error: com.myfitai.app.domain.body.BiaImportService.NotBiaImage) {
        AiJobOutcome.Failure("Importazione rifiutata: ${error.message}")
    } catch (error: com.myfitai.app.domain.body.BiaImportContract.NotBiaDocument) {
        AiJobOutcome.Failure("La foto non sembra una rilevazione BIA")
    }

    companion object {
        fun encode(result: com.myfitai.app.domain.body.BiaImportService.Result): String {
            val value = result.preview
            return JSONObject()
                .put("isBiaDocument", value.isBiaDocument)
                .put("rejectionReason", value.rejectionReason)
                .put("measuredAtEpochMillis", value.measuredAtEpochMillis)
                .put("weightKg", value.weightKg)
                .put("bodyFatPercent", value.bodyFatPercent)
                .put("visceralFatLevel", value.visceralFatLevel)
                .put("muscleMassKg", value.muscleMassKg)
                .put("skeletalMuscleKg", value.skeletalMuscleKg)
                .put("bodyWaterPercent", value.bodyWaterPercent)
                .put("bmrKcal", value.bmrKcal)
                .put("confidence", value.confidence)
                .put("notes", value.notes)
                .put("provider", result.provider)
                .put("model", result.model)
                .toString()
        }

        fun decode(json: String): com.myfitai.app.domain.body.BiaImportService.Result {
            val root = JSONObject(json)
            val preview = com.myfitai.app.domain.body.BiaImportContract.Preview(
                isBiaDocument = root.optBoolean("isBiaDocument"),
                rejectionReason = root.optString("rejectionReason"),
                measuredAtEpochMillis = root.optLong("measuredAtEpochMillis").takeIf { it > 0L },
                weightKg = root.optDouble("weightKg").toFloatOrNull(),
                bodyFatPercent = root.optDouble("bodyFatPercent").toFloatOrNull(),
                visceralFatLevel = root.optDouble("visceralFatLevel").toFloatOrNull(),
                muscleMassKg = root.optDouble("muscleMassKg").toFloatOrNull(),
                skeletalMuscleKg = root.optDouble("skeletalMuscleKg").toFloatOrNull(),
                bodyWaterPercent = root.optDouble("bodyWaterPercent").toFloatOrNull(),
                bmrKcal = root.optDouble("bmrKcal").toFloatOrNull(),
                confidence = root.optString("confidence"),
                notes = root.optString("notes"),
            )
            return com.myfitai.app.domain.body.BiaImportService.Result(preview, root.optString("provider"), root.optString("model"))
        }

        private fun Double.toFloatOrNull(): Float? = if (isNaN()) null else toFloat()
    }
}

private fun JSONArray?.toStringList(): List<String> {
    if (this == null) return emptyList()
    return (0 until length()).map { optString(it) }.filter { it.isNotBlank() }
}
