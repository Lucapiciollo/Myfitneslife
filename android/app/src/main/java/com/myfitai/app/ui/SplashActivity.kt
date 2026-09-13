package com.myfitai.app.ui

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import androidx.appcompat.app.AppCompatActivity
import com.myfitai.app.R

class SplashActivity : AppCompatActivity() {

    private val handler by lazy { Handler(mainLooper) }
    private var navigationExecuted = false

    private val openOnboarding = Runnable {
        if (isFinishing || isDestroyed || navigationExecuted) return@Runnable
        navigationExecuted = true
        startActivity(
            Intent(this, OnboardingActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            }
        )
        finish()
        overridePendingTransition(0, 0)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_splash)
        if (savedInstanceState == null) {
            handler.postDelayed(openOnboarding, 900L)
        }
    }

    override fun onDestroy() {
        handler.removeCallbacks(openOnboarding)
        super.onDestroy()
    }
}
