package com.myfitai.app.ui

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.NotificationManagerCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.button.MaterialButton
import com.google.android.material.switchmaterial.SwitchMaterial
import com.myfitai.app.R
import com.myfitai.app.data.AppDataContainer
import com.myfitai.app.notifications.NotificationPreferences
import com.myfitai.app.notifications.ReminderReceiver
import com.myfitai.app.notifications.ReminderReceiver.Companion.CHANNEL_MEALS
import com.myfitai.app.notifications.ReminderReceiver.Companion.ensureChannels
import kotlinx.coroutines.launch
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

class NotificationsActivity : BaseShellActivity() {
    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { renderSettings() }

    override fun onResume() {
        super.onResume()
        renderSettings()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val mealId = intent.getLongExtra(ReminderReceiver.EXTRA_MEAL_ID, -1L)
        if (mealId <= 0L) {
            showSettings()
            return
        }
        setContentView(R.layout.activity_notifications)
        bindBack()
        val mealType = intent.getStringExtra(ReminderReceiver.EXTRA_MEAL_TYPE).orEmpty().ifBlank { "Pasto" }
        val mealTitle = intent.getStringExtra(ReminderReceiver.EXTRA_MEAL_TITLE).orEmpty().ifBlank { "Apri il piano per i dettagli" }
        val profileId = intent.getLongExtra(ReminderReceiver.EXTRA_PROFILE_ID, -1L)

        renderReminder(mealType, mealTitle)

        findViewById<android.view.View>(R.id.openMealButton).setOnClickListener {
            if (mealId > 0L) {
                startActivity(Intent(this, MealDetailActivity::class.java).putExtra(MealDetailActivity.EXTRA_MEAL_ID, mealId))
            } else {
                openFoodPlan()
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

    private fun showSettings() {
        setContentView(R.layout.activity_notification_settings)
        findViewById<android.view.View>(R.id.settingsBackButton).setOnClickListener { finish() }
        ensureChannels(this)
        val prefs = NotificationPreferences(this)
        findViewById<SwitchMaterial>(R.id.mealRemindersSwitch).setOnCheckedChangeListener(null)
        findViewById<SwitchMaterial>(R.id.weeklyReviewSwitch).setOnCheckedChangeListener(null)
        findViewById<SwitchMaterial>(R.id.mealRemindersSwitch).isChecked = prefs.mealRemindersEnabled
        findViewById<SwitchMaterial>(R.id.weeklyReviewSwitch).isChecked = prefs.weeklyReviewEnabled
        findViewById<SwitchMaterial>(R.id.mealRemindersSwitch).setOnCheckedChangeListener { _, enabled ->
            prefs.mealRemindersEnabled = enabled
            refreshNotifications()
        }
        findViewById<SwitchMaterial>(R.id.weeklyReviewSwitch).setOnCheckedChangeListener { _, enabled ->
            prefs.weeklyReviewEnabled = enabled
            refreshNotifications()
        }
        findViewById<MaterialButton>(R.id.mealLeadButton).setOnClickListener { chooseLeadMinutes() }
        findViewById<MaterialButton>(R.id.requestNotificationPermissionButton).setOnClickListener {
            if (android.os.Build.VERSION.SDK_INT >= 33) permissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }
        findViewById<MaterialButton>(R.id.openAndroidNotificationSettingsButton).setOnClickListener {
            startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName))
        }
        renderSettings()
    }

    private fun renderSettings() {
        if (findViewById<android.view.View?>(R.id.notificationPermissionStatus) == null) return
        val prefs = NotificationPreferences(this)
        val allowed = NotificationManagerCompat.from(this).areNotificationsEnabled()
        val channelImportance = if (android.os.Build.VERSION.SDK_INT >= 26) {
            (getSystemService(NOTIFICATION_SERVICE) as android.app.NotificationManager).getNotificationChannel(CHANNEL_MEALS)?.importance
        } else null
        val status = when {
            !allowed -> "Notifiche Android disattivate: abilita il permesso per vedere i banner nella barra."
            channelImportance != null && channelImportance == android.app.NotificationManager.IMPORTANCE_NONE -> "Canale Promemoria pasti silenziato nelle impostazioni Android."
            else -> "Notifiche abilitate. I promemoria pasti compariranno nella barra quando esiste un pasto futuro programmato."
        }
        findViewById<TextView>(R.id.notificationPermissionStatus).text = status
        findViewById<MaterialButton>(R.id.mealLeadButton).text = "Anticipo promemoria: ${prefs.mealLeadMinutes} minuti"
        findViewById<MaterialButton>(R.id.requestNotificationPermissionButton).isEnabled = !allowed
    }

    private fun chooseLeadMinutes() {
        val values = arrayOf("All'orario del pasto", "5 minuti prima", "10 minuti prima", "15 minuti prima", "30 minuti prima")
        val minutes = intArrayOf(0, 5, 10, 15, 30)
        val current = minutes.indexOf(NotificationPreferences(this).mealLeadMinutes).coerceAtLeast(0)
        MaterialAlertDialogBuilder(this)
            .setTitle("Anticipo promemoria")
            .setSingleChoiceItems(values, current) { dialog, which ->
                NotificationPreferences(this).mealLeadMinutes = minutes[which]
                dialog.dismiss()
                renderSettings()
                refreshNotifications()
            }
            .show()
    }

    private fun refreshNotifications() {
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
            runCatching { AppDataContainer.get(this@NotificationsActivity).notificationScheduler.refresh() }
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
