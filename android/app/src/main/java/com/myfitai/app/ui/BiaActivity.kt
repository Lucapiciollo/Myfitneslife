package com.myfitai.app.ui

import android.os.Bundle
import com.myfitai.app.R
import com.myfitai.app.navigation.BottomNavBinder

class BiaActivity : BaseShellActivity() {
 override fun onCreate(savedInstanceState: Bundle?) { super.onCreate(savedInstanceState); setContentView(R.layout.activity_bia); bindBottom(BottomNavBinder.Tab.PROGRESS); bindBack(); findViewById<android.view.View>(R.id.saveButton).setOnClickListener { go(BodyMeasuresActivity::class.java) } }
}
