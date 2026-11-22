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
import com.myfitai.app.data.AppDataContainer
import com.myfitai.app.data.profile.ActivityLevelFactors
import com.myfitai.app.domain.profile.ActivityLevelChoices
import com.myfitai.app.ui.ProfileEditActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Exercises the profile-create dropdown in the isolated .qa package without creating or changing profiles. */
@RunWith(AndroidJUnit4::class)
class ProfileDropdownDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val device = UiDevice.getInstance(instrumentation)

    @Test
    fun profileCombos_useSharedPopupAndShowOptionsWithoutSaving() {
        assertTrue("Run this device test only against the isolated .qa application", context.packageName.endsWith(".qa"))
        val factors = requireNotNull(AppDataContainer.get(context).activityLevelFactors.read())
        val activityChoices = ActivityLevelChoices.catalogOptions(
            context.resources.getStringArray(R.array.profile_activity_levels).toList(),
            factors,
        )
        assertEquals(5, activityChoices.size)
        assertEquals(ActivityLevelFactors.options.map { it.name }, activityChoices.map { it.value })
        activityChoices.forEach { choice ->
            assertTrue(choice.label.contains(" · "))
            assertEquals(choice.value, ActivityLevelChoices.storedValue(choice.label, null, factors, activityChoices))
        }

        val intent = Intent(context, ProfileEditActivity::class.java)
            .putExtra(ProfileEditActivity.EXTRA_CREATE, true)
        ActivityScenario.launch<ProfileEditActivity>(intent).use { scenario ->
            val dropdowns = listOf(
                R.id.sexInput to listOf("Maschio", "Femmina"),
                R.id.goalInput to context.resources.getStringArray(R.array.profile_goals).toList(),
                R.id.activityInput to activityChoices.map { it.label },
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
                scenario.onActivity { input?.dismissDropDown() }
            }
        }
    }

}
