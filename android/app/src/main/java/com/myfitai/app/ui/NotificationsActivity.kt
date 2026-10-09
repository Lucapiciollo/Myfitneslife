package com.myfitai.app.ui

import android.content.Intent
import android.os.Bundle
import android.widget.TextView
import com.myfitai.app.R
import com.myfitai.app.data.AppDataContainer
import com.myfitai.app.notifications.ReminderReceiver
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

class NotificationsActivity : BaseShellActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_notifications)
        bindBack()

        val menuDate = intent.getLongExtra(ReminderReceiver.EXTRA_MENU_DATE_EPOCH_DAY, Long.MIN_VALUE)
            .takeIf { it != Long.MIN_VALUE }?.let(LocalDate::ofEpochDay)
        val meals = intent.getStringArrayExtra(ReminderReceiver.EXTRA_MENU_MEALS).orEmpty().toList()
        renderReminder(menuDate, meals)

        findViewById<android.view.View>(R.id.openMealButton).setOnClickListener {
            if (menuDate != null) openFoodPlan(menuDate.toEpochDay()) else openFoodPlan()
        }
    }

    private fun renderReminder(menuDate: LocalDate?, meals: List<String>) {
        val now = java.time.ZonedDateTime.now()
        val time = now.format(DateTimeFormatter.ofPattern("HH:mm", Locale.ITALIAN))
        val date = now.format(DateTimeFormatter.ofPattern("EEE d MMMM", Locale.ITALIAN))
            .replaceFirstChar { it.titlecase(Locale.ITALIAN) }
        val menuDateText = menuDate?.format(DateTimeFormatter.ofPattern("EEEE d MMMM", Locale.ITALIAN))
            ?.replaceFirstChar { it.titlecase(Locale.ITALIAN) } ?: "giorno pianificato"

        findViewById<TextView>(R.id.mainClock).text = time
        findViewById<TextView>(R.id.mainDate).text = date
        findViewById<TextView>(R.id.notificationTime).text = time
        findViewById<TextView>(R.id.reminderTitle).text = "Menu in programma · $menuDateText"
        findViewById<TextView>(R.id.reminderText).text = meals.joinToString("\n") { "• $it" }
            .ifBlank { "Apri il piano per consultare il menu." }
        findViewById<android.view.View>(R.id.mealImage).visibility = android.view.View.GONE
    }
}
