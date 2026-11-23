package com.myfitai.app.domain.diary

import com.myfitai.app.data.local.entity.MoodCheckInEntity
import com.myfitai.app.data.local.entity.WeeklyDiaryNoteEntity
import com.myfitai.app.data.repository.EmotionalDiaryRepository
import java.time.Clock
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters

enum class MoodLevel(val storageKey: String, val label: String, val emoji: String) {
    VERY_BAD("VERY_BAD", "Molto male", "😞"),
    BAD("BAD", "Male", "🙁"),
    NEUTRAL("NEUTRAL", "Neutro", "😐"),
    GOOD("GOOD", "Bene", "🙂"),
    VERY_GOOD("VERY_GOOD", "Molto bene", "😄");

    companion object {
        fun fromStorageKey(key: String?): MoodLevel? = entries.firstOrNull { it.storageKey == key }
    }
}

data class MoodDisplay(val mood: MoodLevel?, val automatic: Boolean)

object EmotionalDiaryRules {
    fun weekStartEpochDay(dateEpochDay: Long): Long =
        LocalDate.ofEpochDay(dateEpochDay)
            .with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
            .toEpochDay()

    fun displayFor(dateEpochDay: Long, todayEpochDay: Long, storedMoodKey: String?): MoodDisplay {
        val savedMood = MoodLevel.fromStorageKey(storedMoodKey)
        if (savedMood != null) return MoodDisplay(savedMood, automatic = false)
        return if (dateEpochDay == todayEpochDay) {
            MoodDisplay(MoodLevel.VERY_BAD, automatic = true)
        } else {
            MoodDisplay(mood = null, automatic = false)
        }
    }
}

class EmotionalDiaryService(
    private val repository: EmotionalDiaryRepository,
    private val clock: Clock = Clock.systemDefaultZone(),
) {
    val todayEpochDay: Long get() = LocalDate.now(clock).toEpochDay()

    fun moodsForWeek(profileId: Long, weekStartEpochDay: Long) =
        repository.moods(profileId, weekStartEpochDay, weekStartEpochDay + 6)

    suspend fun moodForDay(profileId: Long, dateEpochDay: Long): MoodCheckInEntity? =
        repository.mood(profileId, dateEpochDay)

    suspend fun weeklyNote(profileId: Long, weekStartEpochDay: Long): WeeklyDiaryNoteEntity? =
        repository.weeklyNote(profileId, weekStartEpochDay)

    suspend fun saveMood(profileId: Long, dateEpochDay: Long, mood: MoodLevel) {
        require(profileId > 0) { "Profilo non valido" }
        require(dateEpochDay <= todayEpochDay) { "Non puoi registrare l'umore di un giorno futuro" }
        val now = clock.millis()
        val existing = repository.mood(profileId, dateEpochDay)
        repository.upsertMood(
            MoodCheckInEntity(
                id = existing?.id ?: 0,
                profileId = profileId,
                dateEpochDay = dateEpochDay,
                mood = mood.storageKey,
                recordedAtEpochMillis = existing?.recordedAtEpochMillis ?: now,
                updatedAtEpochMillis = now,
            ),
        )
    }

    suspend fun deleteMood(profileId: Long, dateEpochDay: Long) {
        require(profileId > 0) { "Profilo non valido" }
        repository.deleteMood(profileId, dateEpochDay)
    }

    suspend fun saveWeeklyNote(profileId: Long, weekStartEpochDay: Long, note: String) {
        require(profileId > 0) { "Profilo non valido" }
        require(EmotionalDiaryRules.weekStartEpochDay(weekStartEpochDay) == weekStartEpochDay) {
            "La settimana deve iniziare di lunedì"
        }
        val text = note.trim()
        if (text.isEmpty()) {
            repository.deleteWeeklyNote(profileId, weekStartEpochDay)
            return
        }
        val now = clock.millis()
        repository.upsertWeeklyNote(
            WeeklyDiaryNoteEntity(
                id = repository.weeklyNote(profileId, weekStartEpochDay)?.id ?: 0,
                profileId = profileId,
                weekStartEpochDay = weekStartEpochDay,
                note = text,
                updatedAtEpochMillis = now,
            ),
        )
    }

    suspend fun deleteWeeklyNote(profileId: Long, weekStartEpochDay: Long) {
        require(profileId > 0) { "Profilo non valido" }
        repository.deleteWeeklyNote(profileId, weekStartEpochDay)
    }
}