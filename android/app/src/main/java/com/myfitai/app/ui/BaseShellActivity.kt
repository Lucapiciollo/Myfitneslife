package com.myfitai.app.ui

import android.content.Intent
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import com.myfitai.app.R
import com.myfitai.app.navigation.BottomNavBinder

abstract class BaseShellActivity : AppCompatActivity() {
    protected fun bindBack() { findViewById<View?>(R.id.backButton)?.setOnClickListener { finish() } }
    protected fun bindBottom(tab: BottomNavBinder.Tab) { BottomNavBinder.bind(this, tab) }
    protected fun go(target: Class<out AppCompatActivity>) { startActivity(Intent(this, target)) }
}
