package com.myfitai.app.e2e

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import com.myfitai.app.data.local.MyFitAiDatabase
import com.myfitai.app.data.profile.ActiveProfileStore
import com.myfitai.app.ui.FoodPlanActivity
import com.myfitai.app.ui.MealDetailActivity
import com.myfitai.app.ui.WeeklyReviewActivity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate

@RunWith(AndroidJUnit4::class)
class MealConsumptionE2ETest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private lateinit var device: UiDevice

    @Before
    fun seedQaPlan() {
        device = UiDevice.getInstance(instrumentation)
        context.startActivity(Intent().apply {
            component = ComponentName(context.packageName, "${context.packageName}.qa.QaSeederActivity")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        })
        assertTrue(device.wait(Until.hasObject(By.text("SEED_6_MONTHS")), 8_000))
        device.findObject(By.text("SEED_6_MONTHS")).click()
        assertTrue(waitForSeed())
    }

    @Test
    fun loadedMeal_canBeConsumed_andAppearsInPlanAndReview() {
        val weekStart = LocalDate.of(2026, 8, 31).toEpochDay()
        val mealId = runBlocking {
            val database = MyFitAiDatabase.getInstance(context)
            val profileId = ActiveProfileStore(context).currentIdOrNull()!!
            val plan = database.mealPlanDao().getPlanForWeek(profileId, weekStart)!!
            val version = database.mealPlanDao().getLatestVersion(profileId, plan.id)!!
            val day = database.mealPlanDao().getDays(profileId, version.id).first { it.dateEpochDay == weekStart }
            database.mealPlanDao().getMeals(profileId, day.id).first().id
        }

        context.startActivity(Intent(context, MealDetailActivity::class.java).apply {
            putExtra(MealDetailActivity.EXTRA_MEAL_ID, mealId)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        })

        // mealTitle exists during loading, so wait for the actual seeded meal text first.
        assertTrue(device.wait(Until.hasObject(By.text("QA Colazione")), 10_000))
        assertTrue(device.wait(Until.hasObject(By.res("com.myfitai.app:id/consumedButton")), 5_000))
        val consumedButton = device.findObject(By.res("com.myfitai.app:id/consumedButton"))
        assertTrue(consumedButton != null)
        consumedButton.click()

        assertTrue(device.wait(Until.hasObject(By.textContains("Annulla consumo")), 5_000))

        context.startActivity(Intent(context, FoodPlanActivity::class.java).apply {
            putExtra(FoodPlanActivity.EXTRA_WEEK_START_EPOCH_DAY, weekStart)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        })
        assertTrue(device.wait(Until.hasObject(By.res("com.myfitai.app:id/weekRangeLabel")), 10_000))
        // Daily totals sit below the fold: scroll until the coverage row enters the a11y tree.
        assertTrue(scrollUntilVisible(By.res("com.myfitai.app:id/consumptionCoverage")))
        assertTrue(device.hasObject(By.textContains("consumati 1")))

        context.startActivity(Intent(context, WeeklyReviewActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        })
        assertTrue(device.wait(Until.hasObject(By.res("com.myfitai.app:id/trackingCoverageText")), 10_000))
    }

    private fun scrollUntilVisible(selector: androidx.test.uiautomator.BySelector, attempts: Int = 6): Boolean {
        if (device.wait(Until.hasObject(selector), 2_000)) return true
        repeat(attempts) {
            device.swipe(device.displayWidth / 2, (device.displayHeight * 0.75).toInt(), device.displayWidth / 2, (device.displayHeight * 0.25).toInt(), 20)
            if (device.wait(Until.hasObject(selector), 1_000)) return true
        }
        return false
    }

    private fun waitForSeed(): Boolean {
        val deadline = System.currentTimeMillis() + 90_000L
        while (System.currentTimeMillis() < deadline) {
            val status = device.findObject(By.desc("qa_status"))
            if (status != null && status.text.contains("profile=")) return true
            Thread.sleep(250L)
        }
        return false
    }
}
