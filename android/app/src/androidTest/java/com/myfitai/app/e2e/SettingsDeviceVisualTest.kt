package com.myfitai.app.e2e

import android.content.Intent
import android.view.WindowManager
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import com.myfitai.app.R
import com.myfitai.app.ui.SettingsActivity
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SettingsDeviceVisualTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val device = UiDevice.getInstance(instrumentation)

    @Test
    fun settings_sectionsAndSecureWindow_arePresentOnDevice() {
        ActivityScenario.launch<SettingsActivity>(Intent(context, SettingsActivity::class.java)).use { scenario ->
            scenario.onActivity { activity ->
                assertTrue(activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0)
                assertTrue(activity.findViewById<android.view.View>(R.id.activeProviderText).visibility == android.view.View.VISIBLE)
            }

            assertTrue(device.wait(Until.hasObject(By.text("Impostazioni")), 5_000))
            assertTrue(device.hasObject(By.text("Generale")))
            assertTrue(device.hasObject(By.text("Rilevazioni")))

            scenario.onActivity { activity ->
                assertTrue(activity.findViewById<android.view.View>(R.id.dataSectionCard).visibility == android.view.View.VISIBLE)
                assertTrue(activity.findViewById<android.view.View>(R.id.aiSectionCard).visibility == android.view.View.VISIBLE)
                assertTrue(activity.findViewById<android.view.View>(R.id.useGeminiSwitch).visibility == android.view.View.VISIBLE)
            }
        }
    }
}
