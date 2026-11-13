package com.myfitai.app.domain.display

import com.myfitai.app.domain.time.TimeProvider
import java.math.BigDecimal
import java.math.RoundingMode
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * Single source of the numbers shown by Home, Food and the PDF export.
 *
 * Rounding rule (the only one): half-up on the decimal value, ties away from zero, so 2067.7 -> 2068 and
 * 1757.5 -> 1758 on every screen. Italian separators (thousands "." and decimal ","); a value that rounds
 * to zero never carries a sign ("+0,0" / "-0,0" are never produced).
 */
object DisplayNumberFormatter {
    const val UNAVAILABLE = "-"
    const val MINUS = "\u2212"

    private fun toDecimal(value: Double): BigDecimal? =
        if (value.isNaN() || value.isInfinite()) null else BigDecimal(value.toString())

    private fun round(value: BigDecimal, decimals: Int): BigDecimal {
        val rounded = value.setScale(decimals, RoundingMode.HALF_UP)
        return if (rounded.signum() == 0) BigDecimal.ZERO.setScale(decimals) else rounded
    }

    private fun format(rounded: BigDecimal, decimals: Int): String {
        val pattern = if (decimals <= 0) "#,##0" else "#,##0." + "#".repeat(decimals)
        return DecimalFormat(pattern, DecimalFormatSymbols(Locale.ITALIAN)).apply {
            roundingMode = RoundingMode.HALF_UP
        }.format(rounded)
    }

    /** Whole number with thousands separator ("2.068"). */
    fun integer(value: Double): String {
        val decimal = toDecimal(value) ?: return UNAVAILABLE
        return format(round(decimal, 0), 0)
    }

    fun integer(value: Int): String = integer(value.toDouble())

    /** At most [maxDecimals] decimals, trailing zeros dropped ("5", "5,3"). */
    fun decimal(value: Double, maxDecimals: Int = 1): String {
        val decimal = toDecimal(value) ?: return UNAVAILABLE
        return format(round(decimal, maxDecimals), maxDecimals)
    }

    /** Floats are read through their shortest decimal form, so 78.45f rounds to 78,5 like the user typed it. */
    fun decimal(value: Float, maxDecimals: Int = 1): String = decimal(value.toString().toDouble(), maxDecimals)

    fun kcal(value: Double): String = if (value.isNaN() || value.isInfinite()) UNAVAILABLE else "${integer(value)} kcal"

    fun kcal(value: Int): String = "${integer(value)} kcal"

    /** "+0,5" / "−0,5" with a real minus sign; a value that rounds to zero is just "0". */
    fun signedDecimal(value: Double, maxDecimals: Int = 1): String {
        val decimal = toDecimal(value) ?: return UNAVAILABLE
        val rounded = round(decimal, maxDecimals)
        return when (rounded.signum()) {
            0 -> format(rounded, maxDecimals)
            1 -> "+" + format(rounded, maxDecimals)
            else -> MINUS + format(rounded.abs(), maxDecimals)
        }
    }

    fun signedDecimal(value: Float, maxDecimals: Int = 1): String = signedDecimal(value.toString().toDouble(), maxDecimals)

    fun signedInteger(value: Int): String = signedDecimal(value.toDouble(), 0)
}

/** How a body-metric delta is shown: direction drives sign and icon, the judgement is only a colour reinforcement. */
object MetricDeltaPresenter {
    enum class Direction { UP, DOWN, FLAT }
    enum class Judgement { POSITIVE, NEGATIVE, NEUTRAL }
    enum class Preference { NEUTRAL, DOWN_IS_POSITIVE, UP_IS_POSITIVE }

    data class Presentation(
        val direction: Direction,
        val judgement: Judgement,
        /** Signed text, e.g. "+0,5 kg" or "−1,2 %"; unsigned "0 kg" when flat. */
        val signedText: String,
        /** Unsigned amount, e.g. "0,5 kg", for the accessible sentence ("aumentato di 0,5 kg"). */
        val magnitudeText: String,
    )

