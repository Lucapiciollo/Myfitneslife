package com.myfitai.app.data.local

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.myfitai.app.data.local.entity.NutritionRecoveryEventEntity
import com.myfitai.app.data.local.entity.NutritionRecoveryWithdrawalEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NutritionRecoveryDatabaseTest {
    private lateinit var db: MyFitAiDatabase

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            MyFitAiDatabase::class.java,
        ).allowMainThreadQueries().build()
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun eventAndWithdrawal_persistPlannedAndConfirmedSeparately_andWithdrawalIsIdempotent() = runBlocking {
        val eventId = db.nutritionRecoveryDao().insertEvent(
            NutritionRecoveryEventEntity(
                profileId = 1,
                createdAtEpochMillis = 100,
                eventEpochDay = 10,
                source = "CHEAT",
                originalExcessKcal = 700,
                remainingKcal = 700,
                recoveredKcal = 0,
                expiresEpochDay = 16,
                status = "ACTIVE",
                reason = "UNAVOIDABLE_FAT_ABOVE_MAX",
            )
        )
        db.nutritionRecoveryDao().upsertWithdrawal(NutritionRecoveryWithdrawalEntity(1, 11, eventId, 100, 0, 200, 200))
        val repository = com.myfitai.app.data.repository.NutritionRecoveryRepository(db)
        assertTrue(repository.confirmWithdrawal(1, 11, 300))
        assertEquals(false, repository.confirmWithdrawal(1, 11, 400))

        val event = db.nutritionRecoveryDao().activeEvents(1, 11).single()
        val withdrawal = db.nutritionRecoveryDao().getWithdrawal(1, 11)!!

        assertEquals(eventId, event.id)
        assertEquals(700, event.remainingKcal)
        assertEquals(100, withdrawal.plannedRecoveryKcal)
        assertEquals(100, withdrawal.confirmedRecoveryKcal)
        assertEquals(600, event.remainingKcal)
        assertEquals(100, event.recoveredKcal)
        assertEquals(1, db.nutritionRecoveryDao().observeWithdrawals(1).first().size)
        assertTrue(event.status == "ACTIVE")
    }
}
