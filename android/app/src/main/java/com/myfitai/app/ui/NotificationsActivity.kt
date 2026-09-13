package com.myfitai.app.ui

import android.os.Bundle
import com.myfitai.app.R

class NotificationsActivity : BaseShellActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_notifications)
        bindBack()
        findViewById<android.view.View>(R.id.openMealButton).setOnClickListener { go(MealDetailActivity::class.java) }
        findViewById<android.view.View>(R.id.snoozeButton).setOnClickListener { finish() }
    }
}
