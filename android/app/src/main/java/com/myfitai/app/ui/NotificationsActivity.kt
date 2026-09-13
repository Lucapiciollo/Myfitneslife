package com.myfitai.app.ui

import android.content.Intent
import android.os.Bundle
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import com.myfitai.app.R
import com.myfitai.app.data.AppDataContainer
import com.myfitai.app.notifications.NotificationPreferences
import com.myfitai.app.notifications.ReminderReceiver
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

class NotificationsActivity : BaseShellActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_notifications)
        bindBack()

        val mealId = intent.getLongExtra(ReminderReceiver.EXTRA_MEAL_ID, -1L)
        val mealType = intent.getStringExtra(ReminderReceiver.EXTRA_MEAL_TYPE).orEmpty().ifBlank { "Pasto" }
        val mealTitle = intent.getStringExtra(ReminderReceiver.EXTRA_MEAL_TITLE).orEmpty().ifBlank { "Apri il piano per i dettagli" }
        val profileId = intent.getLongExtra(ReminderReceiver.EXTRA_PROFILE_ID, -1L)

        renderReminder(mealType, mealTitle)

        findViewById<android.view.View>(R.id.openMealButton).setOnClickListener {
            if (mealId > 0L) {
                startActivity(Intent(this, MealDetailActivity::class.java).putExtra(MealDetailActivity.EXTRA_MEAL_ID, mealId))
            } else {
                go(FoodPlanActivity::class.java)
            }
        }
        findViewById<android.view.View>(R.id.snoozeButton).setOnClickListener {
            if (mealId > 0L) {
                AppDataContainer.get(this).notificationScheduler.snoozeMeal(
                    mealId = mealId,
                    mealType = mealType,
                    mealTitle = mealTitle,
                    profileId = profileId,
                    delayMinutes = 10,
                )
                Toast.makeText(this, "Promemoria posticipato di 10 minuti", Toast.LENGTH_SHORT).show()
            }
            finish()
        }
    }

    private fun renderReminder(mealType: String, mealTitle: String) {
        val now = LocalDateTime.now()
        val time = now.format(DateTimeFormatter.ofPattern("HH:mm", Locale.ITALIAN))
        val date = now.format(DateTimeFormatter.ofPattern("EEE d MMMM", Locale.ITALIAN))
            .replaceFirstChar { it.titlecase(Locale.ITALIAN) }
        val lead = NotificationPreferences(this).mealLeadMinutes

        findViewById<TextView>(R.id.mainClock).text = time
        findViewById<TextView>(R.id.mainDate).text = date
        findViewById<TextView>(R.id.notificationTime).text = time
        findViewById<TextView>(R.id.reminderTitle).text = if (lead > 0) "Tra $lead minuti: $mealType" else "È ora di $mealType"
        findViewById<TextView>(R.id.reminderText).text = mealTitle
        findViewById<ImageView>(R.id.mealImage).setImageResource(
            when (mealType.trim().lowercase(Locale.ROOT)) {
                "colazione", "breakfast" -> R.drawable.img_meal_breakfast
                "spuntino", "snack" -> R.drawable.img_meal_snack
                "pranzo", "lunch" -> R.drawable.img_meal_lunch
                "pre-workout", "preworkout" -> R.drawable.img_meal_preworkout
                "cena", "dinner" -> R.drawable.img_meal_dinner
                else -> R.drawable.img_meal_lunch
            }
        )
    }
}
