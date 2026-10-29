package com.slopus.happy.ui.stories

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.icu.lang.UCharacter
import android.icu.lang.UProperty
import android.os.Build
import android.text.InputType
import android.text.TextPaint
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewOutlineProvider
import android.view.animation.DecelerateInterpolator
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.TextView
import androidx.core.widget.doAfterTextChanged
import com.slopus.happy.R
import com.slopus.happy.icons.Icon
import com.slopus.happy.layout.Palette
import com.slopus.happy.layout.Rect
import com.slopus.happy.layout.Size
import com.slopus.happy.layout.stories.StickerPackKindLayout
import com.slopus.happy.layout.stories.StickerPickerLayout
import com.slopus.happy.layout.stories.StoryStickerPillsLayout
import com.slopus.happy.layout.storiesStickerPill
import com.slopus.happy.layout.storiesStickerSheet
import com.slopus.happy.stories.StickerFormat
import com.slopus.happy.stories.StickerPackContents
import com.slopus.happy.stories.StickerPackKind
import com.slopus.happy.stories.StickerRef
import com.slopus.happy.stories.searchStickers
import com.slopus.happy.stories.stickerPack
import com.slopus.happy.stories.stickerPacks
import com.slopus.happy.ui.components.IconView
import com.slopus.happy.ui.theme.Theme
import com.slopus.happy.ui.view.BounceScrollView
import com.slopus.happy.ui.view.FrameContainer
import com.slopus.happy.ui.view.ManualLayout
import com.slopus.happy.ui.view.dp
import com.slopus.happy.ui.view.px
import com.slopus.happy.ui.view.setFrame
import com.slopus.happy.ui.view.setFrameOrHide
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.BreakIterator
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * What the picker hands back: a sticker from a pack, a system emoji, or
 * one of the interactive stickers over them (a link, a reaction bubble),
 * which the editor asks more of.
 */
sealed class StickerPick {
    data class Sticker(val sticker: com.slopus.happy.stories.Sticker) : StickerPick()
    data class Emoji(val emoji: String) : StickerPick()
    data object Link : StickerPick()
    data object Reaction : StickerPick()
}

/**
 * The story editor's sticker picker (happy-ios `StickerPickerViewController`),
 * drawn as happy-agent's API.md "The picker" says and Telegram's sticker
 * panel looks: a search field and emoji chips, then one scroll of the
 * interactive stickers (LINK and the reaction bubble), Recently used (with
 * its ✕) and every pack under its centred uppercase title, and a floating
 * switch between Stickers and Emoji. Tapping hands the pick back; holding
 * shows it large.
 *
 * iOS shows it as a page sheet with medium and large detents; here it is a
 * sheet over the editor from the foot of the screen that opens half way, a
 * drag taking it full or away, as does a tap above it.
 */
class StickerPickerSheet(context: Context, private val session: StoriesSession, private val mode: Mode) : ManualLayout(context), BounceScrollView.Listener {
    enum class Mode {
        /** Stickers and emoji, for a sticker or emoji overlay. */
        All,

        /** The Emoji tab's system emoji alone, for a reaction bubble. */
        EmojiOnly,
    }

    private enum class Tab { Stickers, Emoji }

    private class Section(val title: String?, val kind: StickerPackKindLayout, val items: List<StickerPick>, val clears: Boolean = false)

    var onPick: ((StickerPick) -> Unit)? = null
    var onClose: (() -> Unit)? = null

    /** The keyboard's height over the screen's foot (dp); the switch floats over it. */
    var keyboard = 0.0
        set(value) { if (field != value) { field = value; requestLayout() } }
    var statusBar = 0.0
        set(value) { field = value; requestLayout() }
    var homeIndicator = 0.0
        set(value) { field = value; requestLayout() }

