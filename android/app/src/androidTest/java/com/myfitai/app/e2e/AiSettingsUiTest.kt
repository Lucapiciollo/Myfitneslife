package com.myfitai.app.e2e

import android.content.Intent
import android.text.InputType
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.myfitai.app.R
import com.myfitai.app.ai.AiProviderAccess
import com.myfitai.app.ai.AiProviderStatus
import com.myfitai.app.data.local.MyFitAiDatabase
import com.myfitai.app.data.local.entity.UserProfileEntity
import com.myfitai.app.data.profile.ActiveProfileStore
import com.myfitai.app.ui.AiSettingsActivity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Impostazioni IA come finestra autonoma: FLAG_SECURE, campi chiave protetti e accessibili, gruppi e ambito globale.
 * Non salva né legge mai chiavi reali: verifica solo struttura, flag e stato derivato dalla presenza delle credenziali.
 */
@RunWith(AndroidJUnit4::class)
class AiSettingsUiTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val device = UiDevice.getInstance(instrumentation)
    private lateinit var scenario: ActivityScenario<AiSettingsActivity>

    @Before
    fun setUp() {
        val database = MyFitAiDatabase.getInstance(context)
        val profile = runBlocking {
            database.userProfileDao().getFirst() ?: database.userProfileDao().insert(
                UserProfileEntity(
                    name = "UI AI Settings Test",
                    birthDateEpochDay = null,
                    heightCm = 186f,
                    currentWeightKg = 89f,
                    goal = "Ricomposizione",
                    activityLevel = "Moderatamente attivo",
                    wakeTimeMinutes = 420,
                    sleepTimeMinutes = 1410,
                    dietaryPreferencesJson = null,
                    createdAtEpochMillis = 1L,
                    updatedAtEpochMillis = 1L,
                    biologicalSex = "Maschio",
                    initialWeightKg = 89f,
                ),
            ).let { database.userProfileDao().get(it)!! }
        }
        ActiveProfileStore(context).selectProfile(profile.id)
        scenario = ActivityScenario.launch(Intent(context, AiSettingsActivity::class.java))
    }

    @After
    fun tearDown() {
        if (::scenario.isInitialized) scenario.close()
    }

    @Test
    fun windowIsSecure() {
        scenario.onActivity { activity ->
            assertTrue(
                "FLAG_SECURE must be set on the window of the credentials screen",
                activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0,
            )
        }
    }

    @Test
    fun keyFieldsAreAccessibleProtectedAndNeverPrefilled() {
        scenario.onActivity { activity ->
            listOf(
                Triple(R.id.geminiApiKeyInput, R.id.geminiInputLayout, "Chiave API Gemini"),
                Triple(R.id.openAiApiKeyInput, R.id.openAiInputLayout, "Chiave API OpenAI"),
            ).forEach { (inputId, layoutId, label) ->
                val input = activity.findViewById<TextInputEditText>(inputId)
                val layout = activity.findViewById<TextInputLayout>(layoutId)
                assertEquals("Visible hint must match the accessible name", label, layout.hint?.toString())
                assertTrue("Accessible name must start with the visible label", input.contentDescription?.toString().orEmpty().startsWith(label))
                assertTrue(
                    "API key field must be exposed to accessibility services",
                    input.importantForAccessibility != View.IMPORTANT_FOR_ACCESSIBILITY_NO &&
                        input.importantForAccessibility != View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS,
                )
                assertTrue("API key field must keep the protected password input", input.inputType and InputType.TYPE_TEXT_VARIATION_PASSWORD != 0)
                assertEquals("API key field must opt out of autofill", View.IMPORTANT_FOR_AUTOFILL_NO, input.importantForAutofill)
                assertTrue("API key field must not offer the long-press menu", !input.isLongClickable)
                assertTrue("No stored value may ever be loaded into the field", input.text.isNullOrEmpty())
            }
            listOf(
                R.id.pasteGeminiKeyButton, R.id.saveGeminiKeyButton, R.id.replaceGeminiKeyButton, R.id.deleteGeminiKeyButton,
                R.id.pasteOpenAiKeyButton, R.id.saveOpenAiKeyButton, R.id.replaceOpenAiKeyButton, R.id.deleteOpenAiKeyButton,
            ).forEach { id ->
                val button = activity.findViewById<View>(id)
                assertTrue(
                    "Key action must be at least 48dp tall",
                    button.layoutParams.height >= activity.resources.getDimensionPixelSize(R.dimen.control_min_height),
                )
            }
        }
    }

    @Test
    fun groupsAndGlobalScopeBannerAreShownWithoutAutomations() {
        scenario.onActivity { activity ->
            assertEquals("Impostazioni IA", activity.findViewById<TextView>(R.id.title).text.toString())
            assertEquals(
                "Valide per tutti i profili. Cambiare profilo non le modifica.",
                activity.findViewById<TextView>(R.id.aiScopeBanner).text.toString(),
            )
            listOf(
                R.id.aiProviderHeader to "Provider attivo",
                R.id.aiKeysHeader to "Chiavi API",
                R.id.aiModelsHeader to "Modelli",
                R.id.aiCostsHeader to "Costi e consumi",
            ).forEach { (id, text) -> assertEquals(text, activity.findViewById<TextView>(id).text.toString()) }
            assertNull("Automations are per-profile and live in their own screen", activity.findViewById<View>(R.id.aiAutomationsSection))
            assertTrue(activity.findViewById<android.view.ViewGroup>(R.id.aiCostGroup).childCount >= 2)
        }
    }

    @Test
    fun providerStatusTextReflectsCredentialPresence() {
        scenario.onActivity { activity ->
            val status = AiProviderAccess.status(activity)
            val text = activity.findViewById<TextView>(R.id.activeProviderText).text.toString()
            val useOther = activity.findViewById<View>(R.id.useOtherProviderButton)
            when (status.state) {
                AiProviderStatus.State.ACTIVE -> assertTrue(text.startsWith("Attivo: "))
                AiProviderStatus.State.NONE -> assertTrue(text.startsWith("Nessun provider IA configurato"))
                AiProviderStatus.State.SELECTED_WITHOUT_KEY -> assertTrue(text.contains("senza chiave"))
            }
            assertEquals(
                "The explicit switch is offered only when the selected provider has no key but the other does",
                status.state == AiProviderStatus.State.SELECTED_WITHOUT_KEY,
                useOther.visibility == View.VISIBLE,
            )
        }
    }

    @Test
    fun automationsRowOpensTheProfileScopedScreen() {
        scenario.onActivity { activity -> activity.findViewById<View>(R.id.rowAiAutomations).performClick() }
        assertTrue(
            "Automazioni IA must open as a separate screen",
            device.wait(Until.hasObject(By.res("com.myfitai.app:id/aiAutomationsScope")), 5_000),
        )
        device.pressBack()
        assertTrue(device.wait(Until.hasObject(By.res("com.myfitai.app:id/aiScopeBanner")), 5_000))
    }
}
