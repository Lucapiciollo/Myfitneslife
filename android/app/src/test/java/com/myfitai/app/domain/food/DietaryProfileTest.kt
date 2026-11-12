package com.myfitai.app.domain.food

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DietaryProfileTest {
    @Test
    fun structuredProfile_roundTripsAndKeepsLegacyNotes() {
        val source = DietaryProfile(
            preferredFoods = listOf("riso", "pollo"),
            dislikedFoods = listOf("broccoli"),
            excludedFoods = listOf("tonno"),
            intolerances = listOf("lattosio"),
            allergies = listOf("arachidi"),
            dietStyle = "Onnivoro",
            notes = "Pranzi rapidi",
        )
        assertEquals(source, DietaryProfile.parse(source.toJson()))
        assertEquals("vecchia nota", DietaryProfile.parse("{\"notes\":\"vecchia nota\"}").notes)
    }

    @Test
    fun hardConstraints_rejectAliasesAndExplicitExclusions() {
        val profile = DietaryProfile(
            excludedFoods = listOf("tonno"),
            intolerances = listOf("lattosio"),
            allergies = listOf("arachidi"),
        )
        val violations = FoodConstraintValidator.validateIngredientNames(
            listOf("Yogurt greco", "Burro di arachidi", "Tonno al naturale", "Riso basmati"),
            profile,
        )
        assertTrue(violations.any { it.kind == "INTOLERANCE" && it.ingredient.contains("Yogurt") })
        assertTrue(violations.any { it.kind == "ALLERGY" && it.ingredient.contains("arachidi") })
        assertTrue(violations.any { it.kind == "EXCLUDED" && it.ingredient.contains("Tonno") })
        assertTrue(violations.none { it.ingredient.contains("Riso") })
    }

    @Test
    fun dietStyle_isHardConstraint() {
        val violations = FoodConstraintValidator.validateIngredientNames(
            listOf("Petto di pollo", "Tofu"),
            DietaryProfile(dietStyle = "Vegetariano"),
        )
        assertEquals(1, violations.size)
        assertEquals("DIET_STYLE", violations.single().kind)
    }

    @Test
    fun blankOrMissingJson_meansNoConstraintsAndIsReadable() {
        listOf(null, "", "   ").forEach { raw ->
            val profile = DietaryProfile.parse(raw)
            assertEquals(DietaryProfile.Status.OK, profile.status)
            assertTrue(profile.allergies.isEmpty() && !FoodConstraintValidator.hasHardConstraints(profile))
        }
    }

    @Test
    fun corruptedJson_isExplicitlyUnreadableInsteadOfSilentlyEmpty() {
        listOf("{\"allergies\":[\"glut", "senza glutine", "[1,2]", "{\"allergies\":{\"a\":1}}", "{\"allergies\":[{\"x\":1}]}", "{\"excludedFoods\":5}")
            .forEach { raw ->
                val profile = DietaryProfile.parse(raw)
                assertEquals(raw, DietaryProfile.Status.UNREADABLE, profile.status)
                assertTrue(raw, profile.isUnreadable)
                assertTrue(FoodConstraintValidator.hasHardConstraints(profile))
            }
        assertEquals("senza glutine", DietaryProfile.parse("senza glutine").notes)
    }

    @Test
    fun unreadableProfile_failsClosedWithStableCode() {
        val profile = DietaryProfile.parse("{broken")
        val violations = FoodConstraintValidator.validateIngredientNames(listOf("Riso"), profile)
        assertEquals("PROFILE_CONSTRAINTS_UNREADABLE", violations.single().kind)
        assertTrue(FoodConstraintValidator.validateIngredientNames(emptyList(), profile).isNotEmpty())
        val failure = FoodConstraintValidator.validateAlternative(
            MealAlternativeContract.Alternative("Riso", 300, 10f, 50f, 5f, "", "", emptyList()), profile,
        ).exceptionOrNull()
        assertEquals("PROFILE_CONSTRAINTS_UNREADABLE", failure?.message)
        assertTrue(!DietaryProfile.UNREADABLE_MESSAGE.contains(DietaryProfile.UNREADABLE_CODE))
    }

    @Test
    fun legacyShapes_stayReadable() {
        val csvStrings = DietaryProfile.parse("{\"allergies\":\"glutine, lattosio\",\"style\":\"onnivoro\"}")
        assertEquals(DietaryProfile.Status.OK, csvStrings.status)
        assertEquals(listOf("glutine", "lattosio"), csvStrings.allergies)
        assertEquals(DietaryProfile.Status.OK, DietaryProfile.parse("{\"style\":\"onnivoro\"}").status)
        assertEquals(DietaryProfile.Status.OK, DietaryProfile.parse("{\"allergies\":null,\"dietStyle\":null}").status)
    }

    @Test
    fun legacyFreeText_staysUnreadableButExposesTheOriginalText() {
        val profile = DietaryProfile.parse("  Allergico alle noci, niente lattosio ")
        assertTrue(profile.isUnreadable)
        assertEquals("Allergico alle noci, niente lattosio", profile.rawLegacyText)
        assertEquals("Allergico alle noci, niente lattosio", profile.notes)
        assertTrue(profile.allergies.isEmpty() && profile.hardConstraints.isEmpty())
        assertTrue(FoodConstraintValidator.hasHardConstraints(profile))
        assertTrue(FoodConstraintValidator.validateIngredientNames(listOf("Riso"), profile).isNotEmpty())
        assertEquals("{\"allergies\":{\"a\":1}}", DietaryProfile.parse("{\"allergies\":{\"a\":1}}").rawLegacyText)
        assertEquals(null, DietaryProfile.parse("{\"allergies\":[\"glutine\"]}").rawLegacyText)
        assertEquals(null, DietaryProfile.parse(null).rawLegacyText)
        assertTrue(!profile.toJson().contains("rawLegacyText"))
    }

    @Test
    fun legacyStyleKey_isReadAsDietStyle_butOnnivoroStaysNoRestriction() {
        assertEquals("vegano", DietaryProfile.parse("{\"style\":\"vegano\"}").dietStyle)
        assertEquals(null, DietaryProfile.parse("{\"style\":\"onnivoro\"}").dietStyle)
        assertEquals("Vegetariano", DietaryProfile.parse("{\"dietStyle\":\"Vegetariano\",\"style\":\"vegano\"}").dietStyle)
        assertTrue(!FoodConstraintValidator.hasHardConstraints(DietaryProfile(dietStyle = "Onnivoro")))
        assertTrue(FoodConstraintValidator.hasHardConstraints(DietaryProfile(dietStyle = "Vegano")))
    }

    @Test
    fun constraintNotices_reportWhatTheListsCannotFullyVerify() {
        val profile = DietaryProfile(
            allergies = listOf("glutine", "kiwi giallo", "nichel"),
            intolerances = listOf("latte e derivati", "solfiti"),
            excludedFoods = listOf("tonno", "nichel"),
            dietStyle = "Paleo",
        )
        val notices = profile.constraintNotices.map { Triple(it.constraint, it.category, it.reason) }
        assertTrue(notices.contains(Triple("kiwi giallo", DietaryProfile.NoticeCategory.ALLERGY, DietaryProfile.NoticeReason.NAME_ONLY)))
        assertTrue(notices.contains(Triple("nichel", DietaryProfile.NoticeCategory.ALLERGY, DietaryProfile.NoticeReason.LIMITED)))
        assertTrue(notices.contains(Triple("solfiti", DietaryProfile.NoticeCategory.INTOLERANCE, DietaryProfile.NoticeReason.LIMITED)))
        assertTrue(notices.contains(Triple("nichel", DietaryProfile.NoticeCategory.EXCLUDED, DietaryProfile.NoticeReason.LIMITED)))
        assertTrue(notices.contains(Triple("Paleo", DietaryProfile.NoticeCategory.DIET_STYLE, DietaryProfile.NoticeReason.UNKNOWN_STYLE)))
        assertTrue(notices.none { it.first == "glutine" || it.first == "latte e derivati" || it.first == "tonno" })
        assertEquals(emptyList<DietaryProfile.ConstraintNotice>(), DietaryProfile(allergies = listOf("lattosio"), dietStyle = "Vegano").constraintNotices)
        assertEquals(emptyList<DietaryProfile.ConstraintNotice>(), DietaryProfile().constraintNotices)
    }

    @Test
    fun nickelAndSulphites_areDeclaredLimitsFoundOnlyByTheirOwnWord() {
        val profile = DietaryProfile(allergies = listOf("nichel", "solfiti"))
        val found = FoodConstraintValidator.validateIngredientNames(listOf("Cacao al nichel", "Vino con solfiti", "Riso", "Lenticchie"), profile)
        assertEquals(setOf("Cacao al nichel", "Vino con solfiti"), found.map { it.ingredient }.toSet())
    }

    @Test
    fun userMessage_hasNoMachineCode_andCodeLivesInTheExceptionField() {
        assertTrue(!DietaryProfile.UNREADABLE_MESSAGE.contains("PROFILE_CONSTRAINTS"))
        assertEquals("PROFILE_CONSTRAINTS_UNREADABLE", DietaryProfile.UNREADABLE_CODE)
        assertTrue(DietaryProfile.LIMITS_NOTICE.contains("non garanzia medica"))
        val generation = NutritionPlanGenerationService.GenerationException.ConstraintsUnreadable()
        assertEquals(DietaryProfile.UNREADABLE_CODE, generation.code)
        assertEquals(DietaryProfile.UNREADABLE_MESSAGE, generation.message)
        val alternative = MealAlternativeService.AlternativeException.ConstraintsUnreadable()
        assertEquals(DietaryProfile.UNREADABLE_CODE, alternative.code)
        assertEquals(DietaryProfile.UNREADABLE_MESSAGE, alternative.message)
    }

    @Test
    fun weeklyPlanHandler_turnsUnreadableConstraintsIntoAFinalClearFailure() {
        val outcome = WeeklyPlanAiJobHandler.unreadableConstraintsOutcome(
            NutritionPlanGenerationService.GenerationException.ConstraintsUnreadable(),
        )
        assertEquals(DietaryProfile.UNREADABLE_MESSAGE, outcome.message)
        assertTrue(!outcome.message.contains(DietaryProfile.UNREADABLE_CODE))
    }

    @Test
    fun validator_checksTitlesWithStrongTermsOnly_andNamesInFull() {
        val gluten = DietaryProfile(intolerances = listOf("glutine"))
        assertTrue(FoodConstraintValidator.validateIngredientNames(listOf("Pasta di riso"), gluten, titles = listOf("Pasta al pomodoro")).isEmpty())
        assertTrue(FoodConstraintValidator.validateIngredientNames(listOf("Riso"), gluten, titles = listOf("Seitan alla piastra")).isNotEmpty())
        assertTrue(FoodConstraintValidator.validateIngredientNames(listOf("Pasta"), gluten).isNotEmpty())
    }

    @Test
    fun statusIsNotPersisted() {
        assertTrue(!DietaryProfile(allergies = listOf("glutine")).toJson().contains("status"))
    }
}