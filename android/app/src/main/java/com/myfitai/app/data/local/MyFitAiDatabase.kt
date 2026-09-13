package com.myfitai.app.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.myfitai.app.data.local.dao.*
import com.myfitai.app.data.local.entity.*

@Database(
    entities = [
        UserProfileEntity::class,
        BiaMeasurementEntity::class,
        BodyMeasurementEntity::class,
        WorkoutEntity::class,
        MealPlanEntity::class,
        MealPlanVersionEntity::class,
        MealPlanDayEntity::class,
        MealEntity::class,
        MealIngredientEntity::class,
        CheatEntryEntity::class,
        WeeklyReviewEntity::class,
    ],
    version = 4,
    exportSchema = true,
)
abstract class MyFitAiDatabase : RoomDatabase() {
    abstract fun userProfileDao(): UserProfileDao
    abstract fun biaMeasurementDao(): BiaMeasurementDao
    abstract fun bodyMeasurementDao(): BodyMeasurementDao
    abstract fun workoutDao(): WorkoutDao
    abstract fun mealPlanDao(): MealPlanDao
    abstract fun cheatEntryDao(): CheatEntryDao
    abstract fun weeklyReviewDao(): WeeklyReviewDao

    companion object {
        const val DATABASE_NAME = "myfitai.db"

        @Volatile private var instance: MyFitAiDatabase? = null

        fun getInstance(context: Context): MyFitAiDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                MyFitAiDatabase::class.java,
                DATABASE_NAME,
            )
                .addMigrations(*DatabaseMigrations.ALL)
                .build()
                .also { instance = it }
        }
    }
}

object DatabaseMigrations {
    /** V1 era single-profile; V2 introduce scope per profilo senza perdita dati. */
    val MIGRATION_1_2 = object : Migration(1, 2) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE user_profile ADD COLUMN photoPath TEXT")
            db.execSQL("ALTER TABLE user_profile ADD COLUMN createdAtEpochMillis INTEGER NOT NULL DEFAULT 0")

            db.execSQL("ALTER TABLE bia_measurements ADD COLUMN profileId INTEGER NOT NULL DEFAULT 1")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_bia_measurements_profileId ON bia_measurements(profileId)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_bia_measurements_profileId_measuredAtEpochMillis ON bia_measurements(profileId, measuredAtEpochMillis)")

            db.execSQL("ALTER TABLE body_measurements ADD COLUMN profileId INTEGER NOT NULL DEFAULT 1")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_body_measurements_profileId ON body_measurements(profileId)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_body_measurements_profileId_measuredAtEpochMillis ON body_measurements(profileId, measuredAtEpochMillis)")

            db.execSQL("ALTER TABLE workouts ADD COLUMN profileId INTEGER NOT NULL DEFAULT 1")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_workouts_profileId ON workouts(profileId)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_workouts_profileId_startedAtEpochMillis ON workouts(profileId, startedAtEpochMillis)")

            db.execSQL("ALTER TABLE meal_plans ADD COLUMN profileId INTEGER NOT NULL DEFAULT 1")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_meal_plans_profileId ON meal_plans(profileId)")
            db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_meal_plans_profileId_weekStartEpochDay ON meal_plans(profileId, weekStartEpochDay)")

            db.execSQL("ALTER TABLE cheat_entries ADD COLUMN profileId INTEGER NOT NULL DEFAULT 1")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_cheat_entries_profileId ON cheat_entries(profileId)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_cheat_entries_profileId_occurredAtEpochMillis ON cheat_entries(profileId, occurredAtEpochMillis)")

            db.execSQL("DROP INDEX IF EXISTS index_weekly_reviews_weekStartEpochDay")
            db.execSQL("ALTER TABLE weekly_reviews ADD COLUMN profileId INTEGER NOT NULL DEFAULT 1")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_weekly_reviews_profileId ON weekly_reviews(profileId)")
            db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_weekly_reviews_profileId_weekStartEpochDay ON weekly_reviews(profileId, weekStartEpochDay)")
        }
    }

    /** V3 completa il profilo con sesso biologico esplicito e peso iniziale. */
    val MIGRATION_2_3 = object : Migration(2, 3) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE user_profile ADD COLUMN biologicalSex TEXT")
            db.execSQL("ALTER TABLE user_profile ADD COLUMN initialWeightKg REAL")
            db.execSQL("UPDATE user_profile SET initialWeightKg = currentWeightKg WHERE initialWeightKg IS NULL")
        }
    }

    /** V4 aggiunge integrazione nutrizionale e nota idratazione per ciascun giorno del piano. */
    val MIGRATION_3_4 = object : Migration(3, 4) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE meal_plan_days ADD COLUMN supplementsJson TEXT")
            db.execSQL("ALTER TABLE meal_plan_days ADD COLUMN hydrationNote TEXT")
        }
    }

    val ALL: Array<Migration> = arrayOf(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
}
