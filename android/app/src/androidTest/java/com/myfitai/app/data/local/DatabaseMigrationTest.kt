package com.myfitai.app.data.local

import android.content.Context
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DatabaseMigrationTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        MyFitAiDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    @Test
    fun migration3To4_preservesLegacyDay_andAddsEmptyNutritionFields() {
        helper.createDatabase(MyFitAiDatabase.DATABASE_NAME, 3).apply {
            execSQL("INSERT INTO meal_plans (id, profileId, createdAtEpochMillis, weekStartEpochDay, status) VALUES (1, 1, 1000, 21000, 'ACTIVE')")
            execSQL("INSERT INTO meal_plan_versions (id, planId, versionNumber, createdAtEpochMillis, source, reason, targetKcal, targetProteinG, targetCarbsG, targetFatG) VALUES (1, 1, 1, 2000, 'LEGACY', NULL, 2200, 160.0, 230.0, 70.0)")
            execSQL("INSERT INTO meal_plan_days (id, versionId, dateEpochDay, totalKcal, proteinG, carbsG, fatG) VALUES (1, 1, 21000, 2200, 160.0, 230.0, 70.0)")
            close()
        }

        helper.runMigrationsAndValidate(
            MyFitAiDatabase.DATABASE_NAME,
            4,
            true,
            DatabaseMigrations.MIGRATION_3_4,
        ).use { db ->
            db.query("SELECT totalKcal, proteinG, carbsG, fatG, supplementsJson, hydrationNote FROM meal_plan_days WHERE id = 1").use { cursor ->
                check(cursor.moveToFirst())
                assertEquals(2200, cursor.getInt(0))
                assertEquals(160f, cursor.getFloat(1))
                assertEquals(230f, cursor.getFloat(2))
                assertEquals(70f, cursor.getFloat(3))
                assertNull(cursor.getString(4))
                assertNull(cursor.getString(5))
            }
        }
    }

    @Test
    fun migration4To5_createsEmptyFoodConsumptionHistory() {
        helper.createDatabase(MyFitAiDatabase.DATABASE_NAME, 4).close()

        helper.runMigrationsAndValidate(
            MyFitAiDatabase.DATABASE_NAME,
            5,
            true,
            DatabaseMigrations.MIGRATION_4_5,
        ).use { db ->
            db.query("SELECT COUNT(*) FROM food_consumptions").use { cursor ->
                check(cursor.moveToFirst())
                assertEquals(0, cursor.getInt(0))
            }
        }
    }
}
