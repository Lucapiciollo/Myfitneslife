package com.myfitai.app.ui

import android.os.Bundle
import com.myfitai.app.R
import com.myfitai.app.navigation.BottomNavBinder
import com.myfitai.app.ui.widgets.HistoryRowView
import com.myfitai.app.ui.widgets.SelectableSegmentView

class HistoryActivity : BaseShellActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_history)
        bindBottom(BottomNavBinder.Tab.PROGRESS)
        bindBack()

        findViewById<SelectableSegmentView>(R.id.categorySegment).setSegments(listOf("Misure", "BIA", "Piani", "Sgarro"), selectedIndex = 2)

        findViewById<HistoryRowView>(R.id.rowPlanWeek3).apply {
            setIcon(R.drawable.ic_calendar)
            setTitle("Settimana 14 - 20 Set 2026")
            setSubtitle("v3 · Aderenza 87%")
            setOnClickListener { go(WeeklyReviewActivity::class.java) }
        }
        findViewById<HistoryRowView>(R.id.rowPlanWeek2).apply {
            setIcon(R.drawable.ic_calendar)
            setTitle("Settimana 7 - 13 Set 2026")
            setSubtitle("v2 · Aderenza 78%")
            setOnClickListener { go(WeeklyReviewActivity::class.java) }
        }
        findViewById<HistoryRowView>(R.id.rowPlanWeek1).apply {
            setIcon(R.drawable.ic_calendar)
            setTitle("Settimana 31 Ago - 6 Set 2026")
            setSubtitle("v1 · Aderenza 71%")
            setOnClickListener { go(WeeklyReviewActivity::class.java) }
        }
        findViewById<HistoryRowView>(R.id.rowBia).apply {
            setIcon(R.drawable.ic_nav_progress)
            setTitle("BIA 14 Set 2026")
            setSubtitle("78,4 kg · 14,2 %")
            setOnClickListener { go(BiaActivity::class.java) }
        }
        findViewById<HistoryRowView>(R.id.rowMeasure).apply {
            setIcon(R.drawable.ic_setting_person)
            setTitle("Misure 14 Set 2026")
            setSubtitle("Vita 84 cm · Torace 102 cm")
            setOnClickListener { go(BodyMeasuresActivity::class.java) }
        }

        findViewById<android.view.View>(R.id.exportButton).setOnClickListener { go(ExportActivity::class.java) }
    }
}
