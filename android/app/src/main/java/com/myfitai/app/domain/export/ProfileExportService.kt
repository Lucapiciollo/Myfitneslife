package com.myfitai.app.domain.export

import android.content.Context
import androidx.core.content.FileProvider
import androidx.room.withTransaction
import com.myfitai.app.data.local.MyFitAiDatabase
import com.myfitai.app.data.local.entity.MealPlanEntity
import com.myfitai.app.data.profile.ActiveProfileStore
import com.myfitai.app.domain.body.BodyProportionEngine
import com.myfitai.app.domain.calculation.LocalCalculationEngine
import com.myfitai.app.domain.calculation.ProfileCalculationMapper
import com.myfitai.app.domain.calculation.WeightResolver
import com.myfitai.app.domain.food.FoodIngredient
import com.myfitai.app.domain.food.FoodMeal
import com.myfitai.app.domain.food.FoodPlanDay
import com.myfitai.app.domain.food.FoodPlanSnapshot
import com.myfitai.app.domain.food.FoodPlanVersion
import com.myfitai.app.domain.food.FoodSupplement
import com.myfitai.app.domain.food.NutritionPlanPeriod
import com.myfitai.app.domain.shopping.ShoppingListEngine
import com.myfitai.app.data.profile.NutritionPlanSchedulePreferences
import com.myfitai.app.data.profile.MealCountPreferences
import com.myfitai.app.data.profile.BiaFrequencyPreferences
import com.myfitai.app.data.profile.AiAutomationPreferences
import android.util.Base64
import com.myfitai.app.domain.time.SystemTimeProvider
import com.myfitai.app.domain.time.TimeProvider
import kotlinx.coroutines.flow.first
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.LocalDate
import java.time.Period
import java.time.format.DateTimeFormatter
import java.util.Locale

