package com.myfitai.app.domain.export

import com.myfitai.app.data.local.entity.BiaMeasurementEntity
import com.myfitai.app.data.local.entity.BodyMeasurementEntity
import com.myfitai.app.data.local.entity.CheatEntryEntity
import com.myfitai.app.data.local.entity.FoodConsumptionEntity
import com.myfitai.app.data.local.entity.UserProfileEntity
import com.myfitai.app.data.local.entity.WeeklyReviewEntity
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipInputStream

class ProfileExportFormatTest {
    @get:Rule val tmp = TemporaryFolder()

    private val profile = UserProfileEntity(
        id = 7, name = "Test", birthDateEpochDay = 9000, heightCm = 170f, currentWeightKg = 70f, goal = "LOSE",
        activityLevel = "Moderato", wakeTimeMinutes = 420, sleepTimeMinutes = 1380, dietaryPreferencesJson = null,
        createdAtEpochMillis = 1, updatedAtEpochMillis = 2, biologicalSex = "female", initialWeightKg = 72f,
    )

    private fun v3Root(): JSONObject = ProfileExportFormat.buildRoot(
        exportedAtEpochMillis = 1_700_000_000_000,
        profileId = 7,
        profile = profile,
        photoBase64 = null,
        bia = listOf(BiaMeasurementEntity(id = 1, profileId = 7, measuredAtEpochMillis = 10, weightKg = 70f, bodyFatPercent = 25f, visceralFatLevel = null, muscleMassKg = null, skeletalMuscleKg = null, bodyWaterPercent = null, bmrKcal = null, fasting = true, justWokeUp = true, afterBathroom = true, noRecentWorkout = true)),
        body = listOf(BodyMeasurementEntity(id = 1, profileId = 7, measuredAtEpochMillis = 11, chestCm = null, waistCm = 80f, abdomenCm = null, shouldersCm = null, glutesCm = null, armLeftCm = null, armRightCm = null, thighLeftCm = null, thighRightCm = null, calfLeftCm = null, calfRightCm = null)),
        biaAnalysisResults = emptyList(),
        expectationGoals = emptyList(),
        profilePreferences = JSONObject().put("mealCount", 4).put("biaIntervalDays", 14),
        cheats = listOf(CheatEntryEntity(id = 1, profileId = 7, occurredAtEpochMillis = 12, description = "Pizza", quantityText = null, estimatedKcal = 800, estimatedProteinG = null, estimatedCarbsG = null, estimatedFatG = null, planVersionId = null)),
        reviews = listOf(WeeklyReviewEntity(id = 1, profileId = 7, weekStartEpochDay = 100, createdAtEpochMillis = 13, adherencePercent = 90f, summary = "ok", structuredJson = null)),
        foodConsumptions = listOf(FoodConsumptionEntity(id = 1, profileId = 7, planId = 1, planVersionId = 1, dayId = 1, plannedDateEpochDay = 100, itemType = "MEAL", itemKey = "MEAL:1", mealId = 1, supplementKey = null, status = "CONSUMED", recordedAtEpochMillis = 14, updatedAtEpochMillis = 14, quantityFactor = 1f, kcal = 500, proteinG = null, carbsG = null, fatG = null)),
        mealPlans = JSONArray().put(JSONObject().put("id", 1).put("versions", JSONArray())),
    )

    @Test
    fun exportV3_declaresNewSchemaAndNoTrainingKeys() {
        val root = v3Root()
        assertEquals("myfitai_profile_export_v3", root.getString("schema"))
        ProfileExportFormat.DISCONTINUED_ROOT_KEYS.forEach { assertFalse("chiave dismessa $it", root.has(it)) }
        val text = root.toString()
        listOf("workouts", "dailyActivityCheckIns", "workoutsEnabled", "trainingProgram", "trainingActivityBasis").forEach {
            assertFalse("export v3 contiene $it", text.contains("\"$it\""))
        }
        assertFalse(root.has("moodCheckIns"))
        assertFalse(root.has("weeklyDiaryNotes"))
        assertFalse(ProfileExportFormat.CSV_TABLES.contains("moodCheckIns"))
        assertFalse(ProfileExportFormat.CSV_TABLES.contains("weeklyDiaryNotes"))
        assertEquals(1, root.getJSONArray("biaMeasurements").length())
        assertEquals(1, root.getJSONArray("mealPlans").length())
        assertEquals(4, root.getJSONObject("profilePreferences").getInt("mealCount"))
    }

