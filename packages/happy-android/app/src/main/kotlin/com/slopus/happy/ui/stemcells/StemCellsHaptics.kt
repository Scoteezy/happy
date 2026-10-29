package com.slopus.happy.ui.stemcells

import android.os.Build
import android.view.HapticFeedbackConstants
import android.view.View

/**
 * What Stem Cells' gestures feel like, by what they mean (happy-ios
 * `StemCellsHaptics`). Desktop's `haptics.ts` names two signals, a
 * `selection` tick each time a drag crosses a slot and an `impact` when
 * something is dropped; the rest are iOS's conventions for the same kinds
 * of moment. Only the person's own acts play one, never a change that
 * arrived from the computer. Played through the view the act happened on,
 * as `StoriesHaptics` plays, each of iOS's generators as the nearest
 * platform constant.
 */
object StemCellsHaptics {
    enum class Moment {
        /** A hold opened a row's preview and menu (Telegram's light tap). */
        Hold,
        /** A held row was lifted to be carried. */
        Lift,
        /** A carried row's landing slot changed. */
        Slot,
        /** A carried row was set down. */
        Drop,
        /** Work was archived, left or closed. */
        PutAway,
        /** A rename was kept. */
        Renamed,
        /** A message went. */
        Send,
        /** New work was made from the dock's field. */
        NewWork,
        /** A picker's choice changed. */
        Pick,
        /** A section folded or unfolded. */
        Fold,
    }

    fun constant(moment: Moment): Int = when (moment) {
        // Medium impact.
        Moment.Lift -> HapticFeedbackConstants.CONTEXT_CLICK
        // Selection.
        Moment.Slot, Moment.Pick -> HapticFeedbackConstants.CLOCK_TICK
        // Light impact.
        Moment.Hold, Moment.Drop, Moment.Renamed, Moment.Send, Moment.NewWork, Moment.Fold -> HapticFeedbackConstants.KEYBOARD_TAP
        // Success.
        Moment.PutAway -> if (Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.VIRTUAL_KEY
    }

    /** Each moment as it plays, for the harness to report. */
    var onPlay: ((Moment) -> Unit)? = null

    fun play(view: View, moment: Moment) {
        onPlay?.invoke(moment)
        view.performHapticFeedback(constant(moment))
    }
}
