package com.myfitai.app.domain.export

import android.content.Context
import android.os.Build
import androidx.core.content.FileProvider
import androidx.room.withTransaction
import com.myfitai.app.data.local.MyFitAiDatabase
import com.myfitai.app.data.local.entity.MealPlanEntity
import com.myfitai.app.data.profile.ActiveProfileStore
import com.myfitai.app.domain.body.BodyProportionEngine
import com.myfitai.app.domain.calculation.LocalCalculationEngine
import com.myfitai.app.domain.calculation.ProfileCalculationMapper
import com.myfitai.app.domain.food.FoodIngredient
import com.myfitai.app.domain.food.FoodMeal
import com.myfitai.app.domain.food.FoodPlanDay
import com.myfitai.app.domain.food.FoodPlanSnapshot
import com.myfitai.app.domain.food.FoodPlanVersion
import com.myfitai.app.domain.food.FoodSupplement
import com.myfitai.app.domain.shopping.ShoppingListEngine
import com.myfitai.app.domain.time.SystemTimeProvider
import com.myfitai.app.domain.time.TimeProvider
import kotlinx.coroutines.flow.first
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.Period
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAdjusters
import java.time.ZoneId
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ProfileExportService(
    context: Context,
    private val db: MyFitAiDatabase,
    private val activeProfileStore: ActiveProfileStore,
    private val time: TimeProvider = SystemTimeProvider,
) {
    enum class Format { JSON, CSV_ZIP, PDF, WEEKLY_PLAN_PDF }
    data class ExportedFile(val file: File, val mimeType: String)

    private val appContext = context.applicationContext

    suspend fun export(format: Format): ExportedFile {
        val profileId = activeProfileStore.currentIdOrNull() ?: error("Nessun profilo attivo")
        val profile = db.userProfileDao().get(profileId) ?: error("Profilo non disponibile")
        val bia = db.biaMeasurementDao().observeAll(profileId).first().sortedBy { it.measuredAtEpochMillis }
        val body = db.bodyMeasurementDao().observeAll(profileId).first().sortedBy { it.measuredAtEpochMillis }
        val workouts = db.workoutDao().observeAll(profileId).first().sortedBy { it.startedAtEpochMillis }
        val cheats = db.cheatEntryDao().observeAll(profileId).first().sortedBy { it.occurredAtEpochMillis }
        val reviews = db.weeklyReviewDao().observeAll(profileId).first().sortedBy { it.weekStartEpochDay }
        val foodConsumptions = db.foodConsumptionDao().observeAll(profileId).first().sortedWith(compareBy({ it.plannedDateEpochDay }, { it.updatedAtEpochMillis }, { it.id }))
        val plans = db.mealPlanDao().observePlans(profileId).first().sortedBy { it.weekStartEpochDay }
        val workoutEnergy = db.workoutEnergyExpenditureDao().observeAll(profileId).first().sortedBy { it.exerciseDateEpochDay }
        val aiUsage = db.aiUsageDao().all()

        if (format == Format.WEEKLY_PLAN_PDF) {
            val currentWeekStart = time.today()
                .with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
                .toEpochDay()
            val selectedPlan = plans.firstOrNull { it.weekStartEpochDay == currentWeekStart }
                ?: plans.maxByOrNull { it.weekStartEpochDay }
                ?: error("Nessun piano alimentare disponibile")
            val snapshot = loadLatestSnapshot(selectedPlan) ?: error("Piano alimentare non disponibile")
            val shopping = ShoppingListEngine.aggregate(snapshot)
            val file = exportFile(profile.name, "dieta-settimanale", "pdf")
            PdfExportRenderer.writeWeeklyPlanReport(file, profile.name, snapshot, shopping)
            return ExportedFile(file, "application/pdf")
        }

        val root = buildCanonicalRoot(profileId, profile, bia, body, workouts, workoutEnergy, cheats, reviews, foodConsumptions, plans, aiUsage)

        return when (format) {
            Format.JSON -> writeJson(profile.name, root)
            Format.CSV_ZIP -> writeCsvZip(profile.name, root)
            Format.PDF -> {
                val latestPlan = plans.maxByOrNull { it.weekStartEpochDay }?.let { loadLatestSnapshot(it) }
                val latestBia = bia.lastOrNull()
                val latestBody = body.lastOrNull()
                val ageYears = profile.birthDateEpochDay?.let { epochDay ->
                    val birth = LocalDate.ofEpochDay(epochDay)
                    if (birth.isAfter(time.today())) null else Period.between(birth, time.today()).years
                }
                val calculation = LocalCalculationEngine.calculate(
                    LocalCalculationEngine.Input(
                        weightKg = (latestBia?.weightKg ?: profile.currentWeightKg)?.toDouble(),
                        heightCm = profile.heightCm?.toDouble(),
                        ageYears = ageYears,
                        biologicalSex = biologicalSex(profile.biologicalSex),
                        bodyFatPercent = latestBia?.bodyFatPercent?.toDouble(),
                        activityLevel = ProfileCalculationMapper.activity(profile.activityLevel),
                        goal = ProfileCalculationMapper.goal(profile.goal),
                        waistCm = latestBody?.waistCm?.toDouble(),
                        exerciseKcal = db.workoutEnergyExpenditureDao().forDay(profileId, time.today().toEpochDay()).sumOf { it.caloriesKcal.coerceAtLeast(0) },
                    )
                )
                val file = exportFile(profile.name, "report-profilo", "pdf")
                PdfExportRenderer.writeProfileReport(
                    file,
                    PdfExportRenderer.ProfileReportInput(
                        profile = profile,
                        latestBia = latestBia,
                        latestBody = latestBody,
                        calculation = calculation,
                        proportions = BodyProportionEngine.analyze(latestBody, profile.heightCm),
                        weightTrend = bia.mapNotNull { row -> row.weightKg?.let { row.measuredAtEpochMillis to it } },
                        waistTrend = body.mapNotNull { row -> row.waistCm?.let { row.measuredAtEpochMillis to it } },
                        biaCount = bia.size,
                        bodyCount = body.size,
                        workoutCount = workouts.size,
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
        workouts: List<com.myfitai.app.data.local.entity.WorkoutEntity>,
        workoutEnergy: List<com.myfitai.app.data.local.entity.WorkoutEnergyExpenditureEntity>,
        cheats: List<com.myfitai.app.data.local.entity.CheatEntryEntity>,
        reviews: List<com.myfitai.app.data.local.entity.WeeklyReviewEntity>,
        foodConsumptions: List<com.myfitai.app.data.local.entity.FoodConsumptionEntity>,
        plans: List<MealPlanEntity>,
        aiUsage: List<com.myfitai.app.data.local.entity.AiUsageRecordEntity>,
    ): JSONObject {
        val root = JSONObject().apply {
            put("schema", "myfitai_profile_export_v2")
            put("schemaVersion", 2)
            put("exportedAtEpochMillis", time.nowEpochMillis())
            put("profileId", profileId)
            put("appVersion", runCatching { appContext.packageManager.getPackageInfo(appContext.packageName, 0).versionName }.getOrNull() ?: "unknown")
            put("locale", Locale.getDefault().toLanguageTag())
            put("timezone", time.zoneId.id)
            put("contentLanguage", "it")
            put("summary", JSONObject().apply {
                put("biaMeasurements", bia.size); put("bodyMeasurements", body.size); put("workouts", workouts.size); put("cheatEntries", cheats.size)
                put("weeklyReviews", reviews.size); put("foodConsumptions", foodConsumptions.size); put("mealPlans", plans.size)
                put("containsQaData", ExportDataQualityAnalyzer.dataQuality(bia, body, workouts, cheats, foodConsumptions, reviews, plans).getBoolean("containsQaData"))
            })
            put("profile", JSONObject().apply {
                put("id", profile.id)
                put("name", profile.name)
                putNullable("birthDateEpochDay", profile.birthDateEpochDay)
                putNullable("biologicalSex", profile.biologicalSex)
                putNullable("heightCm", profile.heightCm)
                putNullable("initialWeightKg", profile.initialWeightKg)
                putNullable("currentWeightKg", profile.currentWeightKg)
                putNullable("goal", profile.goal)
                putNullable("activityLevel", profile.activityLevel)
                putNullable("wakeTimeMinutes", profile.wakeTimeMinutes)
                putNullable("sleepTimeMinutes", profile.sleepTimeMinutes)
                putNullable("dietaryPreferencesJson", profile.dietaryPreferencesJson)
            })
            put("biaMeasurements", JSONArray().apply { bia.forEach { r -> put(JSONObject().apply {
                 put("id", r.id); put("measuredAtEpochMillis", r.measuredAtEpochMillis); put("measuredAtIso", iso(r.measuredAtEpochMillis)); putOrigin(ExportDataQualityAnalyzer.sourceFrom(r.notes))
                putNullable("weightKg", r.weightKg); putNullable("bodyFatPercent", r.bodyFatPercent); putNullable("visceralFatLevel", r.visceralFatLevel)
                putNullable("muscleMassKg", r.muscleMassKg); putNullable("skeletalMuscleKg", r.skeletalMuscleKg); putNullable("bodyWaterPercent", r.bodyWaterPercent)
                putNullable("bmrKcal", r.bmrKcal); put("fasting", r.fasting); put("justWokeUp", r.justWokeUp); put("afterBathroom", r.afterBathroom); put("noRecentWorkout", r.noRecentWorkout); putNullable("notes", r.notes)
            }) } })
            put("bodyMeasurements", JSONArray().apply { body.forEach { r -> put(JSONObject().apply {
                 put("id", r.id); put("measuredAtEpochMillis", r.measuredAtEpochMillis); put("measuredAtIso", iso(r.measuredAtEpochMillis)); putOrigin(ExportDataQualityAnalyzer.sourceFrom(r.notes)); putNullable("chestCm", r.chestCm); putNullable("waistCm", r.waistCm)
                putNullable("abdomenCm", r.abdomenCm); putNullable("shouldersCm", r.shouldersCm); putNullable("glutesCm", r.glutesCm); putNullable("armLeftCm", r.armLeftCm)
                putNullable("armRightCm", r.armRightCm); putNullable("thighLeftCm", r.thighLeftCm); putNullable("thighRightCm", r.thighRightCm); putNullable("calfLeftCm", r.calfLeftCm); putNullable("calfRightCm", r.calfRightCm); putNullable("notes", r.notes)
            }) } })
            put("workouts", JSONArray().apply { workouts.forEach { r -> put(JSONObject().apply {
                 put("id", r.id); put("startedAtEpochMillis", r.startedAtEpochMillis); put("startedAtIso", iso(r.startedAtEpochMillis)); put("type", r.type); put("title", r.title); putNullable("durationMinutes", r.durationMinutes); put("isRestDay", r.isRestDay); putNullable("perceivedIntensity", r.perceivedIntensity); putNullable("notes", r.notes); putOrigin(ExportDataQualityAnalyzer.sourceFrom(r.type, r.title, r.notes))
            }) } })
            put("cheatEntries", JSONArray().apply { cheats.forEach { r -> put(JSONObject().apply {
                 put("id", r.id); put("occurredAtEpochMillis", r.occurredAtEpochMillis); put("occurredAtIso", iso(r.occurredAtEpochMillis)); put("description", r.description); putNullable("quantityText", r.quantityText); putNullable("estimatedKcal", r.estimatedKcal); putNullable("estimatedProteinG", r.estimatedProteinG); putNullable("estimatedCarbsG", r.estimatedCarbsG); putNullable("estimatedFatG", r.estimatedFatG); putNullable("planVersionId", r.planVersionId); putNullable("notes", r.notes); putOrigin(ExportDataQualityAnalyzer.sourceFrom(r.description, r.notes))
            }) } })
            put("weeklyReviews", JSONArray().apply { reviews.forEach { r -> put(JSONObject().apply {
                put("id", r.id); put("weekStartEpochDay", r.weekStartEpochDay); put("createdAtEpochMillis", r.createdAtEpochMillis); putNullable("adherencePercent", r.adherencePercent); put("summary", r.summary); putNullable("structuredJson", r.structuredJson)
            }) } })
            put("foodConsumptions", JSONArray().apply { foodConsumptions.forEach { r -> put(JSONObject().apply {
                 put("id", r.id); put("planId", r.planId); put("planVersionId", r.planVersionId); put("dayId", r.dayId); put("dataSource", "USER")
                put("plannedDateEpochDay", r.plannedDateEpochDay); put("itemType", r.itemType); put("itemKey", r.itemKey)
                putNullable("mealId", r.mealId); putNullable("supplementKey", r.supplementKey); put("status", r.status)
                put("recordedAtEpochMillis", r.recordedAtEpochMillis); put("updatedAtEpochMillis", r.updatedAtEpochMillis)
                put("quantityFactor", r.quantityFactor); putNullable("kcal", r.kcal); putNullable("proteinG", r.proteinG)
                putNullable("carbsG", r.carbsG); putNullable("fatG", r.fatG); putNullable("note", r.note)
            }) } })
            put("workoutEnergyExpenditures", JSONArray().apply { workoutEnergy.forEach { r -> put(JSONObject().apply {
                put("id", r.id); put("workoutId", r.workoutId); put("exerciseDateEpochDay", r.exerciseDateEpochDay); put("caloriesKcal", r.caloriesKcal); put("source", r.source); put("createdAtEpochMillis", r.createdAtEpochMillis); put("updatedAtEpochMillis", r.updatedAtEpochMillis); putOrigin(if (r.source == "DEFAULT") ExportOrigin(ExportDataSource.SYSTEM) else ExportOrigin(ExportDataSource.IMPORT))
            }) } })
            put("dataQuality", ExportDataQualityAnalyzer.dataQuality(bia, body, workouts, cheats, foodConsumptions, reviews, plans))
            put("computedTrends", ExportTrendCalculator.trends(bia, body))
            put("nutritionAdherence", JSONObject().apply {
                put("available", foodConsumptions.isNotEmpty())
                put("reason", if (foodConsumptions.isEmpty()) "NO_FOOD_CONSUMPTIONS" else JSONObject.NULL)
                put("daysWithData", foodConsumptions.map { it.plannedDateEpochDay }.toSet().size)
                put("daysExpected", 7)
            })
            put("workoutSummary", workoutSummary(workouts))
            put("cheatSummary", JSONObject().apply { put("count", cheats.size); put("estimatedKcalTotal", cheats.sumOf { it.estimatedKcal ?: 0 }); put("averageEstimatedKcal", cheats.mapNotNull { it.estimatedKcal }.averageOrZero()) })
            put("weeklyReviewSummary", JSONObject().apply { put("available", reviews.isNotEmpty()); put("count", reviews.size) })
            put("anomalies", detectAnomalies(bia, body, workouts, foodConsumptions, plans))
            put("aiUsage", JSONArray().apply { aiUsage.forEach { r -> put(JSONObject().apply { put("timestampEpochMillis", r.timestampEpochMillis); put("timestampIso", iso(r.timestampEpochMillis)); put("provider", r.provider); put("model", r.model); put("inputTokens", r.inputTokens); put("outputTokens", r.outputTokens); put("thoughtsTokens", r.thoughtsTokens); put("cachedTokens", r.cachedTokens); putNullable("totalTokens", r.totalTokens); put("costUsdNanos", r.costUsdNanos); put("pricingSource", r.pricingSource); put("pricingEffectiveDate", r.pricingEffectiveDate) }) } })
        }

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
                             put("id", ing.id); put("name", ing.name); put("quantity", ing.quantity); put("unit", ing.unit); putNullable("displayDose", ing.displayDose); put("weightState", ExportDataQualityAnalyzer.normalizeWeightState(ing.weightState)); putNullable("nutritionConfidence", ExportDataQualityAnalyzer.nutritionConfidence(ing.nutritionConfidence)); put("category", ExportDataQualityAnalyzer.normalizeCategory(ing.category)); put("sortOrder", ing.sortOrder)
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
                        put("supplements", supplementsJson)
                        put("meals", mealsJson)
                    })
                }
                versionsJson.put(JSONObject().apply { put("id", version.id); put("versionNumber", version.versionNumber); put("createdAtEpochMillis", version.createdAtEpochMillis); put("source", version.source); putNullable("reason", version.reason); putNullable("targetKcal", version.targetKcal); putNullable("targetProteinG", version.targetProteinG); putNullable("targetCarbsG", version.targetCarbsG); putNullable("targetFatG", version.targetFatG); put("days", daysJson) })
            }
            planJson.put(JSONObject().apply { put("id", plan.id); put("weekStartEpochDay", plan.weekStartEpochDay); put("createdAtEpochMillis", plan.createdAtEpochMillis); put("status", plan.status); put("versions", versionsJson) })
        }
        for (planIndex in 0 until planJson.length()) {
            val planJsonObject = planJson.getJSONObject(planIndex)
            val versions = planJsonObject.getJSONArray("versions")
            for (versionIndex in 0 until versions.length()) {
                val version = versions.getJSONObject(versionIndex)
                val origin = ExportDataQualityAnalyzer.sourceFrom(version.optString("source"), version.optString("reason"))
                version.put("dataSource", origin.dataSource.name)
                version.put("isTestData", origin.dataSource == ExportDataSource.QA || origin.dataSource == ExportDataSource.SYNTHETIC)
                version.put("aiMetadata", JSONObject().apply {
                    put("providerOrSource", version.optString("source"))
                    put("generationReason", version.optString("reason"))
                    put("agentValidation", JSONObject.NULL)
                    put("appValidation", JSONObject().put("available", false).put("reason", "NOT_PERSISTED_FOR_HISTORICAL_VERSION"))
                })
            }
        }
        root.put("mealPlans", planJson)
        return root
    }

    private fun iso(epochMillis: Long): String = Instant.ofEpochMilli(epochMillis).atZone(time.zoneId).toOffsetDateTime().toString()

    private fun org.json.JSONObject.putOrigin(origin: ExportOrigin) {
        put("dataSource", origin.dataSource.name)
        put("originReasons", JSONArray(origin.reasons))
        put("isTestData", origin.dataSource == ExportDataSource.QA || origin.dataSource == ExportDataSource.SYNTHETIC)
    }

    private fun workoutSummary(rows: List<com.myfitai.app.data.local.entity.WorkoutEntity>): JSONObject {
        val active = rows.filterNot { it.isRestDay }.sortedBy { it.startedAtEpochMillis }
        var longest = 0
        var current = 0
        var previous: LocalDate? = null
        active.forEach { row ->
            val date = Instant.ofEpochMilli(row.startedAtEpochMillis).atZone(time.zoneId).toLocalDate()
            current = if (previous != null && date == previous!!.plusDays(1)) current + 1 else 1
            longest = maxOf(longest, current)
            previous = date
        }
        return JSONObject().apply {
            put("totalWorkouts", active.size)
            put("totalMinutes", active.sumOf { it.durationMinutes ?: 0 })
            put("restDaysRecorded", rows.count { it.isRestDay })
            putNullable("firstWorkoutAt", active.firstOrNull()?.startedAtEpochMillis)
            putNullable("lastWorkoutAt", active.lastOrNull()?.startedAtEpochMillis)
            put("longestConsecutiveWorkoutDays", longest)
        }
    }

    private fun detectAnomalies(
        bia: List<com.myfitai.app.data.local.entity.BiaMeasurementEntity>,
        body: List<com.myfitai.app.data.local.entity.BodyMeasurementEntity>,
        workouts: List<com.myfitai.app.data.local.entity.WorkoutEntity>,
        consumptions: List<com.myfitai.app.data.local.entity.FoodConsumptionEntity>,
        plans: List<MealPlanEntity>,
    ): JSONArray = JSONArray().apply {
        val duplicateBiaDates = bia.groupBy { Instant.ofEpochMilli(it.measuredAtEpochMillis).atZone(time.zoneId).toLocalDate() }.filterValues { it.size > 1 }
        if (duplicateBiaDates.isNotEmpty()) put(JSONObject().apply { put("type", "DUPLICATE_MEASUREMENT_DATE"); put("severity", "WARNING"); put("message", "Sono presenti più misurazioni BIA nello stesso giorno"); put("count", duplicateBiaDates.values.sumOf { it.size }) })
        val dailyWorkouts = workouts.filterNot { it.isRestDay }.groupBy { Instant.ofEpochMilli(it.startedAtEpochMillis).atZone(time.zoneId).toLocalDate() }.keys.sorted()
        var longest = 0; var current = 0; var previous: LocalDate? = null
        dailyWorkouts.forEach { date -> current = if (previous != null && date == previous!!.plusDays(1)) current + 1 else 1; longest = maxOf(longest, current); previous = date }
        if (longest >= 14) put(JSONObject().apply { put("type", "CONSECUTIVE_DAILY_WORKOUTS"); put("severity", "WARNING"); put("message", "$longest allenamenti su giorni consecutivi"); put("longestConsecutiveDays", longest) })
        if (plans.isNotEmpty() && consumptions.isEmpty()) put(JSONObject().apply { put("type", "PLAN_WITHOUT_CONSUMPTIONS"); put("severity", "INFO"); put("message", "Sono presenti piani ma nessun consumo reale registrato") })
        val invalidBia = bia.count { listOf(it.weightKg, it.bodyFatPercent, it.visceralFatLevel, it.muscleMassKg, it.skeletalMuscleKg, it.bodyWaterPercent, it.bmrKcal).any { value -> value?.isNaN() == true || value?.isInfinite() == true || value != null && value < 0f } }
        if (invalidBia > 0) put(JSONObject().apply { put("type", "INVALID_BIA_VALUE"); put("severity", "ERROR"); put("message", "$invalidBia misurazioni BIA contengono valori non validi"); put("count", invalidBia) })
    }

    private fun List<Int>.averageOrZero(): Double = if (isEmpty()) 0.0 else average()

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
        ZipOutputStream(FileOutputStream(file)).use { zip ->
            val tables = listOf("biaMeasurements", "bodyMeasurements", "workouts", "cheatEntries", "weeklyReviews", "foodConsumptions")
            tables.forEach { key -> addCsv(zip, "$key.csv", root.getJSONArray(key)) }
            addCsv(zip, "profile.csv", JSONArray().put(root.getJSONObject("profile")))
            zip.putNextEntry(ZipEntry("README.txt"))
            zip.write("MyFitAI CSV export. mealPlans are preserved completely in meal_plans.json because the hierarchy plan/version/day/meal/ingredient is not losslessly representable in one flat CSV. foodConsumptions.csv contains explicit consumption events and nutritional snapshots.\n".toByteArray())
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("meal_plans.json"))
            zip.write(root.getJSONArray("mealPlans").toString(2).toByteArray())
            zip.closeEntry()
        }
        return ExportedFile(file, "application/zip")
    }

    private fun addCsv(zip: ZipOutputStream, name: String, rows: JSONArray) {
        zip.putNextEntry(ZipEntry(name))
        if (rows.length() == 0) {
            zip.closeEntry()
            return
        }
        val keys = rows.getJSONObject(0).keys().asSequence().toList()
        zip.write((keys.joinToString(",") + "\n").toByteArray())
        repeat(rows.length()) { index ->
            val row = rows.getJSONObject(index)
            zip.write((keys.joinToString(",") { csv(row.opt(it).takeUnless { v -> v == JSONObject.NULL }?.toString().orEmpty()) } + "\n").toByteArray())
        }
        zip.closeEntry()
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

    private fun csv(value: String) = "\"${value.replace("\"", "\"\"").replace("\r", " ").replace("\n", " ")}\""
    private fun JSONObject.putNullable(key: String, value: Any?) { put(key, value ?: JSONObject.NULL) }
}
