package com.myfitai.app.domain.export

import com.myfitai.app.data.local.entity.BiaAnalysisResultEntity
import com.myfitai.app.data.local.entity.BiaMeasurementEntity
import com.myfitai.app.data.local.entity.BodyExpectationGoalEntity
import com.myfitai.app.data.local.entity.BodyMeasurementEntity
import com.myfitai.app.data.local.entity.CheatEntryEntity
import com.myfitai.app.data.local.entity.FoodConsumptionEntity
import com.myfitai.app.data.local.entity.UserProfileEntity
import com.myfitai.app.data.local.entity.WeeklyReviewEntity
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Formato canonico dell'export/backup del profilo, senza dipendenze Android (testabile su JVM).
 *
 * v3 non contiene piu allenamenti, check-in di attivita giornaliera, programma di allenamento e
 * relative preferenze. I backup v1/v2 restano importabili: le chiavi dismesse vengono ignorate.
 *
 * Revisione 2 (stesso schema v3, solo chiavi AGGIUNTE, nessuna rimossa o rinominata): per i piani con valori calcolati
 * dal catalogo (COMPUTED) i pasti portano `nutritionSource`, le versioni `catalogVersion` e gli ingredienti lo snapshot
 * (`foodId`, `grams`, `kcal`, `proteinG`, `carbsG`, `fatG`, `fiberG`, `sodiumMg`). Chi legge ignora le chiavi sconosciute;
 * il restore le legge se presenti e tratta l'assenza (backup precedenti, piani DECLARED) come prima. Vedi [MealPlanBackupMapper].
 */
object ProfileExportFormat {
    /** 1 = implicita negli export precedenti; 2 = campi COMPUTED dei piani (additivi). */
    const val SCHEMA_REVISION = 2
    const val SCHEMA_V1 = "myfitai_profile_export_v1"
    const val SCHEMA_V2 = "myfitai_profile_export_v2"
    const val SCHEMA_V3 = "myfitai_profile_export_v3"
    const val CURRENT_SCHEMA = SCHEMA_V3

    val RESTORABLE_SCHEMAS: Set<String> = setOf(SCHEMA_V1, SCHEMA_V2, SCHEMA_V3)

    /** Tabelle esportate come CSV (una per chiave radice JSON). */
    val CSV_TABLES: List<String> = listOf("biaMeasurements", "bodyMeasurements", "cheatEntries", "weeklyReviews", "foodConsumptions")

    /** Chiavi radice presenti in v1/v2 e dismesse in v3: mai scritte, ignorate in lettura. */
    val DISCONTINUED_ROOT_KEYS: Set<String> = setOf("workouts", "dailyActivityCheckIns")

    /** Chiavi di `profilePreferences` presenti in v1/v2 e dismesse in v3: mai scritte, ignorate in lettura. */
    val DISCONTINUED_PREFERENCE_KEYS: Set<String> = setOf("workoutsEnabled", "trainingProgram", "trainingActivityBasis")

    fun isRestorable(schema: String?): Boolean = schema != null && schema in RESTORABLE_SCHEMAS

