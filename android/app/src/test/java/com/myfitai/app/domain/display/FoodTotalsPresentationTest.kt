package com.myfitai.app.domain.display

import java.io.File
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** UX5b: origine unica dell'obiettivo in Cibo, nota "piano da aggiornare", tocco rapido solo oggi/passato. */
class FoodTotalsPresentationTest {
    private fun resolved(source: DayTargetResolver.Source, kcal: Int?, profile: Int?) =
        DayTargetResolver.Resolved(kcal = kcal, proteinG = null, source = source, theoreticalKcal = profile)

    @Test
    fun planDay_notPending_isPlanWithProfileFigureOnlyWhenItDiffers() {
        val differs = FoodTotalsPresentation.origin(resolved(DayTargetResolver.Source.PLAN_DAY, 1800, 2000), planUpdatePending = false)
        assertEquals(FoodTotalsPresentation.Origin.Kind.PLAN_DAY, differs.kind)
        assertEquals(2000, differs.profileKcal)
        val same = FoodTotalsPresentation.origin(resolved(DayTargetResolver.Source.PLAN_DAY, 1800, 1800), planUpdatePending = false)
        assertEquals(FoodTotalsPresentation.Origin.Kind.PLAN_DAY, same.kind)
        assertNull(same.profileKcal)
    }

    @Test
    fun planDay_pending_saysPreviousPlanAndShowsProfileFigureWhenItDiffers() {
        val differs = FoodTotalsPresentation.origin(resolved(DayTargetResolver.Source.PLAN_DAY, 1800, 2000), planUpdatePending = true)
        assertEquals(FoodTotalsPresentation.Origin.Kind.PLAN_STALE, differs.kind)
        assertEquals(2000, differs.profileKcal)
        val same = FoodTotalsPresentation.origin(resolved(DayTargetResolver.Source.PLAN_DAY, 1800, 1800), planUpdatePending = true)
        assertEquals(FoodTotalsPresentation.Origin.Kind.PLAN_STALE, same.kind)
        assertNull(same.profileKcal)
        val noProfile = FoodTotalsPresentation.origin(resolved(DayTargetResolver.Source.PLAN_DAY, 1800, null), planUpdatePending = true)
        assertEquals(FoodTotalsPresentation.Origin.Kind.PLAN_STALE, noProfile.kind)
        assertNull(noProfile.profileKcal)
    }

    @Test
    fun planWeek_pending_isStaleToo() {
        val origin = FoodTotalsPresentation.origin(resolved(DayTargetResolver.Source.PLAN_WEEK, 1900, 2100), planUpdatePending = true)
        assertEquals(FoodTotalsPresentation.Origin.Kind.PLAN_STALE, origin.kind)
        assertEquals(2100, origin.profileKcal)
        assertEquals(
            FoodTotalsPresentation.Origin.Kind.PLAN_WEEK,
            FoodTotalsPresentation.origin(resolved(DayTargetResolver.Source.PLAN_WEEK, 1900, 2100), planUpdatePending = false).kind,
        )
    }

    @Test
    fun profileOrNoTarget_neverStale() {
        val profile = FoodTotalsPresentation.origin(resolved(DayTargetResolver.Source.THEORETICAL, 2000, 2000), planUpdatePending = true)
        assertEquals(FoodTotalsPresentation.Origin.Kind.PROFILE, profile.kind)
        assertNull(profile.profileKcal)
        val none = FoodTotalsPresentation.origin(resolved(DayTargetResolver.Source.NONE, null, null), planUpdatePending = true)
        assertEquals(FoodTotalsPresentation.Origin.Kind.NONE, none.kind)
        assertNull(none.profileKcal)
    }

    @Test
    fun quickConsume_onlyTodayAndPast() {
        val today = LocalDate.of(2026, 11, 13)
        assertTrue(FoodTotalsPresentation.quickConsumeAvailable(today.minusDays(3).toEpochDay(), today))
        assertTrue(FoodTotalsPresentation.quickConsumeAvailable(today.toEpochDay(), today))
        assertFalse(FoodTotalsPresentation.quickConsumeAvailable(today.plusDays(1).toEpochDay(), today))
        assertFalse(FoodTotalsPresentation.quickConsumeAvailable(today.plusDays(6).toEpochDay(), today))
    }

    @Test
    fun planUpdateNote_requiresPendingFlagAndAPlanTarget() {
        assertTrue(FoodTotalsPresentation.planUpdateNoteVisible(planUpdatePending = true, hasPlanTarget = true))
        assertFalse(FoodTotalsPresentation.planUpdateNoteVisible(planUpdatePending = true, hasPlanTarget = false))
        assertFalse(FoodTotalsPresentation.planUpdateNoteVisible(planUpdatePending = false, hasPlanTarget = true))
    }

    @Test
    fun ux5bStrings_useTheGlossaryAndKeepStaleWording() {
        val resDir = listOf(File("src/main/res"), File("app/src/main/res")).first { it.isDirectory }
        val xml = File(resDir, "values/strings.xml").readText()
        val s = Regex("""<string name="([^"]+)"[^>]*>(.*?)</string>""", RegexOption.DOT_MATCHES_ALL)
            .findAll(xml).associate { it.groupValues[1] to it.groupValues[2] }
        assertEquals("Obiettivo da profilo: %1\$s", s["food_ux5b_profile_target"])
        assertEquals("Il piano è precedente al cambio di profilo", s["weekly_review_ux5b_plan_stale_note"])
        assertEquals("Piano non aggiornato al profilo attuale", s["export_ux5b_plan_stale_note"])
        assertTrue(s.getValue("food_ux5b_origin_profile").contains("da profilo"))
    }
}
