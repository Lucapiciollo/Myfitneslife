package com.myfitai.app.ui

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import com.myfitai.app.R
import com.myfitai.app.data.AppDataContainer
import com.myfitai.app.notifications.ReminderReceiver

class NotificationsActivity : BaseShellActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_notifications)
        bindBack()

        val mealId = intent.getLongExtra(ReminderReceiver.EXTRA_MEAL_ID, -1L)
        val mealType = intent.getStringExtra(ReminderReceiver.EXTRA_MEAL_TYPE).orEmpty()
        val mealTitle = intent.getStringExtra(ReminderReceiver.EXTRA_MEAL_TITLE).orEmpty()
        val profileId = intent.getLongExtra(ReminderReceiver.EXTRA_PROFILE_ID, -1L)

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
}
