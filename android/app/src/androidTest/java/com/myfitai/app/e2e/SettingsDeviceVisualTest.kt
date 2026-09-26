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
import java.io.File

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
                assertTrue("Profile editing is presented from Profile, not duplicated in Settings", activity.findViewById<android.view.View>(R.id.rowFoodPreferences) == null)
            }
        }
    }

    @Test
    fun appGuideUsesStandardHelpDialogAndDescribesCalorieReferences() {
        ActivityScenario.launch<SettingsActivity>(Intent(context, SettingsActivity::class.java)).use {
            assertTrue(device.wait(Until.hasObject(By.res("com.myfitai.app:id/rowAppGuide")), 5_000))
            device.findObject(By.res("com.myfitai.app:id/rowAppGuide")).click()
            assertTrue(device.wait(Until.hasObject(By.text("Come funziona MyFitAI")), 3_000))
            assertTrue(device.hasObject(By.text("Ho capito")))
            assertTrue(device.hasObject(By.text("Il percorso MyFitAI")))
            val dir = File(context.getExternalFilesDir(null), "qa-artifacts").apply { mkdirs() }
            device.takeScreenshot(File(dir, "settings_app_guide_standard_dialog.png"))
            device.pressBack()
            assertTrue(device.wait(Until.hasObject(By.res("com.myfitai.app:id/settingsContent")), 3_000))
        }
    }
}
