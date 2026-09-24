package com.myfitai.app.notifications

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NotificationPreferencesTest {
    private val preferences = NotificationPreferences(ApplicationProvider.getApplicationContext())

    @Test
    fun aiBackgroundUpdates_areEnabledByDefaultAndPersistChanges() {
        preferences.aiBackgroundUpdatesEnabled = true
        assertTrue(preferences.aiBackgroundUpdatesEnabled)

        preferences.aiBackgroundUpdatesEnabled = false
        assertFalse(NotificationPreferences(ApplicationProvider.getApplicationContext()).aiBackgroundUpdatesEnabled)

        preferences.aiBackgroundUpdatesEnabled = true
    }
}
