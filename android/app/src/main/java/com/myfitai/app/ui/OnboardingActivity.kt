package com.myfitai.app.ui

import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import com.myfitai.app.R
import com.myfitai.app.ui.widgets.OnboardingPageIndicatorView

class OnboardingActivity : AppCompatActivity() {

    private var navigationExecuted = false
    private var interactionArmedAt = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setBackgroundDrawableResource(android.R.color.black)
        setContentView(R.layout.activity_onboarding)

        findViewById<OnboardingPageIndicatorView>(R.id.pageIndicator).apply {
            setCount(3)
            setSelectedIndex(0)
        }

        interactionArmedAt = SystemClock.elapsedRealtime() + 700L

        findViewById<View>(R.id.backButton).setOnClickListener {
            if (isInteractionAllowed()) finish()
        }
        findViewById<View>(R.id.startButton).setOnClickListener {
            navigateHomeIfAllowed()
        }
        findViewById<View>(R.id.alreadyButton).setOnClickListener {
            navigateHomeIfAllowed()
        }
    }

    private fun isInteractionAllowed(): Boolean =
        !navigationExecuted && SystemClock.elapsedRealtime() >= interactionArmedAt

    private fun navigateHomeIfAllowed() {
        if (!isInteractionAllowed()) return
        navigationExecuted = true
        startActivity(
            Intent(this, HomeActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            }
        )
        finish()
        overridePendingTransition(0, 0)
    }
}
