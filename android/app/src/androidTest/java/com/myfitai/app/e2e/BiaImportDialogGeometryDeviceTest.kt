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
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Read-only geometry check of the "Controlla importazione BIA" modal: opens it with a synthetic preview
 * (no photo, no AI call, nothing saved) and verifies the action buttons never move while the content scrolls.
 */
@RunWith(AndroidJUnit4::class)
class BiaImportDialogGeometryDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val device = UiDevice.getInstance(instrumentation)
    private val dir = File(context.getExternalFilesDir(null), "qa-artifacts").apply { mkdirs() }
    private val log = StringBuilder()

    private fun preview() = BiaImportContract.Preview(
        isBiaDocument = true, rejectionReason = "", measuredAtEpochMillis = 1_760_000_000_000L,
        weightKg = 89.4f, bodyFatPercent = 22.1f, visceralFatLevel = 10f, muscleMassKg = 66.2f,
        skeletalMuscleKg = 33.1f, bodyWaterPercent = 55.2f, bmrKcal = 1941f, confidence = "alta", notes = "Valori di verifica",
        fatMassKg = 19.8f, leanMassKg = 69.6f, bodyWaterKg = 49.3f, subcutaneousFatPercent = 18f, boneMassKg = 3.4f,
        proteinPercent = 17.2f, proteinKg = 15.4f, bodyAgeYears = 38f, bmi = 25.8f,
    )

    private fun rect(res: String): android.graphics.Rect? = device.findObject(By.res(res))?.visibleBounds

    private fun snapshot(label: String) {
        val negative = rect("android:id/button2")
        val positive = rect("android:id/button1")
        val scroll = rect("com.myfitai.app:id/biaImportScroll")
        log.appendLine("$label\tcancel=$negative\tconfirm=$positive\tscroll=$scroll")
    }

    @Test
    fun actionButtonsStayFixedWhileContentScrollsAndKeyboardOpens() {
        ActivityScenario.launch<BiaActivity>(Intent(context, BiaActivity::class.java)).use { scenario ->
            scenario.onActivity { activity ->
                val method = BiaActivity::class.java.getDeclaredMethod(
                    "showImportPreview", BiaImportContract.Preview::class.java, String::class.java, String::class.java,
                ).apply { isAccessible = true }
                method.invoke(activity, preview(), "Gemini", "test-model")
            }
            assertTrue(device.wait(Until.hasObject(By.text("Controlla importazione BIA")), 8_000))
            Thread.sleep(500)
            device.takeScreenshot(File(dir, "bia-import-1-open.png"))
            snapshot("open")
            val before = rect("android:id/button1")

            // Scroll the content inside the dialog.
            val scrollArea = device.findObject(By.res("com.myfitai.app:id/biaImportScroll"))
            repeat(3) { scrollArea?.scroll(androidx.test.uiautomator.Direction.DOWN, 0.8f); Thread.sleep(250) }
            device.takeScreenshot(File(dir, "bia-import-2-scrolled.png"))
            snapshot("scrolled")
            val afterScroll = rect("android:id/button1")

            // Open the keyboard on a field.
            device.findObject(By.res("com.myfitai.app:id/biaImportBmi"))?.click()
            Thread.sleep(800)
            device.takeScreenshot(File(dir, "bia-import-3-keyboard.png"))
            snapshot("keyboard")
            val withKeyboard = rect("android:id/button1")
            File(dir, "bia-import-geometry.txt").writeText(log.toString())

            val minButton = (context.resources.getDimensionPixelSize(com.myfitai.app.R.dimen.control_min_height) * 0.9f).toInt()
            assertTrue("confirm button must be fully visible when the modal opens: $before", before != null && before.height() >= minButton)
            assertTrue("confirm button must not move while the content scrolls: $before -> $afterScroll", before == afterScroll)
            assertTrue("confirm button must stay fully visible with the keyboard open: $withKeyboard", withKeyboard != null && withKeyboard.height() >= minButton)
            val panel = rect("com.myfitai.app:id/buttonPanel")
            assertTrue("button panel must not be shorter than its buttons (clipped bar scrolls): panel=$panel button=$withKeyboard", panel != null && withKeyboard!!.bottom <= panel.bottom)
            device.pressBack(); device.pressBack()
        }
    }

    @Test
    fun actionBarStaysFullyVisibleInLandscape() {
        device.setOrientationLeft()
        try {
            ActivityScenario.launch<BiaActivity>(Intent(context, BiaActivity::class.java)).use { scenario ->
                scenario.onActivity { activity ->
                    val method = BiaActivity::class.java.getDeclaredMethod(
                        "showImportPreview", BiaImportContract.Preview::class.java, String::class.java, String::class.java,
                    ).apply { isAccessible = true }
                    method.invoke(activity, preview(), "Gemini", "test-model")
                }
                assertTrue(device.wait(Until.hasObject(By.text("Controlla importazione BIA")), 8_000))
                Thread.sleep(600)
                device.takeScreenshot(File(dir, "bia-import-4-landscape.png"))
                val minButton = (context.resources.getDimensionPixelSize(com.myfitai.app.R.dimen.control_min_height) * 0.9f).toInt()
                val button = rect("android:id/button1")
                assertTrue("landscape: confirm button must be fully visible: $button", button != null && button.height() >= minButton)
                device.pressBack()
            }
        } finally {
            device.unfreezeRotation()
            device.setOrientationNatural()
        }
    }

    @Test
    fun everyImportFieldIsReachableWithoutMovingTheActionBar() {
        val ids = listOf(
            "biaImportWeight", "biaImportBodyFat", "biaImportVisceralFat", "biaImportMuscleMass", "biaImportSkeletalMuscle",
            "biaImportBodyWater", "biaImportBmr", "biaImportFatMass", "biaImportLeanMass", "biaImportBodyWaterKg",
            "biaImportSubcutaneousFat", "biaImportBoneMass", "biaImportProteinPercent", "biaImportProteinKg", "biaImportBodyAge", "biaImportBmi",
        )
        ActivityScenario.launch<BiaActivity>(Intent(context, BiaActivity::class.java)).use { scenario ->
            scenario.onActivity { activity ->
                val method = BiaActivity::class.java.getDeclaredMethod(
                    "showImportPreview", BiaImportContract.Preview::class.java, String::class.java, String::class.java,
                ).apply { isAccessible = true }
                method.invoke(activity, preview(), "Gemini", "test-model")
            }
            assertTrue(device.wait(Until.hasObject(By.text("Controlla importazione BIA")), 8_000))
            Thread.sleep(500)
            val confirmBefore = rect("android:id/button1")
            val missing = ids.filter { id ->
                var found = device.hasObject(By.res("com.myfitai.app:id/$id"))
                var guard = 0
                while (!found && guard++ < 40) {
                    // Small increments avoid jumping past fields at large font scales.
                    device.findObject(By.res("com.myfitai.app:id/biaImportScroll"))?.scroll(androidx.test.uiautomator.Direction.DOWN, 0.1f)
                    Thread.sleep(100)
                    found = device.hasObject(By.res("com.myfitai.app:id/$id"))
                }
                !found
            }
            val confirmAfter = rect("android:id/button1")
            assertTrue("these BIA import fields cannot be reached: $missing", missing.isEmpty())
            assertTrue("action bar moved while reaching the fields: $confirmBefore -> $confirmAfter", confirmBefore == confirmAfter)
            device.pressBack()
        }
    }
}
