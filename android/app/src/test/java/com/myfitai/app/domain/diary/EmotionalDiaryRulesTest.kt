package com.myfitai.app.domain.diary

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EmotionalDiaryRulesTest {
    @Test
    fun weekStart_usesMondayForEveryDayOfWeek() {
        val monday = LocalDate.of(2026, 11, 23).toEpochDay()
        val sunday = LocalDate.of(2026, 11, 29).toEpochDay()

        assertEquals(monday, EmotionalDiaryRules.weekStartEpochDay(monday))
        assertEquals(monday, EmotionalDiaryRules.weekStartEpochDay(sunday))
    }

    @Test
    fun missingMood_defaultsOnlyForTodayWithoutCreatingAResponse() {
        val today = LocalDate.of(2026, 11, 23).toEpochDay()

        assertEquals(MoodDisplay(MoodLevel.VERY_BAD, automatic = true), EmotionalDiaryRules.displayFor(today, today, null))
        assertEquals(MoodDisplay(mood = null, automatic = false), EmotionalDiaryRules.displayFor(today - 1, today, null))
        assertEquals(MoodDisplay(MoodLevel.GOOD, automatic = false), EmotionalDiaryRules.displayFor(today, today, MoodLevel.GOOD.storageKey))
    }

    @Test
    fun moodOptions_haveUniqueStableStorageKeys() {
        assertEquals(5, MoodLevel.entries.size)
        assertEquals(5, MoodLevel.entries.map { it.storageKey }.toSet().size)
        assertTrue(MoodLevel.entries.all { MoodLevel.fromStorageKey(it.storageKey) == it })
        assertNull(MoodLevel.fromStorageKey("UNKNOWN"))
    }
}