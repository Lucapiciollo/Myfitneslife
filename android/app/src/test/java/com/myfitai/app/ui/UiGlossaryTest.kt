package com.myfitai.app.ui

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * UX3: vocabolario unico (docs/GLOSSARIO_UI.md). Lista esplicita e limitata di stringhe chiave e di termini
 * vietati nei testi UI. Non tocca ids, route, colonne Room, JSON e contratti IA.
 */
class UiGlossaryTest {
    private val resDir: File = listOf(File("src/main/res"), File("app/src/main/res")).first { it.isDirectory }

    private fun stringValues(): Map<String, String> {
        val xml = File(resDir, "values/strings.xml").readText()
        return Regex("""<string name="([^"]+)"[^>]*>(.*?)</string>""", RegexOption.DOT_MATCHES_ALL)
            .findAll(xml).associate { it.groupValues[1] to it.groupValues[2] }
    }

    private fun layoutTexts(vararg layouts: String): List<Pair<String, String>> = layouts.flatMap { name ->
        Regex("""android:(?:text|contentDescription)="([^"]*)"""")
            .findAll(File(resDir, "layout/$name").readText()).map { name to it.groupValues[1] }.toList()
    }

    @Test
    fun keyStringsUseTheGlossary() {
        val s = stringValues()
        assertEquals("Metabolismo a riposo (BMR)", s["home_calories_bmr_local"])
        assertEquals("Metabolismo a riposo da BIA (BMR)", s["home_calories_bmr_bia_reference"])
        assertEquals("Obiettivo di oggi", s["food_target_label_plan_day"])
        assertEquals("Obiettivo di oggi (da profilo)", s["food_target_label_theoretical"])
        assertEquals("Obiettivo del giorno", s["food_target_label_plan_day_other"])
        assertEquals("Obiettivo del giorno (da profilo)", s["food_target_label_theoretical_other"])
        assertTrue(s.getValue("target_source_plan_day").startsWith("Obiettivo di oggi dal piano"))
        assertTrue(s.getValue("target_source_theoretical").startsWith("Obiettivo da profilo"))
        assertTrue(s.getValue("food_target_description").contains("obiettivo da profilo"))
        // The stale-plan wording of the recent fix is kept.
        assertEquals("piano da aggiornare", s["home_mode_plan_stale"])
        assertTrue(s.getValue("target_source_plan_stale").contains("piano precedente"))
    }

    @Test
    fun oldTermsAreGoneFromTheStringResources() {
        val forbidden = listOf(
            Regex("""\bTarget\b"""), Regex("""\btarget\b"""), Regex("""\bteorico\b""", RegexOption.IGNORE_CASE),
            Regex("""\bextra\b""", RegexOption.IGNORE_CASE), Regex("""\bBMR locale\b"""),
        )
        val offenders = stringValues().filter { (_, v) -> forbidden.any { it.containsMatchIn(v) } }
        assertTrue("Termini vecchi in strings.xml: ${offenders.keys}", offenders.isEmpty())
    }

    @Test
    fun homeAndFoodLayoutsUseTheGlossary() {
        val texts = layoutTexts("activity_home.xml", "activity_food_plan.xml", "activity_weekly_review.xml")
        val forbidden = listOf(Regex("""\bTarget\b"""), Regex("""\btarget\b"""), Regex("""\bextra\b""", RegexOption.IGNORE_CASE))
        val offenders = texts.filter { (_, t) -> forbidden.any { it.containsMatchIn(t) } }
        assertTrue("Termini vecchi nei layout: $offenders", offenders.isEmpty())
        // TDEE and BMR always come with the Italian name first.
        texts.filter { (_, t) -> t.contains("TDEE") }.forEach { (n, t) -> assertTrue("$n: $t", t.contains("Consumo giornaliero (TDEE)") || t.contains("consumo giornaliero")) }
        texts.filter { (_, t) -> Regex("""\bBMR\b""").containsMatchIn(t) }.forEach { (n, t) -> assertTrue("$n: $t", t.contains("Metabolismo a riposo (BMR)")) }
        val all = texts.map { it.second }
        assertTrue(all.contains("Obiettivo di oggi"))
        assertTrue(all.contains("Consumo giornaliero (TDEE)"))
        assertTrue(all.contains("Inserisci sgarro"))
        assertTrue(all.contains("Sgarro"))
    }
}
