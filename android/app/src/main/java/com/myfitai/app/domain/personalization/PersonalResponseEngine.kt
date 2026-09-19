package com.myfitai.app.domain.personalization

import com.myfitai.app.data.local.entity.BiaMeasurementEntity
import com.myfitai.app.data.local.entity.BodyMeasurementEntity
import com.myfitai.app.data.local.entity.CheatEntryEntity
import com.myfitai.app.data.local.entity.WorkoutEntity
import com.myfitai.app.domain.food.FoodPlanSnapshot
import kotlin.math.roundToInt

/** Deterministic, local-only synthesis of recent history. Associations are descriptive only. */
object PersonalResponseEngine {
    data class Input(
        val plans: List<FoodPlanSnapshot>,
        val planVersionReasons: List<String?> = emptyList(),
        val cheats: List<CheatEntryEntity>,
        val workouts: List<WorkoutEntity>,
        val bia: List<BiaMeasurementEntity>,
        val bodyMeasurements: List<BodyMeasurementEntity>,
        val nowEpochMillis: Long,
        val lookbackDays: Int = 56,
    )

    data class Pattern(val code: String, val evidenceCount: Int, val text: String)

    data class Summary(
        val lookbackDays: Int,
        val planWeeks: Int,
        val planVersions: Int,
        val cheatCount: Int,
        val workoutCount: Int,
        val restDayCount: Int,
        val weightDeltaKg: Double?,
        val bodyFatDeltaPoints: Double?,
        val muscleMassDeltaKg: Double?,
        val waistDeltaCm: Double?,
        val patterns: List<Pattern>,
    ) {
        fun toPromptContext(maxChars: Int = 900): String {
            val text = buildString {
                append("PH:").append(lookbackDays).append(';').append(planWeeks).append(';').append(planVersions)
                    .append(';').append(cheatCount).append(';').append(workoutCount).append(';').append(restDayCount).append('\n')
                append("PD:").append(weightDeltaKg.formatOrNA()).append(';').append(bodyFatDeltaPoints.formatOrNA())
                    .append(';').append(muscleMassDeltaKg.formatOrNA()).append(';').append(waistDeltaCm.formatOrNA())
                patterns.forEach { append("\nPP:").append(it.code).append(';').append(it.evidenceCount).append(';').append(it.text.replace('\n', ' ').take(140)) }
                append("\nSAFE:never causal;Do not alter local numerical targets")
            }
            return text.take(maxChars.coerceAtLeast(256))
        }
    }

    fun analyze(input: Input): Summary {
        require(input.lookbackDays in 7..365)
        val from = input.nowEpochMillis - input.lookbackDays * DAY_MS
        val recentCheats = input.cheats.filter { it.occurredAtEpochMillis in from..input.nowEpochMillis }
        val recentWorkouts = input.workouts.filter { it.startedAtEpochMillis in from..input.nowEpochMillis }
        val recentBia = input.bia.filter { it.measuredAtEpochMillis in from..input.nowEpochMillis }
            .sortedWith(compareBy<BiaMeasurementEntity> { it.measuredAtEpochMillis }.thenBy { it.id })
        val recentBody = input.bodyMeasurements.filter { it.measuredAtEpochMillis in from..input.nowEpochMillis }
            .sortedWith(compareBy<BodyMeasurementEntity> { it.measuredAtEpochMillis }.thenBy { it.id })
        val plans = input.plans.filter { snapshot ->
            val weekMillisApprox = snapshot.weekStartEpochDay * DAY_MS
            weekMillisApprox <= input.nowEpochMillis && weekMillisApprox + 7 * DAY_MS >= from
        }

        val patterns = buildList {
            deviationPattern(recentCheats)?.let(::add)
            workoutPattern(recentWorkouts)?.let(::add)
            adaptationPattern(input.planVersionReasons)?.let(::add)
            simultaneousTrendPattern(recentBia, recentBody)?.let(::add)
        }

        return Summary(
            lookbackDays = input.lookbackDays,
            planWeeks = plans.map { it.weekStartEpochDay }.distinct().size,
            planVersions = input.planVersionReasons.size,
            cheatCount = recentCheats.size,
            workoutCount = recentWorkouts.count { !it.isRestDay },
            restDayCount = recentWorkouts.count { it.isRestDay },
            weightDeltaKg = delta(recentBia.mapNotNull { it.weightKg?.toDouble() }),
            bodyFatDeltaPoints = delta(recentBia.mapNotNull { it.bodyFatPercent?.toDouble() }),
            muscleMassDeltaKg = delta(recentBia.mapNotNull { it.muscleMassKg?.toDouble() }),
            waistDeltaCm = delta(recentBody.mapNotNull { it.waistCm?.toDouble() }),
            patterns = patterns,
        )
    }

    private fun deviationPattern(cheats: List<CheatEntryEntity>): Pattern? {
        if (cheats.size < 3) return null
        val avgKcal = cheats.mapNotNull { it.estimatedKcal }.takeIf { it.size >= 2 }?.average()?.roundToInt()
        return Pattern("RECURRENT_DEVIATIONS", cheats.size, buildString {
            append("deviazioni=").append(cheats.size)
            if (avgKcal != null) append(";avgKcal≈").append(avgKcal)
        })
    }

    private fun workoutPattern(workouts: List<WorkoutEntity>): Pattern? {
        val sessions = workouts.filter { !it.isRestDay }
        if (sessions.size < 3) return null
        val mostFrequent = sessions.groupingBy { it.type.trim().ifBlank { "Altro" } }.eachCount().maxByOrNull { it.value }
        val averageDuration = sessions.mapNotNull { it.durationMinutes }.filter { it > 0 }.takeIf { it.size >= 2 }?.average()?.roundToInt()
        return Pattern("TRAINING_ROUTINE", sessions.size, buildString {
            append("sessions=").append(sessions.size)
            mostFrequent?.let { append(";type=").append(it.key).append(':').append(it.value) }
            averageDuration?.let { append(";avgMin=").append(it) }
        })
    }

    private fun adaptationPattern(versionReasons: List<String?>): Pattern? {
        val adapted = versionReasons.count { it?.startsWith("CHEAT_ADAPTATION:") == true }
        return if (adapted >= 2) Pattern("REPEATED_PLAN_ADAPTATION", adapted, "adaptations=$adapted") else null
    }

    private fun simultaneousTrendPattern(bia: List<BiaMeasurementEntity>, body: List<BodyMeasurementEntity>): Pattern? {
        val weight = delta(bia.mapNotNull { it.weightKg?.toDouble() })
        val fat = delta(bia.mapNotNull { it.bodyFatPercent?.toDouble() })
        val muscle = delta(bia.mapNotNull { it.muscleMassKg?.toDouble() })
        val waist = delta(body.mapNotNull { it.waistCm?.toDouble() })
        if (listOf(weight, fat, muscle, waist).count { it != null } < 2) return null
        val evidence = maxOf(bia.size, body.size)
        if (evidence < 2) return null
        return Pattern("SIMULTANEOUS_BODY_TRENDS", evidence, "w=${weight.formatOrNA()};bf=${fat.formatOrNA()};m=${muscle.formatOrNA()};waist=${waist.formatOrNA()}")
    }

    private fun delta(values: List<Double>): Double? = if (values.size >= 2) values.last() - values.first() else null
    private fun Double?.formatOrNA(): String = this?.let { String.format(java.util.Locale.US, "%.2f", it) } ?: "?"
    private const val DAY_MS = 86_400_000L
}
