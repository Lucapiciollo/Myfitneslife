package com.myfitai.app.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
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
    version = 1,
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

/**
 * Registro centralizzato delle migrazioni. V1 parte senza migrazioni; ogni incremento di versione
 * deve aggiungere qui una Migration esplicita. Non usare fallbackToDestructiveMigration in V1.
 */
object DatabaseMigrations {
    val ALL: Array<Migration> = emptyArray()
}
