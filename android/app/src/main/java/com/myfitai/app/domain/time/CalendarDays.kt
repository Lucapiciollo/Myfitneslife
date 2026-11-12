package com.myfitai.app.domain.time

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/**
 * UNICO modo di contare i "giorni" tra due istanti: giorni di calendario nel fuso indicato.
 * Dividere i millisecondi per 86_400_000 sbaglia di un'ora (quindi di un giorno con troncamento o con
 * confronti su soglie) a cavallo del cambio ora legale: 25 ottobre (fine) e 29 marzo (inizio) in Europa.
 */
object CalendarDays {
    fun dateOf(epochMillis: Long, zone: ZoneId = ZoneId.systemDefault()): LocalDate =
        Instant.ofEpochMilli(epochMillis).atZone(zone).toLocalDate()

    /** Giorni di calendario da [fromEpochMillis] a [toEpochMillis] (negativo se invertiti). */
    fun between(fromEpochMillis: Long, toEpochMillis: Long, zone: ZoneId = ZoneId.systemDefault()): Long =
        ChronoUnit.DAYS.between(dateOf(fromEpochMillis, zone), dateOf(toEpochMillis, zone))
}
