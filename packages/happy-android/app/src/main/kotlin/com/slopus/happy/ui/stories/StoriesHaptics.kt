package com.slopus.happy.ui.stories

import android.os.Build
import android.view.HapticFeedbackConstants
import android.view.View

/**
 * The feedback happy-agent's API.md "Haptics" table names for each story
 * moment (happy-ios `StoriesHaptics`), the same on every client. Only the
 * person's own acts play one, never a change that arrived from the
 * computer. Android plays the platform's haptic constants through the view
 * the act happened on, so the person's touch-feedback setting holds; each
 * of iOS's generators maps to the constant nearest in feel.
 */
object StoriesHaptics {
    enum class Moment(val wire: String) {
        /** The viewer opens from a circle or an inline story. */
        Open("story.open"),
        /** The viewer moves to another frame, story or author. */
        Advance("story.advance"),
        /** `post` answered 200 (not the tap). */
        Post("story.post"),
        /** A confirmed deletion answered 200. */
        Delete("story.delete"),
        /** A reaction bubble or the heart, as the reaction appears. */
        React("story.react"),
        /** A sticker or emoji lands on a frame from the picker. */
        StickerDrop("sticker.drop"),
        /** A dragged overlay snaps to a guide, once each time. */
        Snap("overlay.snap"),
        /** A dragged overlay reaches the delete target. */
        Trash("overlay.trash"),
        /** A refused post or deletion. */
        Refused("refused"),
        /**
         * Home's stories open under the header, or close into the title,
         * under the person's finger (Telegram's chat list: a tap, then a
         * very light impact).
         */
        HeaderOpen("stories.header.open"),
        HeaderClose("stories.header.close"),
    }

    /** iOS's feedback for each moment, as the platform constant that plays it. */
    fun constant(moment: Moment): Int = when (moment) {
        // Light impact.
        Moment.Open, Moment.React -> HapticFeedbackConstants.KEYBOARD_TAP
        // Selection.
        Moment.Advance, Moment.HeaderOpen -> HapticFeedbackConstants.CLOCK_TICK
        // Success.
        Moment.Post -> if (Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.VIRTUAL_KEY
        // Warning.
        Moment.Delete -> HapticFeedbackConstants.LONG_PRESS
        // Medium impact.
        Moment.StickerDrop -> HapticFeedbackConstants.CONTEXT_CLICK
        // "Rigid impact, light".
        Moment.Snap -> if (Build.VERSION.SDK_INT >= 34) HapticFeedbackConstants.SEGMENT_FREQUENT_TICK else HapticFeedbackConstants.CLOCK_TICK
        // Heavy impact.
        Moment.Trash -> HapticFeedbackConstants.LONG_PRESS
        // Error.
        Moment.Refused -> if (Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.REJECT else HapticFeedbackConstants.LONG_PRESS
        // Medium impact at 0.3: the lightest the platform has.
        Moment.HeaderClose -> if (Build.VERSION.SDK_INT >= 27) HapticFeedbackConstants.TEXT_HANDLE_MOVE else HapticFeedbackConstants.CLOCK_TICK
    }

    /** Each moment as it plays, for the harness to report. */
    var onPlay: ((Moment) -> Unit)? = null

    fun play(moment: Moment, view: View) {
        onPlay?.invoke(moment)
        view.performHapticFeedback(constant(moment))
    }
}
