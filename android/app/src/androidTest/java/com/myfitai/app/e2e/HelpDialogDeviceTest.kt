package com.myfitai.app.e2e

import android.content.Intent
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import com.myfitai.app.ui.HomeActivity
import com.myfitai.app.ui.dialog.HelpDialog
import com.myfitai.app.ui.dialog.HelpDialogSpec
import com.myfitai.app.ui.dialog.HelpSection
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Opens the shared help modal with different parameters. Read-only: nothing is saved or deleted. */
@RunWith(AndroidJUnit4::class)
class HelpDialogDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val device = UiDevice.getInstance(instrumentation)
    private val dir = File(context.getExternalFilesDir(null), "qa-artifacts").apply { mkdirs() }

    private fun withDialog(spec: HelpDialogSpec, block: () -> Unit) {
        ActivityScenario.launch<HomeActivity>(Intent(context, HomeActivity::class.java)).use { scenario ->
            assertTrue(device.wait(Until.hasObject(By.res("com.myfitai.app:id/caloriesHelpButton")), 8_000))
            scenario.onActivity { HelpDialog.show(it, spec) }
            assertTrue("dialog title not shown", device.wait(Until.hasObject(By.text(spec.title)), 5_000))
            Thread.sleep(400)
            block()
        }
    }

    private fun assertActionBarIntact(label: String) {
        val minButton = (context.resources.getDimensionPixelSize(com.myfitai.app.R.dimen.control_min_height) * 0.9f).toInt()
        val button = device.findObject(By.res("android:id/button1"))?.visibleBounds
        assertTrue("confirm '$label' not found", device.hasObject(By.text(label)) && button != null)
        assertTrue("confirm button clipped: $button", button!!.height() >= minButton)
    }

    @Test
    fun messageOnly_usesSharedLayoutAndDefaultLabel() {
        val spec = HelpDialogSpec(
            title = "Aiuto di prova",
            message = "Titolo uno: testo del primo paragrafo.\n\nSecondo paragrafo senza titoletto.",
        )
        withDialog(spec) {
            assertTrue(device.hasObject(By.textContains("testo del primo paragrafo")))
            assertTrue(device.hasObject(By.textContains("Secondo paragrafo")))
            assertActionBarIntact("Ho capito")
            device.takeScreenshot(File(dir, "help-message-only.png"))
            device.findObject(By.text("Ho capito")).click()
            assertTrue(device.wait(Until.gone(By.text("Aiuto di prova")), 3_000))
        }
    }

    @Test
    fun metricHelpUsesTitleValueAndIndentedExplanationHierarchy() {
        val spec = HelpDialogSpec(
            title = "Come calcoliamo le calorie",
            message = "BMR locale: 1918 kcal\n  Stima usata dall'app per calcolare TDEE e target.\n\n" +
                "BMR riportato dalla BIA: 1918 kcal\n  Valore salvato dalla rilevazione, mostrato come riferimento.\n\n" +
                "Consumo giornaliero (TDEE): 2302 kcal\n  BMR locale moltiplicato per il livello di attività.\n\n" +
                "Target calorico: 1957 kcal\n  Deficit del -15% rispetto al consumo, per favorire la perdita di grasso.",
        )
        withDialog(spec) {
            listOf("BMR locale", "1918 kcal", "Stima usata dall'app", "Consumo giornaliero (TDEE)", "2302 kcal", "Target calorico", "1957 kcal")
                .forEach { assertTrue("missing help content: $it", device.hasObject(By.textContains(it))) }
            assertActionBarIntact("Ho capito")
            device.takeScreenshot(File(dir, "help-title-value-explanation.png"))
            device.findObject(By.text("Ho capito")).click()
            assertTrue(device.wait(Until.gone(By.text(spec.title)), 3_000))
        }
    }

    @Test
    fun sectionsScrollAndActionBarStaysFixed() {
        val sections = (1..10).map { HelpSection("Sezione $it", if (it % 2 == 0) null else "Sottotitolo $it", "Corpo della sezione $it. ".repeat(12)) }
        withDialog(HelpDialogSpec(title = "Guida di prova", sections = sections)) {
            assertTrue("first section should be initially visible", device.wait(Until.hasObject(By.text("Sezione 1")), 2_000))
            val before = device.findObject(By.res("android:id/button1")).visibleBounds
            val scroll = device.findObject(By.res("com.myfitai.app:id/helpScroll"))
            repeat(16) {
                if (device.hasObject(By.text("Sezione 10"))) return@repeat
                scroll?.scroll(Direction.DOWN, 0.8f)
                Thread.sleep(150)
            }
            assertTrue("last section must be reachable by scrolling", device.wait(Until.hasObject(By.text("Sezione 10")), 2_000))
            device.takeScreenshot(File(dir, "help-sections-scrolled.png"))
            val after = device.findObject(By.res("android:id/button1")).visibleBounds
            assertTrue("action bar moved while scrolling: $before -> $after", before == after)
            assertActionBarIntact("Ho capito")
            device.pressBack()
        }
    }
    @Test
    fun customLabelAndPlainParagraphs() {
        val prose = "Compare quando mancano i valori: una singola misura descrive solo lo stato attuale."
        withDialog(HelpDialogSpec(title = "Aiuto semplice", message = prose, confirmLabel = "Chiudi", highlightHeadings = false)) {
            assertTrue("full sentence must stay one piece of text", device.hasObject(By.textContains(prose)))
            assertActionBarIntact("Chiudi")
            device.findObject(By.text("Chiudi")).click()
            assertTrue(device.wait(Until.gone(By.text("Aiuto semplice")), 3_000))
        }
    }
}
