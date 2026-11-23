package com.myfitai.app.data.repository

import com.myfitai.app.data.local.MyFitAiDatabase
import com.myfitai.app.data.local.entity.MoodCheckInEntity
import com.myfitai.app.data.local.entity.WeeklyDiaryNoteEntity

class EmotionalDiaryRepository(private val db: MyFitAiDatabase) {
    fun moods(profileId: Long, fromEpochDay: Long, toEpochDay: Long) =
        db.emotionalDiaryDao().observeMoods(profileId, fromEpochDay, toEpochDay)

    suspend fun mood(profileId: Long, dateEpochDay: Long) =
        db.emotionalDiaryDao().getMood(profileId, dateEpochDay)

    suspend fun upsertMood(value: MoodCheckInEntity) =
        db.emotionalDiaryDao().upsertMood(value)

    suspend fun deleteMood(profileId: Long, dateEpochDay: Long) =
        db.emotionalDiaryDao().deleteMood(profileId, dateEpochDay)

    suspend fun weeklyNote(profileId: Long, weekStartEpochDay: Long) =
        db.emotionalDiaryDao().getWeeklyNote(profileId, weekStartEpochDay)

    suspend fun upsertWeeklyNote(value: WeeklyDiaryNoteEntity) =
        db.emotionalDiaryDao().upsertWeeklyNote(value)

    suspend fun deleteWeeklyNote(profileId: Long, weekStartEpochDay: Long) =
        db.emotionalDiaryDao().deleteWeeklyNote(profileId, weekStartEpochDay)

    suspend fun deleteByProfile(profileId: Long) {
        db.emotionalDiaryDao().deleteMoodsByProfile(profileId)
        db.emotionalDiaryDao().deleteWeeklyNotesByProfile(profileId)
    }
}