    fun buildRoot(
        exportedAtEpochMillis: Long,
        profileId: Long,
        profile: UserProfileEntity,
        photoBase64: String?,
        bia: List<BiaMeasurementEntity>,
        body: List<BodyMeasurementEntity>,
        biaAnalysisResults: List<BiaAnalysisResultEntity>,
        expectationGoals: List<BodyExpectationGoalEntity>,
        profilePreferences: JSONObject,
        cheats: List<CheatEntryEntity>,
        reviews: List<WeeklyReviewEntity>,
        foodConsumptions: List<FoodConsumptionEntity>,
        mealPlans: JSONArray,
    ): JSONObject = JSONObject().apply {
        put("schema", CURRENT_SCHEMA)
        put("schemaRevision", SCHEMA_REVISION)
        put("exportedAtEpochMillis", exportedAtEpochMillis)
        put("profileId", profileId)
        put("profile", JSONObject().apply {
            put("id", profile.id)
            put("name", profile.name)
            put("createdAtEpochMillis", profile.createdAtEpochMillis)
            put("updatedAtEpochMillis", profile.updatedAtEpochMillis)
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
            putNullable("photoBase64", photoBase64)
        })
        put("biaMeasurements", JSONArray().apply { bia.forEach { r -> put(JSONObject().apply {
            put("id", r.id); put("measuredAtEpochMillis", r.measuredAtEpochMillis)
            putNullable("weightKg", r.weightKg); putNullable("bodyFatPercent", r.bodyFatPercent); putNullable("visceralFatLevel", r.visceralFatLevel)
            putNullable("muscleMassKg", r.muscleMassKg); putNullable("skeletalMuscleKg", r.skeletalMuscleKg); putNullable("bodyWaterPercent", r.bodyWaterPercent)
            putNullable("fatMassKg", r.fatMassKg); putNullable("leanMassKg", r.leanMassKg); putNullable("bodyWaterKg", r.bodyWaterKg)
            putNullable("subcutaneousFatPercent", r.subcutaneousFatPercent); putNullable("boneMassKg", r.boneMassKg)
            putNullable("proteinPercent", r.proteinPercent); putNullable("proteinKg", r.proteinKg)
            putNullable("bodyAgeYears", r.bodyAgeYears); putNullable("bmi", r.bmi)
            putNullable("bmrKcal", r.bmrKcal); put("fasting", r.fasting); put("justWokeUp", r.justWokeUp); put("afterBathroom", r.afterBathroom); put("noRecentWorkout", r.noRecentWorkout); putNullable("notes", r.notes)
        }) } })
        put("bodyMeasurements", JSONArray().apply { body.forEach { r -> put(JSONObject().apply {
            put("id", r.id); put("measuredAtEpochMillis", r.measuredAtEpochMillis); putNullable("chestCm", r.chestCm); putNullable("waistCm", r.waistCm)
            putNullable("abdomenCm", r.abdomenCm); putNullable("shouldersCm", r.shouldersCm); putNullable("glutesCm", r.glutesCm); putNullable("hipsCm", r.hipsCm); putNullable("weightKg", r.weightKg); putNullable("armLeftCm", r.armLeftCm)
            putNullable("armRightCm", r.armRightCm); putNullable("thighLeftCm", r.thighLeftCm); putNullable("thighRightCm", r.thighRightCm); putNullable("calfLeftCm", r.calfLeftCm); putNullable("calfRightCm", r.calfRightCm); putNullable("notes", r.notes)
        }) } })
        put("biaAnalysisResults", JSONArray().apply { biaAnalysisResults.forEach { r -> put(JSONObject().apply {
            put("id", r.id); put("biaMeasurementId", r.biaMeasurementId); put("createdAtEpochMillis", r.createdAtEpochMillis)
            put("provider", r.provider); put("model", r.model); put("payloadJson", r.payloadJson)
        }) } })
        put("bodyExpectationGoals", JSONArray().apply { expectationGoals.forEach { r -> put(JSONObject().apply {
            put("id", r.id); put("periodStartEpochDay", r.periodStartEpochDay); put("periodEndEpochDay", r.periodEndEpochDay)
            put("plannedDays", r.plannedDays); putNullable("theoreticalDeficitKcal", r.theoreticalDeficitKcal)
            putNullable("expectedFatLossMinKg", r.expectedFatLossMinKg); putNullable("expectedFatLossMaxKg", r.expectedFatLossMaxKg)
            putNullable("initialWeightKg", r.initialWeightKg); putNullable("finalWeightKg", r.finalWeightKg)
            put("status", r.status); putNullable("note", r.note); put("createdAtEpochMillis", r.createdAtEpochMillis); put("updatedAtEpochMillis", r.updatedAtEpochMillis)
        }) } })
        put("profilePreferences", profilePreferences)
        put("cheatEntries", JSONArray().apply { cheats.forEach { r -> put(JSONObject().apply {
            put("id", r.id); put("occurredAtEpochMillis", r.occurredAtEpochMillis); put("description", r.description); putNullable("quantityText", r.quantityText); putNullable("estimatedKcal", r.estimatedKcal); putNullable("estimatedProteinG", r.estimatedProteinG); putNullable("estimatedCarbsG", r.estimatedCarbsG); putNullable("estimatedFatG", r.estimatedFatG); putNullable("planVersionId", r.planVersionId); putNullable("notes", r.notes)
        }) } })
        put("weeklyReviews", JSONArray().apply { reviews.forEach { r -> put(JSONObject().apply {
            put("id", r.id); put("weekStartEpochDay", r.weekStartEpochDay); put("createdAtEpochMillis", r.createdAtEpochMillis); putNullable("adherencePercent", r.adherencePercent); put("summary", r.summary); putNullable("structuredJson", r.structuredJson)
        }) } })
        put("foodConsumptions", JSONArray().apply { foodConsumptions.forEach { r -> put(JSONObject().apply {
            put("id", r.id); put("planId", r.planId); put("planVersionId", r.planVersionId); put("dayId", r.dayId)
            put("plannedDateEpochDay", r.plannedDateEpochDay); put("itemType", r.itemType); put("itemKey", r.itemKey)
            putNullable("mealId", r.mealId); putNullable("supplementKey", r.supplementKey); put("status", r.status)
            put("recordedAtEpochMillis", r.recordedAtEpochMillis); put("updatedAtEpochMillis", r.updatedAtEpochMillis)
            put("quantityFactor", r.quantityFactor); putNullable("kcal", r.kcal); putNullable("proteinG", r.proteinG)
            putNullable("carbsG", r.carbsG); putNullable("fatG", r.fatG); putNullable("note", r.note)
        }) } })
        put("mealPlans", mealPlans)
    }

    fun writeCsvZip(file: File, root: JSONObject) {
        ZipOutputStream(FileOutputStream(file)).use { zip ->
            CSV_TABLES.forEach { key -> addCsv(zip, "$key.csv", root.optJSONArray(key) ?: JSONArray()) }
            addCsv(zip, "profile.csv", JSONArray().put(root.getJSONObject("profile")))
            zip.putNextEntry(ZipEntry("README.txt"))
            zip.write("MyFitAI CSV export. mealPlans are preserved completely in meal_plans.json because the hierarchy plan/version/day/meal/ingredient is not losslessly representable in one flat CSV. foodConsumptions.csv contains explicit consumption events and nutritional snapshots.\n".toByteArray())
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("meal_plans.json"))
            zip.write((root.optJSONArray("mealPlans") ?: JSONArray()).toString(2).toByteArray())
            zip.closeEntry()
        }
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

    private fun csv(value: String) = "\"${value.replace("\"", "\"\"").replace("\r", " ").replace("\n", " ")}\""
}

internal fun JSONObject.putNullable(key: String, value: Any?) { put(key, value ?: JSONObject.NULL) }