class ProfileExportService(
    context: Context,
    private val db: MyFitAiDatabase,
    private val activeProfileStore: ActiveProfileStore,
    private val time: TimeProvider = SystemTimeProvider,
    private val nutritionPlanSchedulePreferences: NutritionPlanSchedulePreferences? = null,
    private val mealCountPreferences: MealCountPreferences? = null,
    private val biaFrequencyPreferences: BiaFrequencyPreferences? = null,
    private val aiAutomationPreferences: AiAutomationPreferences? = null,
) {
    enum class Format { JSON, CSV_ZIP, PDF, WEEKLY_PLAN_PDF }
    data class ExportedFile(val file: File, val mimeType: String)

    private val appContext = context.applicationContext

    suspend fun export(format: Format): ExportedFile {
        activeProfileStore.refreshFromPersistence()
        val profileId = activeProfileStore.currentIdOrNull() ?: error("Nessun profilo attivo")
        return exportProfile(profileId, format)
    }

    suspend fun exportProfile(profileId: Long, format: Format = Format.JSON): ExportedFile {
        require(profileId > 0) { "Profilo non valido" }
        val profile = db.userProfileDao().get(profileId) ?: error("Profilo non disponibile")
        val bia = db.biaMeasurementDao().observeAll(profileId).first().sortedBy { it.measuredAtEpochMillis }
        val body = db.bodyMeasurementDao().observeAll(profileId).first().sortedBy { it.measuredAtEpochMillis }
        val biaAnalysisResults = db.biaAnalysisResultDao().observeAll(profileId).first().sortedBy { it.createdAtEpochMillis }
        val expectationGoals = db.bodyExpectationGoalDao().observeAll(profileId).first().sortedBy { it.periodStartEpochDay }
        val cheats = db.cheatEntryDao().observeAll(profileId).first().sortedBy { it.occurredAtEpochMillis }
        val reviews = db.weeklyReviewDao().observeAll(profileId).first().sortedBy { it.weekStartEpochDay }
        val foodConsumptions = db.foodConsumptionDao().observeAll(profileId).first().sortedWith(compareBy({ it.plannedDateEpochDay }, { it.updatedAtEpochMillis }, { it.id }))
        val plans = db.mealPlanDao().observePlans(profileId).first().sortedBy { it.weekStartEpochDay }

        if (format == Format.WEEKLY_PLAN_PDF) {
            val currentDate = time.today()
            val currentMonday = NutritionPlanPeriod.monday(currentDate).toEpochDay()
            val configuredWeeks = nutritionPlanSchedulePreferences?.periodWeeks(profileId) ?: 1
            val snapshots = plans.asReversed().mapNotNull { loadLatestSnapshot(it) }
            val snapshot = snapshots.firstOrNull { snapshot ->
                val start = LocalDate.ofEpochDay(snapshot.weekStartEpochDay)
                val end = NutritionPlanPeriod.endDate(start, configuredWeeks)
                val dates = snapshot.version.days.map { it.dateEpochDay }.sorted()
                val coversConfiguredPeriod = dates.isNotEmpty() &&
                    dates.last() == end.toEpochDay() &&
                    dates.zipWithNext().all { (left, right) -> right == left + 1 }
                coversConfiguredPeriod && (
                    snapshot.weekStartEpochDay == currentMonday ||
                        snapshot.version.days.any { it.dateEpochDay == currentDate.toEpochDay() }
                    )
            } ?: error("Nessun piano completo per il periodo configurato disponibile")
            val shopping = ShoppingListEngine.aggregate(snapshot)
            val file = exportFile(profile.name, "dieta-settimanale", "pdf")
            PdfExportRenderer(appContext).writeWeeklyPlanReport(file, profile.name, snapshot, shopping)
            return ExportedFile(file, "application/pdf")
        }

        val root = buildCanonicalRoot(profileId, profile, bia, body, biaAnalysisResults, expectationGoals, cheats, reviews, foodConsumptions, plans)

        return when (format) {
            Format.JSON -> writeJson(profile.name, root)
            Format.CSV_ZIP -> writeCsvZip(profile.name, root)
            Format.PDF -> {
                val latestPlan = plans.maxByOrNull { it.weekStartEpochDay }?.let { loadLatestSnapshot(it) }
                val latestBia = bia.lastOrNull()
                val latestBody = body.lastOrNull()
                // Stessa regola di Home, Misure e motore: WeightResolver e l'unica fonte del peso corrente.
                val weightObservations = WeightResolver.observations(bia, body, profileId)
                val weight = WeightResolver.resolve(weightObservations, profile.currentWeightKg)
                val recordedWeights = weightObservations.map { it.measuredAtEpochMillis to it.valueKg }
                val latestWaist = body.lastOrNull { it.waistCm != null }?.waistCm
                val ageYears = profile.birthDateEpochDay?.let { epochDay ->
                    val birth = LocalDate.ofEpochDay(epochDay)
                    if (birth.isAfter(time.today())) null else Period.between(birth, time.today()).years
                }
                val calculation = LocalCalculationEngine.calculate(
                    LocalCalculationEngine.Input(
                        weightKg = weight.valueKg?.toDouble(),
                        heightCm = profile.heightCm?.toDouble(),
                        ageYears = ageYears,
                        biologicalSex = biologicalSex(profile.biologicalSex),
                        bodyFatPercent = latestBia?.bodyFatPercent?.toDouble(),
                        activityLevel = ProfileCalculationMapper.activity(profile.activityLevel),
                        goal = ProfileCalculationMapper.goal(profile.goal),
                        waistCm = latestWaist?.toDouble(),
                    )
                )
                val file = exportFile(profile.name, "report-profilo", "pdf")
                PdfExportRenderer(appContext).writeProfileReport(
                    file,
                    PdfExportRenderer.ProfileReportInput(
                        profile = profile,
                        weight = weight,
                        latestBia = latestBia,
                        latestBody = latestBody,
                        calculation = calculation,
                        proportions = BodyProportionEngine.analyze(latestBody, profile.heightCm),
                        weightTrend = recordedWeights,
                        waistTrend = body.mapNotNull { row -> row.waistCm?.let { row.measuredAtEpochMillis to it } },
                        biaCount = bia.size,
                        bodyCount = body.size,
                        cheatCount = cheats.size,
                        reviewCount = reviews.size,
                        planCount = plans.size,
                        latestPlanTargetKcal = latestPlan?.version?.targetKcal,
                        latestPlanTargetProteinG = latestPlan?.version?.targetProteinG,
                        latestPlanTargetCarbsG = latestPlan?.version?.targetCarbsG,
                        latestPlanTargetFatG = latestPlan?.version?.targetFatG,
                    )
                )
                ExportedFile(file, "application/pdf")
            }
            Format.WEEKLY_PLAN_PDF -> error("Gestito prima del root export")
        }
    }

    fun contentUri(file: File) = FileProvider.getUriForFile(appContext, "${appContext.packageName}.fileprovider", file)

    private suspend fun buildCanonicalRoot(
        profileId: Long,
        profile: com.myfitai.app.data.local.entity.UserProfileEntity,
        bia: List<com.myfitai.app.data.local.entity.BiaMeasurementEntity>,
        body: List<com.myfitai.app.data.local.entity.BodyMeasurementEntity>,
        biaAnalysisResults: List<com.myfitai.app.data.local.entity.BiaAnalysisResultEntity>,
        expectationGoals: List<com.myfitai.app.data.local.entity.BodyExpectationGoalEntity>,
        cheats: List<com.myfitai.app.data.local.entity.CheatEntryEntity>,
        reviews: List<com.myfitai.app.data.local.entity.WeeklyReviewEntity>,
        foodConsumptions: List<com.myfitai.app.data.local.entity.FoodConsumptionEntity>,
        plans: List<MealPlanEntity>,
    ): JSONObject {
        val preferences = JSONObject().apply {
            put("mealCount", mealCountPreferences?.get(profileId) ?: MealCountPreferences.DEFAULT)
            put("biaIntervalDays", biaFrequencyPreferences?.intervalDays ?: BiaFrequencyPreferences.DEFAULT_INTERVAL_DAYS)
            nutritionPlanSchedulePreferences?.get(profileId)?.let { config -> put("nutritionSchedule", JSONObject().apply {
                put("enabled", config.enabled); put("frequency", config.frequency.name); put("dayOfWeek", config.dayOfWeek.value); put("timeMinutes", config.timeMinutes)
            }) }
            aiAutomationPreferences?.let { prefs -> put("aiAutomation", JSONObject().apply {
                AiAutomationPreferences.Feature.entries.forEach { feature ->
                    val config = prefs.get(profileId, feature)
                    put(feature.name, JSONObject().apply { put("enabled", config.enabled); put("frequency", config.frequency.name); put("notificationsEnabled", config.notificationsEnabled); putNullable("lastRun", prefs.lastRun(profileId, feature)) })
                }
            }) }
        }
        val photoBase64 = profile.photoPath?.let { path -> File(path).takeIf { it.exists() }?.readBytes()?.let { bytes -> Base64.encodeToString(bytes, Base64.NO_WRAP) } }

        val planJson = JSONArray()
        plans.forEach { plan ->
            val versions = db.mealPlanDao().observeVersions(profileId, plan.id).first().sortedBy { it.versionNumber }
            val versionsJson = JSONArray()
            versions.forEach { version ->
                val daysJson = JSONArray()
                db.mealPlanDao().getDays(profileId, version.id).forEach { day ->
                    val mealsJson = JSONArray()
                    db.mealPlanDao().getMeals(profileId, day.id).forEach { meal ->
                        val ingredients = JSONArray()
                        db.mealPlanDao().getIngredients(profileId, meal.id).forEach { ing -> ingredients.put(JSONObject().apply {
                            put("id", ing.id); put("name", ing.name); put("quantity", ing.quantity); put("unit", ing.unit); putNullable("displayDose", ing.displayDose); putNullable("weightState", ing.weightState); putNullable("nutritionConfidence", ing.nutritionConfidence); putNullable("category", ing.category); put("sortOrder", ing.sortOrder)
                        }) }
                        mealsJson.put(JSONObject().apply {
                            put("id", meal.id); put("sortOrder", meal.sortOrder); put("type", meal.type); put("title", meal.title); putNullable("timeMinutes", meal.timeMinutes); putNullable("kcal", meal.kcal); putNullable("proteinG", meal.proteinG); putNullable("carbsG", meal.carbsG); putNullable("fatG", meal.fatG); putNullable("preparation", meal.preparation); put("ingredients", ingredients)
                        })
                    }
                    val supplementsJson = JSONArray()
                    parseSupplements(day.supplementsJson).forEach { supplement ->
                        supplementsJson.put(JSONObject().apply {
                            put("kind", supplement.kind)
                            put("name", supplement.name)
                            put("dose", supplement.dose)
                            put("unit", supplement.unit)
                            putNullable("timeMinutes", supplement.timeMinutes)
                            put("kcal", supplement.kcal)
                            put("proteinG", supplement.proteinG)
                            put("carbsG", supplement.carbsG)
                            put("fatG", supplement.fatG)
                            putNullable("notes", supplement.notes)
                        })
                    }
                    daysJson.put(JSONObject().apply {
                        put("id", day.id)
                        put("dateEpochDay", day.dateEpochDay)
                        putNullable("totalKcal", day.totalKcal)
                        putNullable("proteinG", day.proteinG)
                        putNullable("carbsG", day.carbsG)
                        putNullable("fatG", day.fatG)
                        putNullable("hydrationNote", day.hydrationNote)
                        putNullable("targetKcal", day.targetKcal); putNullable("targetProteinG", day.targetProteinG); putNullable("targetCarbsG", day.targetCarbsG); putNullable("targetFatG", day.targetFatG); putNullable("baseTargetKcal", day.baseTargetKcal)
                        put("supplements", supplementsJson)
                        put("meals", mealsJson)
                    })
                }
                versionsJson.put(JSONObject().apply { put("id", version.id); put("versionNumber", version.versionNumber); put("createdAtEpochMillis", version.createdAtEpochMillis); put("source", version.source); putNullable("reason", version.reason); putNullable("targetKcal", version.targetKcal); putNullable("targetProteinG", version.targetProteinG); putNullable("targetCarbsG", version.targetCarbsG); putNullable("targetFatG", version.targetFatG); putNullable("appValidationJson", version.appValidationJson); put("days", daysJson) })
            }
            planJson.put(JSONObject().apply { put("id", plan.id); put("weekStartEpochDay", plan.weekStartEpochDay); put("createdAtEpochMillis", plan.createdAtEpochMillis); put("status", plan.status); put("versions", versionsJson) })
        }
        return ProfileExportFormat.buildRoot(
            exportedAtEpochMillis = time.nowEpochMillis(),
            profileId = profileId,
            profile = profile,
            photoBase64 = photoBase64,
            bia = bia,
            body = body,
            biaAnalysisResults = biaAnalysisResults,
            expectationGoals = expectationGoals,
            profilePreferences = preferences,
            cheats = cheats,
            reviews = reviews,
            foodConsumptions = foodConsumptions,
            mealPlans = planJson,
        )
    }

    private suspend fun loadLatestSnapshot(plan: MealPlanEntity): FoodPlanSnapshot? = db.withTransaction {
        val dao = db.mealPlanDao()
        val version = dao.getLatestVersion(plan.profileId, plan.id) ?: return@withTransaction null
        val days = dao.getDays(plan.profileId, version.id).map { day ->
            FoodPlanDay(
                id = day.id,
                dateEpochDay = day.dateEpochDay,
                totalKcal = day.totalKcal,
                proteinG = day.proteinG,
                carbsG = day.carbsG,
                fatG = day.fatG,
                meals = dao.getMeals(plan.profileId, day.id).map { meal ->
                    FoodMeal(
                        id = meal.id,
                        dayId = meal.dayId,
                        sortOrder = meal.sortOrder,
                        type = meal.type,
                        title = meal.title,
                        timeMinutes = meal.timeMinutes,
                        kcal = meal.kcal,
                        proteinG = meal.proteinG,
                        carbsG = meal.carbsG,
                        fatG = meal.fatG,
                        preparation = meal.preparation,
                        ingredients = dao.getIngredients(plan.profileId, meal.id).map { ingredient ->
                            FoodIngredient(
                                id = ingredient.id,
                                mealId = ingredient.mealId,
                                name = ingredient.name,
                                quantity = ingredient.quantity,
                                unit = ingredient.unit,
                                displayDose = ingredient.displayDose,
                                weightState = ingredient.weightState,
                                nutritionConfidence = ingredient.nutritionConfidence,
                                category = ingredient.category,
                                sortOrder = ingredient.sortOrder,
                            )
                        },
                    )
                },
                supplements = parseSupplements(day.supplementsJson),
                hydrationNote = day.hydrationNote,
            )
        }
        FoodPlanSnapshot(
            planId = plan.id,
            profileId = plan.profileId,
            weekStartEpochDay = plan.weekStartEpochDay,
            version = FoodPlanVersion(
                id = version.id,
                versionNumber = version.versionNumber,
                createdAtEpochMillis = version.createdAtEpochMillis,
                source = version.source,
                reason = version.reason,
                targetKcal = version.targetKcal,
                targetProteinG = version.targetProteinG,
                targetCarbsG = version.targetCarbsG,
                targetFatG = version.targetFatG,
                days = days,
            ),
        )
    }

    private fun parseSupplements(json: String?): List<FoodSupplement> {
        if (json.isNullOrBlank()) return emptyList()
        return runCatching {
            val array = JSONArray(json)
            buildList {
                for (index in 0 until array.length()) {
                    val item = array.getJSONObject(index)
                    add(FoodSupplement(
                        kind = item.optString("kind"),
                        name = item.optString("name"),
                        dose = item.optDouble("dose", 0.0).toFloat(),
                        unit = item.optString("unit"),
                        timeMinutes = if (item.isNull("timeMinutes")) null else item.optInt("timeMinutes"),
                        kcal = item.optInt("kcal", 0),
                        proteinG = item.optDouble("proteinG", 0.0).toFloat(),
                        carbsG = item.optDouble("carbsG", 0.0).toFloat(),
                        fatG = item.optDouble("fatG", 0.0).toFloat(),
                        notes = item.optString("notes").takeIf { it.isNotBlank() },
                    ))
                }
            }
        }.getOrDefault(emptyList())
    }

    private fun writeJson(name: String, root: JSONObject): ExportedFile {
        val file = exportFile(name, "profilo", "json")
        file.writeText(root.toString(2))
        return ExportedFile(file, "application/json")
    }

    private fun writeCsvZip(name: String, root: JSONObject): ExportedFile {
        val file = exportFile(name, "dati", "zip")
        ProfileExportFormat.writeCsvZip(file, root)
        return ExportedFile(file, "application/zip")
    }

    private fun biologicalSex(value: String?): LocalCalculationEngine.BiologicalSex? = when (value?.trim()?.lowercase(Locale.ROOT)) {
        "male", "m", "uomo", "maschio" -> LocalCalculationEngine.BiologicalSex.MALE
        "female", "f", "donna", "femmina" -> LocalCalculationEngine.BiologicalSex.FEMALE
        else -> null
    }

    private fun exportFile(name: String, suffix: String, ext: String): File {
        val dir = File(appContext.cacheDir, "exports").apply { mkdirs() }
        val safe = name.lowercase(Locale.ROOT).replace(Regex("[^a-z0-9]+"), "-").trim('-').ifBlank { "profile" }
        return File(dir, "myfitai-$safe-$suffix-${DateTimeFormatter.BASIC_ISO_DATE.format(time.today())}.$ext")
    }
}
