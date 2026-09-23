package com.myfitai.app.e2e

import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import com.myfitai.app.data.local.MyFitAiDatabase
import com.myfitai.app.ui.BiaActivity
import com.myfitai.app.ui.BodyMeasuresActivity
import com.myfitai.app.ui.NewBodyMeasurementActivity
import kotlinx.coroutines.flow.first
import com.myfitai.app.qa.QaSeederActivity
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class MeasurementHistoryDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val device = UiDevice.getInstance(instrumentation)
    private var seededProfileId: Long = 0L

    @Before
    fun seedQaData() {
        context.startActivity(Intent(context, QaSeederActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        })
        assertTrue(device.wait(Until.hasObject(By.text("SEED_6_MONTHS")), 5_000))
        device.findObject(By.text("SEED_6_MONTHS")).click()
        assertTrue(waitForSeed())
    }

    @Test
    fun biaAndBodyHistory_haveVisibleCardsAndActions() {
        val dir = File(context.getExternalFilesDir(null), "qa-artifacts").apply { mkdirs() }

        instrumentation.startActivitySync(Intent(context, BiaActivity::class.java).apply {
            putExtra(BiaActivity.EXTRA_OPEN_HISTORY, true)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        })
        assertTrue(device.wait(Until.hasObject(By.text("Storico")), 5_000))
        assertTrue(device.hasObject(By.res("com.myfitai.app:id/historyList")))
        assertTrue(device.hasObject(By.text("Tocca per modificare · Tieni premuto per eliminare")))
        device.takeScreenshot(File(dir, "bia_history.png"))

        instrumentation.startActivitySync(Intent(context, BodyMeasuresActivity::class.java).apply {
            putExtra(BodyMeasuresActivity.EXTRA_OPEN_HISTORY, true)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        })
        assertTrue(device.wait(Until.hasObject(By.text("Storico misurazioni")), 5_000))
        assertTrue(device.hasObject(By.res("com.myfitai.app:id/historyList")))
        assertTrue(device.hasObject(By.text("Tocca per modificare · Tieni premuto per eliminare")))
        device.takeScreenshot(File(dir, "body_measurement_history.png"))
    }

    @Test
    fun historyCards_openExistingValuesForEdit_withoutSaving() {
        val database = MyFitAiDatabase.getInstance(context)
        val profileId = seededProfileId
        assertTrue(profileId > 0L)
        val biaId = kotlinx.coroutines.runBlocking {
            database.biaMeasurementDao().getAll(profileId).first().id
        }
        val bodyId = kotlinx.coroutines.runBlocking {
            database.bodyMeasurementDao().observeAll(profileId).first().first().id
        }

        instrumentation.startActivitySync(Intent(context, BiaActivity::class.java).apply {
            putExtra(BiaActivity.EXTRA_EDIT_ID, biaId)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        })
        assertTrue(device.wait(Until.hasObject(By.text("Bioimpedenziometria")), 5_000))
        device.waitForIdle()
        assertTrue(device.hasObject(By.res("com.myfitai.app:id/dateInput")))
        assertTrue(device.hasObject(By.res("com.myfitai.app:id/rowWeight")))
        assertTrue(scrollToButton("com.myfitai.app:id/saveButton", "Salva modifiche"))

        instrumentation.startActivitySync(Intent(context, BodyMeasuresActivity::class.java).apply {
            putExtra(BodyMeasuresActivity.EXTRA_OPEN_HISTORY, true)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        })
        assertTrue(device.wait(Until.hasObject(By.text("Storico misurazioni")), 5_000))
        assertTrue(device.wait(Until.hasObject(By.text("Tocca per modificare · Tieni premuto per eliminare")), 5_000))

        instrumentation.startActivitySync(Intent(context, NewBodyMeasurementActivity::class.java).apply {
            putExtra(NewBodyMeasurementActivity.EXTRA_EDIT_ID, bodyId)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        })
        assertTrue(device.wait(Until.hasObject(By.text("Modifica misurazione")), 5_000))
        assertTrue(device.hasObject(By.res("com.myfitai.app:id/dateInput")))
        assertTrue(scrollToButton("com.myfitai.app:id/saveMeasurementButton", "Salva modifiche"))
    }

    private fun waitForSeed(): Boolean {
        val deadline = System.currentTimeMillis() + 90_000L
        while (System.currentTimeMillis() < deadline) {
            val status = device.findObject(By.desc("qa_status"))
            if (status != null && status.text.contains("profile=")) {
                seededProfileId = Regex("profile=(\\d+)").find(status.text)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
                return seededProfileId > 0L
            }
            Thread.sleep(250)
        }
        return false
    }

    private fun waitForButtonText(resourceId: String, expected: String): Boolean {
        val deadline = System.currentTimeMillis() + 5_000L
        while (System.currentTimeMillis() < deadline) {
            val button = device.findObject(By.res(resourceId))
            if (button != null && button.text == expected) return true
            Thread.sleep(100)
        }
        return false
    }

    private fun scrollToButton(resourceId: String, expected: String): Boolean {
        repeat(6) {
            if (waitForButtonText(resourceId, expected)) return true
            device.swipe(540, 1800, 540, 500, 20)
        }
        return waitForButtonText(resourceId, expected)
    }

}