    fun present(delta: Float, unit: String, preference: Preference): Presentation {
        val signed = DisplayNumberFormatter.signedDecimal(delta)
        val direction = when {
            signed.startsWith("+") -> Direction.UP
            signed.startsWith(DisplayNumberFormatter.MINUS) -> Direction.DOWN
            else -> Direction.FLAT
        }
        val judgement = when (preference) {
            Preference.NEUTRAL -> Judgement.NEUTRAL
            Preference.DOWN_IS_POSITIVE -> when (direction) {
                Direction.DOWN -> Judgement.POSITIVE
                Direction.UP -> Judgement.NEGATIVE
                Direction.FLAT -> Judgement.NEUTRAL
            }
            Preference.UP_IS_POSITIVE -> when (direction) {
                Direction.UP -> Judgement.POSITIVE
                Direction.DOWN -> Judgement.NEGATIVE
                Direction.FLAT -> Judgement.NEUTRAL
            }
        }
        return Presentation(
            direction = direction,
            judgement = judgement,
            signedText = "$signed $unit",
            magnitudeText = "${DisplayNumberFormatter.decimal(kotlin.math.abs(delta))} $unit",
        )
    }
}

/** "Rimangono" / "Oltre il target": the overshoot is never shown as a plain positive remainder. */
object RemainingKcal {
    enum class Kind { REMAINING, OVER, UNAVAILABLE }

    data class Presentation(val kind: Kind, val amountKcal: Int)

    fun of(targetKcal: Int?, consumedKcal: Int?): Presentation {
        if (targetKcal == null || consumedKcal == null || targetKcal <= 0) return Presentation(Kind.UNAVAILABLE, 0)
        val remaining = targetKcal - consumedKcal
        return if (remaining >= 0) Presentation(Kind.REMAINING, remaining) else Presentation(Kind.OVER, -remaining)
    }
}

/**
 * Maintenance used for the weekly energy-balance estimate: the profile's TDEE (BMR x activity-level factor), the
 * same figure for every day of the week.
 */
object WeeklyMaintenance {
    fun baseKcal(habitualTdeeKcal: Int?, operationalTdeeKcal: Int?): Int? =
        habitualTdeeKcal?.takeIf { it > 0 } ?: operationalTdeeKcal?.takeIf { it > 0 }
}

/**
 * Target of the day shown by Home and Food. When the weekly plan has an entry for the day it IS the target
 * (adaptation and sgarro recovery included, the same value the menu and the AI were built on); the theoretical
 * target (profile + activity, no adaptation or recovery) is the fallback and is labelled as such.
 */
object DayTargetResolver {
    enum class Source { PLAN_DAY, THEORETICAL, PLAN_WEEK, NONE }

    data class Resolved(
        val kcal: Int?,
        val proteinG: Double?,
        val source: Source,
        val theoreticalKcal: Int?,
    ) {
        /** True when the theoretical figure is worth showing next to the target (it differs from it). */
        val theoreticalDiffers: Boolean get() = source == Source.PLAN_DAY && theoreticalKcal != null && theoreticalKcal != kcal
    }

    fun resolve(
        theoreticalKcal: Double?,
        theoreticalProteinG: Double?,
        planDayKcal: Int?,
        planDayProteinG: Double?,
        planWeekKcal: Int?,
    ): Resolved {
        val theoretical = theoreticalKcal?.takeIf { it.isFinite() && it > 0.0 }?.let { Math.round(it).toInt() }
        val dayKcal = planDayKcal?.takeIf { it > 0 }
        val weekKcal = planWeekKcal?.takeIf { it > 0 }
        val (kcal, source) = when {
            dayKcal != null -> dayKcal to Source.PLAN_DAY
            theoretical != null -> theoretical to Source.THEORETICAL
            weekKcal != null -> weekKcal to Source.PLAN_WEEK
            else -> null to Source.NONE
        }
        return Resolved(
            kcal = kcal,
            proteinG = planDayProteinG?.takeIf { it > 0.0 } ?: theoreticalProteinG,
            source = source,
            theoreticalKcal = theoretical,
        )
    }
}

/** Emits today's date and again whenever the day changes, so screens do not keep the day they were created on. */
object DayChange {
    const val MAX_POLL_MILLIS = 60_000L

    fun millisUntilNextDay(nowEpochMillis: Long, zone: ZoneId): Long {
        val nextMidnight = Instant.ofEpochMilli(nowEpochMillis).atZone(zone).toLocalDate().plusDays(1)
            .atStartOfDay(zone).toInstant().toEpochMilli()
        return (nextMidnight - nowEpochMillis).coerceAtLeast(1L)
    }

    fun todayFlow(
        time: TimeProvider,
        wait: suspend (Long) -> Unit = { delay(it) },
    ): Flow<LocalDate> = flow {
        var current = time.today()
        emit(current)
        while (true) {
            // Short polling also covers manual clock / time-zone changes, not just the natural midnight.
            wait(minOf(millisUntilNextDay(time.nowEpochMillis(), time.zoneId), MAX_POLL_MILLIS))
            val now = time.today()
            if (now != current) {
                current = now
                emit(now)
            }
        }
    }
}
