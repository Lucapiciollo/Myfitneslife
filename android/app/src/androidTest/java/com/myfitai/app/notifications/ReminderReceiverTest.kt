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
        val date = java.time.LocalDate.of(2026, 10, 9).toEpochDay()
        manager.cancel(ReminderReceiver.stableCode("menu-preview:9002:$date"))
        manager.cancel(ReminderReceiver.stableCode("weekly-review-notification"))
    }

    @Test
    fun weeklyReviewBroadcast_createsReviewChannelAndDoesNotCrash() {
        receiver.onReceive(context, Intent(context, ReminderReceiver::class.java).apply {
            action = ReminderReceiver.ACTION_WEEKLY_REVIEW
        })

        assertNotNull(manager.getNotificationChannel(ReminderReceiver.CHANNEL_REVIEW))
        assertEquals(ReminderReceiver.CHANNEL_REVIEW, manager.getNotificationChannel(ReminderReceiver.CHANNEL_REVIEW)?.id)
    }

    @Test
    fun menuPreviewBroadcast_postsTomorrowMenuAndUsesMenuSpecificAction() {
        val menuDate = java.time.LocalDate.of(2026, 10, 9).toEpochDay()
        val notificationId = ReminderReceiver.stableCode("menu-preview:9002:$menuDate")
        try {
            receiver.onReceive(context, Intent(context, ReminderReceiver::class.java).apply {
                action = ReminderReceiver.ACTION_MENU_PREVIEW
                putExtra(ReminderReceiver.EXTRA_MENU_DATE_EPOCH_DAY, menuDate)
                putExtra(ReminderReceiver.EXTRA_MENU_MEALS, arrayOf("Colazione: Yogurt e frutta", "Pranzo: Riso e pollo"))
                putExtra(ReminderReceiver.EXTRA_PROFILE_ID, 9002L)
            })

            if (Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) {
                val deadline = System.currentTimeMillis() + 2_000L
                var posted = false
                while (System.currentTimeMillis() < deadline && !posted) {
                    posted = manager.activeNotifications.any { it.id == notificationId }
                    if (!posted) Thread.sleep(50L)
                }
                assertTrue("menu-preview notification is posted", posted)
                val notification = manager.activeNotifications.first { it.id == notificationId }.notification
                assertTrue(notification.extras.getCharSequence(android.app.Notification.EXTRA_TITLE).toString().contains("Menu in programma"))
                assertTrue(notification.extras.getCharSequence(android.app.Notification.EXTRA_BIG_TEXT).toString().contains("Yogurt e frutta"))
                assertEquals(1, notification.actions?.size ?: 0)
                assertEquals("Apri menu", notification.actions?.firstOrNull()?.title)
            }
        } finally {
            manager.cancel(notificationId)
        }
    }
}
