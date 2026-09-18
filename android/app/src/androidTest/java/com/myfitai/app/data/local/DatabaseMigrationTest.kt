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
        val databaseName = "migration_3_4.db"
        context.deleteDatabase(databaseName)
        helper.createDatabase(databaseName, 3).apply {
            execSQL("INSERT INTO meal_plans (id, profileId, createdAtEpochMillis, weekStartEpochDay, status) VALUES (1, 1, 1000, 21000, 'ACTIVE')")
            execSQL("INSERT INTO meal_plan_versions (id, planId, versionNumber, createdAtEpochMillis, source, reason, targetKcal, targetProteinG, targetCarbsG, targetFatG) VALUES (1, 1, 1, 2000, 'LEGACY', NULL, 2200, 160.0, 230.0, 70.0)")
            execSQL("INSERT INTO meal_plan_days (id, versionId, dateEpochDay, totalKcal, proteinG, carbsG, fatG) VALUES (1, 1, 21000, 2200, 160.0, 230.0, 70.0)")
            close()
        }

        helper.runMigrationsAndValidate(
            databaseName,
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
        val databaseName = "migration_4_5.db"
        context.deleteDatabase(databaseName)
        helper.createDatabase(databaseName, 4).close()

        helper.runMigrationsAndValidate(
            databaseName,
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

    @Test
    fun migration5To6_createsEmptyAiUsageHistory() {
        val databaseName = "migration_5_6.db"
        context.deleteDatabase(databaseName)
        helper.createDatabase(databaseName, 5).close()

        helper.runMigrationsAndValidate(
            databaseName,
            6,
            true,
            DatabaseMigrations.MIGRATION_5_6,
        ).use { db ->
            db.query("SELECT COUNT(*) FROM ai_usage_records").use { cursor ->
                check(cursor.moveToFirst())
                assertEquals(0, cursor.getInt(0))
            }
        }
    }

    @Test
    fun migration7To8_backfillsDefaultEnergyOnlyForNonRestWorkouts() {
        val databaseName = "migration_7_8.db"
        context.deleteDatabase(databaseName)
        helper.createDatabase(databaseName, 7).apply {
            execSQL("INSERT INTO workouts (id, profileId, startedAtEpochMillis, type, title, durationMinutes, isRestDay, notes) VALUES (1, 1, 86400000, 'PESI', 'Pesi', 45, 0, NULL)")
            execSQL("INSERT INTO workouts (id, profileId, startedAtEpochMillis, type, title, durationMinutes, isRestDay, notes) VALUES (2, 1, 86400000, 'REST', 'Riposo', NULL, 1, NULL)")
            close()
        }

        helper.runMigrationsAndValidate(
            databaseName,
            8,
            true,
            DatabaseMigrations.MIGRATION_7_8,
        ).use { db ->
            db.query("SELECT workoutId, caloriesKcal, source FROM workout_energy_expenditures ORDER BY workoutId").use { cursor ->
                check(cursor.moveToFirst())
                assertEquals(1, cursor.getInt(0))
                assertEquals(500, cursor.getInt(1))
                assertEquals("DEFAULT", cursor.getString(2))
                check(!cursor.moveToNext())
            }
        }
    }

    @Test
    fun migration8To9_addsNullablePerceivedIntensityToWorkouts() {
        val databaseName = "migration_8_9.db"
        context.deleteDatabase(databaseName)
        helper.createDatabase(databaseName, 8).apply {
            execSQL("INSERT INTO workouts (id, profileId, startedAtEpochMillis, type, title, durationMinutes, isRestDay, notes) VALUES (1, 1, 86400000, 'PESI', 'Pesi', 45, 0, NULL)")
            close()
        }

        helper.runMigrationsAndValidate(
            databaseName,
            9,
            true,
            DatabaseMigrations.MIGRATION_8_9,
        ).use { db ->
            db.query("SELECT perceivedIntensity FROM workouts WHERE id = 1").use { cursor ->
                check(cursor.moveToFirst())
                assertEquals(null, if (cursor.isNull(0)) null else cursor.getInt(0))
            }
        }
    }

    @Test
    fun migration9To10_createsEmptyAiJobResultsAndKeepsExistingData() {
        val databaseName = "migration_9_10.db"
        context.deleteDatabase(databaseName)
        helper.createDatabase(databaseName, 9).apply {
            execSQL("INSERT INTO workouts (id, profileId, startedAtEpochMillis, type, title, durationMinutes, isRestDay, notes, perceivedIntensity) VALUES (1, 1, 86400000, 'PESI', 'Pesi', 45, 0, NULL, 7)")
            close()
        }

        helper.runMigrationsAndValidate(
            databaseName,
            10,
            true,
            DatabaseMigrations.MIGRATION_9_10,
        ).use { db ->
            db.query("SELECT COUNT(*) FROM ai_job_results").use { cursor ->
                check(cursor.moveToFirst())
                assertEquals(0, cursor.getInt(0))
            }
            db.query("SELECT perceivedIntensity FROM workouts WHERE id = 1").use { cursor ->
                check(cursor.moveToFirst())
                assertEquals(7, cursor.getInt(0))
            }
        }
    }
}