    private var shownTab = if (mode == Mode.EmojiOnly) Tab.Emoji else Tab.Stickers
    private val panel = Panel(context)
    private val searchBox = ManualFrame(context)
    private val searchIcon = IconView(context, Icon.ionicons("search"), 16.0).apply { tintOverride = SECONDARY }
    private val search = EditText(context)
    private val chips = HorizontalScrollView(context)
    private val chipStrip = FrameContainer(context)
    private val grid = BounceScrollView(context)
    private val pills = ManualFrame(context)
    private val linkPill = LinkPill(context)
    private val reactionPill = ReactionPill(context)
    private val empty = TextView(context)
    private val switcher = ManualFrame(context)
    private val switchButtons = listOf(TextView(context), TextView(context))
    private val previewDim = ManualFrame(context)
    private val preview = StickerCell(context)
    private var packs: List<StickerPackContents> = emptyList()
    private var recents: List<StickerRef> = emptyList()
    private var results: List<com.slopus.happy.stories.Sticker>? = null
    private var sections: List<Section> = emptyList()
    private var placed: StickerPickerLayout? = null
    private var pillsHeight = 0.0
    private val headers = ArrayList<Header>()
    private val shownCells = HashMap<Long, StickerCell>()
    private val pool = ArrayList<StickerCell>()
    private val scope = MainScope()
    private var watch: Job? = null
    private var searching: Job? = null
    private var holding = false

