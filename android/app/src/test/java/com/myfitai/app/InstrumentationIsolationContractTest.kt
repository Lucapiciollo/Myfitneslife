package com.myfitai.app

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InstrumentationIsolationContractTest {
    @Test
    fun instrumentationUsesAnIsolatedQATargetAndGuardsScheduleNamespace() {
        val buildFile = source("build.gradle.kts").readText()
        val manifest = source("src/main/AndroidManifest.xml").readText()
        val productionApplication = source("src/main/java/com/myfitai/app/MyFitAiApplication.kt").readText()
        val bootstrapFile = source("src/androidTest/java/com/myfitai/app/InstrumentationTestBootstrap.kt")
        val bootstrap = bootstrapFile.readText()
        val qaBuildType = buildFile.substringAfter("create(\"qa\")").substringBefore("\n    }")

        assertTrue(buildFile.contains("testInstrumentationRunner = \"com.myfitai.app.IsolatedAndroidJUnitRunner\""))
        assertTrue(buildFile.contains("testBuildType = \"qa\""))
        assertTrue(buildFile.contains("applicationId = \"com.myfitai.app\""))
        assertEquals("Only the QA build type may add an application ID suffix", 1, Regex("\\bapplicationId\\s*=").findAll(buildFile).count())
        assertTrue(qaBuildType.contains("initWith(getByName(\"debug\"))"))
        assertTrue(qaBuildType.contains("applicationIdSuffix = \".qa\""))
        assertFalse("QA must keep the default application ID and add only its suffix", qaBuildType.contains("applicationId ="))
        assertTrue(buildFile.contains("sourceSets.getByName(\"qa\").apply"))
        assertTrue(buildFile.contains("src/debug/java"))
        assertTrue(buildFile.contains("src/debug/AndroidManifest.xml"))
        assertTrue(manifest.contains("android:name=\".MyFitAiApplication\""))
        assertTrue(productionApplication.contains("data.nutritionPlanScheduler.reschedule(profileId)"))
        assertTrue(bootstrap.contains("override fun newApplication("))
        assertTrue(bootstrap.contains("IsolatedAndroidTestApplication::class.java.name"))
        assertTrue(bootstrap.contains("super.getSharedPreferences(AndroidTestSharedPreferencesNames.isolatedName(name), mode)"))
        assertTrue(bootstrap.contains("PRODUCTION_NUTRITION_SCHEDULE = \"nutrition_plan_schedule\""))
        assertTrue(bootstrap.contains("TEST_NUTRITION_SCHEDULE = \"android_test_nutrition_plan_schedule\""))
        assertFalse("Test Application must not initialize the production composition root", bootstrap.contains("AppDataContainer.get"))

        val unsafeTestSources = source("src/androidTest").walkTopDown()
            .filter { it.isFile && it.extension == "kt" && it.canonicalFile != bootstrapFile.canonicalFile }
            .filter { file ->
                val text = file.readText()
                text.contains("NutritionPlanScheduler") ||
                    text.contains("nutritionPlanScheduler") ||
                    text.contains("\"nutrition_plan_schedule\"")
            }
            .map { it.relativeTo(source("src/androidTest")).invariantSeparatorsPath }
            .toList()

        assertTrue(
            "Instrumented tests must not instantiate the production scheduler or name its prefs directly: $unsafeTestSources",
            unsafeTestSources.isEmpty(),
        )
    }

    private fun source(path: String): File = File(path).also {
        check(it.exists()) { "Expected app-module source file at ${it.absolutePath}" }
    }
}
