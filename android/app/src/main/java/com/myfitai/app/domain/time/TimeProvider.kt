package com.myfitai.app.domain.time

import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/** Production time source with a deterministic seam for historical tests. */
interface TimeProvider {
    val zoneId: ZoneId
    fun nowEpochMillis(): Long

    fun today(): LocalDate = java.time.Instant.ofEpochMilli(nowEpochMillis()).atZone(zoneId).toLocalDate()

    fun currentTime(): LocalTime = java.time.Instant.ofEpochMilli(nowEpochMillis()).atZone(zoneId).toLocalTime()
}

object SystemTimeProvider : TimeProvider {
    override val zoneId: ZoneId = ZoneId.systemDefault()
    override fun nowEpochMillis(): Long = System.currentTimeMillis()
}
