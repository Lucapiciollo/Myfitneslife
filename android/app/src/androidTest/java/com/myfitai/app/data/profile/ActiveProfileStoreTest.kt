package com.myfitai.app.data.profile

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ActiveProfileStoreTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Before
    fun clearSession() {
        ActiveProfileStore(context).clear()
    }

    @After
    fun tearDown() {
        ActiveProfileStore(context).clear()
    }

    /**
     * Regression: a component selecting a profile through its own instance must not leave
     * other instances (for example the one held by AppDataContainer) on a stale profileId.
     */
    @Test
    fun selectionFromOneInstance_isVisibleToAlreadyCreatedInstances() {
        val existing = ActiveProfileStore(context)
        assertNull(existing.currentIdOrNull())

        ActiveProfileStore(context).selectProfile(42L)

        assertEquals(42L, existing.currentIdOrNull())
    }

    @Test
    fun selectionFromOneInstance_updatesObservableStateOfOtherInstances() = runBlocking {
        val existing = ActiveProfileStore(context)

        ActiveProfileStore(context).selectProfile(7L)

        assertEquals(7L, existing.activeProfileId.first())
    }

    @Test
    fun clearFromOneInstance_isVisibleToOtherInstances() {
        val existing = ActiveProfileStore(context)
        ActiveProfileStore(context).selectProfile(11L)
        assertEquals(11L, existing.currentIdOrNull())

        ActiveProfileStore(context).clear()

        assertNull(existing.currentIdOrNull())
    }
}
