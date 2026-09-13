package com.myfitai.app.ui

import android.os.Bundle
import com.myfitai.app.R
import com.myfitai.app.navigation.BottomNavBinder
import com.myfitai.app.ui.widgets.WeekDaySelectorView
import com.myfitai.app.ui.widgets.WorkoutRowView

class WorkoutsActivity : BaseShellActivity() {

    private data class DayWorkout(val id: Int, val title: String, val subtitle: String, val photo: Int?)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_workouts)
        bindBottom(BottomNavBinder.Tab.HOME)
        bindBack()

        findViewById<WeekDaySelectorView>(R.id.weekDaySelector).setDays(
            listOf(
                WeekDaySelectorView.Day("Lun", "9"),
                WeekDaySelectorView.Day("Mar", "10"),
                WeekDaySelectorView.Day("Mer", "11"),
                WeekDaySelectorView.Day("Gio", "12"),
                WeekDaySelectorView.Day("Ven", "13"),
                WeekDaySelectorView.Day("Sab", "14"),
                WeekDaySelectorView.Day("Dom", "15"),
            ),
            selectedIndex = 3,
        )

        val days = listOf(
            DayWorkout(R.id.rowMon, "Pesi - Full body", "07:00 · 45 minuti · Intensità: Alta", R.drawable.img_workout_weights),
            DayWorkout(R.id.rowTue, "Cardio", "18:30 · 30 minuti · Intensità: Moderata", R.drawable.img_workout_cardio),
            DayWorkout(R.id.rowWed, "Pesi - Upper body", "07:00 · 50 minuti · Intensità: Alta", R.drawable.img_workout_weights),
            DayWorkout(R.id.rowThu, "Riposo", "Giornata di recupero", null),
            DayWorkout(R.id.rowFri, "Pesi - Lower body", "18:30 · 50 minuti · Intensità: Alta", R.drawable.img_workout_weights),
            DayWorkout(R.id.rowSat, "Cardio", "09:00 · 30 minuti · Intensità: Moderata", R.drawable.img_workout_cardio),
            DayWorkout(R.id.rowSun, "Riposo", "Giornata di recupero", null),
        )
        days.forEach { day ->
            findViewById<WorkoutRowView>(day.id).apply {
                setTitle(day.title)
                setSubtitle(day.subtitle)
                if (day.photo != null) setPhoto(day.photo) else setRestDay()
            }
        }
    }
}
