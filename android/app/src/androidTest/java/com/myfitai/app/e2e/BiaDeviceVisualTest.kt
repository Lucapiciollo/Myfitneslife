package com.myfitai.app.e2e

import android.content.Intent
import android.view.View
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import com.myfitai.app.ui.BiaActivity
import com.myfitai.app.R
import com.myfitai.app.domain.body.BiaImportContract
import com.myfitai.app.ui.widgets.MeasurementRowView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class BiaDeviceVisualTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val device = UiDevice.getInstance(instrumentation)

    @Test
    fun dateAndTimePickers_showStandardNeutralDialogTheme() {
        instrumentation.startActivitySync(Intent(context, BiaActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        })

        assertTrue(device.wait(Until.hasObject(By.text("Bioimpedenziometria")), 5_000))
        val dir = File(context.getExternalFilesDir(null), "qa-artifacts").apply { mkdirs() }

        device.findObject(By.res("com.myfitai.app:id/dateInput")).click()
        assertTrue(device.wait(Until.hasObject(By.text("Data misurazione")), 3_000))
        assertTrue(device.hasObject(By.text("OK")))
        device.takeScreenshot(File(dir, "bia_date_picker_neutral.png"))
        device.pressBack()

        device.findObject(By.res("com.myfitai.app:id/timeInput")).click()
        assertTrue(device.wait(Until.hasObject(By.text("Ora misurazione")), 3_000))
        assertTrue(device.hasObject(By.text("OK")))
        device.takeScreenshot(File(dir, "bia_time_picker_neutral.png"))
        device.pressBack()
    }

    @Test
    fun newBiaForm_usesStandardWhiteCardsAndCompactFields() {
        instrumentation.startActivitySync(Intent(context, BiaActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        })

        assertTrue(device.wait(Until.hasObject(By.text("Bioimpedenziometria")), 5_000))
        assertTrue(device.hasObject(By.res("com.myfitai.app:id/biaDateCard")))
        assertTrue(device.hasObject(By.res("com.myfitai.app:id/biaConditionsCard")))
        assertTrue(device.hasObject(By.res("com.myfitai.app:id/biaResultsCard")))
        assertTrue(device.hasObject(By.res("com.myfitai.app:id/dateInput")))
        assertTrue(device.hasObject(By.res("com.myfitai.app:id/timeInput")))
        assertTrue(device.hasObject(By.text("Condizioni della misura")))
        assertTrue(device.hasObject(By.text("Risultati")))

        device.findObject(By.res("com.myfitai.app:id/dateInput")).click()
        assertTrue(device.wait(Until.hasObject(By.text("Data misurazione")), 3_000))
        assertTrue(device.hasObject(By.text("OK")))
        assertTrue(device.hasObject(By.text("ANNULLA")) || device.hasObject(By.text("CANCELLA")))
        device.takeScreenshot(File(context.getExternalFilesDir(null), "qa-artifacts/bia_date_picker.png"))
        device.pressBack()

        device.findObject(By.res("com.myfitai.app:id/timeInput")).click()
        assertTrue(device.wait(Until.hasObject(By.text("Ora misurazione")), 3_000))
        assertTrue(device.hasObject(By.text("OK")))
        assertTrue(device.hasObject(By.desc("Seleziona ora")) || device.hasObject(By.desc("Seleziona minuti")))
        device.takeScreenshot(File(context.getExternalFilesDir(null), "qa-artifacts/bia_time_picker.png"))
        device.pressBack()

        val dir = File(context.getExternalFilesDir(null), "qa-artifacts").apply { mkdirs() }
        device.takeScreenshot(File(dir, "bia_new_form_white_cards_top.png"))

        device.swipe(540, 1800, 540, 500, 20)
        assertTrue(device.wait(Until.hasObject(By.res("com.myfitai.app:id/importPhotoButton")), 3_000))
        assertTrue(device.hasObject(By.res("com.myfitai.app:id/importPhotoButton")))
        assertTrue(device.hasObject(By.res("com.myfitai.app:id/saveButton")))

        device.takeScreenshot(File(dir, "bia_new_form_white_cards_bottom.png"))
    }

    @Test
    fun biaValueDialog_baselineAndCancelDoNotPersist() {
        val dir = File(context.getExternalFilesDir(null), "qa-artifacts").apply { mkdirs() }
        ActivityScenario.launch<BiaActivity>(Intent(context, BiaActivity::class.java)).use { scenario ->
            var originalRowText = ""
            scenario.onActivity { activity ->
                val row = activity.findViewById<MeasurementRowView>(R.id.rowWeight)
                originalRowText = (row.getChildAt(row.childCount - 1) as TextView).text.toString()
                row.performClick()
            }
            assertTrue(device.wait(Until.hasObject(By.text("Peso")), 3_000))
            assertTrue(device.hasObject(By.text("Annulla")))
            assertTrue(device.hasObject(By.text("Svuota")))
            assertTrue(device.hasObject(By.text("Conferma")))
            device.takeScreenshot(File(dir, "bia-value-dialog-before.png"))
            device.pressBack()
            assertTrue(device.wait(Until.gone(By.text("Conferma")), 3_000))
            scenario.onActivity { activity ->
                val row = activity.findViewById<MeasurementRowView>(R.id.rowWeight)
                assertEquals(originalRowText, (row.getChildAt(row.childCount - 1) as TextView).text.toString())
                row.performClick()
            }
            assertTrue(device.wait(Until.hasObject(By.text("Peso")), 3_000))
            device.findObject(By.text("Annulla")).click()
            assertTrue(device.wait(Until.gone(By.text("Conferma")), 3_000))
            scenario.onActivity { activity ->
                val row = activity.findViewById<MeasurementRowView>(R.id.rowWeight)
                assertEquals(originalRowText, (row.getChildAt(row.childCount - 1) as TextView).text.toString())
            }
        }
    }

    @Test
    fun biaValueDialog_fontScaleAndLandscape_keepControlsAvailable() {
        val originalFont = device.executeShellCommand("settings get system font_scale").trim()
        try {
            device.executeShellCommand("settings put system font_scale 1.3")
            device.executeShellCommand("wm size 2340x1080")
            ActivityScenario.launch<BiaActivity>(Intent(context, BiaActivity::class.java)).use { scenario ->
                scenario.onActivity { activity -> activity.findViewById<MeasurementRowView>(R.id.rowWeight).performClick() }
                assertTrue(device.wait(Until.hasObject(By.text("Peso")), 5_000))
                assertTrue(device.hasObject(By.text("Annulla")))
                assertTrue(device.hasObject(By.text("Svuota")))
                assertTrue(device.hasObject(By.text("Conferma")))
                device.takeScreenshot(File(context.getExternalFilesDir(null), "qa-artifacts/bia-value-dialog-font130-landscape.png"))
                device.pressBack()
                assertTrue(device.wait(Until.gone(By.text("Conferma")), 3_000))
            }
        } finally {
            device.executeShellCommand("settings put system font_scale $originalFont")
            device.executeShellCommand("wm size 1080x2340")
            device.executeShellCommand("settings put system accelerometer_rotation 1")
            device.executeShellCommand("settings put system user_rotation 0")
        }
        assertTrue(device.executeShellCommand("wm size").contains("1080x2340"))
        assertTrue(device.executeShellCommand("settings get system font_scale").trim() == originalFont)
    }

    @Test
    fun biaImportPreview_baselineScrollAndCancelAreNonPersistent() {
        val dir = File(context.getExternalFilesDir(null), "qa-artifacts").apply { mkdirs() }
        ActivityScenario.launch<BiaActivity>(Intent(context, BiaActivity::class.java)).use { scenario ->
            scenario.onActivity { activity ->
                val preview = BiaImportContract.Preview(
                    isBiaDocument = true,
                    rejectionReason = "",
                    measuredAtEpochMillis = null,
                    weightKg = 76.8f,
                    bodyFatPercent = 20.1f,
                    visceralFatLevel = 8f,
                    muscleMassKg = 58.4f,
                    skeletalMuscleKg = 31.2f,
                    bodyWaterPercent = 55.3f,
                    bmrKcal = 1710f,
                    confidence = "HIGH",
                    notes = "",
                    fatMassKg = 15.4f,
                    leanMassKg = 61.4f,
                    bodyWaterKg = 42.5f,
                    subcutaneousFatPercent = 17.2f,
                    boneMassKg = 3.1f,
                    proteinPercent = 18.4f,
                    proteinKg = 14.1f,
                    bodyAgeYears = 34f,
                    bmi = 24.2f,
                )
                val method = BiaActivity::class.java.getDeclaredMethod(
                    "showImportPreview",
                    BiaImportContract.Preview::class.java,
                    String::class.java,
                    String::class.java,
                ).apply { isAccessible = true }
                method.invoke(activity, preview, "Test provider", "QA model")
            }
            assertTrue(device.wait(Until.hasObject(By.text("Controlla importazione BIA")), 5_000))
            assertTrue(device.hasObject(By.text("Annulla")))
            assertTrue(device.hasObject(By.text("Usa valori")))
            assertTrue(device.hasObject(By.text("Valori letti dalla foto")))
            assertTrue(device.hasObject(By.res("com.myfitai.app:id/biaImportWeight")))
            device.takeScreenshot(File(dir, "bia-import-preview-before.png"))
            device.swipe(540, 1650, 540, 750, 24)
            device.takeScreenshot(File(dir, "bia-import-preview-scrolled.png"))
            device.pressBack()
            assertTrue(device.wait(Until.gone(By.text("Controlla importazione BIA")), 3_000))
            scenario.onActivity { activity ->
                assertTrue(activity.findViewById<View>(R.id.rowWeight).isShown)
                assertTrue(activity.findViewById<View>(R.id.saveButton).isShown)
            }
        }
    }
}
