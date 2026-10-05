package com.myfitai.app.e2e

import android.content.Intent
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.core.widget.NestedScrollView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import com.google.android.material.textfield.MaterialAutoCompleteTextView
import com.google.android.material.textfield.TextInputEditText
import com.myfitai.app.R
import com.myfitai.app.data.AppDataContainer
import com.myfitai.app.ui.DietaryPreferencesActivity
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Non-destructive presentation and control checks; does not press Save. */
@RunWith(AndroidJUnit4::class)
class DietaryPreferencesVisualDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val device = UiDevice.getInstance(instrumentation)

    @Test
    fun existingPreferencesFormKeepsFieldsDropdownKeyboardAndScroll() {
        var originalPreferred = ""
        val data = AppDataContainer.get(context)
        val profileId = data.activeProfileStore.currentIdOrNull() ?: error("Seeded active profile is missing")
        val originalJson = runBlocking { data.userProfileRepository.get(profileId)?.dietaryPreferencesJson }
        ActivityScenario.launch<DietaryPreferencesActivity>(Intent(context, DietaryPreferencesActivity::class.java)).use { scenario ->
            scenario.onActivity { activity ->
                originalPreferred = activity.findViewById<TextInputEditText>(R.id.preferredFoodsInput).text?.toString().orEmpty()
            }

            scenario.onActivity { activity ->
                val fieldIds = listOf(
                    R.id.preferredFoodsInput,
                    R.id.dislikedFoodsInput,
                    R.id.excludedFoodsInput,
                    R.id.intolerancesInput,
                    R.id.allergiesInput,
                    R.id.dietStyleInput,
                    R.id.preferencesInput,
                )
                fieldIds.forEach { id -> assertNotNull("missing field $id", activity.findViewById<View>(id)) }
                assertTrue(activity.findViewById<View>(R.id.saveDietaryPreferencesButton).hasOnClickListeners())
            }
            instrumentation.waitForIdleSync()
            val dir = File(context.getExternalFilesDir(null), "qa-artifacts").apply { mkdirs() }
            device.takeScreenshot(File(dir, "dietary-preferences.png"))

            scenario.onActivity { activity ->
                val preferred = activity.findViewById<TextInputEditText>(R.id.preferredFoodsInput)
                preferred.requestFocus()
                assertTrue("preferred field should receive focus", preferred.hasFocus())
                preferred.setText("alimento stress, testo lungo di verifica visuale senza salvare")
                assertTrue(preferred.text?.contains("testo lungo di verifica") == true)
            }
            scenario.onActivity { activity ->
                activity.findViewById<TextInputEditText>(R.id.preferredFoodsInput).setText(originalPreferred)
                val scroll = descendants(activity.findViewById<ViewGroup>(android.R.id.content))
                    .filterIsInstance<NestedScrollView>().single()
                scroll.fullScroll(View.FOCUS_DOWN)
                assertTrue(activity.findViewById<View>(R.id.saveDietaryPreferencesButton).isShown)
            }

            scenario.onActivity { activity ->
                val style = activity.findViewById<MaterialAutoCompleteTextView>(R.id.dietStyleInput)
                val current = style.text.toString()
                assertTrue(current.isNotBlank())
                assertTrue("diet style options missing", style.adapter.count >= 5)
                style.performClick()
            }
            scenario.onActivity { activity ->
                assertEquals(originalPreferred, activity.findViewById<TextInputEditText>(R.id.preferredFoodsInput).text.toString())
                val scroll = descendants(activity.findViewById<ViewGroup>(android.R.id.content))
                    .filterIsInstance<NestedScrollView>().single()
                scroll.fullScroll(View.FOCUS_UP)
            }
        }
        val persistedJson = runBlocking { data.userProfileRepository.get(profileId)?.dietaryPreferencesJson }
        assertEquals("preferences were not saved during visual checks", originalJson, persistedJson)
    }

    private fun descendants(view: View): List<View> = when (view) {
        is ViewGroup -> listOf(view) + (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) }
        else -> listOf(view)
    }
}
