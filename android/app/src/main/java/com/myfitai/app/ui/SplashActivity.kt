package com.myfitai.app.ui

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.myfitai.app.R

class SplashActivity : AppCompatActivity() {
 override fun onCreate(savedInstanceState: Bundle?) { super.onCreate(savedInstanceState); setContentView(R.layout.activity_splash); android.os.Handler(mainLooper).postDelayed({ startActivity(Intent(this, OnboardingActivity::class.java)); finish() }, 900) }
}