    init {
        tag = "sticker-picker"
        clipChildren = true
        panel.tag = "sticker-picker-panel"
        searchBox.background = rounded(10.0, SEARCH_FILL)
        searchBox.visibility = if (mode == Mode.EmojiOnly) GONE else VISIBLE
        searchBox.addView(searchIcon)
        search.tag = "sticker-search"
        search.hint = context.getString(R.string.stories_search)
        search.setHintTextColor(SECONDARY)
        search.setTextColor(Color.WHITE)
        search.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 17f)
        search.background = null
        search.setPadding(0, 0, 0, 0)
        search.isSingleLine = true
        search.gravity = Gravity.CENTER_VERTICAL
        search.imeOptions = EditorInfo.IME_ACTION_SEARCH
        search.inputType = InputType.TYPE_CLASS_TEXT
        if (Build.VERSION.SDK_INT >= 29) search.textCursorDrawable?.setTint(Color.WHITE)
        search.doAfterTextChanged { runSearch() }
        search.setOnEditorActionListener { _, _, _ -> hideKeyboard(); true }
        // Searching grows the sheet, so the results show over the keyboard.
        search.setOnFocusChangeListener { _, focused -> if (focused && !full) { full = true; animate(to = fullHeight) } }
        searchBox.addView(search)
        panel.addView(searchBox)
        chips.isHorizontalScrollBarEnabled = false
        chips.overScrollMode = OVER_SCROLL_NEVER
        chips.visibility = searchBox.visibility
        for (emoji in StoryEmojiCatalog.chips) {
            val chip = TextView(context)
            chip.text = emoji
            chip.gravity = Gravity.CENTER
            chip.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 22f)
            chip.includeFontPadding = false
            chip.background = rounded(17.0, Color.argb(20, 255, 255, 255))
            chip.tag = "sticker-chip:$emoji"
            chip.contentDescription = emoji
            chip.setOnClickListener { chip(emoji) }
            chipStrip.addView(chip)
        }
        chips.addView(chipStrip)
        panel.addView(chips)
        grid.tag = "sticker-grid"
        grid.listener = this
        panel.addView(grid)
        makePills()
        empty.text = context.getString(R.string.stories_no_results)
        empty.setTextColor(SECONDARY)
        empty.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 17f)
        empty.gravity = Gravity.CENTER
        empty.visibility = GONE
        panel.addView(empty)
        // The floating switch.
        switcher.background = rounded(18.0, Color.argb(242, 51, 51, 51))
        switcher.visibility = searchBox.visibility
        for ((button, value) in switchButtons.zip(listOf(Tab.Stickers, Tab.Emoji))) {
            button.text = context.getString(if (value == Tab.Stickers) R.string.stories_stickers else R.string.stories_emoji)
            button.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14f)
            button.typeface = StoryViewerPageView.semibold
            button.gravity = Gravity.CENTER
            button.tag = if (value == Tab.Stickers) "sticker-tab-stickers" else "sticker-tab-emoji"
            button.setOnClickListener { select(value) }
            switcher.addView(button)
        }
        panel.addView(switcher)
        previewDim.setBackgroundColor(Color.argb(153, 0, 0, 0))
        previewDim.visibility = GONE
        previewDim.addView(preview)
        panel.addView(previewDim)
        addView(panel)
        watch = session.watch { snapshot ->
            if (snapshot.recents == recents) return@watch
            recents = snapshot.recents
            rebuild()
        }
        // The reaction's emoji are the system's alone; no packs to read.
        if (mode == Mode.All) load()
        rebuild()
        styleSwitch()
    }

    // Presenting

    private var full = false
    /** How much of the sheet shows above the screen's foot (dp). */
    private var shown = 0.0
    private var animator: ValueAnimator? = null
    private var closing = false

    /** The medium detent: half the screen. */
    private val half: Double get() = px(height) / 2
    /** The large detent: up to just under the status bar. */
    private val fullHeight: Double get() = px(height) - statusBar - TOP_GAP
    private val resting: Double get() = if (full) fullHeight else half

    /** Slides up into place, half open. */
    fun present() {
        shown = 0.0
        requestLayout()
        post { animate(to = half) }
    }

    /** Slides down away from wherever it is, then [then]. */
    fun dismiss(then: (() -> Unit)? = null) {
        if (closing) return
        closing = true
        hideKeyboard()
        animate(to = 0.0) {
            (parent as? android.view.ViewGroup)?.removeView(this)
            release()
            onClose?.invoke()
            onClose = null
            then?.invoke()
        }
    }

    private fun release() {
        scope.cancel()
        watch?.cancel()
        for (cell in shownCells.values + pool) cell.content.visible = false
        preview.content.visible = false
    }

    private fun animate(to: Double, end: (() -> Unit)? = null) {
        animator?.cancel()
        val from = shown
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 350
            interpolator = DecelerateInterpolator(2f)
            addUpdateListener {
                shown = from + (to - from) * (it.animatedValue as Float)
                placePanel()
            }
            if (end != null) addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) = end()
            })
            start()
        }
    }

    private fun placePanel() {
        val height = px(height)
        // Below half it keeps its half height and slides down.
        val tall = max(shown, half)
        panel.setFrame(Rect(0.0, height - max(0.0, shown), px(width), tall))
        layoutPanel()
    }

    private fun hideKeyboard() {
        if (!search.hasFocus()) return
        context.getSystemService(InputMethodManager::class.java)?.hideSoftInputFromWindow(search.windowToken, 0)
        search.clearFocus()
    }

    override fun layoutSubviews(width: Double, height: Double) {
        placePanel()
    }

    private fun layoutPanel() {
        val width = px(panel.width)
        val height = px(panel.height)
        if (width <= 0) return
        var y = 12.0
        if (mode == Mode.All) {
            searchBox.setFrame(Rect(12.0, y, width - 24, 36.0))
            searchIcon.setFrame(Rect(8.0, 10.0, 16.0, 16.0))
            search.setFrame(Rect(30.0, 0.0, width - 24 - 38, 36.0))
            y += 36 + 8
            var x = 0.0
            for (index in 0 until chipStrip.childCount) {
                chipStrip.frames[chipStrip.getChildAt(index)] = Rect(x, 0.0, 44.0, 34.0)
                x += 44 + 6
            }
            chipStrip.contentSize = Size(max(0.0, x - 6), 34.0)
            chips.setFrame(Rect(12.0, y, width - 24, 34.0))
            chipStrip.setFrame(Rect(0.0, 0.0, chipStrip.contentSize.width, 34.0))
            chipStrip.layoutSubviews(chipStrip.contentSize.width, 34.0)
            y += 34 + 8
        }
        grid.setFrame(Rect(0.0, y, width, max(0.0, height - y)))
        layoutGrid(width)
        empty.setFrame(Rect(16.0, y + 40, width - 32, 24.0))
        val switchSize = Size(200.0, 36.0)
        // The keyboard's top in the sheet, while it is up.
        val keyboardTop = if (keyboard > 0) px(this.height) - keyboard - px(panel.top) else Double.MAX_VALUE
        val floor = min(height - homeIndicator, keyboardTop)
        switcher.setFrame(Rect((width - switchSize.width) / 2, floor - 12 - switchSize.height, switchSize.width, switchSize.height))
        for ((index, button) in switchButtons.withIndex()) button.setFrame(Rect(3.0 + index * 97, 3.0, 97.0, 30.0))
        previewDim.setFrame(Rect(0.0, 0.0, width, height))
        val side = min(width * 0.7, 300.0)
        preview.setFrame(Rect((width - side) / 2, (height - side) / 2 - 40, side, side))
    }

    // Data

    private fun load() {
        val client = session.client
        val media = session.media
        scope.launch {
            val list = runCatching { client.stickerPacks() }.getOrNull() ?: return@launch
            val contents = list.map { pack -> async { runCatching { client.stickerPack(pack.id) }.getOrNull() } }.awaitAll().filterNotNull()
            for (pack in contents) media.remember(pack)
            // The app ships no VP9 decoder: webm stickers are left out.
            packs = contents.map { pack -> pack.copy(stickers = pack.stickers.filter { it.format != StickerFormat.Webm }) }.filter { it.stickers.isNotEmpty() }
            rebuild()
        }
    }

    private fun rebuild() {
        val next = mutableListOf<Section>()
        val results = results
        if (results != null) {
            next += Section(null, if (shownTab == Tab.Stickers) StickerPackKindLayout.Stickers else StickerPackKindLayout.Emoji, results.map { StickerPick.Sticker(it) })
        } else if (shownTab == Tab.Stickers) {
            val known = HashMap<StickerRef, com.slopus.happy.stories.Sticker>()
            for (sticker in packs.flatMap { it.stickers }) known.putIfAbsent(sticker.ref, sticker)
            // A recent sticker whose pack is gone is left out.
            val recent = recents.mapNotNull { known[it] }
            if (recent.isNotEmpty()) next += Section(context.getString(R.string.stories_recently_used), StickerPackKindLayout.Stickers, recent.map { StickerPick.Sticker(it) }, clears = true)
            for (pack in packs) if (pack.pack.kind == StickerPackKind.Stickers) {
                next += Section(pack.pack.title, StickerPackKindLayout.Stickers, pack.stickers.map { StickerPick.Sticker(it) })
            }
        } else {
            for (category in StoryEmojiCatalog.categories(context)) {
                next += Section(category.name, StickerPackKindLayout.Emoji, category.emoji.map { StickerPick.Emoji(it) })
            }
            if (mode == Mode.All) for (pack in packs) if (pack.pack.kind == StickerPackKind.Emoji) {
                next += Section(pack.pack.title, StickerPackKindLayout.Emoji, pack.stickers.map { StickerPick.Sticker(it) })
            }
        }
        sections = next
        empty.visibility = if (results?.isEmpty() == true) VISIBLE else GONE
        // Every cell shows its pick afresh.
        for (cell in shownCells.values) recycle(cell)
        shownCells.clear()
        while (headers.size < next.size) Header(context).also { headers += it; grid.content.addView(it) }
        for ((index, header) in headers.withIndex()) {
            val section = next.getOrNull(index)
            if (section == null) grid.content.frames[header] = null else header.show(section.title ?: "", section.clears)
        }
        placed = null
        layoutPanel()
    }

    private fun layoutGrid(width: Double) {
        val shownPills = mode == Mode.All && shownTab == Tab.Stickers && results == null
        pills.visibility = if (shownPills) VISIBLE else GONE
        var pillsHeight = 0.0
        if (shownPills) {
            val r = StoryStickerPillsLayout.Metrics.reaction
            val layout = StoryStickerPillsLayout(width, listOf(StoryStickerPillsLayout.pill(linkPill.titleWidth), Size(r, r)))
            pillsHeight = layout.height
            grid.content.frames[pills] = Rect(0.0, 0.0, width, layout.height)
            pills.setFrame(Rect(0.0, 0.0, width, layout.height))
            linkPill.setFrame(layout.items[0])
            reactionPill.setFrame(layout.items[1])
        } else {
            grid.content.frames[pills] = null
        }
        // The pills are part of the scroll, over the stickers.
        if (pillsHeight != this.pillsHeight) {
            this.pillsHeight = pillsHeight
            grid.setContentOffset(0.0)
        }
        val placed = placed?.takeIf { placedWidth == width } ?: StickerPickerLayout(
            width,
            sections.map { StickerPickerLayout.Section(it.kind, it.items.size, header = it.title != null) },
            sections.indices.filter { sections[it].clears }.toSet(),
        ).also { placed = it; placedWidth = width }
        grid.setInsets(0.0, homeIndicator)
        grid.contentHeight = pillsHeight + placed.height
        val most = max(0.0, grid.contentHeight + homeIndicator - px(grid.height))
        if (grid.contentOffset > most && !grid.isDragging) grid.setContentOffset(most)
        for ((index, header) in headers.withIndex()) {
            val frame = placed.sections.getOrNull(index)?.header
            val moved = frame?.let { Rect(it.minX, it.minY + pillsHeight, it.width, it.height) }
            grid.content.frames[header] = moved
            header.setFrameOrHide(moved)
        }
        placeCells()
    }

    private var placedWidth = 0.0

    /** Only the cells in sight (and a little past) are views; the rest wait in a pool. */
    private fun placeCells() {
        val placed = placed ?: return
        val top = grid.contentOffset - pillsHeight - 120
        val bottom = grid.contentOffset - pillsHeight + px(grid.height) + 120
        val wanted = HashSet<Long>()
        for ((s, section) in placed.sections.withIndex()) {
            val items = sections.getOrNull(s)?.items ?: continue
            for ((i, frame) in section.cells.withIndex()) {
                if (frame.maxY < top || frame.minY > bottom || i >= items.size) continue
                val key = (s.toLong() shl 32) or i.toLong()
                wanted += key
                val cell = shownCells[key] ?: (pool.removeLastOrNull() ?: newCell()).also { cell ->
                    shownCells[key] = cell
                    cell.pick = items[i]
                    cell.show(items[i], session.media)
                }
                val moved = Rect(frame.minX, frame.minY + pillsHeight, frame.width, frame.height)
                grid.content.frames[cell] = moved
                cell.setFrameOrHide(moved)
            }
        }
        val gone = shownCells.keys.filter { it !in wanted }
        for (key in gone) shownCells.remove(key)?.let(::recycle)
    }

    private fun recycle(cell: StickerCell) {
        // Animated stickers play only while visible.
        cell.content.visible = false
        grid.content.frames[cell] = null
        cell.visibility = GONE
        if (cell !in pool) pool += cell
    }

    private fun newCell(): StickerCell {
        val cell = StickerCell(context)
        cell.setOnClickListener { cell.pick?.let(::picked) }
        cell.setOnLongClickListener { held(cell) }
        grid.content.addView(cell)
        return cell
    }

    override fun didScroll() = placeCells()

    override fun willBeginDragging() = hideKeyboard()

    // Interactive stickers

    /** Telegram's pills over the stickers: LINK, and the white reaction bubble with a heart. */
    private fun makePills() {
        linkPill.tag = "sticker-link"
        linkPill.contentDescription = context.getString(R.string.stories_link)
        linkPill.setOnClickListener { onPick?.invoke(StickerPick.Link) }
        pills.addView(linkPill)
        reactionPill.tag = "sticker-reaction"
        reactionPill.contentDescription = context.getString(R.string.stories_reaction)
        reactionPill.setOnClickListener { onPick?.invoke(StickerPick.Reaction) }
        pills.addView(reactionPill)
        grid.content.addView(pills)
    }

    private fun select(next: Tab) {
        if (next == shownTab) return
        shownTab = next
        if (results != null) runSearch()
        rebuild()
        grid.setContentOffset(0.0)
        placeCells()
        styleSwitch()
    }

    private fun styleSwitch() {
        for ((index, button) in switchButtons.withIndex()) {
            val on = (index == 0) == (shownTab == Tab.Stickers)
            button.background = if (on) rounded(15.0, Color.argb(46, 255, 255, 255)) else null
            button.setTextColor(if (on) Color.WHITE else Color.argb(153, 255, 255, 255))
            button.isSelected = on
        }
    }

    // Search

    private fun chip(emoji: String) {
        search.setText(emoji)
        search.setSelection(search.length())
    }

    private fun runSearch() {
        searching?.cancel()
        val text = search.text?.toString()?.trim() ?: ""
        if (text.isEmpty()) {
            if (results == null) return
            results = null
            rebuild()
            return
        }
        val client = session.client
        val kind = if (shownTab == Tab.Stickers) StickerPackKind.Stickers else StickerPackKind.Emoji
        val isEmoji = isOneEmoji(text)
        searching = scope.launch {
            delay(250)
            val found = runCatching {
                client.searchStickers(query = if (isEmoji) null else text.take(64), emoji = if (isEmoji) text else null, kind = kind)
            }.getOrNull() ?: emptyList()
            results = found.filter { it.format != StickerFormat.Webm }
            rebuild()
        }
    }

    // Picking

    private fun picked(pick: StickerPick) {
        if (closing) return
        if (pick is StickerPick.Sticker) session.store.useSticker(pick.sticker.ref)
        onPick?.invoke(pick)
    }

    private fun clearRecents() = session.store.clearRecents()

    private fun held(cell: StickerCell): Boolean {
        val pick = cell.pick as? StickerPick.Sticker ?: return false
        holding = true
        parent?.requestDisallowInterceptTouchEvent(true)
        grid.requestDisallowInterceptTouchEvent(true)
        preview.show(pick, session.media)
        preview.content.visible = true
        previewDim.animate().cancel()
        previewDim.visibility = VISIBLE
        previewDim.alpha = 0f
        previewDim.animate().alpha(1f).setDuration(150).start()
        return true
    }

    private fun letGo() {
        holding = false
        preview.content.visible = false
        previewDim.animate().alpha(0f).setDuration(150).withEndAction {
            // A new hold that began during the fade keeps it.
            if (previewDim.alpha == 0f) previewDim.visibility = GONE
        }.start()
    }

    // Dragging

    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private var tracker: VelocityTracker? = null
    private var downX = 0f
    private var downY = 0f
    private var start = 0.0
    private var dragging = false
    private var onGrid = false

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        val handled = super.dispatchTouchEvent(ev)
        if (holding && (ev.actionMasked == MotionEvent.ACTION_UP || ev.actionMasked == MotionEvent.ACTION_CANCEL)) letGo()
        return handled
    }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        if (closing) return true
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                tracker?.recycle()
                tracker = VelocityTracker.obtain().also { it.addMovement(ev) }
                downX = ev.x
                downY = ev.y
                dragging = false
                // Above the sheet: a tap there closes it.
                if (ev.y < panel.top) return true
                animator?.cancel()
                onGrid = ev.y >= panel.top + grid.top && ev.y < panel.top + grid.bottom
                start = shown
            }
            MotionEvent.ACTION_MOVE -> {
                tracker?.addMovement(ev)
                val dy = ev.y - downY
                if (!dragging && !holding && abs(dy) > slop && abs(dy) > abs(ev.x - downX)) {
                    // Only a full sheet's grid scrolls; below full the sheet moves.
                    val fullNow = shown >= fullHeight - 0.5
                    val gridTakes = onGrid && fullNow && (dy < 0 || grid.contentOffset > 0)
                    if (!gridTakes) {
                        dragging = true
                        downY = ev.y
                        start = shown
                        hideKeyboard()
                        return true
                    }
                }
            }
        }
        return dragging
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        tracker?.addMovement(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> return true
            MotionEvent.ACTION_MOVE -> {
                if (!dragging) {
                    if (abs(event.y - downY) <= slop) return true
                    dragging = true
                    start = shown
                    downY = event.y
                }
                shown = max(0.0, min(fullHeight, start - px(event.y - downY)))
                placePanel()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val wasDragging = dragging
                dragging = false
                if (!wasDragging) {
                    if (event.actionMasked == MotionEvent.ACTION_UP && downY < panel.top && !closing) dismiss()
                    return true
                }
                tracker?.computeCurrentVelocity(1000)
                val velocity = px(tracker?.yVelocity ?: 0f)
                if (event.actionMasked == MotionEvent.ACTION_UP) {
                    if (shown < half - 80 || (velocity > 800 && shown <= half + 1)) return true.also { dismiss() }
                    full = if (velocity < -500) true else if (velocity > 500) false else shown > (half + fullHeight) / 2
                }
                animate(to = resting)
            }
        }
        return true
    }

    /** The sheet itself: its dark ground, the top corners rounded, and the grabber. */
    private inner class Panel(context: Context) : ManualLayout(context) {
        private val grabber = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(77, 255, 255, 255) }

        init {
            setWillNotDraw(false)
            isClickable = true
            setBackgroundColor(Theme.color(Palette.storiesStickerSheet))
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    val r = dp(CORNER)
                    outline.setRoundRect(0, 0, view.width, view.height + r.toInt(), r)
                }
            }
            clipToOutline = true
        }

        override fun dispatchDraw(canvas: Canvas) {
            super.dispatchDraw(canvas)
            val w = dp(36.0)
            val h = dp(5.0)
            val y = dp(5.0)
            canvas.drawRoundRect(RectF((width - w) / 2, y, (width + w) / 2, y + h), h / 2, h / 2, grabber)
        }

        override fun layoutSubviews(width: Double, height: Double) = layoutPanel()
    }

    /** A pack's (or Recently used's) centred uppercase title, with the ✕. */
    private inner class Header(context: Context) : ManualLayout(context) {
        private val label = TextView(context)
        private val clear = ManualFrame(context)
        private val clearIcon = IconView(context, Icon.ionicons("close"), 16.0).apply { tintOverride = SECONDARY }

        init {
            label.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13f)
            label.typeface = StoryViewerPageView.semibold
            label.setTextColor(SECONDARY)
            label.gravity = Gravity.CENTER
            label.isSingleLine = true
            label.includeFontPadding = false
            addView(label)
            clear.addView(clearIcon)
            clear.tag = "sticker-recents-clear"
            clear.contentDescription = context.getString(R.string.stories_clear_recents)
            clear.setOnClickListener { clearRecents() }
            addView(clear)
        }

        fun show(title: String, clears: Boolean) {
            label.text = title.uppercase()
            tag = "sticker-header:$title"
            clear.visibility = if (clears) VISIBLE else GONE
        }

        override fun layoutSubviews(width: Double, height: Double) {
            val side = StickerPickerLayout.Metrics.clear
            label.setFrame(Rect(side, 0.0, max(0.0, width - 2 * side), height))
            clear.setFrame(Rect(width - side, 0.0, side, height))
            clearIcon.setFrame(Rect((side - 16) / 2, (height - 16) / 2, 16.0, 16.0))
        }
    }

    /** LINK, in Telegram's condensed camera face (the system's condensed semibold stands in), after the link glyph. */
    private class LinkPill(context: Context) : View(context) {
        private val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = (22 * Theme.density).toFloat()
            color = Color.WHITE
            typeface = if (Build.VERSION.SDK_INT >= 28) Typeface.create(Typeface.create("sans-serif-condensed", Typeface.NORMAL), 600, false) else Typeface.create("sans-serif-condensed", Typeface.BOLD)
        }
        private val title = context.getString(R.string.stories_link).uppercase()
        val titleWidth: Double get() = paint.measureText(title) / Theme.density.toDouble()

        init {
            background = rounded(6.0, Theme.color(Palette.storiesStickerPill))
        }

        override fun onDraw(canvas: Canvas) {
            val m = StoryStickerPillsLayout.Metrics
            val icon = StoryEditorIcons.link
            icon.draw(canvas, dp(m.pillPadding), (height - dp(icon.height)) / 2, Color.WHITE)
            val x = dp(m.pillPadding + m.pillIcon + m.pillGap)
            val baseline = height / 2f - (paint.ascent() + paint.descent()) / 2
            canvas.drawText(title, x, baseline, paint)
        }
    }

    /** The white reaction bubble with a heart in it. */
    private class ReactionPill(context: Context) : View(context) {
        private val heart = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = (26 * Theme.density).toFloat()
            textAlign = Paint.Align.CENTER
        }

        override fun onDraw(canvas: Canvas) {
            StoryEditorIcons.reactionBubble.draw(canvas, 0f, 0f, Color.WHITE)
            val cx = dp(5.0 + 22)
            val cy = dp(3.0 + 22)
            canvas.drawText(HEART, cx, cy - (heart.ascent() + heart.descent()) / 2, heart)
        }
    }

    /** A ManualLayout whose children are placed from outside. */
    private class ManualFrame(context: Context) : ManualLayout(context) {
        override fun layoutSubviews(width: Double, height: Double) {}
    }

    companion object {
        private const val CORNER = 10.0
        private const val TOP_GAP = 10.0
        private const val HEART = "❤️"
        private val SECONDARY = Color.argb(128, 255, 255, 255)
        /** UISearchTextField's dark fill. */
        private val SEARCH_FILL = Color.argb(61, 118, 118, 128)

        private fun rounded(radius: Double, color: Int) = GradientDrawable().apply {
            cornerRadius = (radius * Theme.density).toFloat()
            setColor(color)
        }

        /** One emoji: drawn as emoji by default, or asked to be (❤️ is ❤ and U+FE0F). */
        fun isOneEmoji(text: String): Boolean {
            val graphemes = BreakIterator.getCharacterInstance()
            graphemes.setText(text)
            graphemes.first()
            if (graphemes.next() != text.length) return false
            val first = text.codePointAt(0)
            val scalars = text.codePointCount(0, text.length)
            if (Build.VERSION.SDK_INT >= 28) {
                return UCharacter.hasBinaryProperty(first, UProperty.EMOJI_PRESENTATION) ||
                    (UCharacter.hasBinaryProperty(first, UProperty.EMOJI) && scalars > 1)
            }
            return first >= 0x1F000 || (scalars > 1 && text.contains('\uFE0F'))
        }
    }
}

