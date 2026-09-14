package com.myfitai.app.notifications

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ReminderReceiverTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    private val receiver = ReminderReceiver()

    @After
    fun tearDown() {
        manager.cancel(ReminderReceiver.stableCode("meal-notification:9001"))
        manager.cancel(ReminderReceiver.stableCode("weekly-review-notification"))
    }

    @Test
    fun mealBroadcast_createsMealChannelAndNotificationWhenPermissionAllows() {
        receiver.onReceive(context, Intent(context, ReminderReceiver::class.java).apply {
            action = ReminderReceiver.ACTION_MEAL
            putExtra(ReminderReceiver.EXTRA_MEAL_ID, 9001L)
            putExtra(ReminderReceiver.EXTRA_MEAL_TYPE, "Pranzo")
            putExtra(ReminderReceiver.EXTRA_MEAL_TITLE, "Riso e pollo")
            putExtra(ReminderReceiver.EXTRA_PROFILE_ID, 1L)
        })

        assertNotNull(manager.getNotificationChannel(ReminderReceiver.CHANNEL_MEALS))
        if (Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) {
            assertTrue(manager.activeNotifications.any { it.id == ReminderReceiver.stableCode("meal-notification:9001") })
        }
    }

    @Test
    fun weeklyReviewBroadcast_createsReviewChannelAndDoesNotCrash() {
        receiver.onReceive(context, Intent(context, ReminderReceiver::class.java).apply {
            action = ReminderReceiver.ACTION_WEEKLY_REVIEW
        })

        assertNotNull(manager.getNotificationChannel(ReminderReceiver.CHANNEL_REVIEW))
        assertEquals(ReminderReceiver.CHANNEL_REVIEW, manager.getNotificationChannel(ReminderReceiver.CHANNEL_REVIEW)?.id)
    }
}
