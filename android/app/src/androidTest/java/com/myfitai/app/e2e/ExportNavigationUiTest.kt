package com.myfitai.app.e2e

import android.view.View
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import com.myfitai.app.data.local.MyFitAiDatabase
import com.myfitai.app.data.local.entity.UserProfileEntity
import com.myfitai.app.data.profile.ActiveProfileStore
import com.myfitai.app.ui.SettingsActivity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class ExportNavigationUiTest {
    private lateinit var device: UiDevice
    private lateinit var settingsScenario: ActivityScenario<SettingsActivity>
    private var exportScenario: ActivityScenario<com.myfitai.app.ui.ExportActivity>? = null
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    @Before
    fun setUp() {
        device = UiDevice.getInstance(instrumentation)
        seedProfile()
        settingsScenario = ActivityScenario.launch(SettingsActivity::class.java)
    }

    @org.junit.After
    fun tearDown() {
        exportScenario?.close()
        if (::settingsScenario.isInitialized) settingsScenario.close()
    }

    @Test
    fun moreToExport_opensAllExportOptions() {
        openExport()
        assertTrue(device.hasObject(By.res("com.myfitai.app:id/exportCsvRow")))
        assertTrue(device.hasObject(By.res("com.myfitai.app:id/exportPdfRow")))
        assertTrue(device.hasObject(By.res("com.myfitai.app:id/exportWeeklyPlanPdfRow")))
    }

    @Test
    fun jsonExport_fromRealUiProducesFeedbackOrSystemChooser() {
        exportScenario = ActivityScenario.launch(com.myfitai.app.ui.ExportActivity::class.java)
        exportScenario!!.onActivity { activity ->
            activity.findViewById<View>(com.myfitai.app.R.id.exportJsonRow).performClick()
        }
        assertTrue(waitForExportFile("profilo", ".json", 30_000))
    }

    private fun openExport() {
        settingsScenario.onActivity { activity ->
            activity.findViewById<View>(com.myfitai.app.R.id.rowExport).performClick()
        }
        assertTrue(device.wait(Until.hasObject(By.res("com.myfitai.app:id/exportJsonRow")), 4_000))
    }

    private fun scrollTo(id: String) {
        repeat(4) {
            if (device.hasObject(By.res("com.myfitai.app:id/$id"))) return
            device.swipe(540, 1900, 540, 500, 20)
        }
    }

    private fun waitForExportFile(suffix: String, extension: String, timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val exports = File(context.cacheDir, "exports")
            if (exports.listFiles().orEmpty().any { it.name.contains(suffix) && it.name.endsWith(extension) && it.length() > 0L }) return true
            Thread.sleep(250)
        }
        return false
    }

    private fun seedProfile() {
        val database = MyFitAiDatabase.getInstance(context)
        val profile = runBlocking {
            database.userProfileDao().getFirst() ?: database.userProfileDao().insert(UserProfileEntity(
                name = "UI Export Test",
                birthDateEpochDay = null,
                heightCm = 186f,
                currentWeightKg = 89f,
                goal = "Ricomposizione",
                activityLevel = "Moderatamente attivo",
                wakeTimeMinutes = 420,
                sleepTimeMinutes = 1410,
                dietaryPreferencesJson = null,
                createdAtEpochMillis = 1L,
                updatedAtEpochMillis = 1L,
                biologicalSex = "Maschio",
                initialWeightKg = 89f,
            )).let { database.userProfileDao().get(it)!! }
        }
        ActiveProfileStore(context).selectProfile(profile.id)
    }
}
