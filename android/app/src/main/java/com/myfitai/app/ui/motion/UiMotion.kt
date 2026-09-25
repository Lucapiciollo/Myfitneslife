package com.myfitai.app.ui.motion

import android.content.Context
import android.os.PowerManager
import android.animation.ValueAnimator
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import com.google.android.material.checkbox.MaterialCheckBox
import androidx.transition.AutoTransition
import androidx.transition.TransitionManager
import com.myfitai.app.R

/** Shared, short and interruptible motion primitives for the Android Views UI. */
object UiMotion {
    private val interpolator = DecelerateInterpolator()

    fun animationsEnabled(context: Context): Boolean {
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        val animatorScale = runCatching { ValueAnimator.areAnimatorsEnabled() }.getOrDefault(true)
        val accessibility = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as? android.view.accessibility.AccessibilityManager
        val spokenFeedbackEnabled = accessibility?.let {
            it.isEnabled && it.getEnabledAccessibilityServiceList(android.accessibilityservice.AccessibilityServiceInfo.FEEDBACK_SPOKEN).isNotEmpty()
        } == true
        return animatorScale && powerManager?.isPowerSaveMode != true && !spokenFeedbackEnabled
    }

    fun screenEnter(view: View) {
        if (!animationsEnabled(view.context)) {
            view.alpha = 1f
            view.translationY = 0f
            return
        }
        view.animate().cancel()
        view.alpha = 0f
        view.translationY = view.resources.getDimension(R.dimen.motion_screen_offset)
        view.animate()
            .alpha(1f)
            .translationY(0f)
            .setDuration(view.resources.getInteger(R.integer.motion_screen_enter_ms).toLong())
            .setInterpolator(interpolator)
            .start()
    }

    fun selection(view: View, selected: Boolean, animateSelection: Boolean = true) {
        if (!animationsEnabled(view.context) || !animateSelection) {
            view.scaleX = 1f
            view.scaleY = 1f
            return
        }
        view.animate().cancel()
        val selectionScale = view.resources.getFraction(R.fraction.motion_selection_scale, 1, 1)
        val target = if (selected) 1f + selectionScale else 1f
        view.animate()
            .scaleX(target)
            .scaleY(target)
            .setDuration(view.resources.getInteger(R.integer.motion_selection_ms).toLong())
            .setInterpolator(interpolator)
            .start()
    }

    fun checked(checkBox: MaterialCheckBox, checked: Boolean) {
        if (!animationsEnabled(checkBox.context)) {
            checkBox.scaleX = 1f
            checkBox.scaleY = 1f
            return
        }
        val checkedScale = checkBox.resources.getFraction(R.fraction.motion_checkbox_scale, 1, 1)
        val selected = if (checked) 1f + checkedScale else 1f
        checkBox.animate().cancel()
        checkBox.animate()
            .scaleX(selected)
            .scaleY(selected)
            .setDuration(checkBox.resources.getInteger(R.integer.motion_feedback_ms).toLong())
            .setInterpolator(interpolator)
            .withEndAction {
                checkBox.animate()
                    .scaleX(1f)
                    .scaleY(1f)
                    .setDuration(checkBox.resources.getInteger(R.integer.motion_feedback_ms).toLong())
                    .setInterpolator(interpolator)
                    .start()
            }
            .start()
    }

    fun reveal(view: View, show: Boolean, animateChange: Boolean = true) {
        val previousTarget = view.getTag(R.id.motionVisibilityTarget) as? Boolean
            ?: ((view.visibility == View.VISIBLE).takeUnless { view.alpha == 0f } ?: false)
        if (previousTarget == show) return
        view.setTag(R.id.motionVisibilityTarget, show)
        view.animate().cancel()
        if (!animateChange || !animationsEnabled(view.context)) {
            view.visibility = if (show) View.VISIBLE else View.GONE
            view.alpha = 1f
            view.translationY = 0f
            return
        }
        if (!show && (view.width == 0 || !view.isLaidOut)) {
            view.visibility = View.GONE
            view.alpha = 1f
            view.translationY = 0f
            return
        }
        val duration = view.resources.getInteger(R.integer.motion_reveal_ms).toLong()
        if (show) {
            view.visibility = View.VISIBLE
            view.alpha = 0f
            view.translationY = view.resources.getDimension(R.dimen.motion_reveal_offset)
            val startAnimation = Runnable {
                if (view.getTag(R.id.motionVisibilityTarget) == true && view.visibility == View.VISIBLE) {
                    view.animate()
                        .alpha(1f)
                        .translationY(0f)
                        .setDuration(duration)
                        .setInterpolator(interpolator)
                        .start()
                }
            }
            if (view.width == 0 || !view.isLaidOut) view.post(startAnimation) else startAnimation.run()
        } else {
            view.animate()
                .alpha(0f)
                .translationY(-view.resources.getDimension(R.dimen.motion_reveal_offset))
                .setDuration(duration)
                .setInterpolator(interpolator)
                .withEndAction {
                    if (view.getTag(R.id.motionVisibilityTarget) == false) {
                        view.visibility = View.GONE
                        view.alpha = 1f
                        view.translationY = 0f
                    }
                }
                .start()
        }
    }

    fun crossfade(first: View, second: View, showFirst: Boolean) {
        val incoming = if (showFirst) first else second
        val outgoing = if (showFirst) second else first
        reveal(outgoing, false)
        reveal(incoming, true)
    }

    fun beginLayoutChange(container: ViewGroup, animateChange: Boolean) {
        if (!animateChange || !animationsEnabled(container.context)) return
        TransitionManager.beginDelayedTransition(
            container,
            AutoTransition().apply {
                duration = container.resources.getInteger(R.integer.motion_layout_change_ms).toLong()
                interpolator = this@UiMotion.interpolator
            },
        )
    }
}
