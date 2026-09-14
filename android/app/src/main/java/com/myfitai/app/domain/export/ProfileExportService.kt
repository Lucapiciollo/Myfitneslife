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
import java.time.LocalDate
import java.time.Period
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAdjusters
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
        val plans = db.mealPlanDao().observePlans(profileId).first().sortedBy { it.weekStartEpochDay }

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

        val root = buildCanonicalRoot(profileId, profile, bia, body, workouts, cheats, reviews, plans)

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
        cheats: List<com.myfitai.app.data.local.entity.CheatEntryEntity>,
        reviews: List<com.myfitai.app.data.local.entity.WeeklyReviewEntity>,
        plans: List<MealPlanEntity>,
    ): JSONObject {
        val root = JSONObject().apply {
            put("schema", "myfitai_profile_export_v1")
            put("exportedAtEpochMillis", time.nowEpochMillis())
            put("profileId", profileId)
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
                put("id", r.id); put("measuredAtEpochMillis", r.measuredAtEpochMillis)
                putNullable("weightKg", r.weightKg); putNullable("bodyFatPercent", r.bodyFatPercent); putNullable("visceralFatLevel", r.visceralFatLevel)
                putNullable("muscleMassKg", r.muscleMassKg); putNullable("skeletalMuscleKg", r.skeletalMuscleKg); putNullable("bodyWaterPercent", r.bodyWaterPercent)
                putNullable("bmrKcal", r.bmrKcal); put("fasting", r.fasting); put("justWokeUp", r.justWokeUp); put("afterBathroom", r.afterBathroom); put("noRecentWorkout", r.noRecentWorkout); putNullable("notes", r.notes)
            }) } })
            put("bodyMeasurements", JSONArray().apply { body.forEach { r -> put(JSONObject().apply {
                put("id", r.id); put("measuredAtEpochMillis", r.measuredAtEpochMillis); putNullable("chestCm", r.chestCm); putNullable("waistCm", r.waistCm)
                putNullable("abdomenCm", r.abdomenCm); putNullable("shouldersCm", r.shouldersCm); putNullable("glutesCm", r.glutesCm); putNullable("armLeftCm", r.armLeftCm)
                putNullable("armRightCm", r.armRightCm); putNullable("thighLeftCm", r.thighLeftCm); putNullable("thighRightCm", r.thighRightCm); putNullable("calfLeftCm", r.calfLeftCm); putNullable("calfRightCm", r.calfRightCm); putNullable("notes", r.notes)
            }) } })
            put("workouts", JSONArray().apply { workouts.forEach { r -> put(JSONObject().apply {
                put("id", r.id); put("startedAtEpochMillis", r.startedAtEpochMillis); put("type", r.type); put("title", r.title); putNullable("durationMinutes", r.durationMinutes); put("isRestDay", r.isRestDay); putNullable("notes", r.notes)
            }) } })
            put("cheatEntries", JSONArray().apply { cheats.forEach { r -> put(JSONObject().apply {
                put("id", r.id); put("occurredAtEpochMillis", r.occurredAtEpochMillis); put("description", r.description); putNullable("quantityText", r.quantityText); putNullable("estimatedKcal", r.estimatedKcal); putNullable("estimatedProteinG", r.estimatedProteinG); putNullable("estimatedCarbsG", r.estimatedCarbsG); putNullable("estimatedFatG", r.estimatedFatG); putNullable("planVersionId", r.planVersionId); putNullable("notes", r.notes)
            }) } })
            put("weeklyReviews", JSONArray().apply { reviews.forEach { r -> put(JSONObject().apply {
                put("id", r.id); put("weekStartEpochDay", r.weekStartEpochDay); put("createdAtEpochMillis", r.createdAtEpochMillis); putNullable("adherencePercent", r.adherencePercent); put("summary", r.summary); putNullable("structuredJson", r.structuredJson)
            }) } })
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
                        put("supplements", supplementsJson)
                        put("meals", mealsJson)
                    })
                }
                versionsJson.put(JSONObject().apply { put("id", version.id); put("versionNumber", version.versionNumber); put("createdAtEpochMillis", version.createdAtEpochMillis); put("source", version.source); putNullable("reason", version.reason); putNullable("targetKcal", version.targetKcal); putNullable("targetProteinG", version.targetProteinG); putNullable("targetCarbsG", version.targetCarbsG); putNullable("targetFatG", version.targetFatG); put("days", daysJson) })
            }
            planJson.put(JSONObject().apply { put("id", plan.id); put("weekStartEpochDay", plan.weekStartEpochDay); put("createdAtEpochMillis", plan.createdAtEpochMillis); put("status", plan.status); put("versions", versionsJson) })
        }
        root.put("mealPlans", planJson)
        return root
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
        ZipOutputStream(FileOutputStream(file)).use { zip ->
            val tables = listOf("biaMeasurements", "bodyMeasurements", "workouts", "cheatEntries", "weeklyReviews")
            tables.forEach { key -> addCsv(zip, "$key.csv", root.getJSONArray(key)) }
            addCsv(zip, "profile.csv", JSONArray().put(root.getJSONObject("profile")))
            zip.putNextEntry(ZipEntry("README.txt"))
            zip.write("MyFitAI CSV export. mealPlans are preserved completely in meal_plans.json because the hierarchy plan/version/day/meal/ingredient is not losslessly representable in one flat CSV.\n".toByteArray())
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
