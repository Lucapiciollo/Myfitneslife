package com.myfitai.app.ui

import com.google.android.material.materialswitch.MaterialSwitch
import com.myfitai.app.R

/** Applies the shared green-enabled / grey-disabled switch color state lists. */
fun MaterialSwitch.applyMyFitAiSwitchTints() {
    thumbTintList = context.getColorStateList(R.color.myfitai_switch_thumb)
    trackTintList = context.getColorStateList(R.color.myfitai_switch_track)
}
