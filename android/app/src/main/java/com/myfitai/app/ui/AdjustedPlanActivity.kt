package com.myfitai.app.ui

import android.os.Bundle
import com.myfitai.app.R

class AdjustedPlanActivity : BaseShellActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_adjusted_plan)
        bindBack()
        findViewById<android.view.View>(R.id.okButton).setOnClickListener { go(FoodPlanActivity::class.java) }
    }
}
