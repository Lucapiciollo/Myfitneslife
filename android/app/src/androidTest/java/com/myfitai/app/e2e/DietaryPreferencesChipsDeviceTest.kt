package com.myfitai.app.e2e

import android.content.Intent
import android.view.View
import android.view.ViewGroup
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.textfield.TextInputEditText
import com.myfitai.app.R
import com.myfitai.app.data.AppDataContainer
import com.myfitai.app.ui.DietaryPreferencesActivity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Chip rimovibili delle preferenze alimentari. NON premere mai "Salva": il test lavora solo sulla bozza in memoria
 * e verifica che il JSON del profilo attivo resti identico. Scritto ma non eseguito in questa tranche.
 */
@RunWith(AndroidJUnit4::class)
class DietaryPreferencesChipsDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val device = UiDevice.getInstance(instrumentation)

    @Test
    fun addAndRemoveChipsKeepsDraftOnlyAndExposesAccessibleLabels() {
        val data = AppDataContainer.get(context)
        val profileId = data.activeProfileStore.currentIdOrNull() ?: error("Seeded active profile is missing")
        val originalJson = runBlocking { data.userProfileRepository.get(profileId)?.dietaryPreferencesJson }
        ActivityScenario.launch<DietaryPreferencesActivity>(Intent(context, DietaryPreferencesActivity::class.java)).use { scenario ->
            instrumentation.waitForIdleSync()
            scenario.onActivity { activity ->
                val input = activity.findViewById<TextInputEditText>(R.id.allergiesInput)
                input.setText("Zzz test chip, Zzz TEST chip")
                val addButton = descendants(input.rootView).filterIsInstance<MaterialButton>()
                    .first { it.contentDescription?.toString() == "Aggiungi a Allergie" }
                assertTrue("add target must be at least 48dp", addButton.minHeight >= (48 * activity.resources.displayMetrics.density).toInt())
                addButton.performClick()
                val chip = descendants(input.rootView).filterIsInstance<Chip>().first { it.text == "Zzz test chip" }
                assertEquals("Rimuovi Zzz test chip da Allergie", chip.closeIconContentDescription.toString())
                val group = chip.parent as ChipGroup
                assertEquals("duplicates must be collapsed", 1, group.children().count { (it as Chip).text == "Zzz test chip" })
            }
            scenario.onActivity { activity ->
                val chip = descendants(activity.findViewById<View>(android.R.id.content)).filterIsInstance<Chip>()
                    .first { it.text == "Zzz test chip" }
                chip.performCloseIconClick()
                assertTrue(descendants(activity.findViewById<View>(android.R.id.content)).filterIsInstance<Chip>().none { it.text == "Zzz test chip" })
            }
            // Testo digitato e non aggiunto = modifica non salvata: Indietro deve chiedere conferma.
            scenario.onActivity { activity -> activity.findViewById<TextInputEditText>(R.id.preferredFoodsInput).setText("modifica non salvata") }
            device.pressBack()
            assertTrue(
                "unsaved dialog must offer to keep editing",
                device.wait(Until.hasObject(By.text("Continua a modificare")), 3_000),
            )
            assertTrue(device.hasObject(By.text("Esci senza salvare")))
            device.findObject(By.text("Continua a modificare")).click()
        }
        val persisted = runBlocking { data.userProfileRepository.get(profileId)?.dietaryPreferencesJson }
        assertEquals("preferences must not be saved by this test", originalJson, persisted)
    }

    private fun ChipGroup.children(): List<View> = (0 until childCount).map(::getChildAt)

    private fun descendants(view: View): List<View> = when (view) {
        is ViewGroup -> listOf(view) + (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) }
        else -> listOf(view)
    }
}
