package com.myfitai.app.domain.export

import android.content.Context
import android.net.Uri
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.myfitai.app.data.local.MyFitAiDatabase
import com.myfitai.app.data.profile.ActiveProfileStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Il restore accetta v1/v2/v3 e non scrive piu allenamenti/check-in, ma importa tutto il resto come prima. */
@RunWith(AndroidJUnit4::class)
class ProfileBackupRestoreTest {
    private lateinit var context: Context
    private lateinit var db: MyFitAiDatabase
    private lateinit var store: ActiveProfileStore
    private val files = mutableListOf<File>()

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, MyFitAiDatabase::class.java).allowMainThreadQueries().build()
        store = ActiveProfileStore(context)
        store.clear()
    }

    @After
    fun tearDown() {
        db.close()
        store.clear()
        files.forEach { it.delete() }
    }

    @Test
    fun restoreV2WithWorkouts_importsEverythingElseAndWritesNoWorkouts() = runBlocking {
        val uri = write(backup(ProfileExportFormat.SCHEMA_V2, withLegacy = true))
        val name = ProfileBackupService(context, db, store).restore(uri)

        assertEquals("Profilo Test", name)
        val profileId = store.currentIdOrNull()!!
        assertEquals("Moderato", db.userProfileDao().get(profileId)!!.activityLevel)
        assertEquals(1, db.biaMeasurementDao().observeAll(profileId).first().size)
        assertEquals(1, db.bodyMeasurementDao().observeAll(profileId).first().size)
        assertEquals(1, db.cheatEntryDao().observeAll(profileId).first().size)
        assertTrue(db.workoutDao().observeAll(profileId).first().isEmpty())
        assertTrue(db.dailyActivityCheckInDao().observeAll(profileId).isEmpty())
    }

    @Test
    fun restoreV3WithoutLegacyKeys_succeeds() = runBlocking {
        val uri = write(backup(ProfileExportFormat.SCHEMA_V3, withLegacy = false))
        ProfileBackupService(context, db, store).restore(uri)
        val profileId = store.currentIdOrNull()!!
        assertEquals(1, db.biaMeasurementDao().observeAll(profileId).first().size)
        assertTrue(db.workoutDao().observeAll(profileId).first().isEmpty())
    }

    @Test
    fun restoreUnknownSchema_isRejected() = runBlocking {
        val uri = write(backup("myfitai_profile_export_v9", withLegacy = false))
        try {
            ProfileBackupService(context, db, store).restore(uri)
            fail("schema sconosciuto accettato")
        } catch (expected: IllegalArgumentException) {
            // atteso
        }
    }

    private fun write(root: JSONObject): Uri {
        val file = File(context.cacheDir, "restore-${System.nanoTime()}.json").also { files += it }
        file.writeText(root.toString())
        return Uri.fromFile(file)
    }

    private fun backup(schema: String, withLegacy: Boolean): JSONObject = JSONObject().apply {
        put("schema", schema)
        put("exportedAtEpochMillis", 1_700_000_000_000)
        put("profile", JSONObject().put("name", "Profilo Test").put("heightCm", 170.0).put("currentWeightKg", 70.0).put("activityLevel", "Moderato").put("createdAtEpochMillis", 1).put("updatedAtEpochMillis", 2))
        put("biaMeasurements", JSONArray().put(JSONObject().put("id", 1).put("measuredAtEpochMillis", 1_000).put("weightKg", 70.0).put("fasting", true)))
        put("bodyMeasurements", JSONArray().put(JSONObject().put("id", 1).put("measuredAtEpochMillis", 2_000).put("waistCm", 80.0)))
        put("cheatEntries", JSONArray().put(JSONObject().put("id", 1).put("occurredAtEpochMillis", 3_000).put("description", "Pizza")))
        put("profilePreferences", JSONObject().put("mealCount", 4).apply {
            if (withLegacy) {
                put("workoutsEnabled", true)
                put("trainingProgram", JSONObject().put("days", JSONArray()))
                put("trainingActivityBasis", "PROFILE_LEVEL")
            }
        })
        put("mealPlans", JSONArray())
        if (withLegacy) {
            put("workouts", JSONArray()
                .put(JSONObject().put("id", 1).put("startedAtEpochMillis", 4_000).put("type", "PESI").put("title", "Pesi").put("durationMinutes", 45).put("isRestDay", false))
                .put(JSONObject().put("id", 2).put("startedAtEpochMillis", 5_000).put("type", "RIPOSO").put("title", "Riposo").put("isRestDay", true)))
            put("dailyActivityCheckIns", JSONArray().put(JSONObject().put("id", 1).put("dateEpochDay", 100).put("status", "DONE").put("adjustmentKcal", 150)))
        }
    }
}
