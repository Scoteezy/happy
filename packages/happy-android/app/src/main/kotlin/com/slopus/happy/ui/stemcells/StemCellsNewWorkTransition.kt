package com.slopus.happy.ui.stemcells

import android.view.View
import android.view.animation.PathInterpolator
import com.slopus.happy.ui.nav.ScreenTransition

/** happy-ios StemCellsNewWorkTransition; the IME supplies the composer's motion. */
internal class StemCellsNewWorkTransition(
    private val dock: View,
    private val chat: StemCellChatScreen,
    private val pushing: Boolean,
) : ScreenTransition {
    override val durationMillis = 350L
    override val interpolator = PathInterpolator(0.42f, 0f, 0.58f, 1f)
    private var restoreFocus = false

    override fun begin(interactive: Boolean) {
        dock.alpha = 0f
        if (!pushing) restoreFocus = chat.blurForNewWorkPop()
    }

    override fun apply(shown: Float) {
        chat.newWorkFade(shown)
        dock.alpha = if (!pushing && !chat.composerIsEmpty) 1f - shown else 0f
    }

    override fun finish(cancelled: Boolean) {
        dock.alpha = 1f
        chat.newWorkFade(1f)
        if (cancelled && !pushing && restoreFocus) chat.restoreNewWorkFocus()
        restoreFocus = false
    }
}