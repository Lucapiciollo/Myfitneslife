package com.myfitai.app.e2e

import android.content.Intent
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import com.myfitai.app.domain.body.BiaImportContract
import com.myfitai.app.ui.BiaActivity
import com.myfitai.app.ui.HomeActivity
import com.myfitai.app.ui.SettingsActivity
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Invariant for every dialog that hosts custom content: the action buttons must lie fully inside their panel
 * (no clipping, nothing to scroll in the button area). Read-only: dialogs are opened and dismissed with Back.
 */
@RunWith(AndroidJUnit4::class)
class DialogActionBarIntegrityDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val device = UiDevice.getInstance(instrumentation)
    private val report = StringBuilder()
    private val failures = mutableListOf<String>()

    private fun check(name: String) {
        Thread.sleep(700)
        val panel = device.findObject(By.res("com.myfitai.app:id/buttonPanel"))?.visibleBounds
        val button = (device.findObject(By.res("android:id/button1")) ?: device.findObject(By.res("android:id/button2")))?.visibleBounds
        report.appendLine("$name\tpanel=$panel\tbutton=$button")
        if (panel == null || button == null) { failures += "$name: dialog panel/buttons not found"; return }
        // The button must fit the panel it lives in (a clipped bar turns the panel into a scroller).
        val clippedPx = button.bottom - panel.bottom
        if (clippedPx > 0 || button.height() < context.resources.getDimensionPixelSize(com.myfitai.app.R.dimen.control_min_height) * 0.9) {
            failures += "$name: buttons clipped by ${clippedPx}px (panel=$panel button=$button)"
        }
    }

    @Test
    fun customContentDialogsKeepTheirActionBarFullyVisible() {
        // 1) BIA import preview (modal with 16 fields)
        ActivityScenario.launch<BiaActivity>(Intent(context, BiaActivity::class.java)).use { scenario ->
            scenario.onActivity { activity ->
                val m = BiaActivity::class.java.getDeclaredMethod("showImportPreview", BiaImportContract.Preview::class.java, String::class.java, String::class.java).apply { isAccessible = true }
                m.invoke(activity, BiaImportContract.Preview(true, "", 1_760_000_000_000L, 89f, 22f, 10f, 66f, 33f, 55f, 1941f, "alta", "verifica"), "Gemini", "m")
            }
            assertTrue(device.wait(Until.hasObject(By.text("Controlla importazione BIA")), 8_000))
            check("bia_import_preview")
            device.pressBack()
        }
        // 2) Settings guide and delete chooser (long content in a ScrollView)
        ActivityScenario.launch<SettingsActivity>(Intent(context, SettingsActivity::class.java)).use {
            if (device.wait(Until.hasObject(By.text("Come funziona MyFitAI")), 6_000)) {
                device.findObject(By.text("Come funziona MyFitAI")).click()
                if (device.wait(Until.hasObject(By.text("Ho capito")), 4_000)) { check("settings_guide"); device.pressBack() }
            }
        }
        // 3) Home help (capped ScrollView, reference behaviour)
        ActivityScenario.launch<HomeActivity>(Intent(context, HomeActivity::class.java)).use {
            if (device.wait(Until.hasObject(By.res("com.myfitai.app:id/caloriesHelpButton")), 8_000)) {
                device.findObject(By.res("com.myfitai.app:id/caloriesHelpButton")).click()
                if (device.wait(Until.hasObject(By.text("Ho capito")), 4_000)) { check("home_help"); device.pressBack() }
            }
        }
        File(context.getExternalFilesDir(null), "qa-artifacts").apply { mkdirs() }.let { File(it, "dialog-actionbar.txt").writeText(report.toString()) }
        assertTrue("Dialog action bars must be fully visible:\n" + failures.joinToString("\n"), failures.isEmpty())
    }
}
