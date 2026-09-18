package com.myfitai.app.ui

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.myfitai.app.R
import com.myfitai.app.data.AppDataContainer
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class SplashActivity : AppCompatActivity() {

    private val data by lazy { AppDataContainer.get(this) }
    private var navigationExecuted = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_splash)
        if (savedInstanceState == null) lifecycleScope.launch { routeAfterBootstrap() }
    }

    private suspend fun routeAfterBootstrap() {
        delay(650L)
        if (isFinishing || isDestroyed || navigationExecuted) return

        val defaultId = data.activeProfileStore.defaultIdOrNull()
        val defaultProfile = defaultId?.let { data.userProfileRepository.get(it) }
        val fallbackProfile = defaultProfile ?: data.userProfileRepository.getFirst()

        navigationExecuted = true
        if (fallbackProfile == null) {
            data.activeProfileStore.clear()
            startActivity(Intent(this, ProfileEditActivity::class.java).apply {
                putExtra(ProfileEditActivity.EXTRA_BOOTSTRAP, true)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            })
        } else {
            data.activeProfileStore.selectProfile(fallbackProfile.id, makeDefault = true)
            startActivity(Intent(this, TabHostActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            })
        }
        finish()
    }
}
