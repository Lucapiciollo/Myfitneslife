package com.myfitai.app.ui

import com.myfitai.app.domain.food.DietaryCategory
import com.myfitai.app.domain.food.DietaryProfile
import com.myfitai.app.domain.food.DietaryProfile.NoticeReason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class DietaryPreferencesPresentationTest {
    private val legacy = "Allergico alle noci, niente lattosio"
    private val unreadable = DietaryProfile.parse(legacy)

    @Test
    fun noticesFor_returnsOnlyTheNoticesOfThatCategory() {
        val profile = DietaryProfile(
            allergies = listOf("glutine", "kiwi giallo", "nichel"),
            intolerances = listOf("latte e derivati", "solfiti"),
            excludedFoods = listOf("tonno", "nichel"),
            dislikedFoods = listOf("kiwi giallo"),
            dietStyle = "Paleo",
        )
        assertEquals(
            listOf(
                DietaryPreferencesPresentation.ItemNotice("kiwi giallo", NoticeReason.NAME_ONLY),
                DietaryPreferencesPresentation.ItemNotice("nichel", NoticeReason.LIMITED),
            ),
            DietaryPreferencesPresentation.noticesFor(profile, DietaryCategory.ALLERGY),
        )
        assertEquals(
            listOf(DietaryPreferencesPresentation.ItemNotice("solfiti", NoticeReason.LIMITED)),
            DietaryPreferencesPresentation.noticesFor(profile, DietaryCategory.INTOLERANCE),
        )
        assertEquals(
            listOf(DietaryPreferencesPresentation.ItemNotice("nichel", NoticeReason.LIMITED)),
            DietaryPreferencesPresentation.noticesFor(profile, DietaryCategory.EXCLUDED),
        )
        assertTrue(DietaryPreferencesPresentation.noticesFor(profile, DietaryCategory.DISLIKED).isEmpty())
        assertTrue(DietaryPreferencesPresentation.noticesFor(profile, DietaryCategory.PREFERRED).isEmpty())
        assertEquals(
            DietaryPreferencesPresentation.ItemNotice("Paleo", NoticeReason.UNKNOWN_STYLE),
            DietaryPreferencesPresentation.styleNotice(profile),
        )
    }

    @Test
    fun noticesFor_isEmptyForFullyCoveredProfile() {
        val profile = DietaryProfile(allergies = listOf("lattosio"), excludedFoods = listOf("tonno"), dietStyle = "Vegano")
        DietaryCategory.values().forEach { assertTrue(DietaryPreferencesPresentation.noticesFor(profile, it).isEmpty()) }
        assertNull(DietaryPreferencesPresentation.styleNotice(profile))
    }

    @Test
    fun noticesForValues_keepsOnlyJustAddedItems() {
        val profile = DietaryProfile(allergies = listOf("kiwi giallo", "nichel"))
        val added = DietaryPreferencesPresentation.noticesForValues(profile, DietaryCategory.ALLERGY, listOf("Nichel"))
        assertEquals(listOf(DietaryPreferencesPresentation.ItemNotice("nichel", NoticeReason.LIMITED)), added)
    }

    @Test
    fun legacyTextToShow_onlyForUnreadableProfiles() {
        assertTrue(unreadable.isUnreadable)
        assertEquals(legacy, DietaryPreferencesPresentation.legacyTextToShow(unreadable))
        assertNull(DietaryPreferencesPresentation.legacyTextToShow(DietaryProfile(allergies = listOf("soia"))))
        assertNull(DietaryPreferencesPresentation.legacyTextToShow(null))
    }

    @Test
    fun saveCheck_unreadableWithEmptyListsAsksExplicitConfirmation() {
        val candidate = unreadable.copy(notes = legacy) // liste vuote, testo ancora nelle note
        val check = DietaryPreferencesPresentation.saveCheck(unreadable, candidate)
        assertTrue(check.confirmEmptyConstraints)
        assertFalse(check.confirmLegacyTextDropped)
        assertTrue(check.needsConfirmation)
    }

    @Test
    fun saveCheck_unreadableWithDeclaredConstraintsKeepingNotesNeedsNothing() {
        val candidate = unreadable.copy(allergies = listOf("frutta a guscio"), notes = legacy)
        assertFalse(DietaryPreferencesPresentation.saveCheck(unreadable, candidate).needsConfirmation)
        val withStyle = unreadable.copy(dietStyle = "Vegano", notes = "$legacy\nextra")
        assertFalse(DietaryPreferencesPresentation.saveCheck(unreadable, withStyle).needsConfirmation)
    }

    @Test
    fun saveCheck_asksConsentWhenLegacyTextIsRemovedFromNotes() {
        val candidate = unreadable.copy(allergies = listOf("frutta a guscio"), notes = null)
        val check = DietaryPreferencesPresentation.saveCheck(unreadable, candidate)
        assertFalse(check.confirmEmptyConstraints)
        assertTrue(check.confirmLegacyTextDropped)
        val both = DietaryPreferencesPresentation.saveCheck(unreadable, unreadable.copy(notes = "  "))
        assertTrue(both.confirmEmptyConstraints && both.confirmLegacyTextDropped)
    }

    @Test
    fun saveCheck_readableProfilesNeverAskForConfirmation() {
        val readable = DietaryProfile(allergies = listOf("soia"))
        assertFalse(DietaryPreferencesPresentation.saveCheck(readable, DietaryProfile()).needsConfirmation)
        assertFalse(DietaryPreferencesPresentation.saveCheck(null, DietaryProfile()).needsConfirmation)
        assertFalse(DietaryPreferencesPresentation.saveCheck(DietaryProfile(), DietaryProfile()).needsConfirmation)
    }

    @Test
    fun afterSave_resultIsReadableAndForgetsLegacyState() {
        val saved = DietaryPreferencesPresentation.afterSave(unreadable.copy(allergies = listOf("soia"), notes = legacy))
        assertFalse(saved.isUnreadable)
        assertNull(saved.rawLegacyText)
        assertEquals(legacy, saved.notes)
        assertEquals(listOf("soia"), saved.allergies)
    }

    @Test
    fun withLegacyState_restoresUnreadableStatusLostByJson() {
        val rebuilt = DietaryProfile.parse(unreadable.toJson())
        assertFalse("toJson must not carry the unreadable state", rebuilt.isUnreadable)
        val restored = DietaryPreferencesPresentation.withLegacyState(rebuilt, legacy)
        assertTrue(restored.isUnreadable)
        assertEquals(legacy, restored.rawLegacyText)
        assertEquals(rebuilt, DietaryPreferencesPresentation.withLegacyState(rebuilt, null))
    }

    @Test
    fun limitsStringResourceMatchesDomainNotice() {
        val strings = sequenceOf(File("src/main/res/values/strings.xml"), File("app/src/main/res/values/strings.xml"))
            .first { it.exists() }
            .readText(Charsets.UTF_8)
        assertTrue(strings.contains(">${DietaryProfile.LIMITS_NOTICE}</string>"))
    }
}
