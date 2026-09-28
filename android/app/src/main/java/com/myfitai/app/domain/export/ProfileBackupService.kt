package com.myfitai.app.domain.export

import android.content.Context
import android.net.Uri
import androidx.room.withTransaction
import com.myfitai.app.data.local.MyFitAiDatabase
import com.myfitai.app.data.local.entity.*
import com.myfitai.app.data.profile.ActiveProfileStore
import com.myfitai.app.data.profile.MealCountPreferences
import com.myfitai.app.data.profile.WorkoutPreferences
import com.myfitai.app.data.profile.BiaFrequencyPreferences
import com.myfitai.app.data.profile.NutritionPlanSchedulePreferences
import com.myfitai.app.data.profile.AiAutomationPreferences
import com.myfitai.app.data.profile.ProfilePhotoStore
import org.json.JSONObject

class ProfileBackupService(
    context: Context,
    private val db: MyFitAiDatabase,
    private val activeProfiles: ActiveProfileStore,
    private val mealCountPreferences: MealCountPreferences? = null,
    private val workoutPreferences: WorkoutPreferences? = null,
    private val biaFrequencyPreferences: BiaFrequencyPreferences? = null,
    private val nutritionPlanSchedulePreferences: NutritionPlanSchedulePreferences? = null,
    private val aiAutomationPreferences: AiAutomationPreferences? = null,
    private val profilePhotoStore: ProfilePhotoStore? = null,
) {
    private val resolver = context.applicationContext.contentResolver

    suspend fun restore(uri: Uri): String = db.withTransaction {
        val bytes = resolver.openInputStream(uri)?.use { it.readBytes() } ?: error("Impossibile leggere il backup")
        require(bytes.size <= MAX_BYTES) { "Backup troppo grande" }
        val root = JSONObject(bytes.toString(Charsets.UTF_8))
        require(root.optString("schema") == "myfitai_profile_export_v2" || root.optString("schema") == "myfitai_profile_export_v1") { "Formato backup MyFitAI non riconosciuto" }
        val currentProfileId = activeProfiles.currentIdOrNull()
        val replace = currentProfileId != null
        val profile = root.getJSONObject("profile")
        val profileId = currentProfileId ?: db.userProfileDao().insert(UserProfileEntity(
            name = profile.optString("name", "Profilo importato"),
            birthDateEpochDay = profile.longOrNull("birthDateEpochDay"),
            heightCm = profile.floatOrNull("heightCm"),
            currentWeightKg = profile.floatOrNull("currentWeightKg"),
            goal = profile.stringOrNull("goal"),
            activityLevel = profile.stringOrNull("activityLevel"),
            wakeTimeMinutes = profile.intOrNull("wakeTimeMinutes"),
            sleepTimeMinutes = profile.intOrNull("sleepTimeMinutes"),
            dietaryPreferencesJson = profile.stringOrNull("dietaryPreferencesJson"),
            createdAtEpochMillis = profile.optLong("createdAtEpochMillis", System.currentTimeMillis()),
            updatedAtEpochMillis = profile.optLong("updatedAtEpochMillis", System.currentTimeMillis()),
            biologicalSex = profile.stringOrNull("biologicalSex"),
            initialWeightKg = profile.floatOrNull("initialWeightKg"),
        ))
        if (replace) {
            db.biaAnalysisResultDao().deleteByProfile(profileId)
            db.bodyExpectationGoalDao().deleteByProfile(profileId)
            db.biaMeasurementDao().deleteByProfile(profileId)
            db.bodyMeasurementDao().deleteByProfile(profileId)
            db.workoutDao().deleteByProfile(profileId)
            db.dailyActivityCheckInDao().deleteByProfile(profileId)
            db.mealPlanDao().deleteByProfile(profileId)
            db.cheatEntryDao().deleteByProfile(profileId)
            db.weeklyReviewDao().deleteByProfile(profileId)
            db.foodConsumptionDao().deleteByProfile(profileId)
            db.userProfileDao().update(UserProfileEntity(
                id = profileId,
                name = profile.optString("name", "Profilo importato"),
                birthDateEpochDay = profile.longOrNull("birthDateEpochDay"), heightCm = profile.floatOrNull("heightCm"), currentWeightKg = profile.floatOrNull("currentWeightKg"),
                goal = profile.stringOrNull("goal"), activityLevel = profile.stringOrNull("activityLevel"), wakeTimeMinutes = profile.intOrNull("wakeTimeMinutes"), sleepTimeMinutes = profile.intOrNull("sleepTimeMinutes"),
                dietaryPreferencesJson = profile.stringOrNull("dietaryPreferencesJson"), photoPath = null, createdAtEpochMillis = profile.optLong("createdAtEpochMillis", System.currentTimeMillis()), updatedAtEpochMillis = profile.optLong("updatedAtEpochMillis", System.currentTimeMillis()),
                biologicalSex = profile.stringOrNull("biologicalSex"), initialWeightKg = profile.floatOrNull("initialWeightKg"),
            ))
        }
        profile.stringOrNull("photoBase64")?.let { encoded -> profilePhotoStore?.importFromBase64(profileId, encoded)?.let { path ->
            db.userProfileDao().update(db.userProfileDao().get(profileId)!!.copy(photoPath = path))
        } }
        val biaIds = restoreBia(root, profileId)
        restoreBody(root, profileId)
        restoreWorkouts(root, profileId)
        restoreActivityCheckIns(root, profileId)
        restoreBiaAnalysisResults(root, profileId, biaIds)
        restoreExpectationGoals(root, profileId)
        val maps = restorePlans(root, profileId)
        restoreCheats(root, profileId, maps)
        restoreReviews(root, profileId)
        restoreConsumptions(root, profileId, maps)
        restorePreferences(root, profileId)
        activeProfiles.selectProfile(profileId, makeDefault = false)
        profile.optString("name", "Profilo importato")
    }

    private suspend fun restoreBiaAnalysisResults(root: JSONObject, profileId: Long, biaIds: Map<Long, Long>) {
        val rows = root.optJSONArray("biaAnalysisResults") ?: return
        for (index in 0 until rows.length()) {
            val row = rows.getJSONObject(index)
            val biaMeasurementId = biaIds[row.optLong("biaMeasurementId")] ?: continue
            db.biaAnalysisResultDao().insert(BiaAnalysisResultEntity(profileId = profileId, biaMeasurementId = biaMeasurementId, createdAtEpochMillis = row.optLong("createdAtEpochMillis"), provider = row.optString("provider"), model = row.optString("model"), payloadJson = row.optString("payloadJson")))
        }
    }

    private suspend fun restoreExpectationGoals(root: JSONObject, profileId: Long) {
        val rows = root.optJSONArray("bodyExpectationGoals") ?: return
        for (index in 0 until rows.length()) {
            val row = rows.getJSONObject(index)
            db.bodyExpectationGoalDao().upsert(BodyExpectationGoalEntity(profileId = profileId, periodStartEpochDay = row.optLong("periodStartEpochDay"), periodEndEpochDay = row.optLong("periodEndEpochDay"), plannedDays = row.optInt("plannedDays"), theoreticalDeficitKcal = row.intOrNull("theoreticalDeficitKcal"), expectedFatLossMinKg = row.doubleOrNull("expectedFatLossMinKg"), expectedFatLossMaxKg = row.doubleOrNull("expectedFatLossMaxKg"), initialWeightKg = row.floatOrNull("initialWeightKg"), finalWeightKg = row.floatOrNull("finalWeightKg"), status = row.optString("status"), note = row.stringOrNull("note"), createdAtEpochMillis = row.optLong("createdAtEpochMillis"), updatedAtEpochMillis = row.optLong("updatedAtEpochMillis")))
        }
    }

    private fun restorePreferences(root: JSONObject, profileId: Long) {
        val prefs = root.optJSONObject("profilePreferences") ?: return
        mealCountPreferences?.set(profileId, prefs.optInt("mealCount", MealCountPreferences.DEFAULT))
        workoutPreferences?.setEnabled(profileId, prefs.optBoolean("workoutsEnabled", WorkoutPreferences.DEFAULT_ENABLED))
        biaFrequencyPreferences?.intervalDays = prefs.optInt("biaIntervalDays", BiaFrequencyPreferences.DEFAULT_INTERVAL_DAYS)
        prefs.optJSONObject("nutritionSchedule")?.let { schedule ->
            nutritionPlanSchedulePreferences?.setEnabled(profileId, schedule.optBoolean("enabled"))
            runCatching { nutritionPlanSchedulePreferences?.setFrequency(profileId, NutritionPlanSchedulePreferences.Frequency.valueOf(schedule.optString("frequency"))) }
            runCatching { nutritionPlanSchedulePreferences?.setDayOfWeek(profileId, java.time.DayOfWeek.of(schedule.optInt("dayOfWeek"))) }
            nutritionPlanSchedulePreferences?.setTimeMinutes(profileId, schedule.optInt("timeMinutes", NutritionPlanSchedulePreferences.DEFAULT_TIME_MINUTES))
        }
        prefs.optJSONObject("aiAutomation")?.let { automation ->
            AiAutomationPreferences.Feature.entries.forEach { feature ->
                val config = automation.optJSONObject(feature.name) ?: return@forEach
                aiAutomationPreferences?.setEnabled(profileId, feature, config.optBoolean("enabled"))
                runCatching { aiAutomationPreferences?.setFrequency(profileId, feature, AiAutomationPreferences.Frequency.valueOf(config.optString("frequency"))) }
                aiAutomationPreferences?.setNotificationsEnabled(profileId, feature, config.optBoolean("notificationsEnabled", true))
                config.longOrNull("lastRun")?.let { aiAutomationPreferences?.markRun(profileId, feature, it) }
            }
        }
    }

    private suspend fun restoreBia(root: JSONObject, profileId: Long): Map<Long, Long> {
        val rows = root.optJSONArray("biaMeasurements") ?: return emptyMap()
        val ids = mutableMapOf<Long, Long>()
        for (index in 0 until rows.length()) {
            val row = rows.getJSONObject(index)
            val newId = db.biaMeasurementDao().insert(BiaMeasurementEntity(
                profileId = profileId,
                measuredAtEpochMillis = row.getLong("measuredAtEpochMillis"),
                weightKg = row.floatOrNull("weightKg"), bodyFatPercent = row.floatOrNull("bodyFatPercent"),
                visceralFatLevel = row.floatOrNull("visceralFatLevel"), muscleMassKg = row.floatOrNull("muscleMassKg"),
                skeletalMuscleKg = row.floatOrNull("skeletalMuscleKg"), bodyWaterPercent = row.floatOrNull("bodyWaterPercent"),
                bmrKcal = row.floatOrNull("bmrKcal"), fasting = row.optBoolean("fasting"), justWokeUp = row.optBoolean("justWokeUp"),
                afterBathroom = row.optBoolean("afterBathroom"), noRecentWorkout = row.optBoolean("noRecentWorkout"),
                notes = row.stringOrNull("notes"), fatMassKg = row.floatOrNull("fatMassKg"), leanMassKg = row.floatOrNull("leanMassKg"),
                bodyWaterKg = row.floatOrNull("bodyWaterKg"), subcutaneousFatPercent = row.floatOrNull("subcutaneousFatPercent"),
                boneMassKg = row.floatOrNull("boneMassKg"), proteinPercent = row.floatOrNull("proteinPercent"), proteinKg = row.floatOrNull("proteinKg"),
                bodyAgeYears = row.intOrNull("bodyAgeYears"), bmi = row.floatOrNull("bmi"),
            ))
            row.optLong("id").takeIf { it > 0L }?.let { ids[it] = newId }
        }
        return ids
    }

    private suspend fun restoreBody(root: JSONObject, profileId: Long) {
        val rows = root.optJSONArray("bodyMeasurements") ?: return
        for (index in 0 until rows.length()) {
            val row = rows.getJSONObject(index)
            db.bodyMeasurementDao().insert(BodyMeasurementEntity(
                profileId = profileId, measuredAtEpochMillis = row.getLong("measuredAtEpochMillis"),
                chestCm = row.floatOrNull("chestCm"), waistCm = row.floatOrNull("waistCm"), abdomenCm = row.floatOrNull("abdomenCm"),
                shouldersCm = row.floatOrNull("shouldersCm"), glutesCm = row.floatOrNull("glutesCm"), armLeftCm = row.floatOrNull("armLeftCm"),
                armRightCm = row.floatOrNull("armRightCm"), thighLeftCm = row.floatOrNull("thighLeftCm"), thighRightCm = row.floatOrNull("thighRightCm"),
                calfLeftCm = row.floatOrNull("calfLeftCm"), calfRightCm = row.floatOrNull("calfRightCm"), notes = row.stringOrNull("notes"),
                hipsCm = row.floatOrNull("hipsCm"), weightKg = row.floatOrNull("weightKg"),
            ))
        }
    }

    private suspend fun restoreWorkouts(root: JSONObject, profileId: Long) {
        val rows = root.optJSONArray("workouts") ?: return
        for (index in 0 until rows.length()) {
            val row = rows.getJSONObject(index)
            db.workoutDao().insert(WorkoutEntity(
                profileId = profileId, startedAtEpochMillis = row.getLong("startedAtEpochMillis"),
                type = row.optString("type"), title = row.optString("title"), durationMinutes = row.intOrNull("durationMinutes"),
                isRestDay = row.optBoolean("isRestDay"), notes = row.stringOrNull("notes"),
            ))
        }
    }

    private suspend fun restoreActivityCheckIns(root: JSONObject, profileId: Long) {
        val rows = root.optJSONArray("dailyActivityCheckIns") ?: return
        for (index in 0 until rows.length()) {
            val row = rows.getJSONObject(index)
            db.dailyActivityCheckInDao().upsert(DailyActivityCheckInEntity(
                profileId = profileId,
                dateEpochDay = row.getLong("dateEpochDay"),
                status = row.optString("status"),
                durationMinutes = row.intOrNull("durationMinutes"),
                intensity = row.stringOrNull("intensity"),
                adjustmentKcal = row.optInt("adjustmentKcal", 0),
                createdAtEpochMillis = row.optLong("createdAtEpochMillis", System.currentTimeMillis()),
                updatedAtEpochMillis = row.optLong("updatedAtEpochMillis", System.currentTimeMillis()),
            ))
        }
    }

    private data class IdMaps(
        val planIds: Map<Long, Long>, val versionIds: Map<Long, Long>, val dayIds: Map<Long, Long>, val mealIds: Map<Long, Long>,
    )

    private suspend fun restorePlans(root: JSONObject, profileId: Long): IdMaps {
        val planIds = mutableMapOf<Long, Long>(); val versionIds = mutableMapOf<Long, Long>(); val dayIds = mutableMapOf<Long, Long>(); val mealIds = mutableMapOf<Long, Long>()
        val plans = root.optJSONArray("mealPlans") ?: return IdMaps(planIds, versionIds, dayIds, mealIds)
        for (pIndex in 0 until plans.length()) {
            val oldPlan = plans.getJSONObject(pIndex)
            val newPlan = db.mealPlanDao().insertPlan(MealPlanEntity(profileId = profileId, createdAtEpochMillis = oldPlan.getLong("createdAtEpochMillis"), weekStartEpochDay = oldPlan.getLong("weekStartEpochDay"), status = oldPlan.optString("status", "ACTIVE")))
            planIds[oldPlan.getLong("id")] = newPlan
            val versions = oldPlan.optJSONArray("versions") ?: continue
            for (vIndex in 0 until versions.length()) {
                val oldVersion = versions.getJSONObject(vIndex)
                val newVersion = db.mealPlanDao().insertVersion(MealPlanVersionEntity(
                    planId = newPlan, versionNumber = oldVersion.getInt("versionNumber"), createdAtEpochMillis = oldVersion.getLong("createdAtEpochMillis"),
                    source = oldVersion.optString("source"), reason = oldVersion.stringOrNull("reason"), targetKcal = oldVersion.intOrNull("targetKcal"),
                    targetProteinG = oldVersion.floatOrNull("targetProteinG"), targetCarbsG = oldVersion.floatOrNull("targetCarbsG"), targetFatG = oldVersion.floatOrNull("targetFatG"),
                    appValidationJson = oldVersion.stringOrNull("appValidationJson"),
                ))
                versionIds[oldVersion.getLong("id")] = newVersion
                val days = oldVersion.optJSONArray("days") ?: continue
                for (dIndex in 0 until days.length()) {
                    val oldDay = days.getJSONObject(dIndex)
                    val newDay = db.mealPlanDao().insertDays(listOf(MealPlanDayEntity(
                        versionId = newVersion, dateEpochDay = oldDay.getLong("dateEpochDay"), totalKcal = oldDay.intOrNull("totalKcal"),
                        proteinG = oldDay.floatOrNull("proteinG"), carbsG = oldDay.floatOrNull("carbsG"), fatG = oldDay.floatOrNull("fatG"),
                         supplementsJson = oldDay.stringOrNull("supplementsJson") ?: oldDay.optJSONArray("supplements")?.toString(), hydrationNote = oldDay.stringOrNull("hydrationNote"),
                         targetKcal = oldDay.intOrNull("targetKcal"), targetProteinG = oldDay.floatOrNull("targetProteinG"), targetCarbsG = oldDay.floatOrNull("targetCarbsG"), targetFatG = oldDay.floatOrNull("targetFatG"), baseTargetKcal = oldDay.intOrNull("baseTargetKcal"),
                    ))).single()
                    dayIds[oldDay.optLong("id")] = newDay
                    val meals = oldDay.optJSONArray("meals") ?: continue
                    for (mIndex in 0 until meals.length()) {
                        val oldMeal = meals.getJSONObject(mIndex)
                        val newMeal = db.mealPlanDao().insertMeals(listOf(MealEntity(
                            dayId = newDay, sortOrder = oldMeal.getInt("sortOrder"), type = oldMeal.optString("type"), title = oldMeal.optString("title"),
                            timeMinutes = oldMeal.intOrNull("timeMinutes"), kcal = oldMeal.intOrNull("kcal"), proteinG = oldMeal.floatOrNull("proteinG"),
                            carbsG = oldMeal.floatOrNull("carbsG"), fatG = oldMeal.floatOrNull("fatG"), preparation = oldMeal.stringOrNull("preparation"),
                        ))).single()
                        mealIds[oldMeal.optLong("id")] = newMeal
                        val ingredients = oldMeal.optJSONArray("ingredients") ?: continue
                        for (iIndex in 0 until ingredients.length()) {
                            val oldIngredient = ingredients.getJSONObject(iIndex)
                            db.mealPlanDao().insertIngredients(listOf(MealIngredientEntity(
                                mealId = newMeal, name = oldIngredient.optString("name"), quantity = oldIngredient.optDouble("quantity").toFloat(),
                                unit = oldIngredient.optString("unit"), displayDose = oldIngredient.stringOrNull("displayDose"), weightState = oldIngredient.stringOrNull("weightState"),
                                nutritionConfidence = oldIngredient.stringOrNull("nutritionConfidence"), category = oldIngredient.stringOrNull("category"), sortOrder = oldIngredient.optInt("sortOrder"),
                            )))
                        }
                    }
                }
            }
        }
        return IdMaps(planIds, versionIds, dayIds, mealIds)
    }

    private suspend fun restoreCheats(root: JSONObject, profileId: Long, maps: IdMaps) {
        val rows = root.optJSONArray("cheatEntries") ?: return
        for (index in 0 until rows.length()) {
            val row = rows.getJSONObject(index)
            db.cheatEntryDao().insert(CheatEntryEntity(profileId = profileId, occurredAtEpochMillis = row.getLong("occurredAtEpochMillis"), description = row.optString("description"), quantityText = row.stringOrNull("quantityText"), estimatedKcal = row.intOrNull("estimatedKcal"), estimatedProteinG = row.floatOrNull("estimatedProteinG"), estimatedCarbsG = row.floatOrNull("estimatedCarbsG"), estimatedFatG = row.floatOrNull("estimatedFatG"), planVersionId = row.optLong("planVersionId").takeIf { it > 0 }?.let(maps.versionIds::get), notes = row.stringOrNull("notes")))
        }
    }

    private suspend fun restoreReviews(root: JSONObject, profileId: Long) {
        val rows = root.optJSONArray("weeklyReviews") ?: return
        for (index in 0 until rows.length()) {
            val row = rows.getJSONObject(index)
            db.weeklyReviewDao().upsert(WeeklyReviewEntity(profileId = profileId, weekStartEpochDay = row.getLong("weekStartEpochDay"), createdAtEpochMillis = row.getLong("createdAtEpochMillis"), adherencePercent = row.floatOrNull("adherencePercent"), summary = row.optString("summary"), structuredJson = row.stringOrNull("structuredJson")))
        }
    }

    private suspend fun restoreConsumptions(root: JSONObject, profileId: Long, maps: IdMaps) {
        val rows = root.optJSONArray("foodConsumptions") ?: return
        for (index in 0 until rows.length()) {
            val row = rows.getJSONObject(index)
            val plan = maps.planIds[row.optLong("planId")] ?: continue
            val version = maps.versionIds[row.optLong("planVersionId")] ?: continue
            val day = maps.dayIds[row.optLong("dayId")] ?: continue
            db.foodConsumptionDao().upsert(FoodConsumptionEntity(profileId = profileId, planId = plan, planVersionId = version, dayId = day, plannedDateEpochDay = row.getLong("plannedDateEpochDay"), itemType = row.optString("itemType"), itemKey = row.optString("itemKey"), mealId = row.optLong("mealId").takeIf { it > 0 }?.let(maps.mealIds::get), supplementKey = row.stringOrNull("supplementKey"), status = row.optString("status"), recordedAtEpochMillis = row.getLong("recordedAtEpochMillis"), updatedAtEpochMillis = row.getLong("updatedAtEpochMillis"), quantityFactor = row.optDouble("quantityFactor", 1.0).toFloat(), kcal = row.intOrNull("kcal"), proteinG = row.floatOrNull("proteinG"), carbsG = row.floatOrNull("carbsG"), fatG = row.floatOrNull("fatG"), note = row.stringOrNull("note")))
        }
    }

    companion object { private const val MAX_BYTES = 50_000_000L }
}

private fun JSONObject.stringOrNull(key: String): String? = if (!has(key) || isNull(key)) null else optString(key).takeIf { it.isNotBlank() }
private fun JSONObject.longOrNull(key: String): Long? = if (!has(key) || isNull(key)) null else optLong(key)
private fun JSONObject.intOrNull(key: String): Int? = if (!has(key) || isNull(key)) null else optInt(key)
private fun JSONObject.floatOrNull(key: String): Float? = if (!has(key) || isNull(key)) null else optDouble(key).toFloat()
private fun JSONObject.doubleOrNull(key: String): Double? = if (!has(key) || isNull(key)) null else optDouble(key)
