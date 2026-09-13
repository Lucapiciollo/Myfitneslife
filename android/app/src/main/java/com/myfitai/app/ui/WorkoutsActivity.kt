package com.myfitai.app.ui

import android.os.Bundle
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.myfitai.app.R
import com.myfitai.app.data.AppDataContainer
import com.myfitai.app.data.local.entity.WorkoutEntity
import com.myfitai.app.navigation.BottomNavBinder
import com.myfitai.app.ui.widgets.WeekDaySelectorView
import com.myfitai.app.ui.widgets.WorkoutRowView
import com.myfitai.app.ui.workout.WorkoutViewModel
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAdjusters
import java.util.Locale

class WorkoutsActivity : BaseShellActivity() {

    private val data by lazy { AppDataContainer.get(this) }
    private val viewModel: WorkoutViewModel by viewModels {
        WorkoutViewModel.Factory(data.workoutRepository, data.activeProfileStore)
    }

    private var workouts: List<WorkoutEntity> = emptyList()
    private var weekStart: LocalDate = LocalDate.now().with(TemporalAdjusters.previousOrSame(java.time.DayOfWeek.MONDAY))
    private var selectedDayIndex: Int = (LocalDate.now().dayOfWeek.value - 1).coerceIn(0, 6)
    private val zone: ZoneId = ZoneId.systemDefault()
    private val dayFormatter = DateTimeFormatter.ofPattern("EEE d MMM", Locale.ITALIAN)
    private val weekFormatter = DateTimeFormatter.ofPattern("d MMM", Locale.ITALIAN)
    private val timeFormatter = DateTimeFormatter.ofPattern("HH:mm", Locale.ITALIAN)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_workouts)
        bindBottom(BottomNavBinder.Tab.HOME)
        bindBack()
        bindWeekNavigation()
        bindWeek()
        findViewById<android.view.View>(R.id.addWorkoutButton).setOnClickListener {
            go(NewWorkoutActivity::class.java)
        }
        observeData()
    }

    private fun bindWeekNavigation() {
        findViewById<android.view.View>(R.id.previousWeekButton).setOnClickListener {
            weekStart = weekStart.minusWeeks(1)
            selectedDayIndex = 0
            bindWeek()
        }
        findViewById<android.view.View>(R.id.nextWeekButton).setOnClickListener {
            weekStart = weekStart.plusWeeks(1)
            selectedDayIndex = 0
            bindWeek()
        }
    }

    private fun bindWeek() {
        findViewById<TextView>(R.id.weekLabel).text = "${weekStart.format(weekFormatter)} - ${weekStart.plusDays(6).format(weekFormatter)}"
        val days = (0..6).map { offset ->
            val date = weekStart.plusDays(offset.toLong())
            WeekDaySelectorView.Day(
                abbreviation = date.dayOfWeek.getDisplayName(java.time.format.TextStyle.SHORT, Locale.ITALIAN).replaceFirstChar { it.uppercase() },
                dayNumber = date.dayOfMonth.toString(),
            )
        }
        findViewById<WeekDaySelectorView>(R.id.weekDaySelector).apply {
            setDays(days, selectedIndex = selectedDayIndex)
            setOnDaySelectedListener { index ->
                selectedDayIndex = index
                renderSelectedDay()
            }
        }
        renderSelectedDay()
    }

    private fun observeData() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.workouts.collect { values ->
                        workouts = values
                        renderSelectedDay()
                    }
                }
                launch {
                    viewModel.deleted.collect {
                        Toast.makeText(this@WorkoutsActivity, "Voce rimossa dallo storico", Toast.LENGTH_SHORT).show()
                    }
                }
                launch {
                    viewModel.error.collect { message ->
                        Toast.makeText(this@WorkoutsActivity, message, Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    private fun renderSelectedDay() {
        val selectedDate = weekStart.plusDays(selectedDayIndex.toLong())
        val dayItems = workouts
            .filter { workoutDate(it) == selectedDate }
            .sortedWith(compareBy<WorkoutEntity> { it.startedAtEpochMillis }.thenBy { it.id })

        val summary = findViewById<TextView>(R.id.daySummary)
        val container = findViewById<LinearLayout>(R.id.workoutList)
        container.removeAllViews()

        if (dayItems.isEmpty()) {
            summary.text = "${selectedDate.format(dayFormatter)}\nNessun allenamento o riposo registrato."
            container.addView(TextView(this).apply {
                text = "Usa il pulsante sotto per aggiungere una voce allo storico."
                setTextColor(getColor(R.color.text_secondary))
                textSize = 14f
                setPadding(0, dp(12), 0, dp(12))
            })
            return
        }

        val workoutCount = dayItems.count { !it.isRestDay }
        val restCount = dayItems.count { it.isRestDay }
        val totalMinutes = dayItems.filterNot { it.isRestDay }.mapNotNull { it.durationMinutes }.sum()
        summary.text = buildString {
            append(selectedDate.format(dayFormatter))
            append("\n")
            append("$workoutCount allenamenti")
            if (restCount > 0) append(" · $restCount riposo")
            if (totalMinutes > 0) append(" · $totalMinutes min")
        }

        dayItems.forEach { workout -> container.addView(createWorkoutRow(workout)) }
    }

    private fun createWorkoutRow(workout: WorkoutEntity): WorkoutRowView = WorkoutRowView(this).apply {
        val instant = Instant.ofEpochMilli(workout.startedAtEpochMillis).atZone(zone)
        setTitle(workout.title)
        val subtitle = buildList {
            add(instant.format(timeFormatter))
            workout.durationMinutes?.let { add("$it minuti") }
            if (!workout.isRestDay) add(typeLabel(workout.type))
            workout.notes?.takeIf { it.isNotBlank() }?.let { add(it) }
        }.joinToString(" · ")
        setSubtitle(subtitle.ifBlank { if (workout.isRestDay) "Giornata di recupero" else "Allenamento" })
        if (workout.isRestDay) setRestDay() else setPhoto(if (workout.type.equals("CARDIO", true)) R.drawable.img_workout_cardio else R.drawable.img_workout_weights)
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(8) }
        isLongClickable = true
        setOnLongClickListener {
            confirmDelete(workout)
            true
        }
    }

    private fun confirmDelete(workout: WorkoutEntity) {
        MaterialAlertDialogBuilder(this)
            .setTitle("Elimina dallo storico?")
            .setMessage("${workout.title} verrà eliminato definitivamente dallo storico allenamenti.")
            .setNegativeButton("Annulla", null)
            .setPositiveButton("Elimina") { _, _ -> viewModel.delete(workout) }
            .show()
    }

    private fun workoutDate(value: WorkoutEntity): LocalDate = Instant.ofEpochMilli(value.startedAtEpochMillis).atZone(zone).toLocalDate()

    private fun typeLabel(type: String): String = when (type.uppercase(Locale.ROOT)) {
        "PESI" -> "Pesi"
        "CARDIO" -> "Cardio"
        "MOBILITÀ", "MOBILITA" -> "Mobilità"
        "SPORT" -> "Sport"
        "REST" -> "Riposo"
        else -> type.ifBlank { "Altro" }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
