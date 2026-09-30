package com.myfitai.app.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy

/** Installs token-backed Compose content in an existing Activity/Fragment-owned View hierarchy. */
fun ComposeView.setMyFitAiContent(content: @Composable () -> Unit) {
    setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
    setContent {
        MyFitAiTheme(content = content)
    }
}