/** One sticker, fitted in its square, or one emoji. */
class StickerCell(context: Context) : View(context) {
    val content = StoryStickerContent(this)
    var pick: StickerPick? = null
    private var emoji: String? = null
    private val emojiPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }

    fun show(pick: StickerPick, media: StoriesMedia) {
        this.pick = pick
        when (pick) {
            is StickerPick.Sticker -> {
                emoji = null
                content.visible = true
                content.show(pick.sticker, media)
                tag = "sticker:${pick.sticker.packId}/${pick.sticker.id}"
                contentDescription = pick.sticker.emoji.firstOrNull()
            }
            is StickerPick.Emoji -> {
                // A cell that showed an animated sticker stops it.
                content.visible = false
                emoji = pick.emoji
                tag = "emoji:${pick.emoji}"
                contentDescription = pick.emoji
            }
            StickerPick.Link, StickerPick.Reaction -> {}
        }
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        content.read()
    }

    override fun onDraw(canvas: Canvas) {
        val emoji = emoji
        if (emoji == null) {
            // Inset by 2 on each side, as the content view is.
            val inset = (2 * Theme.density).toFloat()
            val sx = (width - 2 * inset) / width.coerceAtLeast(1)
            val sy = (height - 2 * inset) / height.coerceAtLeast(1)
            canvas.save()
            canvas.translate(inset, inset)
            canvas.scale(sx, sy)
            content.draw(canvas)
            canvas.restore()
            return
        }
        emojiPaint.textSize = height * 0.72f
        canvas.drawText(emoji, width / 2f, height / 2f - (emojiPaint.ascent() + emojiPaint.descent()) / 2, emojiPaint)
    }
}
