package com.myfitai.app.e2e

import android.content.Intent
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.material.chip.Chip
import com.myfitai.app.R
import com.myfitai.app.data.AppDataContainer
import com.myfitai.app.data.profile.TrainingProgramPreferences
import com.myfitai.app.domain.calculation.DailyActivityCheckInEngine.Intensity
import com.myfitai.app.domain.calculation.TrainingProgram
import com.myfitai.app.domain.export.ProfileExportService
import com.myfitai.app.ui.NutritionPlanSettingsActivity
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import java.time.DayOfWeek
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Non-destructive: the active profile's original program is restored at the end of every test. */
@RunWith(AndroidJUnit4::class)
class TrainingProgramSettingsDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun preferencesPersistAndClearPerProfile() {
        val prefs = TrainingProgramPreferences(context)
        val fakeProfileId = 9_000_001L
        val program = TrainingProgram(setOf(DayOfWeek.MONDAY, DayOfWeek.THURSDAY), 90, Intensity.LIGHT, 7 * 60)
        try {
            assertTrue(prefs.get(fakeProfileId).isEmpty)

            prefs.set(fakeProfileId, program, synchronous = true)

            assertEquals(program, TrainingProgramPreferences(context).get(fakeProfileId))
            assertTrue("other profiles are not affected", prefs.get(fakeProfileId + 1).isEmpty)

            prefs.clearProfile(fakeProfileId)
            assertEquals(TrainingProgram(), prefs.get(fakeProfileId))
        } finally {
            prefs.clearProfile(fakeProfileId)
        }
    }

    @Test
    fun exportContainsTheProgram() = runBlocking<Unit> {
        val data = AppDataContainer.get(context)
        val profileId = data.activeProfileStore.currentIdOrNull() ?: error("No active profile")
        val original = data.trainingProgramPreferences.get(profileId)
        val program = TrainingProgram(setOf(DayOfWeek.TUESDAY, DayOfWeek.SATURDAY), 45, Intensity.HARD, 6 * 60 + 45)
        try {
            data.trainingProgramPreferences.set(profileId, program, synchronous = true)

            val exported = data.profileExportService.exportProfile(profileId, ProfileExportService.Format.JSON).file
            val stored = JSONObject(exported.readText()).getJSONObject("profilePreferences").optJSONObject("trainingProgram")

            assertEquals(program, TrainingProgram.fromJson(stored))
            exported.delete()
        } finally {
            data.trainingProgramPreferences.set(profileId, original, synchronous = true)
        }
    }

    @Test
    fun dayChipsSaveImmediatelyAndReloadFromStorage() {
        val data = AppDataContainer.get(context)
        val profileId = data.activeProfileStore.currentIdOrNull() ?: error("No active profile")
        val original = data.trainingProgramPreferences.get(profileId)
        try {
            data.trainingProgramPreferences.set(profileId, TrainingProgram(), synchronous = true)
            ActivityScenario.launch<NutritionPlanSettingsActivity>(
                Intent(context, NutritionPlanSettingsActivity::class.java),
            ).use { scenario ->
                scenario.onActivity { activity ->
                    assertFalse(activity.findViewById<Chip>(R.id.trainingDayMonday).isChecked)
                    activity.findViewById<Chip>(R.id.trainingDayMonday).performClick()
                    activity.findViewById<Chip>(R.id.trainingDayWednesday).performClick()
                    activity.findViewById<Chip>(R.id.trainingDayFriday).performClick()
                }
                assertEquals(
                    setOf(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY, DayOfWeek.FRIDAY),
                    data.trainingProgramPreferences.get(profileId).days,
                )
                scenario.onActivity { activity ->
                    val summary = activity.findViewById<android.widget.TextView>(R.id.trainingProgramValue).text.toString()
                    assertTrue(summary, summary.startsWith("Lun, Mer, Ven"))
                    activity.findViewById<Chip>(R.id.trainingDayWednesday).performClick()
                }
                assertEquals(
                    setOf(DayOfWeek.MONDAY, DayOfWeek.FRIDAY),
                    data.trainingProgramPreferences.get(profileId).days,
                )
            }
            ActivityScenario.launch<NutritionPlanSettingsActivity>(
                Intent(context, NutritionPlanSettingsActivity::class.java),
            ).use { reopened ->
                reopened.onActivity { activity ->
                    assertTrue(activity.findViewById<Chip>(R.id.trainingDayMonday).isChecked)
                    assertFalse(activity.findViewById<Chip>(R.id.trainingDayWednesday).isChecked)
                    assertTrue(activity.findViewById<Chip>(R.id.trainingDayFriday).isChecked)
                }
            }
        } finally {
            // Synchronous on purpose: an asynchronous write can be lost when the instrumentation process ends.
            data.trainingProgramPreferences.set(profileId, original, synchronous = true)
        }
    }

}
