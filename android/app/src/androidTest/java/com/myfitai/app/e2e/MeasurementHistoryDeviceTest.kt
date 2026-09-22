package com.myfitai.app.e2e

import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import com.myfitai.app.ui.BiaActivity
import com.myfitai.app.ui.BodyMeasuresActivity
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

    private fun waitForSeed(): Boolean {
        val deadline = System.currentTimeMillis() + 90_000L
        while (System.currentTimeMillis() < deadline) {
            val status = device.findObject(By.desc("qa_status"))
            if (status != null && status.text.contains("profile=")) return true
            Thread.sleep(250)
        }
        return false
    }
}
