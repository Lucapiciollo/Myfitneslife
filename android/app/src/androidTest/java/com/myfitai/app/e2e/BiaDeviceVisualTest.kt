package com.myfitai.app.e2e

import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import com.myfitai.app.ui.BiaActivity
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
}
