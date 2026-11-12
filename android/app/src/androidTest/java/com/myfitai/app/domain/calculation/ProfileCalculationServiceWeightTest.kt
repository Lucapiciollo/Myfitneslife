package com.myfitai.app.domain.calculation

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.myfitai.app.data.local.MyFitAiDatabase
import com.myfitai.app.data.local.entity.BiaMeasurementEntity
import com.myfitai.app.data.local.entity.BodyMeasurementEntity
import com.myfitai.app.data.local.entity.UserProfileEntity
import com.myfitai.app.data.profile.ActiveProfileStore
import com.myfitai.app.data.repository.BiaRepository
import com.myfitai.app.data.repository.BodyMeasurementRepository
import com.myfitai.app.data.repository.UserProfileRepository
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** In-memory database only: never touches the user's data. */
@RunWith(AndroidJUnit4::class)
class ProfileCalculationServiceWeightTest {
    private lateinit var db: MyFitAiDatabase
    private lateinit var profiles: UserProfileRepository
    private lateinit var bia: BiaRepository
    private lateinit var body: BodyMeasurementRepository
    private lateinit var service: ProfileCalculationService
    private var profileId = 0L

    @Before
    fun setUp() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        db = Room.inMemoryDatabaseBuilder(context, MyFitAiDatabase::class.java).allowMainThreadQueries().build()
        profiles = UserProfileRepository(db)
        bia = BiaRepository(db)
        body = BodyMeasurementRepository(db)
        service = ProfileCalculationService(profiles, bia, body, ActiveProfileStore(context))
        profileId = profiles.create(
            UserProfileEntity(
                name = "Peso", birthDateEpochDay = null, heightCm = 180f, currentWeightKg = 95f, goal = null,
                activityLevel = null, wakeTimeMinutes = null, sleepTimeMinutes = null, dietaryPreferencesJson = null,
                createdAtEpochMillis = 1, updatedAtEpochMillis = 1,
            ),
        )
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun profileOnly_usesProfileWeightWithoutDate() = runBlocking {
        val snapshot = service.profileSnapshot(profileId)!!
        assertEquals(WeightReading(95f, null, WeightSource.PROFILO), snapshot.weight)
        assertEquals(95f, snapshot.latestWeightKg)
        assertEquals(95f, profiles.get(profileId)!!.currentWeightKg)
    }

    @Test
    fun latestBiaOrMeasurement_winsAndProfileWeightStaysUntouched() = runBlocking {
        bia.insert(biaRow(1_000, 90f))
        assertEquals(WeightReading(90f, 1_000, WeightSource.BIA), service.profileSnapshot(profileId)!!.weight)

        body.insert(bodyRow(2_000, 89f))
        val snapshot = service.profileSnapshot(profileId)!!
        assertEquals(WeightReading(89f, 2_000, WeightSource.MISURA), snapshot.weight)
        assertEquals(89f, snapshot.latestWeightKg)
        assertEquals(95f, profiles.get(profileId)!!.currentWeightKg)
    }

    @Test
    fun sameTimestamp_prefersMeasurement() = runBlocking {
        bia.insert(biaRow(3_000, 91f))
        body.insert(bodyRow(3_000, 90f))
        assertEquals(WeightReading(90f, 3_000, WeightSource.MISURA), service.profileSnapshot(profileId)!!.weight)
    }

    @Test
    fun invalidWeightsFallBackToProfile() = runBlocking {
        bia.insert(biaRow(1_000, 0f))
        body.insert(bodyRow(2_000, Float.NaN))
        val snapshot = service.profileSnapshot(profileId)!!
        assertEquals(WeightSource.PROFILO, snapshot.weight.source)
        assertNull(snapshot.weight.measuredAtEpochMillis)
    }

    private fun biaRow(at: Long, weight: Float?) = BiaMeasurementEntity(
        profileId = profileId, measuredAtEpochMillis = at, weightKg = weight, bodyFatPercent = null,
        visceralFatLevel = null, muscleMassKg = null, skeletalMuscleKg = null, bodyWaterPercent = null, bmrKcal = null,
        fasting = true, justWokeUp = true, afterBathroom = true, noRecentWorkout = true,
    )

    private fun bodyRow(at: Long, weight: Float?) = BodyMeasurementEntity(
        profileId = profileId, measuredAtEpochMillis = at, chestCm = null, waistCm = null, abdomenCm = null,
        shouldersCm = null, glutesCm = null, armLeftCm = null, armRightCm = null, thighLeftCm = null,
        thighRightCm = null, calfLeftCm = null, calfRightCm = null, weightKg = weight,
    )
}