    @Test
    fun csvZip_hasNoWorkoutsEntry_andDoesNotThrowWithoutWorkoutsKey() {
        val file = tmp.newFile("dati.zip")
        ProfileExportFormat.writeCsvZip(file, v3Root())
        val entries = zipEntries(file)
        assertFalse(entries.any { it.contains("workout", ignoreCase = true) })
        listOf("biaMeasurements.csv", "bodyMeasurements.csv", "cheatEntries.csv", "weeklyReviews.csv", "foodConsumptions.csv", "profile.csv", "README.txt", "meal_plans.json")
            .forEach { assertTrue("manca $it", entries.contains(it)) }
    }

    @Test
    fun csvZip_ofLegacyRootWithWorkouts_stillIgnoresWorkouts() {
        val legacy = v3Root().put("workouts", JSONArray().put(JSONObject().put("id", 1).put("title", "Pesi")))
        val file = tmp.newFile("legacy.zip")
        ProfileExportFormat.writeCsvZip(file, legacy)
        assertFalse(zipEntries(file).contains("workouts.csv"))
    }

    @Test
    fun restorableSchemas_areV1V2V3Only() {
        assertTrue(ProfileExportFormat.isRestorable("myfitai_profile_export_v1"))
        assertTrue(ProfileExportFormat.isRestorable("myfitai_profile_export_v2"))
        assertTrue(ProfileExportFormat.isRestorable("myfitai_profile_export_v3"))
        assertFalse(ProfileExportFormat.isRestorable("myfitai_profile_export_v4"))
        assertFalse(ProfileExportFormat.isRestorable(""))
        assertFalse(ProfileExportFormat.isRestorable(null))
    }

    /** Backup reali (dati di salute): letti solo se presenti sulla macchina locale, mai copiati. */
    @Test
    fun realV2Backups_areRestorableAndEveryKeyIsRestoredOrDiscontinued() {
        val files = listOf(
            "C:\\Users\\LucaPiciollo\\Luca\\backup-myfitai\\myfitai-backup-luca-2026-11-13.json",
            "C:\\Users\\LucaPiciollo\\Luca\\backup-myfitai\\myfitai-backup-mamma-2026-11-13.json",
        ).map(::File).filter { it.isFile }
        assumeTrue("backup reali non presenti: test saltato", files.isNotEmpty())
        val restoredRootKeys = setOf(
            "schema", "exportedAtEpochMillis", "profileId", "profile", "biaMeasurements", "bodyMeasurements", "biaAnalysisResults",
            "bodyExpectationGoals", "profilePreferences", "cheatEntries", "weeklyReviews", "foodConsumptions", "mealPlans",
        )
        val restoredPreferenceKeys = setOf("mealCount", "biaIntervalDays", "nutritionSchedule", "aiAutomation")
        files.forEach { file ->
            val root = JSONObject(file.readText())
            assertTrue(ProfileExportFormat.isRestorable(root.getString("schema")))
            root.keys().forEach { key ->
                assertTrue("${file.name}: chiave radice non gestita $key", key in restoredRootKeys || key in ProfileExportFormat.DISCONTINUED_ROOT_KEYS)
            }
            root.getJSONObject("profilePreferences").keys().forEach { key ->
                assertTrue("${file.name}: preferenza non gestita $key", key in restoredPreferenceKeys || key in ProfileExportFormat.DISCONTINUED_PREFERENCE_KEYS)
            }
            // Il CSV di un backup v2 reale (con workouts non vuoti) non deve piu produrre workouts.csv ne lanciare.
            val zip = tmp.newFile("real-${System.nanoTime()}.zip")
            ProfileExportFormat.writeCsvZip(zip, root)
            assertFalse(zipEntries(zip).contains("workouts.csv"))
        }
    }

    private fun zipEntries(file: File): List<String> = buildList {
        ZipInputStream(file.inputStream()).use { stream ->
            while (true) add(stream.nextEntry?.name ?: break)
        }
    }
}
