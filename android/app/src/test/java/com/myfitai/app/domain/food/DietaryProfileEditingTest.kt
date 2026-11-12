package com.myfitai.app.domain.food

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DietaryProfileEditingTest {
    @Test
    fun addItems_splitsOnSeparatorsAndKeepsInsertionOrder() {
        val result = DietaryProfile().addItems(DietaryCategory.ALLERGY, " arachidi, soia;\nUova ")
        assertEquals(listOf("arachidi", "soia", "Uova"), result.profile.allergies)
        assertEquals(listOf("arachidi", "soia", "Uova"), result.added)
        assertTrue(result.duplicates.isEmpty())
    }

    @Test
    fun addItems_dedupesWithNormalizedComparisonAndReportsDuplicates() {
        val start = DietaryProfile(intolerances = listOf("Lattosio"))
        val result = start.addItems(DietaryCategory.INTOLERANCE, "lattòsio, glutine, GLUTINE")
        assertEquals(listOf("Lattosio", "glutine"), result.profile.intolerances)
        assertEquals(listOf("glutine"), result.added)
        assertEquals(listOf("lattòsio"), result.duplicates) // i doppioni interni allo stesso input sono gia' collassati da csv()
    }

    @Test
    fun addItems_rejectsBlankAndPunctuationOnlyAndTooLongValues() {
        val start = DietaryProfile()
        assertTrue(start.addItems(DietaryCategory.PREFERRED, "  ,  ").isEmptyInput)
        assertTrue(start.addItems(DietaryCategory.PREFERRED, "!!!").isEmptyInput)
        assertTrue(start.addItems(DietaryCategory.PREFERRED, null).isEmptyInput)
        val long = "a".repeat(DIETARY_ITEM_MAX_LENGTH + 1)
        val result = start.addItems(DietaryCategory.PREFERRED, long)
        assertEquals(listOf(long), result.tooLong)
        assertTrue(result.profile.preferredFoods.isEmpty())
    }

    @Test
    fun addItems_touchesOnlyTheRequestedCategory() {
        val start = DietaryProfile(preferredFoods = listOf("riso"), allergies = listOf("arachidi"))
        val result = start.addItems(DietaryCategory.DISLIKED, "broccoli").profile
        assertEquals(listOf("riso"), result.preferredFoods)
        assertEquals(listOf("broccoli"), result.dislikedFoods)
        assertEquals(listOf("arachidi"), result.allergies)
    }

    @Test
    fun removeItem_removesOnlyTheSingleValueInThatCategory() {
        val start = DietaryProfile(
            excludedFoods = listOf("tonno", "gamberi"),
            allergies = listOf("Tonno", "arachidi"),
        )
        val result = start.removeItem(DietaryCategory.EXCLUDED, "TONNO")
        assertEquals(listOf("gamberi"), result.excludedFoods)
        assertEquals(listOf("Tonno", "arachidi"), result.allergies)
    }

    @Test
    fun removeItem_unknownValueIsNoOp() {
        val start = DietaryProfile(allergies = listOf("arachidi"))
        assertEquals(start, start.removeItem(DietaryCategory.ALLERGY, "soia"))
    }

    @Test
    fun serialization_roundTripsChipListsWithoutMixingCategories() {
        val profile = DietaryProfile()
            .addItems(DietaryCategory.PREFERRED, "riso, pollo").profile
            .addItems(DietaryCategory.DISLIKED, "broccoli").profile
            .addItems(DietaryCategory.EXCLUDED, "tonno").profile
            .addItems(DietaryCategory.INTOLERANCE, "lattosio").profile
            .addItems(DietaryCategory.ALLERGY, "arachidi").profile
            .copy(dietStyle = "Vegetariano", notes = "Pranzi rapidi")
            .canonical()
        val parsed = DietaryProfile.parse(profile.toJson())
        assertEquals(profile, parsed)
        DietaryCategory.values().forEach { assertEquals(profile.items(it), parsed.items(it)) }
    }

    @Test
    fun removalAfterRoundTrip_persistsOnlyWhenSerialized() {
        val saved = DietaryProfile(allergies = listOf("arachidi", "soia"))
        val draft = saved.removeItem(DietaryCategory.ALLERGY, "soia")
        // La stringa persistita non cambia finche' non si serializza la bozza (Annulla non scrive).
        assertEquals(listOf("arachidi", "soia"), DietaryProfile.parse(saved.toJson()).allergies)
        assertEquals(listOf("arachidi"), DietaryProfile.parse(draft.toJson()).allergies)
    }

    @Test
    fun legacyNonJsonText_staysInNotesAndNeverBecomesAllergy() {
        val parsed = DietaryProfile.parse("Allergico alle noci, niente lattosio").canonical()
        assertEquals("Allergico alle noci, niente lattosio", parsed.notes)
        assertTrue(parsed.allergies.isEmpty())
        assertTrue(parsed.intolerances.isEmpty())
        assertTrue(parsed.hardConstraints.isEmpty())
        val reparsed = DietaryProfile.parse(parsed.toJson())
        assertEquals(parsed.notes, reparsed.notes)
        assertTrue(reparsed.allergies.isEmpty())
    }

    @Test
    fun legacyJsonWithNotesOnly_keepsNotesAndEmptyListsAreNotAnAllergyStatement() {
        val parsed = DietaryProfile.parse("{\"notes\":\"vecchia nota\"}").canonical()
        assertEquals("vecchia nota", parsed.notes)
        assertTrue(parsed.allergies.isEmpty())
        assertTrue(!FoodConstraintValidator.hasHardConstraints(parsed))
    }

    @Test
    fun canonical_dedupesLegacyDuplicatesAndTreatsNessunoAsNoStyle() {
        val dirty = DietaryProfile(
            preferredFoods = listOf(" riso ", "Riso", "", "!!!"),
            dietStyle = "Nessuno",
            notes = "   ",
        )
        val clean = dirty.canonical()
        assertEquals(listOf("riso"), clean.preferredFoods)
        assertNull(clean.dietStyle)
        assertNull(clean.notes)
    }

    @Test
    fun canonical_isIdempotentSoUnchangedDraftIsNotDirty() {
        val loaded = DietaryProfile.parse(
            DietaryProfile(allergies = listOf("arachidi"), dietStyle = "Vegano", notes = "ok").toJson(),
        ).canonical()
        assertEquals(loaded, loaded.canonical())
        assertEquals(loaded, loaded.copy(notes = " ok ").canonical())
    }

    @Test
    fun hardConstraintFlag_distinguishesRigidCategoriesFromPreferences() {
        assertTrue(DietaryCategory.ALLERGY.isHardConstraint)
        assertTrue(DietaryCategory.INTOLERANCE.isHardConstraint)
        assertTrue(DietaryCategory.EXCLUDED.isHardConstraint)
        assertTrue(!DietaryCategory.PREFERRED.isHardConstraint)
        assertTrue(!DietaryCategory.DISLIKED.isHardConstraint)
    }
}
