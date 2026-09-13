package com.myfitai.app.ui

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.myfitai.app.R
import com.myfitai.app.ui.widgets.OnboardingPageIndicatorView

class OnboardingActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_onboarding)
        findViewById<OnboardingPageIndicatorView>(R.id.pageIndicator).apply {
            setCount(3)
            setSelectedIndex(0)
        }
        findViewById<android.view.View>(R.id.backButton).setOnClickListener { finish() }
        findViewById<android.view.View>(R.id.startButton).setOnClickListener { startActivity(Intent(this, HomeActivity::class.java)) }
        findViewById<android.view.View>(R.id.alreadyButton).setOnClickListener { startActivity(Intent(this, HomeActivity::class.java)) }
    }
}
