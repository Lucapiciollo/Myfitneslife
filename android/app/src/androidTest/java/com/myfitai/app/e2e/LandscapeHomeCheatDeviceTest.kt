package com.myfitai.app.e2e

import android.content.ComponentName
import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import com.myfitai.app.ui.CheatEntryActivity
import com.myfitai.app.ui.HomeActivity
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LandscapeHomeCheatDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val device = UiDevice.getInstance(instrumentation)

    @Before
    fun rotateLandscape() {
        instrumentation.startActivitySync(Intent().apply {
            component = ComponentName(context.packageName, "${context.packageName}.qa.QaSeederActivity")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        })
        assertTrue(device.wait(Until.hasObject(By.text("SEED_DEMO_12_MONTHS")), 8_000))
        device.findObject(By.text("SEED_DEMO_12_MONTHS")).click()
        assertTrue(waitForDemoSeed())
        device.setOrientationLeft()
    }

    @After
    fun restoreOrientation() {
        runCatching { device.unfreezeRotation() }
    }

    @Test
    fun homeLandscape_keepsChartAndQuickActionLabelsReadable() {
        ActivityScenario.launch(HomeActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                assertTrue(activity.findViewById<android.view.View>(com.myfitai.app.R.id.bodyMeasurementTrendChart) != null)
                assertTrue(activity.findViewById<android.widget.TextView>(com.myfitai.app.R.id.measurementsButton).text.toString() == "Aggiungi rilevazione")
                assertTrue(activity.findViewById<android.widget.TextView>(com.myfitai.app.R.id.addExtraButton).text.toString() == "Inserisci extra")
            }
        }
    }

    @Test
    fun cheatLandscape_keepsModeAndNutritionLabelReadable() {
        instrumentation.startActivitySync(Intent(context, CheatEntryActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        })
        assertTrue(device.wait(Until.hasObject(By.text("Rapido")), 8_000))
        assertTrue(device.hasObject(By.text("Dettagliato")))
        assertTrue(device.hasObject(By.text("Etichetta nutrizionale (opzionale)")))
        assertTrue(device.hasObject(By.res("com.myfitai.app:id/addLabelPhotoButton")))
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

}
