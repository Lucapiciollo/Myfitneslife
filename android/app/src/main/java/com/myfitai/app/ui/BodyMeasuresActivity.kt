package com.myfitai.app.ui

import android.os.Bundle
import com.myfitai.app.R
import com.myfitai.app.navigation.BottomNavBinder
import com.myfitai.app.ui.widgets.MeasurementRowView
import com.myfitai.app.ui.widgets.SelectableSegmentView

class BodyMeasuresActivity : BaseShellActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_body_measures)
        bindBottom(BottomNavBinder.Tab.PROGRESS)
        bindBack()
        findViewById<android.view.View>(R.id.saveButton).setOnClickListener { go(HomeActivity::class.java) }

        findViewById<SelectableSegmentView>(R.id.measureSegment).setSegments(listOf("Misura", "Storico"), selectedIndex = 0)
        findViewById<SelectableSegmentView>(R.id.measureSegment).setOnSegmentSelectedListener { index ->
            if (index == 1) go(HistoryActivity::class.java)
        }

        val rows = listOf(
            R.id.rowChest to ("Torace" to "102 cm"),
            R.id.rowWaist to ("Vita" to "84 cm"),
            R.id.rowAbdomen to ("Addome" to "88 cm"),
            R.id.rowArmLeft to ("Braccio sx" to "36 cm"),
            R.id.rowArmRight to ("Braccio dx" to "35 cm"),
            R.id.rowThighLeft to ("Coscia sx" to "58 cm"),
            R.id.rowThighRight to ("Coscia dx" to "57 cm"),
            R.id.rowCalfLeft to ("Polpaccio sx" to "37 cm"),
            R.id.rowCalfRight to ("Polpaccio dx" to "37 cm"),
        )
        rows.forEach { (id, pair) ->
            findViewById<MeasurementRowView>(id).apply {
                setLabel(pair.first)
                setValue(pair.second)
            }
        }
    }
}