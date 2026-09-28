package com.myfitai.app.e2e

import android.content.Intent
import android.widget.AutoCompleteTextView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import com.myfitai.app.R
import com.myfitai.app.ui.ProfileEditActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Opens the existing profile editor read-only; no profile or demo data is created or changed. */
@RunWith(AndroidJUnit4::class)
class ProfileDropdownDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val device = UiDevice.getInstance(instrumentation)

    @Test
    fun profileCombos_useSharedPopupAndShowOptionsWithoutSaving() {
        ActivityScenario.launch<ProfileEditActivity>(Intent(context, ProfileEditActivity::class.java)).use { scenario ->
            val dropdowns = listOf(
                R.id.sexInput to listOf("Maschio", "Femmina"),
                R.id.goalInput to context.resources.getStringArray(R.array.profile_goals).toList(),
                R.id.activityInput to context.resources.getStringArray(R.array.profile_activity_levels).toList(),
            )

            dropdowns.forEach { (viewId, expectedOptions) ->
                var input: AutoCompleteTextView? = null
                scenario.onActivity { activity ->
                    input = activity.findViewById(viewId)
                    assertNotNull(input?.adapter)
                    assertEquals(expectedOptions.size, input?.adapter?.count)
                    assertNotNull(input?.dropDownBackground)
                    input?.performClick()
                }

                assertTrue(device.wait(Until.hasObject(By.text(expectedOptions.first())), 2_000))
                if (viewId == R.id.sexInput) {
                    val artifactDirectory = File(context.getExternalFilesDir(null), "qa-artifacts").apply { mkdirs() }
                    device.takeScreenshot(File(artifactDirectory, "profile_dropdown_popup.png"))
                }
                scenario.onActivity { input?.dismissDropDown() }
            }
        }
    }

}
