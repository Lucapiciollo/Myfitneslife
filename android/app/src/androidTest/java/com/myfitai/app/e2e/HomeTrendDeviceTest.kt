package com.myfitai.app.e2e

import android.content.ComponentName
import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HomeTrendDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val device = UiDevice.getInstance(instrumentation)

    @Test
    fun bodyTrend_selectorAndRealChart_areInteractive() {
        context.startActivity(Intent().apply {
            component = ComponentName(context.packageName, "${context.packageName}.qa.QaSeederActivity")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        })
        assertTrue(device.wait(Until.hasObject(By.text("SEED_DEMO_12_MONTHS")), 8_000))
        device.findObject(By.text("SEED_DEMO_12_MONTHS")).click()
        assertTrue(waitForDemoSeed())

        context.startActivity(Intent(context, com.myfitai.app.ui.HomeActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        })
        assertTrue(device.wait(Until.hasObject(By.text("Panoramica del corpo")), 10_000))
        assertTrue(scrollUntilVisible(By.res("com.myfitai.app:id/bodyMeasurementTrendChart")))
        assertTrue(device.hasObject(By.res("com.myfitai.app:id/bodyMeasurementTrendChart")))
        val selector = device.findObject(By.res("com.myfitai.app:id/bodyTrendMetricSelector"))
        assertTrue(selector != null)
        selector.click()
        assertTrue(device.wait(Until.hasObject(By.text("Vita")), 3_000))
        device.findObject(By.text("Vita")).click()
        assertTrue(device.wait(Until.hasObject(By.text("Vita")), 2_000))
    }

    private fun waitForDemoSeed(): Boolean {
        val deadline = System.currentTimeMillis() + 90_000L
        while (System.currentTimeMillis() < deadline) {
            val status = device.findObject(By.desc("qa_status"))
            if (status != null && status.text.contains("demo=")) return true
            Thread.sleep(250L)
        }
        return false
    }

    private fun scrollUntilVisible(selector: androidx.test.uiautomator.BySelector): Boolean {
        if (device.wait(Until.hasObject(selector), 1_000)) return true
        repeat(8) {
            device.swipe(device.displayWidth / 2, (device.displayHeight * 0.78).toInt(), device.displayWidth / 2, (device.displayHeight * 0.28).toInt(), 20)
            if (device.wait(Until.hasObject(selector), 1_000)) return true
        }
        return false
    }
}
