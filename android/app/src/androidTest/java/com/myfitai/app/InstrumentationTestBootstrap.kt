package com.myfitai.app

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.runner.AndroidJUnitRunner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Replaces the target Application only for instrumentation. Production and debug launches
 * continue to use MyFitAiApplication from the main manifest.
 */
class IsolatedAndroidJUnitRunner : AndroidJUnitRunner() {
    override fun newApplication(classLoader: ClassLoader, className: String, context: Context): Application =
        super.newApplication(classLoader, IsolatedAndroidTestApplication::class.java.name, context)
}

/**
 * Deliberately does not initialize AppDataContainer or production startup schedulers.
 * If an instrumented component later requests the production nutrition schedule namespace,
 * route it to a distinct test-only SharedPreferences file instead.
 */
class IsolatedAndroidTestApplication : Application() {
    override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
        super.getSharedPreferences(AndroidTestSharedPreferencesNames.isolatedName(name), mode)
}

internal object AndroidTestSharedPreferencesNames {
    const val PRODUCTION_NUTRITION_SCHEDULE = "nutrition_plan_schedule"
    const val TEST_NUTRITION_SCHEDULE = "android_test_nutrition_plan_schedule"

    fun isolatedName(requestedName: String): String =
        if (requestedName == PRODUCTION_NUTRITION_SCHEDULE) TEST_NUTRITION_SCHEDULE else requestedName
}

@RunWith(AndroidJUnit4::class)
class InstrumentationBootstrapIsolationTest {
    @Test
    fun targetUsesTestApplicationAndNutritionSchedulePreferencesAreNamespaced() {
        val application = ApplicationProvider.getApplicationContext<Application>()

        assertTrue("Instrumentation must replace the target Application", application is IsolatedAndroidTestApplication)
        assertFalse("Instrumentation must not start MyFitAiApplication", application is MyFitAiApplication)
        assertEquals(
            AndroidTestSharedPreferencesNames.TEST_NUTRITION_SCHEDULE,
            AndroidTestSharedPreferencesNames.isolatedName(AndroidTestSharedPreferencesNames.PRODUCTION_NUTRITION_SCHEDULE),
        )
        assertEquals(
            "unrelated_test_preferences",
            AndroidTestSharedPreferencesNames.isolatedName("unrelated_test_preferences"),
        )
    }
}
