package com.myfitai.app.data.local

import android.content.Context
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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

    @Test
    fun migration5To6_createsEmptyAiUsageHistory() {
        helper.createDatabase(MyFitAiDatabase.DATABASE_NAME, 5).close()

        helper.runMigrationsAndValidate(
            MyFitAiDatabase.DATABASE_NAME,
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
    fun migration8To9_preservesBodyHistoryAndAddsNullableHipsAndWeight() {
        helper.createDatabase(MyFitAiDatabase.DATABASE_NAME, 8).apply {
            execSQL(
                "INSERT INTO body_measurements (id, profileId, measuredAtEpochMillis, waistCm, glutesCm) " +
                    "VALUES (1, 1, 1234567, 81.5, 97.0)"
            )
            close()
        }

        helper.runMigrationsAndValidate(
            MyFitAiDatabase.DATABASE_NAME,
            9,
            true,
            DatabaseMigrations.MIGRATION_8_9,
        ).use { db ->
            db.query("SELECT waistCm, glutesCm, hipsCm, weightKg FROM body_measurements WHERE id = 1").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(81.5f, cursor.getFloat(0))
                assertEquals(97f, cursor.getFloat(1))
                assertNull(cursor.getString(2))
                assertNull(cursor.getString(3))
            }
            db.execSQL("UPDATE body_measurements SET hipsCm = 99.0, weightKg = 88.5 WHERE id = 1")
            db.query("SELECT hipsCm, weightKg FROM body_measurements WHERE id = 1").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(99f, cursor.getFloat(0))
                assertEquals(88.5f, cursor.getFloat(1))
            }
        }
    }

    @Test
    fun migration7To8_addsNullableAppValidationJson() {
        helper.createDatabase(MyFitAiDatabase.DATABASE_NAME, 7).close()
        helper.runMigrationsAndValidate(
            MyFitAiDatabase.DATABASE_NAME,
            8,
            true,
            DatabaseMigrations.MIGRATION_7_8,
        ).use { db ->
            db.query("PRAGMA table_info(meal_plan_versions)").use { cursor ->
                var found = false
                while (cursor.moveToNext()) if (cursor.getString(1) == "appValidationJson") found = true
                assertTrue(found)
            }
        }
    }

    @Test
    fun migration9To10_isIdempotentForPartiallyAddedOptionalBiaColumns() {
        helper.createDatabase(MyFitAiDatabase.DATABASE_NAME, 9).apply {
            execSQL("ALTER TABLE bia_measurements ADD COLUMN fatMassKg REAL")
            execSQL("ALTER TABLE bia_measurements ADD COLUMN leanMassKg REAL")
            close()
        }

        helper.runMigrationsAndValidate(
            MyFitAiDatabase.DATABASE_NAME,
            10,
            true,
            DatabaseMigrations.MIGRATION_9_10,
        ).use { db ->
            db.query("PRAGMA table_info(bia_measurements)").use { cursor ->
                val columns = buildSet {
                    while (cursor.moveToNext()) add(cursor.getString(cursor.getColumnIndexOrThrow("name")))
                }
                assertTrue(columns.containsAll(setOf(
                    "fatMassKg",
                    "leanMassKg",
                    "bodyWaterKg",
                    "subcutaneousFatPercent",
                    "boneMassKg",
                    "proteinPercent",
                    "proteinKg",
                    "bodyAgeYears",
                    "bmi",
                )))
            }
        }
    }

    @Test
    fun migration10To11_isIdempotentForOptionalBiaColumns() {
        helper.createDatabase(MyFitAiDatabase.DATABASE_NAME, 10).apply {
            listOf(
                "fatMassKg",
                "leanMassKg",
                "bodyWaterKg",
                "subcutaneousFatPercent",
                "boneMassKg",
                "proteinPercent",
                "proteinKg",
                "bodyAgeYears",
                "bmi",
            ).forEach { column ->
                execSQL("ALTER TABLE bia_measurements DROP COLUMN $column")
            }
            close()
        }

        helper.runMigrationsAndValidate(
            MyFitAiDatabase.DATABASE_NAME,
            11,
            true,
            DatabaseMigrations.MIGRATION_10_11,
        ).use { db ->
            db.query("PRAGMA table_info(bia_measurements)").use { cursor ->
                val columns = buildSet {
                    while (cursor.moveToNext()) add(cursor.getString(cursor.getColumnIndexOrThrow("name")))
                }
                assertTrue(columns.containsAll(setOf(
                    "fatMassKg",
                    "leanMassKg",
                    "bodyWaterKg",
                    "subcutaneousFatPercent",
                    "boneMassKg",
                    "proteinPercent",
                    "proteinKg",
                    "bodyAgeYears",
                    "bmi",
                )))
            }
        }
    }

    @Test
    fun migration11To12_isIdempotentForOptionalBodyColumns() {
        helper.createDatabase(MyFitAiDatabase.DATABASE_NAME, 11).apply {
            execSQL("ALTER TABLE body_measurements DROP COLUMN hipsCm")
            execSQL("ALTER TABLE body_measurements DROP COLUMN weightKg")
            close()
        }

        helper.runMigrationsAndValidate(
            MyFitAiDatabase.DATABASE_NAME,
            12,
            true,
            DatabaseMigrations.MIGRATION_11_12,
        ).use { db ->
            db.query("PRAGMA table_info(body_measurements)").use { cursor ->
                val columns = buildSet {
                    while (cursor.moveToNext()) add(cursor.getString(cursor.getColumnIndexOrThrow("name")))
                }
                assertTrue(columns.containsAll(setOf("hipsCm", "weightKg")))
            }
        }
    }

    @Test
    fun migration12To13_repairsIncompleteOptionalBiaAndBodyColumns() {
        helper.createDatabase(MyFitAiDatabase.DATABASE_NAME, 12).apply {
            execSQL("ALTER TABLE bia_measurements DROP COLUMN fatMassKg")
            execSQL("ALTER TABLE bia_measurements DROP COLUMN bmi")
            execSQL("ALTER TABLE body_measurements DROP COLUMN hipsCm")
            execSQL("ALTER TABLE body_measurements DROP COLUMN weightKg")
            close()
        }

        helper.runMigrationsAndValidate(
            MyFitAiDatabase.DATABASE_NAME,
            13,
            true,
            DatabaseMigrations.MIGRATION_12_13,
        ).use { db ->
            db.query("PRAGMA table_info(bia_measurements)").use { cursor ->
                val columns = buildSet {
                    while (cursor.moveToNext()) add(cursor.getString(cursor.getColumnIndexOrThrow("name")))
                }
                assertTrue(columns.containsAll(setOf("fatMassKg", "bmi")))
            }
            db.query("PRAGMA table_info(body_measurements)").use { cursor ->
                val columns = buildSet {
                    while (cursor.moveToNext()) add(cursor.getString(cursor.getColumnIndexOrThrow("name")))
                }
                assertTrue(columns.containsAll(setOf("hipsCm", "weightKg")))
            }
        }
    }

    @Test
    fun migration13To14_repairsCurrentDatabaseWithMissingOptionalColumns() {
        helper.createDatabase(MyFitAiDatabase.DATABASE_NAME, 13).apply {
            execSQL("ALTER TABLE body_measurements DROP COLUMN hipsCm")
            execSQL("ALTER TABLE body_measurements DROP COLUMN weightKg")
            execSQL("ALTER TABLE bia_measurements DROP COLUMN fatMassKg")
            execSQL("ALTER TABLE bia_measurements DROP COLUMN bmi")
            close()
        }

        helper.runMigrationsAndValidate(
            MyFitAiDatabase.DATABASE_NAME,
            14,
            true,
            DatabaseMigrations.MIGRATION_13_14,
        ).use { db ->
            db.query("PRAGMA table_info(bia_measurements)").use { cursor ->
                val columns = buildSet {
                    while (cursor.moveToNext()) add(cursor.getString(cursor.getColumnIndexOrThrow("name")))
                }
                assertTrue(columns.containsAll(setOf("fatMassKg", "bmi")))
            }
            db.query("PRAGMA table_info(body_measurements)").use { cursor ->
                val columns = buildSet {
                    while (cursor.moveToNext()) add(cursor.getString(cursor.getColumnIndexOrThrow("name")))
                }
                assertTrue(columns.containsAll(setOf("hipsCm", "weightKg")))
            }
        }
    }

    @Test
    fun migration14To15_repairsCurrentDatabaseWithMissingOptionalColumns() {
        helper.createDatabase(MyFitAiDatabase.DATABASE_NAME, 14).apply {
            execSQL("ALTER TABLE body_measurements DROP COLUMN hipsCm")
            execSQL("ALTER TABLE body_measurements DROP COLUMN weightKg")
            close()
        }

        helper.runMigrationsAndValidate(
            MyFitAiDatabase.DATABASE_NAME,
            15,
            true,
            DatabaseMigrations.MIGRATION_14_15,
        ).use { db ->
            db.query("PRAGMA table_info(body_measurements)").use { cursor ->
                val columns = buildSet {
                    while (cursor.moveToNext()) add(cursor.getString(cursor.getColumnIndexOrThrow("name")))
                }
                assertTrue(columns.containsAll(setOf("hipsCm", "weightKg")))
            }
        }
    }

    @Test
    fun migration19To20_dropsWorkoutsAndCheckinsAndKeepsEveryOtherTable() {
        helper.createDatabase(MyFitAiDatabase.DATABASE_NAME, 19).apply {
            execSQL("INSERT INTO user_profile (id, name, createdAtEpochMillis, updatedAtEpochMillis) VALUES (1, 'Luca', 1000, 2000)")
            execSQL("INSERT INTO bia_measurements (id, profileId, measuredAtEpochMillis, weightKg, fasting, justWokeUp, afterBathroom, noRecentWorkout) VALUES (1, 1, 3000, 80.5, 1, 1, 1, 1)")
            execSQL("INSERT INTO body_measurements (id, profileId, measuredAtEpochMillis, waistCm, glutesCm) VALUES (1, 1, 4000, 81.5, 97.0)")
            execSQL("INSERT INTO meal_plans (id, profileId, createdAtEpochMillis, weekStartEpochDay, status) VALUES (1, 1, 5000, 21000, 'ACTIVE')")
            execSQL("INSERT INTO workouts (id, profileId, startedAtEpochMillis, type, title, durationMinutes, isRestDay) VALUES (1, 1, 6000, 'PESI', 'Allenamento', 45, 0)")
            execSQL("INSERT INTO daily_activity_checkins (id, profileId, dateEpochDay, status, adjustmentKcal, createdAtEpochMillis, updatedAtEpochMillis) VALUES (1, 1, 21000, 'DONE', 150, 7000, 7000)")
            close()
        }

        helper.runMigrationsAndValidate(
            MyFitAiDatabase.DATABASE_NAME,
            20,
            true,
            DatabaseMigrations.MIGRATION_19_20,
        ).use { db ->
            db.query("SELECT name FROM sqlite_master WHERE name IN ('workouts', 'daily_activity_checkins') OR tbl_name IN ('workouts', 'daily_activity_checkins')").use { cursor ->
                assertEquals(0, cursor.count)
            }
            mapOf(
                "user_profile" to "SELECT name FROM user_profile WHERE id = 1",
                "bia_measurements" to "SELECT weightKg FROM bia_measurements WHERE id = 1",
                "body_measurements" to "SELECT waistCm FROM body_measurements WHERE id = 1",
                "meal_plans" to "SELECT status FROM meal_plans WHERE id = 1",
            ).forEach { (table, sql) ->
                db.query(sql).use { cursor ->
                    assertTrue("$table lost its row", cursor.moveToFirst())
                }
            }
            db.query("SELECT name FROM user_profile WHERE id = 1").use { cursor ->
                cursor.moveToFirst()
                assertEquals("Luca", cursor.getString(0))
            }
            db.query("SELECT weightKg FROM bia_measurements WHERE id = 1").use { cursor ->
                cursor.moveToFirst()
                assertEquals(80.5f, cursor.getFloat(0))
            }
        }
    }
}
