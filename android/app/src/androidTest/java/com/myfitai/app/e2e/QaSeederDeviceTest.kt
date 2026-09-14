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
class QaSeederDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val device = UiDevice.getInstance(instrumentation)

    @Test
    fun qaSeeder_writesRealRoomDataThenNormalAppReopensIt() {
        context.startActivity(Intent().apply {
            component = ComponentName(context.packageName, "${context.packageName}.qa.QaSeederActivity")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        })
        assertTrue(device.wait(Until.hasObject(By.text("SEED_6_MONTHS")), 5_000))
        device.findObject(By.text("SEED_6_MONTHS")).click()
        assertTrue(waitForSeedResult(90_000))
        val seedStatus = device.findObject(By.desc("qa_status"))
        assertTrue(seedStatus != null && seedStatus.text.contains("profile="))
        device.pressHome()
        instrumentation.startActivitySync(Intent(context, com.myfitai.app.ui.SplashActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        })
        assertTrue(device.wait(Until.hasObject(By.res("${context.packageName}:id/navHome")), 15_000))
        assertTrue(device.hasObject(By.res("${context.packageName}:id/navProgress")))
        assertTrue(device.hasObject(By.res("${context.packageName}:id/navFood")))
        assertTrue(device.hasObject(By.res("${context.packageName}:id/navMore")))
    }

    private fun waitForSeedResult(timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val status = device.findObject(By.desc("qa_status"))
            if (status != null && (status.text.contains("profile=") || status.text.contains("ERRORE:"))) return true
            Thread.sleep(250)
        }
        return false
    }
}
