package com.myfitai.app.data.local

import android.content.Context
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.myfitai.app.data.local.entity.UserProfileEntity
import com.myfitai.app.domain.safety.HealthDeclarationCodec
import com.myfitai.app.domain.safety.HealthDeclarationDecodeResult
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

    @Test
    fun migration20To21_addsNullableFingerprintAndKeepsExistingPlanVersionsIntact() {
        helper.createDatabase(MyFitAiDatabase.DATABASE_NAME, 20).apply {
            execSQL("INSERT INTO user_profile (id, name, createdAtEpochMillis, updatedAtEpochMillis) VALUES (1, 'Luca', 1000, 2000)")
            execSQL("INSERT INTO meal_plans (id, profileId, createdAtEpochMillis, weekStartEpochDay, status) VALUES (1, 1, 5000, 21000, 'ACTIVE')")
            execSQL("INSERT INTO meal_plan_versions (id, planId, versionNumber, createdAtEpochMillis, source, reason, targetKcal, targetProteinG, targetCarbsG, targetFatG, appValidationJson) VALUES (1, 1, 1, 6000, 'GEMINI', 'AI_GENERATION', 2200, 160.0, 230.0, 70.0, '{\"ok\":true}')")
            execSQL("INSERT INTO meal_plan_versions (id, planId, versionNumber, createdAtEpochMillis, source, reason, targetKcal, targetProteinG, targetCarbsG, targetFatG) VALUES (2, 1, 2, 7000, 'LEGACY', NULL, 2100, 150.0, 220.0, 65.0)")
            execSQL("INSERT INTO meal_plan_days (id, versionId, dateEpochDay, totalKcal, proteinG, carbsG, fatG) VALUES (1, 1, 21000, 2200, 160.0, 230.0, 70.0)")
            close()
        }

        helper.runMigrationsAndValidate(
            MyFitAiDatabase.DATABASE_NAME,
            21,
            true,
            DatabaseMigrations.MIGRATION_20_21,
        ).use { db ->
            db.query("SELECT id, versionNumber, source, reason, targetKcal, targetProteinG, appValidationJson, inputsFingerprint FROM meal_plan_versions ORDER BY id").use { cursor ->
                assertEquals(2, cursor.count)
                cursor.moveToFirst()
                assertEquals(1, cursor.getInt(0))
                assertEquals(1, cursor.getInt(1))
                assertEquals("GEMINI", cursor.getString(2))
                assertEquals("AI_GENERATION", cursor.getString(3))
                assertEquals(2200, cursor.getInt(4))
                assertEquals(160.0f, cursor.getFloat(5))
                assertEquals("{\"ok\":true}", cursor.getString(6))
                assertTrue("le righe esistenti devono avere impronta null", cursor.isNull(7))
                cursor.moveToNext()
                assertEquals(2, cursor.getInt(0))
                assertEquals("LEGACY", cursor.getString(2))
                assertTrue(cursor.isNull(3))
                assertEquals(2100, cursor.getInt(4))
                assertTrue("le righe esistenti devono avere impronta null", cursor.isNull(7))
            }
            db.query("SELECT totalKcal FROM meal_plan_days WHERE id = 1").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(2200, cursor.getInt(0))
            }
            db.query("SELECT name FROM user_profile WHERE id = 1").use { cursor ->
                cursor.moveToFirst()
                assertEquals("Luca", cursor.getString(0))
            }
        }
    }

    @Test
    fun migration21To22_addsNullableIngredientSnapshot_declaredNutritionSource_catalogVersion_andEmptyCustomFoods() {
        helper.createDatabase(MyFitAiDatabase.DATABASE_NAME, 21).apply {
            execSQL("INSERT INTO user_profile (id, name, createdAtEpochMillis, updatedAtEpochMillis) VALUES (1, 'Luca', 1000, 2000)")
            execSQL("INSERT INTO meal_plans (id, profileId, createdAtEpochMillis, weekStartEpochDay, status) VALUES (1, 1, 5000, 21000, 'ACTIVE')")
            execSQL("INSERT INTO meal_plan_versions (id, planId, versionNumber, createdAtEpochMillis, source, reason, targetKcal, targetProteinG, targetCarbsG, targetFatG, inputsFingerprint) VALUES (1, 1, 1, 6000, 'GEMINI', 'AI_GENERATION', 2200, 160.0, 230.0, 70.0, 'fp1')")
            execSQL("INSERT INTO meal_plan_days (id, versionId, dateEpochDay, totalKcal, proteinG, carbsG, fatG) VALUES (1, 1, 21000, 2200, 160.0, 230.0, 70.0)")
            execSQL("INSERT INTO meals (id, dayId, sortOrder, type, title, timeMinutes, kcal, proteinG, carbsG, fatG, preparation) VALUES (1, 1, 0, 'Pranzo', 'Riso e pollo', 780, 700, 50.0, 80.0, 18.0, 'Cuocere')")
            execSQL("INSERT INTO meals (id, dayId, sortOrder, type, title, timeMinutes, kcal, proteinG, carbsG, fatG, preparation) VALUES (2, 1, 1, 'Cena', 'Salmone', 1200, 650, 45.0, 40.0, 30.0, NULL)")
            execSQL("INSERT INTO meal_ingredients (id, mealId, name, quantity, unit, displayDose, weightState, nutritionConfidence, category, sortOrder) VALUES (1, 1, 'Riso', 80.0, 'g', '80 g', 'DRY', 'HIGH', 'carbs', 0)")
            execSQL("INSERT INTO meal_ingredients (id, mealId, name, quantity, unit, displayDose, weightState, nutritionConfidence, category, sortOrder) VALUES (2, 1, 'Pollo', 200.0, 'g', '200 g', 'RAW', 'HIGH', 'protein', 1)")
            execSQL("INSERT INTO meal_ingredients (id, mealId, name, quantity, unit, displayDose, weightState, nutritionConfidence, category, sortOrder) VALUES (3, 2, 'Salmone', 180.0, 'g', '180 g', 'RAW', 'HIGH', 'protein', 0)")
            close()
        }

        helper.runMigrationsAndValidate(
            MyFitAiDatabase.DATABASE_NAME,
            22,
            true,
            DatabaseMigrations.MIGRATION_21_22,
        ).use { db ->
            // Ingredienti: dati intatti e snapshot nullo.
            db.query("SELECT id, mealId, name, quantity, unit, displayDose, weightState, nutritionConfidence, category, sortOrder, foodId, grams, kcal, proteinG, carbsG, fatG, fiberG, sodiumMg FROM meal_ingredients ORDER BY id").use { cursor ->
                assertEquals(3, cursor.count)
                cursor.moveToFirst()
                assertEquals("Riso", cursor.getString(2))
                assertEquals(80f, cursor.getFloat(3))
                assertEquals("g", cursor.getString(4))
                assertEquals("80 g", cursor.getString(5))
                assertEquals("DRY", cursor.getString(6))
                assertEquals(0, cursor.getInt(9))
                do {
                    for (column in 10..17) assertTrue("colonna $column deve essere NULL", cursor.isNull(column))
                } while (cursor.moveToNext())
            }
            // Pasti: dati intatti e nutritionSource = DECLARED.
            db.query("SELECT id, title, timeMinutes, kcal, proteinG, carbsG, fatG, preparation, nutritionSource FROM meals ORDER BY id").use { cursor ->
                assertEquals(2, cursor.count)
                cursor.moveToFirst()
                assertEquals("Riso e pollo", cursor.getString(1))
                assertEquals(780, cursor.getInt(2))
                assertEquals(700, cursor.getInt(3))
                assertEquals(50f, cursor.getFloat(4))
                assertEquals("Cuocere", cursor.getString(7))
                assertEquals("DECLARED", cursor.getString(8))
                cursor.moveToNext()
                assertEquals("Salmone", cursor.getString(1))
                assertTrue(cursor.isNull(7))
                assertEquals("DECLARED", cursor.getString(8))
            }
            // Versioni: dati intatti e catalogVersion nulla.
            db.query("SELECT versionNumber, source, targetKcal, inputsFingerprint, catalogVersion FROM meal_plan_versions WHERE id = 1").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(1, cursor.getInt(0))
                assertEquals("GEMINI", cursor.getString(1))
                assertEquals(2200, cursor.getInt(2))
                assertEquals("fp1", cursor.getString(3))
                assertTrue("le versioni esistenti non hanno versione di catalogo", cursor.isNull(4))
            }
            // Cascata e giorni invariati.
            db.query("SELECT totalKcal FROM meal_plan_days WHERE id = 1").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(2200, cursor.getInt(0))
            }
            // Nuova tabella vuota e indicizzata.
            db.query("SELECT COUNT(*) FROM custom_foods").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(0, cursor.getInt(0))
            }
            db.query("SELECT name FROM sqlite_master WHERE type = 'index' AND tbl_name = 'custom_foods' AND name = 'index_custom_foods_profileId'").use { cursor ->
                assertEquals(1, cursor.count)
            }
            // La nuova tabella accetta un alimento con i default di fonte/affidabilita.
            db.execSQL("INSERT INTO custom_foods (profileId, name, kcalPer100, proteinPer100, carbsPer100, fatPer100, fiberPer100, sodiumMgPer100, createdAtEpochMillis) VALUES (1, 'Torta della nonna', 380.0, 5.0, 50.0, 18.0, 1.0, 90.0, 9000)")
            db.query("SELECT source, confidence, notes FROM custom_foods").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("USER", cursor.getString(0))
                assertEquals("ESTIMATED", cursor.getString(1))
                assertTrue(cursor.isNull(2))
            }
            db.query("SELECT name FROM user_profile WHERE id = 1").use { cursor ->
                cursor.moveToFirst()
                assertEquals("Luca", cursor.getString(0))
            }
        }
    }

    @Test
    fun migration23To24_createsEmptyProfileScopedDiaryTables() {
        val testDbName = "diary-migration-test-${System.nanoTime()}.db"
        try {
            helper.createDatabase(testDbName, 23).apply {
                try {
                    execSQL("INSERT INTO user_profile (id, name, createdAtEpochMillis, updatedAtEpochMillis) VALUES (1, 'Profilo esistente', 1000, 2000)")
                } finally {
                    close()
                }
            }

            helper.runMigrationsAndValidate(
                testDbName,
                24,
                true,
                DatabaseMigrations.MIGRATION_23_24,
            ).use { db ->
                db.query("SELECT id, name FROM user_profile WHERE id = 1").use { cursor ->
                    assertTrue(cursor.moveToFirst())
                    assertEquals(1, cursor.getLong(0))
                    assertEquals("Profilo esistente", cursor.getString(1))
                }
                db.query("SELECT COUNT(*) FROM mood_check_ins").use { cursor ->
                    assertTrue(cursor.moveToFirst())
                    assertEquals(0, cursor.getInt(0))
                }
                db.query("SELECT COUNT(*) FROM weekly_diary_notes").use { cursor ->
                    assertTrue(cursor.moveToFirst())
                    assertEquals(0, cursor.getInt(0))
                }
                db.execSQL("INSERT INTO mood_check_ins (profileId, dateEpochDay, mood, recordedAtEpochMillis, updatedAtEpochMillis) VALUES (1, 20780, 'GOOD', 10, 10)")
                db.execSQL("INSERT INTO mood_check_ins (profileId, dateEpochDay, mood, recordedAtEpochMillis, updatedAtEpochMillis) VALUES (2, 20780, 'NEUTRAL', 20, 20)")
                db.query("SELECT COUNT(*) FROM mood_check_ins WHERE dateEpochDay = 20780").use { cursor ->
                    assertTrue(cursor.moveToFirst())
                    assertEquals(2, cursor.getInt(0))
                }
            }
        } finally {
            context.deleteDatabase(testDbName)
        }
    }

    @Test
    fun migration24To25_addsEmptyAnamnesisAndPreservesExistingProfile() {
        val testDbName = "anamnesis-migration-test-${System.nanoTime()}.db"
        try {
            helper.createDatabase(testDbName, 24).apply {
                try {
                    execSQL("INSERT INTO user_profile (id, name, currentWeightKg, createdAtEpochMillis, updatedAtEpochMillis) VALUES (1, 'Profilo esistente', 72.5, 1000, 2000)")
                } finally {
                    close()
                }
            }

            helper.runMigrationsAndValidate(
                testDbName,
                25,
                true,
                DatabaseMigrations.MIGRATION_24_25,
            ).use { db ->
                db.query("SELECT id, name, currentWeightKg, anamnesisJson FROM user_profile WHERE id = 1").use { cursor ->
                    assertTrue(cursor.moveToFirst())
                    assertEquals(1, cursor.getLong(0))
                    assertEquals("Profilo esistente", cursor.getString(1))
                    assertEquals(72.5, cursor.getDouble(2), 0.001)
                    assertTrue(cursor.isNull(3))
                }
            }
        } finally {
            context.deleteDatabase(testDbName)
        }
    }

    @Test
    fun migration22To23_addsUndeclaredHealthFieldAndPreservesProfilesAndPlans() {
        val testDbName = "s2b-migration-test-${System.nanoTime()}.db"
        try {
            helper.createDatabase(testDbName, 22).apply {
                try {
                    execSQL("INSERT INTO user_profile (id, name, createdAtEpochMillis, updatedAtEpochMillis) VALUES (1, 'Profilo uno', 1000, 2000)")
                    execSQL("INSERT INTO user_profile (id, name, createdAtEpochMillis, updatedAtEpochMillis) VALUES (2, 'Profilo due', 3000, 4000)")
                    execSQL("INSERT INTO meal_plans (id, profileId, createdAtEpochMillis, weekStartEpochDay, status) VALUES (1, 1, 5000, 21000, 'ACTIVE')")
                    execSQL("INSERT INTO meal_plans (id, profileId, createdAtEpochMillis, weekStartEpochDay, status) VALUES (2, 2, 6000, 21007, 'ARCHIVED')")
                    execSQL("INSERT INTO meal_plan_versions (id, planId, versionNumber, createdAtEpochMillis, source, reason, targetKcal, targetProteinG, targetCarbsG, targetFatG) VALUES (1, 1, 1, 7000, 'GEMINI', 'AI_GENERATION', 2200, 160.0, 230.0, 70.0)")
                    execSQL("INSERT INTO meal_plan_versions (id, planId, versionNumber, createdAtEpochMillis, source, reason, targetKcal, targetProteinG, targetCarbsG, targetFatG) VALUES (2, 2, 1, 8000, 'LEGACY', NULL, 1900, 140.0, 200.0, 60.0)")
                    execSQL("INSERT INTO meal_plan_days (id, versionId, dateEpochDay, totalKcal, proteinG, carbsG, fatG) VALUES (1, 1, 21000, 2200, 160.0, 230.0, 70.0)")
                    execSQL("INSERT INTO meal_plan_days (id, versionId, dateEpochDay, totalKcal, proteinG, carbsG, fatG) VALUES (2, 2, 21007, 1900, 140.0, 200.0, 60.0)")
                } finally {
                    close()
                }
            }

            helper.runMigrationsAndValidate(
                testDbName,
                23,
                true,
                DatabaseMigrations.MIGRATION_22_23,
            ).use { db ->
                db.query("SELECT id, name, healthDeclarationJson FROM user_profile ORDER BY id").use { cursor ->
                    assertEquals(2, cursor.count)
                    assertTrue(cursor.moveToFirst())
                    assertEquals(1, cursor.getLong(0))
                    assertEquals("Profilo uno", cursor.getString(1))
                    assertTrue("un profilo legacy non deve acquisire una dichiarazione implicita", cursor.isNull(2))
                    assertEquals(HealthDeclarationDecodeResult.Absent, HealthDeclarationCodec.decode(cursor.getString(2)))
                    assertTrue(cursor.moveToNext())
                    assertEquals(2, cursor.getLong(0))
                    assertEquals("Profilo due", cursor.getString(1))
                    assertTrue("anche gli altri profili legacy restano non dichiarati", cursor.isNull(2))
                }
                db.query("SELECT id, profileId, status FROM meal_plans ORDER BY id").use { cursor ->
                    assertEquals(2, cursor.count)
                    assertTrue(cursor.moveToFirst())
                    assertEquals(1, cursor.getLong(0))
                    assertEquals(1, cursor.getLong(1))
                    assertEquals("ACTIVE", cursor.getString(2))
                    assertTrue(cursor.moveToNext())
                    assertEquals(2, cursor.getLong(0))
                    assertEquals(2, cursor.getLong(1))
                    assertEquals("ARCHIVED", cursor.getString(2))
                }
                db.query("SELECT id, source, targetKcal FROM meal_plan_versions ORDER BY id").use { cursor ->
                    assertEquals(2, cursor.count)
                    assertTrue(cursor.moveToFirst())
                    assertEquals(1, cursor.getLong(0))
                    assertEquals("GEMINI", cursor.getString(1))
                    assertEquals(2200, cursor.getInt(2))
                    assertTrue(cursor.moveToNext())
                    assertEquals(2, cursor.getLong(0))
                    assertEquals("LEGACY", cursor.getString(1))
                    assertEquals(1900, cursor.getInt(2))
                }
                db.query("SELECT id, totalKcal FROM meal_plan_days ORDER BY id").use { cursor ->
                    assertEquals(2, cursor.count)
                    assertTrue(cursor.moveToFirst())
                    assertEquals(1, cursor.getLong(0))
                    assertEquals(2200, cursor.getInt(1))
                    assertTrue(cursor.moveToNext())
                    assertEquals(2, cursor.getLong(0))
                    assertEquals(1900, cursor.getInt(1))
                }
            }

            val declaration = """{"schemaVersion":1,"policyVersion":"S2-A.1","pregnancyOrLactation":true,"clinicianManagedConditionOrMedication":false,"confirmedAtEpochMillis":1}"""
            val profile = UserProfileEntity(
                name = "Profilo test",
                birthDateEpochDay = null,
                heightCm = null,
                currentWeightKg = null,
                goal = null,
                activityLevel = null,
                wakeTimeMinutes = null,
                sleepTimeMinutes = null,
                dietaryPreferencesJson = null,
                createdAtEpochMillis = 1,
                updatedAtEpochMillis = 1,
                healthDeclarationJson = declaration,
                anamnesisJson = "dati anamnestici riservati",
            )
            assertTrue(
                "i dati sensibili non devono finire nella rappresentazione testuale dell'entità",
                profile.toString().contains("healthDeclarationJson=<redacted>") &&
                    profile.toString().contains("anamnesisJson=<redacted>") &&
                    !profile.toString().contains(declaration) &&
                    !profile.toString().contains("dati anamnestici riservati"),
            )
        } finally {
            context.deleteDatabase(testDbName)
            context.getDatabasePath("$testDbName.lck").delete()
        }
    }
}
