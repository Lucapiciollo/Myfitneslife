package com.myfitai.app.notifications

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import android.Manifest
import android.content.pm.PackageManager
import com.myfitai.app.R
import com.myfitai.app.ui.NutritionPathActivity

object NutritionPathNotification {
    const val CHANNEL = "nutrition_path"
    fun show(context: Context, profileId: Long, jobKey: String) {
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        if (Build.VERSION.SDK_INT >= 26) (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(NotificationChannel(CHANNEL, "Percorso nutrizionale", NotificationManager.IMPORTANCE_DEFAULT))
        val intent = Intent(context, NutritionPathActivity::class.java).putExtra(NutritionPathActivity.EXTRA_JOB_KEY, jobKey)
        val pending = PendingIntent.getActivity(context, jobKey.hashCode(), intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        NotificationManagerCompat.from(context).notify(jobKey.hashCode(), NotificationCompat.Builder(context, CHANNEL).setSmallIcon(R.drawable.ic_notification_small).setContentTitle("Suggerimento nutrizionale pronto").setContentText("Scegli il percorso da seguire").setContentIntent(pending).setAutoCancel(true).build())
    }
}
