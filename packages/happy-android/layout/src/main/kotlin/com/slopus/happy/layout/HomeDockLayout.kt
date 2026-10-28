package com.slopus.happy.layout

import com.slopus.happy.layout.composer.MobileCollapsedComposerGeometry
import com.slopus.happy.layout.composer.MobileComposerMetrics
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * Home's resting dock (HomeDock.tsx's `safeArea` and `renderComposer`,
 * happy-ios `HomeDockLayout`): a one-line capsule at the foot of the screen
 * that opens the composer.
 *
 *     |16| ( Plan, ask, build…     Sonnet 5 ⌄ (↑) ) |16|
 *
 * An accessory (Stem Cells' model control) may sit in the capsule just before
 * the send button; the entry gives up its room. The capsule is 56 tall, 8
 * below the dock's top and `max(10, safe bottom)` above the screen's foot.
 * Its text starts where the rounded end meets the straight edge; the send
 * button is the composer's 42dp primary action. A scrim reaches up from the
 * screen's foot to the capsule's middle.
 */
data class HomeDockLayout(
    /** The dock's box, from the top padding to the screen's foot. */
    val dock: Rect,
    /** The scrim behind the dock, from the capsule's middle down. */
    val scrim: Rect,
    /** The capsule, rim included. */
    val composer: Rect,
    /** What a tap opens the composer from (`inputEntry`). */
    val entry: Rect,
    val entryText: Rect,
    val send: Rect,
    /** Zero-sized at the send button's left when there is none. */
    val accessory: Rect,
) {
    object Metrics {
        /** `MOBILE_HOME_DOCK_CONTENT_INSET`: what home's content leaves clear for the dock. */
        const val contentInset = 108.0
        const val paddingHorizontal = 16.0
        /** `MOBILE_HOME_DOCK_TOP_PADDING`. */
        const val paddingTop = 8.0
        const val minPaddingBottom = 10.0
        /** `composerContent`'s gap between the text and the button. */
        const val contentGap = 4.0
        /** The arrow in the send button. */
        const val sendIconSize = 16.0
        /** Between the entry and the accessory. */
        const val accessoryGap = 4.0
    }

    class Input(
        /** The home screen. */
        val size: Size,
        val safeBottom: Double,
        /** One device pixel: the capsule's rim. */
        val hairline: Double,
        /** `layout.maxWidth`. */
        val maxWidth: Double = Double.MAX_VALUE,
        /** The prompt, or the placeholder, on one line. */
        val entry: Measurable,
        /** What sits before the send button; zero for nothing. It keeps at least half the row for the entry. */
        val accessory: Size = Size.Zero,
    )

    companion object {
        operator fun invoke(input: Input): HomeDockLayout {
            val m = Metrics
            val geometry = MobileCollapsedComposerGeometry()
            val metrics = MobileComposerMetrics.mobile
            val width = max(0.0, input.size.width)
            val height = max(0.0, input.size.height)

            val dockHeight = m.paddingTop + geometry.shellHeight + max(m.minPaddingBottom, input.safeBottom)
            val dock = Rect(0.0, height - dockHeight, width, dockHeight)
            val scrimTop = m.paddingTop + geometry.shellHeight / 2
            val scrim = Rect(0.0, dock.minY + scrimTop, width, dockHeight - scrimTop)

            val lane = max(0.0, width - 2 * m.paddingHorizontal)
            val composerWidth = min(input.maxWidth, lane)
            val composer = Rect(
                m.paddingHorizontal + (lane - composerWidth) / 2,
                dock.minY + m.paddingTop,
                composerWidth,
                geometry.shellHeight,
            )

            // The rim is inside the capsule, and the row inside the rim.
            val inner = composer.insetBy(input.hairline, input.hairline)
            val contentLeft = inner.minX + geometry.contentPaddingLeft
            val contentRight = inner.maxX - geometry.contentPaddingRight
            val button = metrics.primaryActionSize
            val send = Rect(contentRight - button, inner.midY - button / 2, button, button)
            val beforeSend = send.minX - metrics.primaryActionMarginLeft - m.contentGap
            val accessoryWidth = min(input.accessory.width, max(0.0, (beforeSend - contentLeft) / 2))
            val accessory = Rect(
                beforeSend - accessoryWidth,
                inner.midY - input.accessory.height / 2,
                accessoryWidth,
                input.accessory.height,
            )
            val entryRight = if (accessoryWidth > 0) accessory.minX - m.accessoryGap else beforeSend
            val entry = Rect(contentLeft, inner.minY, max(0.0, entryRight - contentLeft), inner.height)

            val textLane = max(0.0, entry.width - geometry.inputPaddingLeft - geometry.inputPaddingRight)
            val text = input.entry.size(textLane)
            val entryText = Rect(
                entry.minX + geometry.inputPaddingLeft,
                entry.midY - text.height / 2,
                min(text.width, textLane),
                text.height,
            )
            return HomeDockLayout(dock, scrim, composer, entry, entryText, send, accessory)
        }
    }
}

/**
 * The open composer (HomeDock.tsx's focus mode), in the dock's own space: a
 * box the size of the screen whose foot is the screen's foot with the
 * keyboard down. [keyboardShift] moves the whole box up onto the keyboard.
 *
 *       ▢  MacBook Pro                       ← a picker row, 42 tall
 *       ▢  ~/happy                              1 apart, inset 24
 *       ▢  No worktree
 *       ▢  Claude Code
 *                                            ← 22 clear
 *     |16| ● Starting Claude Code…   To interrupt press stop |16|
 *     |16| [ the composer card ] |16|
 *                                            ← safe bottom + 8
 *
 * The status line sits in the 22 the rows leave clear, so showing it moves
 * nothing. While the composer opens its shell grows from the capsule's 56 to
 * the card's height and everything above rides on it.
 */
data class HomeDockFocusLayout(
    val rows: List<Row>,
    /** The composer's shell. */
    val composer: Rect,
    val progress: Rect?,
    val progressDot: Rect?,
    val progressLabel: Rect?,
    val progressHint: Rect?,
) {
    object Metrics {
        const val configPaddingHorizontal = 24.0
        /** `START_PROGRESS_ROW_HEIGHT`. */
        const val progressRowHeight = 18.0
        /** The rows hold the status line's room open (`paddingBottom`). */
        const val configPaddingBottom = progressRowHeight + 4
        /** `focusConfigGroup`'s gap. */
        const val rowSpacing = 1.0
        const val rowMinHeight = 42.0
        const val rowPaddingHorizontal = 6.0
        const val rowRadius = 12.0
        /** Between the icon box and the value. */
        const val rowGap = 12.0
        const val iconBox = 24.0
        const val iconSize = 21.0
        /** The native trigger's `Spacer(minLength: 8)` after the value. */
        const val valueTrailing = 8.0
        /**
         * `focusConfigTriggerValue`, the hidden RN copy of the value that
         * sizes the native trigger: 17 with no line height, so Plex's own
         * 1.3 em at the designed size.
         */
        const val triggerLine = 22.1
        const val composerPaddingHorizontal = 16.0
        /** Above the safe area, or above the keyboard. */
        const val composerPaddingBottom = 8.0
        const val progressPaddingHorizontal = 16.0
        const val progressGap = 6.0
        const val progressDot = 6.0
    }

    class Input(
        val size: Size,
        val safeBottom: Double,
        val maxWidth: Double = Double.MAX_VALUE,
        /** The shell's height now: the card's, or less while it opens. */
        val composerHeight: Double,
        /** Each picker row's value, on one line. */
        val rows: List<Measurable>,
        /** One line of `focusConfigTriggerValue` at the text size. */
        val triggerLine: Double = Metrics.triggerLine,
        /** "Starting Claude Code…", while a start runs. */
        val progress: Measurable? = null,
        /** "To interrupt press stop", while Stop can stop it. */
        val hint: Measurable? = null,
    )

    data class Row(
        val frame: Rect,
        val icon: Rect,
        /** The menu's trigger: the rest of the row after the icon. */
        val trigger: Rect,
        val value: Rect,
    )

    companion object {
        operator fun invoke(input: Input): HomeDockFocusLayout {
            val m = Metrics
            val width = max(0.0, input.size.width)
            val height = max(0.0, input.size.height)

            val composerLane = max(0.0, width - 2 * m.composerPaddingHorizontal)
            val composerWidth = min(input.maxWidth, composerLane)
            val composerBottom = height - input.safeBottom - m.composerPaddingBottom
            val composer = Rect(
                m.composerPaddingHorizontal + (composerLane - composerWidth) / 2,
                composerBottom - input.composerHeight,
                composerWidth,
                input.composerHeight,
            )

            var progress: Rect? = null
            var progressDot: Rect? = null
            var progressLabel: Rect? = null
            var progressHint: Rect? = null
            input.progress?.let { label ->
                val row = Rect(m.composerPaddingHorizontal, composer.minY - m.progressRowHeight, composerLane, m.progressRowHeight)
                val content = Rect(composer.minX, row.minY, composerWidth, row.height)
                progress = row
                val dot = Rect(content.minX + m.progressPaddingHorizontal, row.midY - m.progressDot / 2, m.progressDot, m.progressDot)
                progressDot = dot
                val contentRight = content.maxX - m.progressPaddingHorizontal
                var labelRight = contentRight
                input.hint?.let { hint ->
                    val size = hint.size(Double.MAX_VALUE)
                    val frame = Rect(contentRight - size.width, row.midY - size.height / 2, size.width, size.height)
                    progressHint = frame
                    labelRight = frame.minX - m.progressGap
                }
                val labelX = dot.maxX + m.progressGap
                val lane = max(0.0, labelRight - labelX)
                val size = label.size(lane)
                progressLabel = Rect(labelX, row.midY - size.height / 2, min(size.width, lane), size.height)
            }

            val configWidth = min(input.maxWidth, width)
            val rowX = (width - configWidth) / 2 + m.configPaddingHorizontal
            val rowWidth = max(0.0, configWidth - 2 * m.configPaddingHorizontal)
            val triggerX = rowX + m.rowPaddingHorizontal + m.iconBox + m.rowGap
            val triggerWidth = max(0.0, rowX + rowWidth - m.rowPaddingHorizontal - triggerX)
            val valueLane = max(0.0, triggerWidth - m.valueTrailing)
            val values = input.rows.map { it.size(valueLane) }
            // RN's row is its 42, the icon's box or the trigger's sizing copy;
            // the menu's own label is centred in it.
            val rowHeight = maxOf(m.rowMinHeight, m.iconBox, input.triggerLine)
            val total = values.size * rowHeight + max(0, values.size - 1) * m.rowSpacing
            var y = composer.minY - m.configPaddingBottom - total
            val rows = values.map { value ->
                val frame = Rect(rowX, y, rowWidth, rowHeight)
                y += rowHeight + m.rowSpacing
                Row(
                    frame = frame,
                    icon = Rect(rowX + m.rowPaddingHorizontal, frame.midY - m.iconBox / 2, m.iconBox, m.iconBox),
                    trigger = Rect(triggerX, frame.minY, triggerWidth, rowHeight),
                    value = Rect(triggerX, frame.midY - value.height / 2, min(value.width, valueLane), value.height),
                )
            }
            return HomeDockFocusLayout(rows, composer, progress, progressDot, progressLabel, progressHint)
        }

        /**
         * How far the dock rides up on the keyboard: the dock's foot follows
         * the keyboard's top once it is above the safe area.
         */
        fun keyboardShift(keyboardTop: Double, size: Size, safeBottom: Double): Double =
            min(0.0, keyboardTop - (size.height - safeBottom))
    }
}

/**
 * Session | Bot, held at the top of the screen over the dimmed list
 * (HomeDock.tsx `focusHeader`). A bot's lede sits beneath it.
 *
 *              ( Session | Bot )            ← safe top + 8, at most 260
 *       Bots are virtual colleagues great   ← 10 below, at most 340
 */
data class HomeDockHeaderLayout(
    /** The control and the lede together. */
    val content: Rect,
    val segment: Rect,
    val lede: Rect?,
) {
    object Metrics {
        const val paddingTop = 8.0
        const val paddingHorizontal = 24.0
        const val gap = 10.0
        const val segmentMaxWidth = 260.0
        /** `NATIVE_SEGMENTED_CONTROL_HEIGHT`. */
        const val segmentHeight = 32.0
        const val ledeMaxWidth = 340.0
    }

    class Input(
        val width: Double,
        val safeTop: Double,
        val maxWidth: Double = Double.MAX_VALUE,
        /** The bot's lede, centred; null for a session. */
        val lede: Measurable? = null,
    )

    companion object {
        operator fun invoke(input: Input): HomeDockHeaderLayout {
            val m = Metrics
            val lane = max(0.0, input.width - 2 * m.paddingHorizontal)
            val contentWidth = min(input.maxWidth, lane)
            val left = m.paddingHorizontal + (lane - contentWidth) / 2
            val top = input.safeTop + m.paddingTop

            val segmentWidth = min(m.segmentMaxWidth, contentWidth)
            val segment = Rect(left + (contentWidth - segmentWidth) / 2, top, segmentWidth, m.segmentHeight)
            var bottom = segment.maxY
            val lede = input.lede?.let { text ->
                val ledeLane = ledeWidth(input.width, input.maxWidth)
                val size = text.size(ledeLane)
                val width = min(size.width, ledeLane)
                Rect(left + (contentWidth - width) / 2, segment.maxY + m.gap, width, size.height).also { bottom = it.maxY }
            }
            return HomeDockHeaderLayout(Rect(left, top, contentWidth, bottom - top), segment, lede)
        }

        /** The lane the lede wraps in on a screen [width] wide. */
        fun ledeWidth(width: Double, maxWidth: Double = Double.MAX_VALUE): Double {
            val lane = max(0.0, width - 2 * Metrics.paddingHorizontal)
            return min(Metrics.ledeMaxWidth, min(maxWidth, lane))
        }
    }
}

/**
 * How the composer opens and closes (HomeDock.tsx's `focusPresentation`):
 * every piece is a function of one progress value, 0 closed to 1 open.
 */
data class HomeDockFocusMotion(val progress: Double) {
    /** The blur and dim behind: in by 0.35. */
    val backdropAlpha get() = ramp(progress, 0.0, 0.35)

    /** Row [index] (from the top) fades in and rises 10dp, each a beat after the one above. */
    fun row(index: Int): Pair<Double, Double> {
        val start = 0.18 + index * 0.09
        val reveal = ramp(progress, start, start + 0.28)
        return reveal to 10 * (1 - reveal)
    }

    /** The shell's height on the way from the capsule to [full]. */
    fun composerHeight(full: Double): Double {
        val p = min(1.0, max(0.0, progress))
        return collapsedHeight + (full - collapsedHeight) * p
    }

    val composerAlpha get() = 0.72 + 0.28 * ramp(progress, 0.0, 0.12)

    val composerScaleX get() = 0.96 + 0.04 * min(1.0, max(0.0, progress))

    companion object {
        /** Opening waits a frame for the modal, then runs 340ms ease-out. */
        const val openDelay = 0.016
        const val openDuration = 0.34
        /** Closing runs 180ms ease-in from wherever it got to. */
        const val closeDuration = 0.18
        /** How long the open waits for the card's text height before it runs on the height it has. */
        const val heightWait = 0.032
        /** The capsule the shell grows from. */
        const val collapsedHeight = 56.0

        /** The frame's wait before the open runs, less the time spent waiting for the height. */
        fun openDelay(afterWaiting: Double) = max(0.0, openDelay - max(0.0, afterWaiting))

        /** `Easing.out(Easing.cubic)`. */
        fun easeOut(t: Double): Double {
            val clamped = min(1.0, max(0.0, t))
            return 1 - (1 - clamped).pow(3)
        }

        /** `Easing.in(Easing.cubic)`. */
        fun easeIn(t: Double): Double {
            val clamped = min(1.0, max(0.0, t))
            return clamped * clamped * clamped
        }

        /** `interpolate(progress, [start, end], [0, 1], CLAMP)`. */
        internal fun ramp(value: Double, start: Double, end: Double) = min(1.0, max(0.0, (value - start) / (end - start)))
    }
}

/**
 * The resting dock fading in as it goes from hidden to shown. Two parts: the
 * capsule's surface and what is on it and around it (the prompt, the send
 * button, the scrim).
 */
data class HomeDockFade(
    /** The surface's presence when the fade starts. */
    val surfaceFrom: Double,
    /** The prompt's, the button's and the scrim's. */
    val contentFrom: Double,
    val duration: Double,
) {
    enum class Origin {
        /** Home's content came in. */
        Appearing,
        /** The open composer closed. */
        Focus,
    }

    /** How present each part is [elapsed] seconds in, each from where it starts to 1 on the same curve. */
    fun presence(elapsed: Double): Pair<Double, Double> {
        if (duration <= 0) return 1.0 to 1.0
        val eased = HomeDockFocusMotion.easeOut(elapsed / duration)
        return (surfaceFrom + (1 - surfaceFrom) * eased) to (contentFrom + (1 - contentFrom) * eased)
    }

    fun isFinished(elapsed: Double) = elapsed >= duration

    companion object {
        /** The first show: from nothing, 220ms ease-out. */
        const val appearDuration = 0.22
        /** Back from the composer, in the close's own 180ms, ease-out. */
        const val returnDuration = HomeDockFocusMotion.closeDuration

        /**
         * The fade for the dock going from hidden to shown, or null to show
         * it at once: off a window, or with animations off.
         */
        fun shown(origin: Origin, onWindow: Boolean, reduceMotion: Boolean): HomeDockFade? {
            if (!onWindow || reduceMotion) return null
            return when (origin) {
                Origin.Appearing -> HomeDockFade(0.0, 0.0, appearDuration)
                Origin.Focus -> HomeDockFade(HomeDockFocusMotion(0.0).composerAlpha, 0.0, returnDuration)
            }
        }
    }
}

/** A refused press while a session starts: two beats pointing at Stop (`refusalFlash`). */
object HomeDockRefusal {
    /** (target, duration) from 0. */
    val flash: List<Pair<Double, Double>> = listOf(1.0 to 0.09, 0.0 to 0.13, 1.0 to 0.09, 0.0 to 0.34)

    /** The flash at [time] seconds after the refusal. */
    fun flash(time: Double): Double {
        var start = 0.0
        var from = 0.0
        for ((value, duration) in flash) {
            if (time < start + duration) {
                val t = (time - start) / duration
                return from + (value - from) * easeInOutQuad(t)
            }
            start += duration
            from = value
        }
        return 0.0
    }

    /** The whole flash's length. */
    val flashDuration get() = flash.sumOf { it.second }

    /** `Easing.inOut(Easing.quad)`, Reanimated's `withTiming` default. */
    internal fun easeInOutQuad(t: Double): Double {
        val c = min(1.0, max(0.0, t))
        return if (c < 0.5) 2 * c * c else 1 - (-2 * c + 2).pow(2) / 2
    }
}
