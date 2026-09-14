package com.myfitai.app.e2e

import android.content.ComponentName
import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import com.myfitai.app.data.local.MyFitAiDatabase
import com.myfitai.app.data.local.entity.UserProfileEntity
import com.myfitai.app.data.profile.ActiveProfileStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class ExportNavigationUiTest {
    private lateinit var device: UiDevice
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    @Before
    fun setUp() {
        device = UiDevice.getInstance(instrumentation)
        seedProfile()
        context.startActivity(Intent.makeMainActivity(ComponentName(context, com.myfitai.app.ui.SplashActivity::class.java)).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        })
        assertTrue(device.wait(Until.hasObject(By.res("com.myfitai.app:id/navMore")), 8_000))
    }

    @Test
    fun moreToExport_opensAllExportOptions() {
        device.findObject(By.res("com.myfitai.app:id/navMore")).click()
        scrollTo("rowExport")
        assertTrue(device.wait(Until.hasObject(By.res("com.myfitai.app:id/rowExport")), 4_000))
        device.findObject(By.res("com.myfitai.app:id/rowExport")).click()
        assertTrue(device.wait(Until.hasObject(By.res("com.myfitai.app:id/exportJsonRow")), 4_000))
        assertTrue(device.hasObject(By.res("com.myfitai.app:id/exportCsvRow")))
        assertTrue(device.hasObject(By.res("com.myfitai.app:id/exportPdfRow")))
        assertTrue(device.hasObject(By.res("com.myfitai.app:id/exportWeeklyPlanPdfRow")))
    }

    @Test
    fun jsonExport_fromRealUiProducesFeedbackOrSystemChooser() {
        openExport()
        device.findObject(By.res("com.myfitai.app:id/exportJsonRow")).click()
        assertTrue(waitForExportFile("profilo", ".json", 30_000))
    }

    private fun openExport() {
        device.findObject(By.res("com.myfitai.app:id/navMore")).click()
        scrollTo("rowExport")
        assertTrue(device.wait(Until.hasObject(By.res("com.myfitai.app:id/rowExport")), 4_000))
        device.findObject(By.res("com.myfitai.app:id/rowExport")).click()
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
