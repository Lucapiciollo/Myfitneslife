package com.myfitai.app.e2e

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.myfitai.app.data.AppDataContainer
import com.myfitai.app.domain.body.BiaHistoryImportContract
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** One-time non-destructive import of the user-supplied history; reads the staged JSON from app external files. */
@RunWith(AndroidJUnit4::class)
class LocalBiaHistoryImportOnceDeviceTest {
    @Test
    fun verifyImportedHistoryCountForLuca() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val data = AppDataContainer.get(context)
        val profile = data.activeProfileStore.currentIdOrNull()?.let { data.userProfileRepository.get(it) }
            ?: error("No active profile")
        assertEquals("Luca", profile.name)
        val dates = data.biaRepository.all(profile.id).first()
            .map { BiaHistoryImportContract.dayKey(it.measuredAtEpochMillis) }
            .toSet()
        val expected = ("2026-01-10,2026-01-16,2026-01-29,2026-02-03,2026-02-10,2026-02-24," +
            "2026-03-02,2026-03-19,2026-04-12,2026-05-20,2026-05-29,2026-06-11," +
            "2026-06-21,2026-07-03,2026-07-19,2026-09-12,2026-09-27").split(',').toSet()
        assertTrue("missing BIA dates: ${expected - dates}", dates.containsAll(expected))
        assertTrue("unexpected count of imported dates: ${dates.size}", dates.size >= 17)
    }

    @Test
    fun importSuppliedHistoryIntoLucaWithoutReplacingExistingDates() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val data = AppDataContainer.get(context)
        val profileId = data.activeProfileStore.currentIdOrNull() ?: error("No active profile")
        val profile = data.userProfileRepository.get(profileId) ?: error("Active profile not found")
        assertEquals("Luca", profile.name)

        val staged = File(context.getExternalFilesDir(null), "bia-history-luca.json")
        require(staged.isFile) { "Expected staged import at app-specific external files path" }
        val readings = BiaHistoryImportContract.parse(staged.readText(), profileId)
        assertEquals(17, readings.size)

        val (inserted, skipped) = data.biaRepository.importMissing(profileId, readings)
        assertEquals(17, inserted + skipped)

        val saved = data.biaRepository.all(profileId).first()
            .associateBy { BiaHistoryImportContract.dayKey(it.measuredAtEpochMillis) }
        readings.forEach { expected ->
            val actual = saved[BiaHistoryImportContract.dayKey(expected.measuredAtEpochMillis)]
                ?: error("Missing imported date ${BiaHistoryImportContract.dayKey(expected.measuredAtEpochMillis)}")
            assertEquals(expected.weightKg, actual.weightKg)
            assertEquals(expected.bodyFatPercent, actual.bodyFatPercent)
            assertEquals(expected.fatMassKg, actual.fatMassKg)
            assertEquals(expected.muscleMassKg, actual.muscleMassKg)
            assertEquals(expected.notes, actual.notes)
        }
        assertTrue("Only the selected Luca profile may receive this history", saved.values.all { it.profileId == profileId })
    }
}
