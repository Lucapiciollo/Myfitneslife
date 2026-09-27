package com.myfitai.app.data.profile

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AiAutomationPreferencesTest {
    @Test
    fun defaultsPersistFrequencyNotificationsAndNextDue() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val preferences = AiAutomationPreferences(context)
        val profileId = System.currentTimeMillis()
        val feature = AiAutomationPreferences.Feature.BIA_PROGRESS_COACH

        assertFalse(preferences.get(profileId, feature).enabled)
        assertTrue(preferences.get(profileId, feature).notificationsEnabled)

        preferences.setEnabled(profileId, feature, true)
        preferences.setFrequency(profileId, feature, AiAutomationPreferences.Frequency.MONTHLY)
        preferences.setNotificationsEnabled(profileId, feature, false)
        preferences.markRun(profileId, feature, 1_000L)

        assertTrue(preferences.get(profileId, feature).enabled)
        assertEquals(AiAutomationPreferences.Frequency.MONTHLY, preferences.get(profileId, feature).frequency)
        assertFalse(preferences.get(profileId, feature).notificationsEnabled)
        assertEquals(1_000L + 30L * 24L * 60L * 60L * 1_000L, preferences.nextDue(profileId, feature))
        preferences.clearProfile(profileId)
    }
}
