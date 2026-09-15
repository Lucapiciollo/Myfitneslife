package com.myfitai.app.ui.widgets

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.widget.LinearLayout
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.myfitai.app.R
import com.myfitai.app.data.AppDataContainer
import com.myfitai.app.ui.BiaActivity
import com.myfitai.app.ui.BiaTrendBinder
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.launch

@OptIn(ExperimentalCoroutinesApi::class)
class BiaSegmentView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : SelectableSegmentView(context, attrs) {

    private val activity: BiaActivity
        get() = context as BiaActivity

    private var trendBinder: BiaTrendBinder? = null
    private var observerStarted = false

    override fun setSegments(labels: List<String>, selectedIndex: Int) {
        // Keep Storico as the second item because BiaActivity already opens getChildAt(1)
        // when reached from the "Apri storico BIA" shortcut.
        super.setSegments(listOf("Nuova misurazione", "Storico", "Andamento"), selectedIndex.coerceIn(0, 2))
        ensureTrendBinder()
    }

    override fun setOnSegmentSelectedListener(listener: (Int) -> Unit) {
        ensureTrendBinder()
        super.setOnSegmentSelectedListener { index ->
            val newContainer = activity.findViewById<View>(R.id.newMeasurementContainer)
            val historyContainer = activity.findViewById<View>(R.id.historyContainer)
            when (index) {
                0 -> {
                    trendBinder?.hide()
                    listener(0)
                }
                1 -> {
                    trendBinder?.hide()
                    listener(1)
                }
                2 -> {
                    newContainer.visibility = View.GONE
                    historyContainer.visibility = View.GONE
                    trendBinder?.show()
                }
            }
        }
    }

    private fun ensureTrendBinder() {
        if (trendBinder != null || !isAttachedToWindow) return
        val historyContainer = activity.findViewById<View>(R.id.historyContainer)
        val parent = historyContainer.parent as? LinearLayout ?: return
        val insertIndex = parent.indexOfChild(historyContainer) + 1
        trendBinder = BiaTrendBinder(activity, parent, insertIndex)
        startObserving()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        post { ensureTrendBinder() }
    }

    private fun startObserving() {
        if (observerStarted) return
        observerStarted = true
        val data = AppDataContainer.get(activity)
        activity.lifecycleScope.launch {
            activity.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                data.activeProfileStore.activeProfileId
                    .flatMapLatest(data.biaRepository::all)
                    .collect { trendBinder?.update(it) }
            }
        }
    }
}
