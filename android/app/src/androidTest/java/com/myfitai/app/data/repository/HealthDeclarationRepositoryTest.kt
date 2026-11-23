package com.myfitai.app.data.repository

import android.content.Context
import android.os.Parcelable
import android.util.SparseArray
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.widget.RadioGroup
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.myfitai.app.data.local.MyFitAiDatabase
import com.myfitai.app.data.local.entity.UserProfileEntity
import com.myfitai.app.data.profile.ActiveProfileStore
import com.myfitai.app.R
import com.myfitai.app.domain.profile.ProfileAnamnesis
import com.myfitai.app.domain.profile.ProfileAnamnesisCodec
import com.myfitai.app.domain.safety.HealthDeclaration
import com.myfitai.app.domain.safety.HealthDeclarationCodec
import com.myfitai.app.domain.safety.HealthDeclarationDecodeResult
import com.myfitai.app.ui.profile.ProfileEditViewModel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HealthDeclarationRepositoryTest {
    private lateinit var db: MyFitAiDatabase
    private lateinit var profiles: UserProfileRepository
    private lateinit var activeProfileStore: ActiveProfileStore
    private lateinit var productionSessionStore: ActiveProfileStore
    private lateinit var fixtureViewModelStore: ViewModelStore
    private lateinit var viewModel: ProfileEditViewModel
    private val viewModelStores = mutableListOf<ViewModelStore>()
    private var productionActiveProfileId: Long? = null
    private var profileA = 0L
    private var profileB = 0L
    private val now = 1_790_000_000_000L

    @Before
    fun setUp() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        productionSessionStore = ActiveProfileStore(context)
        productionActiveProfileId = productionSessionStore.currentIdOrNull()
        context.getSharedPreferences(TEST_SESSION_PREFS, Context.MODE_PRIVATE).edit().clear().commit()

        db = Room.inMemoryDatabaseBuilder(context, MyFitAiDatabase::class.java).allowMainThreadQueries().build()
        profiles = UserProfileRepository(db)
        profileA = profiles.create(profile("Profilo A", dietaryPreferences = "{\"notes\":\"preferenze A\"}"))
        val existingB = declaration(pregnancyOrLactation = false, clinicianManaged = false, at = now)
        profileB = profiles.create(
            profile("Profilo B", dietaryPreferences = "{\"notes\":\"preferenze B\"}")
                .copy(healthDeclarationJson = HealthDeclarationCodec.encode(existingB)),
        )

        activeProfileStore = ActiveProfileStore(context, TEST_SESSION_PREFS)
        activeProfileStore.clear()
        activeProfileStore.refreshFromPersistence()
        activeProfileStore.selectProfile(profileA, makeDefault = false)
        fixtureViewModelStore = newViewModelStore()
        viewModel = createViewModel(fixtureViewModelStore)
    }

    @After
    fun tearDown() {
        viewModelStores.toList().forEach(ViewModelStore::clear)
        viewModelStores.clear()
        activeProfileStore.clear()
        val context = ApplicationProvider.getApplicationContext<Context>()
        check(context.getSharedPreferences(TEST_SESSION_PREFS, Context.MODE_PRIVATE).edit().clear().commit())
        db.close()
        assertEquals(productionActiveProfileId, productionSessionStore.currentIdOrNull())
    }

    @Test
    fun declarationIsProfileScopedPartialAndDoesNotOverwriteProfileFields() = runBlocking {
        val beforeA = profiles.get(profileA)!!
        val beforeB = profiles.get(profileB)!!
        assertEquals(HealthDeclarationDecodeResult.Absent, viewModel.readHealthDeclaration(profileA))

        val incomplete = declaration(pregnancyOrLactation = true, clinicianManaged = null, at = now + 1)
        viewModel.updateHealthDeclarationDraft(profileA, ProfileEditViewModel.HealthDeclarationDraft(true, null))
        assertTrue(viewModel.saveHealthDeclaration(profileA, incomplete))
        assertEquals(
            HealthDeclarationDecodeResult.Incomplete(incomplete),
            profiles.healthDeclaration(profileA),
        )
        assertEquals(
            HealthDeclarationDecodeResult.Valid(declaration(false, false, now)),
            profiles.healthDeclaration(profileB),
        )
        val afterA = profiles.get(profileA)!!
        assertEquals(beforeA.copy(healthDeclarationJson = null), afterA.copy(healthDeclarationJson = null))
        assertTrue(afterA.healthDeclarationJson == HealthDeclarationCodec.encode(incomplete))
        assertEquals(beforeB.copy(healthDeclarationJson = null), profiles.get(profileB)!!.copy(healthDeclarationJson = null))

        val complete = declaration(pregnancyOrLactation = false, clinicianManaged = true, at = now + 2)
        viewModel.updateHealthDeclarationDraft(profileA, ProfileEditViewModel.HealthDeclarationDraft(false, true))
        assertTrue(viewModel.saveHealthDeclaration(profileA, complete))
        assertEquals(HealthDeclarationDecodeResult.Valid(complete), viewModel.readHealthDeclaration(profileA))
        val completedA = profiles.get(profileA)!!
        assertEquals(beforeA.copy(healthDeclarationJson = null), completedA.copy(healthDeclarationJson = null))
        assertTrue(completedA.healthDeclarationJson == HealthDeclarationCodec.encode(complete))
    }

    @Test
    fun generalProfileUpdatePreservesHealthDeclarationSavedAfterItsSnapshotWasLoaded() = runBlocking {
        val staleSnapshot = profiles.get(profileA)!!
        val savedDeclaration = declaration(pregnancyOrLactation = true, clinicianManaged = false, at = now + 5)
        viewModel.readHealthDeclaration(profileA)
        viewModel.updateHealthDeclarationDraft(profileA, ProfileEditViewModel.HealthDeclarationDraft(true, false))
        assertTrue(viewModel.saveHealthDeclaration(profileA, savedDeclaration))

        profiles.update(
            staleSnapshot.copy(
                name = "Profilo A aggiornato",
                currentWeightKg = 71.5f,
                updatedAtEpochMillis = now + 6,
            ),
        )

        val updated = profiles.get(profileA)!!
        assertEquals("Profilo A aggiornato", updated.name)
        assertEquals(71.5f, updated.currentWeightKg!!, 0f)
        assertEquals(now + 6, updated.updatedAtEpochMillis)
        assertEquals(staleSnapshot.dietaryPreferencesJson, updated.dietaryPreferencesJson)
        assertEquals(HealthDeclarationDecodeResult.Valid(savedDeclaration), profiles.healthDeclaration(profileA))
    }

    @Test
    fun anamnesisSaveUpdatesOnlyItsFieldAndPreservesHealthDeclaration() = runBlocking {
        val declaration = declaration(pregnancyOrLactation = false, clinicianManaged = true, at = now + 7)
        val initialBaseline = profiles.healthDeclarationSnapshot(profileA)!!
        assertTrue(profiles.compareAndSetHealthDeclaration(initialBaseline, declaration) != null)
        val baseline = profiles.healthDeclarationSnapshot(profileA)!!
        val beforeA = profiles.get(profileA)!!
        val beforeB = profiles.get(profileB)!!
        val json = ProfileAnamnesisCodec.encode(ProfileAnamnesis(profession = "Professione privata"))

        val saved = profiles.saveAnamnesis(
            profileId = profileA,
            json = json,
            updatedAt = now + 8,
            healthBaseline = baseline,
            healthDeclaration = null,
        )

        assertTrue(saved != null)
        assertEquals(HealthDeclarationDecodeResult.Valid(declaration), saved!!.decoded)
        assertEquals(
            beforeA.copy(anamnesisJson = json, updatedAtEpochMillis = now + 8),
            profiles.get(profileA),
        )
        assertEquals(beforeB, profiles.get(profileB))
    }

    @Test
    fun profileSwitchRejectsStaleReadAndWrite() = runBlocking {
        val beforeA = profiles.get(profileA)!!
        val beforeB = profiles.get(profileB)!!
        viewModel.readHealthDeclaration(profileA)
        viewModel.updateHealthDeclarationDraft(profileA, ProfileEditViewModel.HealthDeclarationDraft(true, false))
        activeProfileStore.setActiveProfile(profileB)

        assertNull(viewModel.readHealthDeclaration(profileA))
        assertFalse(viewModel.saveHealthDeclaration(profileA, declaration(true, true, now + 3)))
        assertTrue(viewModel.healthDeclarationEditor.value.stale)
        assertEquals(beforeA.copy(healthDeclarationJson = null), profiles.get(profileA)!!.copy(healthDeclarationJson = null))
        assertEquals(beforeB.copy(healthDeclarationJson = null), profiles.get(profileB)!!.copy(healthDeclarationJson = null))

        val updatedB = declaration(pregnancyOrLactation = null, clinicianManaged = false, at = now + 4)
        viewModel.readHealthDeclaration(profileB)
        viewModel.updateHealthDeclarationDraft(profileB, ProfileEditViewModel.HealthDeclarationDraft(null, false))
        assertTrue(viewModel.saveHealthDeclaration(profileB, updatedB))
        assertEquals(HealthDeclarationDecodeResult.Incomplete(updatedB), profiles.healthDeclaration(profileB))
        assertEquals(beforeA.copy(healthDeclarationJson = null), profiles.get(profileA)!!.copy(healthDeclarationJson = null))
    }

    @Test
    fun compareAndSetRejectsAStaleWriterAndComparesExactNullInvalidAndUnsupportedRawJson() = runBlocking {
        val nullBaseline = profiles.healthDeclarationSnapshot(profileA)!!
        assertEquals(HealthDeclarationDecodeResult.Absent, nullBaseline.decoded)
        assertFalse(nullBaseline.toString().contains("healthDeclarationJson"))

        val concurrentDeclaration = declaration(pregnancyOrLactation = false, clinicianManaged = true, at = now + 10)
        assertTrue(profiles.compareAndSetHealthDeclaration(nullBaseline, concurrentDeclaration) != null)
        val staleWrite = declaration(pregnancyOrLactation = true, clinicianManaged = false, at = now + 11)
        assertNull(profiles.compareAndSetHealthDeclaration(nullBaseline, staleWrite))
        assertEquals(HealthDeclarationDecodeResult.Valid(concurrentDeclaration), profiles.healthDeclaration(profileA))

        val oldValidJson = profiles.get(profileB)!!.healthDeclarationJson!!
        val invalidA = """{"schemaVersion":1,"policyVersion":"S2-A.1","pregnancyOrLactation":true,"clinicianManagedConditionOrMedication":false,"confirmedAtEpochMillis":1790000000000,"extra":1}"""
        val invalidB = invalidA.replace("true", "false")
        assertEquals(1, db.userProfileDao().compareAndSetHealthDeclaration(profileB, oldValidJson, invalidA))
        val invalidBaseline = profiles.healthDeclarationSnapshot(profileB)!!
        assertTrue(invalidBaseline.decoded is HealthDeclarationDecodeResult.Invalid)
        assertFalse(invalidBaseline.toString().contains(invalidA))
        assertEquals(1, db.userProfileDao().compareAndSetHealthDeclaration(profileB, invalidA, invalidB))
        assertNull(profiles.compareAndSetHealthDeclaration(invalidBaseline, staleWrite))
        assertTrue(profiles.get(profileB)!!.healthDeclarationJson == invalidB)

        val unsupportedA = """{"schemaVersion":2,"policyVersion":"S2-A.1","pregnancyOrLactation":true,"clinicianManagedConditionOrMedication":false,"confirmedAtEpochMillis":1790000000000}"""
        val unsupportedB = unsupportedA.replace("1790000000000", "1790000000001")
        assertEquals(1, db.userProfileDao().compareAndSetHealthDeclaration(profileB, invalidB, unsupportedA))
        val unsupportedBaseline = profiles.healthDeclarationSnapshot(profileB)!!
        assertTrue(unsupportedBaseline.decoded is HealthDeclarationDecodeResult.UnsupportedVersion)
        assertEquals(1, db.userProfileDao().compareAndSetHealthDeclaration(profileB, unsupportedA, unsupportedB))
        assertNull(profiles.compareAndSetHealthDeclaration(unsupportedBaseline, staleWrite))
        assertTrue(profiles.get(profileB)!!.healthDeclarationJson == unsupportedB)
    }

    @Test
    fun refreshAfterConcurrentWriteKeepsDraftAndCannotReenableStaleSave() = runBlocking {
        val baseline = profiles.healthDeclarationSnapshot(profileA)!!
        viewModel.readHealthDeclaration(profileA)
        val draft = ProfileEditViewModel.HealthDeclarationDraft(true, false)
        viewModel.updateHealthDeclarationDraft(profileA, draft)

        val concurrent = declaration(pregnancyOrLactation = false, clinicianManaged = true, at = now + 20)
        assertTrue(profiles.compareAndSetHealthDeclaration(baseline, concurrent) != null)
        viewModel.readHealthDeclaration(profileA, refresh = true)

        val staleEditor = viewModel.healthDeclarationEditor.value
        assertTrue(staleEditor.stale)
        assertEquals(draft, staleEditor.draft)
        assertTrue(staleEditor.dirty)
        assertTrue(staleEditor.baseline!!.hasSameRawBaseline(baseline))
        assertFalse(viewModel.saveHealthDeclaration(profileA, declaration(true, false, now + 21)))
        viewModel.readHealthDeclaration(profileA, refresh = true)
        assertTrue(viewModel.healthDeclarationEditor.value.stale)
        assertEquals(HealthDeclarationDecodeResult.Valid(concurrent), profiles.healthDeclaration(profileA))

        viewModel.abandonHealthDeclarationDraft(profileA)
        assertEquals(HealthDeclarationDecodeResult.Valid(concurrent), viewModel.readHealthDeclaration(profileA))
        assertFalse(viewModel.healthDeclarationEditor.value.dirty)
        assertFalse(viewModel.healthDeclarationEditor.value.stale)
        assertEquals(ProfileEditViewModel.HealthDeclarationDraft(false, true), viewModel.healthDeclarationEditor.value.draft)
    }

    @Test
    fun committedSaveSurvivesCallerCancellationAndProfileSwitchAsSavedAndStale() = runBlocking {
        val store = newViewModelStore()
        try {
            val firstActivityViewModel = createViewModel(store)
            firstActivityViewModel.readHealthDeclaration(profileA)
            val declaration = declaration(pregnancyOrLactation = true, clinicianManaged = false, at = now + 30)
            firstActivityViewModel.updateHealthDeclarationDraft(profileA, ProfileEditViewModel.HealthDeclarationDraft(true, false))

            val oldActivityCaller = launch { firstActivityViewModel.saveHealthDeclaration(profileA, declaration) }
            withTimeout(5_000) {
                firstActivityViewModel.healthDeclarationSaveState.first {
                    it.profileId == profileA && it.status == ProfileEditViewModel.HealthDeclarationSaveStatus.SAVING
                }
            }
            activeProfileStore.setActiveProfile(profileB)
            oldActivityCaller.cancelAndJoin()

            // A recreated Activity uses the same retained ViewModelStore; its lifecycle cancellation did not cancel the save.
            val recreatedActivityViewModel = createViewModel(store)
            assertSame(firstActivityViewModel, recreatedActivityViewModel)

            val outcome = withTimeout(5_000) {
                recreatedActivityViewModel.healthDeclarationSaveState.first {
                    it.profileId == profileA && it.status == ProfileEditViewModel.HealthDeclarationSaveStatus.SAVED
                }
            }
            assertEquals(ProfileEditViewModel.HealthDeclarationSaveStatus.SAVED, outcome.status)
            assertEquals(HealthDeclarationDecodeResult.Valid(declaration), profiles.healthDeclaration(profileA))
            assertTrue(recreatedActivityViewModel.healthDeclarationEditor.value.stale)
            assertFalse(recreatedActivityViewModel.healthDeclarationEditor.value.dirty)
            assertEquals(HealthDeclarationDecodeResult.Valid(declaration), recreatedActivityViewModel.healthDeclarationEditor.value.baseline?.decoded)
        } finally {
            clearViewModelStore(store)
        }
    }

    @Test
    fun abandoningStaleDraftClearsItWhenItsProfileIsNoLongerActive() = runBlocking {
        viewModel.readHealthDeclaration(profileA)
        val draft = ProfileEditViewModel.HealthDeclarationDraft(true, false)
        viewModel.updateHealthDeclarationDraft(profileA, draft)
        activeProfileStore.setActiveProfile(profileB)
        assertNull(viewModel.readHealthDeclaration(profileA))
        assertTrue(viewModel.healthDeclarationEditor.value.stale)

        viewModel.abandonHealthDeclarationDraft(profileA)

        val cleared = viewModel.healthDeclarationEditor.value
        assertEquals(profileA, cleared.profileId)
        assertNull(cleared.baseline)
        assertEquals(ProfileEditViewModel.HealthDeclarationDraft(null, null), cleared.draft)
        assertFalse(cleared.dirty)
        assertFalse(cleared.stale)
        assertEquals(profileB, activeProfileStore.currentIdOrNull())
    }

    @Test
    fun healthDeclarationDraftAndEditorToStringRedactAnswers() = runBlocking {
        val draft = ProfileEditViewModel.HealthDeclarationDraft(true, false)
        val baseline = profiles.healthDeclarationSnapshot(profileA)!!
        val editor = ProfileEditViewModel.HealthDeclarationEditorState(
            profileId = profileA,
            baseline = baseline,
            draft = draft,
            dirty = true,
            stale = false,
        )

        listOf(draft.toString(), editor.toString()).forEach { formatted ->
            assertFalse(formatted.contains("true", ignoreCase = true))
            assertFalse(formatted.contains("false", ignoreCase = true))
        }
    }

    @Test
    fun healthDeclarationRadioAnswersAreExcludedFromHierarchySavedState() {
        val context = ContextThemeWrapper(ApplicationProvider.getApplicationContext<Context>(), R.style.Theme_MyFitAI)
        val screen = LayoutInflater.from(context).inflate(R.layout.activity_profile_edit, null, false)
        val sensitiveViewIds = listOf(
            R.id.healthPregnancyGroup,
            R.id.healthPregnancyYes,
            R.id.healthPregnancyNo,
            R.id.healthConditionGroup,
            R.id.healthConditionYes,
            R.id.healthConditionNo,
        )
        sensitiveViewIds.forEach { id -> assertFalse("View $id must not save state", screen.findViewById<android.view.View>(id).isSaveEnabled) }
        assertEquals(2, (screen.findViewById<RadioGroup>(R.id.healthPregnancyGroup)).childCount)
        assertEquals(2, (screen.findViewById<RadioGroup>(R.id.healthConditionGroup)).childCount)

        val hierarchyState = SparseArray<Parcelable>()
        screen.saveHierarchyState(hierarchyState)
        sensitiveViewIds.forEach { id -> assertNull("Sensitive answer view $id was serialized", hierarchyState.get(id)) }
    }

    @Test
    fun dirtyDraftRemainsInRetainedViewModelAcrossActivityRecreationWithoutPreferenceOrRoomPersistence() = runBlocking {
        val store = newViewModelStore()
        try {
            val firstActivityViewModel = createViewModel(store)
            firstActivityViewModel.readHealthDeclaration(profileA)
            val prefs = ApplicationProvider.getApplicationContext<Context>()
                .getSharedPreferences(TEST_SESSION_PREFS, Context.MODE_PRIVATE)
            val prefsBeforeDraft = prefs.all.toMap()
            val draft = ProfileEditViewModel.HealthDeclarationDraft(false, true)
            firstActivityViewModel.updateHealthDeclarationDraft(profileA, draft)

            // Activity recreation reuses the ViewModelStore, as ProfileEditActivity's by viewModels delegate does.
            val recreatedActivityViewModel = createViewModel(store)
            assertSame(firstActivityViewModel, recreatedActivityViewModel)
            assertEquals(draft, recreatedActivityViewModel.healthDeclarationEditor.value.draft)
            assertTrue(recreatedActivityViewModel.healthDeclarationEditor.value.dirty)
            assertTrue(prefsBeforeDraft == prefs.all.toMap())
            assertNull(profiles.get(profileA)!!.healthDeclarationJson)
        } finally {
            clearViewModelStore(store)
        }
    }

    private fun newViewModelStore(): ViewModelStore = ViewModelStore().also(viewModelStores::add)

    private fun clearViewModelStore(store: ViewModelStore) {
        store.clear()
        viewModelStores.remove(store)
    }

    private suspend fun createViewModel(store: ViewModelStore): ProfileEditViewModel {
        val factory = ProfileEditViewModel.Factory(
            repository = profiles,
            activeProfileStore = activeProfileStore,
            ageMessage = { "Età non valida" },
        )
        val instance = ViewModelProvider(store, factory)[ProfileEditViewModel::class.java]
        withTimeout(5_000) {
            instance.profile.first { it?.id == profileA }
        }
        return instance
    }

    private fun profile(name: String, dietaryPreferences: String) = UserProfileEntity(
        name = name,
        birthDateEpochDay = 7_000L,
        heightCm = 170f,
        currentWeightKg = 70f,
        goal = "Mantenimento",
        activityLevel = "Moderato",
        wakeTimeMinutes = 420,
        sleepTimeMinutes = 1_380,
        dietaryPreferencesJson = dietaryPreferences,
        photoPath = null,
        createdAtEpochMillis = now,
        updatedAtEpochMillis = now,
        biologicalSex = "Femmina",
        initialWeightKg = 72f,
    )

    private fun declaration(pregnancyOrLactation: Boolean?, clinicianManaged: Boolean?, at: Long) = HealthDeclaration(
        pregnancyOrLactation = pregnancyOrLactation,
        clinicianManagedConditionOrMedication = clinicianManaged,
        confirmedAtEpochMillis = at,
    )

    companion object {
        private const val TEST_SESSION_PREFS = "health_declaration_repository_test_session"
    }
}
