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
}
