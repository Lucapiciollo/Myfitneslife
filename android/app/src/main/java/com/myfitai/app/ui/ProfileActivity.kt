package com.myfitai.app.ui

import android.os.Bundle
import com.myfitai.app.R
import com.myfitai.app.security.SecureOpenAiKeyStore
import com.myfitai.app.ui.widgets.SettingRowView

class ProfileActivity : BaseShellActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_profile)
        bindBack()

        findViewById<SettingRowView>(R.id.rowWorkouts).setOnClickListener { go(WorkoutsActivity::class.java) }
        findViewById<SettingRowView>(R.id.rowNotifications).setOnClickListener { go(NotificationsActivity::class.java) }
        findViewById<SettingRowView>(R.id.rowExport).setOnClickListener { go(ExportActivity::class.java) }
        findViewById<SettingRowView>(R.id.rowSettings).setOnClickListener { go(SettingsActivity::class.java) }

        val openAiRow = findViewById<SettingRowView>(R.id.rowOpenAiKey)
        openAiRow.setOnClickListener { go(SettingsActivity::class.java) }
        val hasKey = SecureOpenAiKeyStore(this).hasKey()
        openAiRow.setTrailingBadge(
            getString(if (hasKey) R.string.profile_openai_configured else R.string.profile_openai_not_configured),
            if (hasKey) R.color.accent_green else R.color.text_muted,
        )
    }
}