package com.myfitai.app.e2e

import android.content.ComponentName
import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class ProgressRangeDeviceTest {
    private lateinit var device: UiDevice
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    @Before
    fun setUp() {
        device = UiDevice.getInstance(instrumentation)
        context.startActivity(Intent(context, com.myfitai.app.qa.QaSeederActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        })
        assertTrue(device.wait(Until.hasObject(By.text("SEED_6_MONTHS")), 5_000))
        device.findObject(By.text("SEED_6_MONTHS")).click()
        assertTrue(waitForSeed())
        instrumentation.startActivitySync(Intent(context, com.myfitai.app.ui.PhysicalEvolutionActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        })
        assertTrue(device.wait(Until.hasObject(By.res("com.myfitai.app:id/timeRangeSelector")), 5_000))
    }

    @Test
    fun progressRanges_areRenderedAndCapturedOnDevice() {
        val dir = File(context.getExternalFilesDir(null), "qa-artifacts").apply { mkdirs() }
        val rangeLabels = listOf("1M", "3M", "6M", "1Y")
        rangeLabels.forEachIndexed { index, label ->
            val node = device.findObject(By.text(label))
            assertTrue("Missing range $label", node != null)
            node.click()
            assertTrue(device.wait(Until.hasObject(By.res("com.myfitai.app:id/evolutionChart")), 2_000))
            device.takeScreenshot(File(dir, "progress_${label.lowercase()}.png"))
        }
        val metricSegment = device.findObject(By.res("com.myfitai.app:id/metricSegment"))
        assertTrue(metricSegment != null)
        metricSegment.click()
        assertTrue(device.hasObject(By.text("Grasso corporeo")) || device.hasObject(By.text("Grasso")))
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
