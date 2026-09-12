package com.myfitai.app.ui

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.myfitai.app.R

class OnboardingActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_onboarding)
        findViewById<android.view.View>(R.id.startButton).setOnClickListener { startActivity(Intent(this, ProfileActivity::class.java)) }
        findViewById<android.view.View>(R.id.alreadyButton).setOnClickListener { startActivity(Intent(this, HomeActivity::class.java)) }
    }
}
