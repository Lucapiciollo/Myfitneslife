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
        assertTrue(DietaryProfile.UNREADABLE_MESSAGE.startsWith("PROFILE_CONSTRAINTS_UNREADABLE:"))
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
    fun statusIsNotPersisted() {
        assertTrue(!DietaryProfile(allergies = listOf("glutine")).toJson().contains("status"))
    }
}