package com.myfitai.app.e2e

import android.content.Context
import android.content.Intent
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
import com.myfitai.app.ui.ExportActivity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class ExportFormatsE2ETest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var device: UiDevice
    private var scenario: ActivityScenario<ExportActivity>? = null

    @Before
    fun seedProfile() {
        device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        val database = MyFitAiDatabase.getInstance(context)
        val profileId = runBlocking {
            database.userProfileDao().getFirst()?.id ?: database.userProfileDao().insert(UserProfileEntity(
                name = "UI Export Formats Test",
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
            ))
        }
        ActiveProfileStore(context).selectProfile(profileId)
        File(context.cacheDir, "exports").deleteRecursively()
    }

    @After
    fun tearDown() {
        scenario?.close()
    }

    @Test
    fun csvAndPdfRows_createExpectedFilesFromRealExportUi() {
        scenario = ActivityScenario.launch(ExportActivity::class.java)
        clickAndWaitForFile(com.myfitai.app.R.id.exportCsvRow, "dati", ".zip")
        clickAndWaitForFile(com.myfitai.app.R.id.exportPdfRow, "report-profilo", ".pdf")
    }

    @Test
    fun weeklyPlanPdfRow_createsPdfFromQaPlanThroughRealUi() {
        seedSixMonthQaPlan()
        scenario = ActivityScenario.launch(ExportActivity::class.java)
        clickAndWaitForFile(com.myfitai.app.R.id.exportWeeklyPlanPdfRow, "dieta-settimanale", ".pdf")
    }

    private fun clickAndWaitForFile(id: Int, suffix: String, extension: String) {
        scenario!!.onActivity { activity -> activity.findViewById<View>(id).performClick() }
        val deadline = System.currentTimeMillis() + 30_000L
        var file: File? = null
        while (System.currentTimeMillis() < deadline && file == null) {
            file = File(context.cacheDir, "exports").listFiles().orEmpty()
                .firstOrNull { it.name.contains(suffix) && it.name.endsWith(extension) && it.length() > 0L }
            if (file == null) Thread.sleep(200L)
        }
        assertTrue("Missing export $suffix$extension", file != null)
    }

    private fun seedSixMonthQaPlan() {
        context.startActivity(Intent().apply {
            component = android.content.ComponentName(context.packageName, "${context.packageName}.qa.QaSeederActivity")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        })
        assertTrue(device.wait(Until.hasObject(By.text("SEED_6_MONTHS")), 8_000))
        device.findObject(By.text("SEED_6_MONTHS")).click()
        val deadline = System.currentTimeMillis() + 90_000L
        var seeded = false
        while (System.currentTimeMillis() < deadline && !seeded) {
            val status = device.findObject(By.desc("qa_status"))
            seeded = status != null && status.text.contains("profile=")
            if (!seeded) Thread.sleep(250L)
        }
        assertTrue("QA plan seed did not complete", seeded)
        File(context.cacheDir, "exports").deleteRecursively()
    }
}
