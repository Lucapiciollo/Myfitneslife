package com.myfitai.app.ui

import android.os.Bundle
import com.myfitai.app.R
import com.myfitai.app.navigation.BottomNavBinder

class BodyMeasuresActivity : BaseShellActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_body_measures)
        bindBottom(BottomNavBinder.Tab.MEASURES)
        bindBack()
        findViewById<android.view.View>(R.id.saveButton).setOnClickListener { go(HomeActivity::class.java) }
    }
}
