package com.myfitai.app.ui

import android.os.Bundle
import com.myfitai.app.R
import com.myfitai.app.navigation.BottomNavBinder

class HistoryActivity : BaseShellActivity() {
 override fun onCreate(savedInstanceState: Bundle?) { super.onCreate(savedInstanceState); setContentView(R.layout.activity_history); bindBottom(BottomNavBinder.Tab.PROGRESS); bindBack() }
}
