package com.myfitai.app.ui

import android.os.Bundle
import com.myfitai.app.R

class CheatEntryActivity : BaseShellActivity() {
 override fun onCreate(savedInstanceState: Bundle?) { super.onCreate(savedInstanceState); setContentView(R.layout.activity_cheat_entry); bindBack(); findViewById<android.view.View>(R.id.confirmButton).setOnClickListener { go(AdjustedPlanActivity::class.java) } }
}
