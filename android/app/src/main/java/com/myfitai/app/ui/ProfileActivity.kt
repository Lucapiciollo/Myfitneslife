package com.myfitai.app.ui

import android.os.Bundle
import com.myfitai.app.R

class ProfileActivity : BaseShellActivity() {
 override fun onCreate(savedInstanceState: Bundle?) { super.onCreate(savedInstanceState); setContentView(R.layout.activity_profile); findViewById<android.view.View>(R.id.saveButton).setOnClickListener { go(HomeActivity::class.java) } }
}
