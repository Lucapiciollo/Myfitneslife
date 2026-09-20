package com.myfitai.app.domain.body

import com.myfitai.app.data.local.entity.BiaMeasurementEntity
import com.myfitai.app.data.local.entity.BodyMeasurementEntity
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * Links historical body measures to a BIA weight only when both were recorded on
 * the same calendar date. This is a read-only projection: no BIA entry or body row
 * is changed, and today's weight is never copied onto older measurements.
 */
object BodyWeightHistory {
    data class DisplayWeight(val kg: Float, val fromBia: Boolean)

    fun weightFor(
        measurement: BodyMeasurementEntity,
        biaHistory: List<BiaMeasurementEntity>,
        biaZone: ZoneId = ZoneId.systemDefault(),
    ): DisplayWeight? {
        measurement.weightKg?.takeIf(::valid)?.let { return DisplayWeight(it, fromBia = false) }
        val date = bodyDate(measurement.measuredAtEpochMillis)
        val matchingBia = biaHistory.asSequence()
            .filter { it.weightKg?.let(::valid) == true }
            .filter {
                Instant.ofEpochMilli(it.measuredAtEpochMillis)
                    .atZone(biaZone).toLocalDate() == date
            }
            .maxByOrNull { it.measuredAtEpochMillis }
        return matchingBia?.weightKg?.let { DisplayWeight(it, fromBia = true) }
    }

    private fun bodyDate(timestamp: Long): LocalDate =
        Instant.ofEpochMilli(timestamp).atZone(ZoneOffset.UTC).toLocalDate()

    private fun valid(value: Float): Boolean = value.isFinite() && value > 0f
}
