package com.myfitai.app.ui

import android.os.Bundle
import com.myfitai.app.R
import com.myfitai.app.navigation.BottomNavBinder

class HomeActivity : BaseShellActivity() {
 override fun onCreate(savedInstanceState: Bundle?) { super.onCreate(savedInstanceState); setContentView(R.layout.activity_home); bindBottom(BottomNavBinder.Tab.HOME); findViewById<android.view.View>(R.id.profileButton).setOnClickListener { go(ProfileActivity::class.java) }; findViewById<android.view.View>(R.id.newMeasureButton).setOnClickListener { go(BiaActivity::class.java) }; findViewById<android.view.View>(R.id.aiCard).setOnClickListener { go(AiAnalysisActivity::class.java) }; findViewById<android.view.View>(R.id.statusCard).setOnClickListener { go(HistoryActivity::class.java) } }
}
