package com.slopus.happy.ui.stemcells

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.text.Layout
import android.text.format.DateFormat
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import com.slopus.happy.R
import com.slopus.happy.layout.Rect
import com.slopus.happy.layout.StemCellsPalette
import com.slopus.happy.layout.stemcells.StemCellSessionDetailLayout
import com.slopus.happy.layout.stemcells.StemCellsSettingsLayout
import com.slopus.happy.layout.stemcells.StemCellsSidebarLayout
import com.slopus.happy.stemcells.StemCellChatItem
import com.slopus.happy.stemcells.StemCellChatSnapshot
import com.slopus.happy.stemcells.StemCellModelMenus
import com.slopus.happy.stemcells.StemCellsDetailFormat
import com.slopus.happy.stemcells.StemCellsFamily
import com.slopus.happy.stemcells.StemCellsRow
import com.slopus.happy.stemcells.StemCellsSessionDetail
import com.slopus.happy.stemcells.StemCellsSessionDetailFeed
import com.slopus.happy.stemcells.StemCellsShared
import com.slopus.happy.stemcells.StemCellsSharedFeed
import com.slopus.happy.stemcells.StemCellsSnapshot
import com.slopus.happy.ui.components.Label
import com.slopus.happy.ui.components.TextField
import com.slopus.happy.ui.nav.BackInterceptor
import com.slopus.happy.ui.nav.Screen
import com.slopus.happy.ui.theme.Theme
import com.slopus.happy.ui.theme.ThemeAware
import com.slopus.happy.ui.view.BounceScrollView
import com.slopus.happy.ui.view.ManualLayout
import com.slopus.happy.ui.view.px
import com.slopus.happy.ui.view.setFrame
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.util.Date
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.max

/**
 * A conversation's detail, pushed from its header's capsule (happy-ios
 * `StemCellSessionDetailViewController`): the legacy app's session screen
 * made over in Settings' glass style. Its mark and name (renamed where it
 * stands), its model, effort and when it started as tiles; what it shared,
 * Telegram's shared media in our style (its pictures, recordings and files
 * in a grid, then the links written in it); where it sits in the sidebar's
 * tree drawn by the sidebar's own rows; and desktop's Activity: the
 * children at work and the terminals running, then the settled children
 * under a fold. Every row opens its conversation. Frames are
 * [StemCellSessionDetailLayout]'s; what it shows is read off the main
 * thread ([StemCellsSessionDetailFeed], [StemCellsSharedFeed]) and again as
 * the conversation's events come.
 */
class StemCellSessionDetailScreen(
    context: Context,
    private val session: StemCellsSession,
    private val agentId: String,
    /** What its screen called it, for one the sidebar doesn't hold. */
    title: String,
    private val spaceId: String?,
    /** The conversation as its screen holds it: what it shared, at once. */
    private val chatUpdates: StateFlow<StemCellChatSnapshot>?,
) : Screen(context), BackInterceptor {
    /** Renamed here: the conversation under it keeps new work it would otherwise put away. */
    var onRenamed: (() -> Unit)? = null
    /** A conversation of its family or a child was opened: the host pushes it. */
    var openConversation: ((agentId: String, title: String, spaceId: String?, row: StemCellsRow?) -> Unit)? = null

    private val fallbackTitle = title.ifEmpty { null }
    private val feed = StemCellsSessionDetailFeed(session.client, session.store, agentId)
    private val sharedFeed = StemCellsSharedFeed(session.client, agentId)
    private var sharedState = StemCellsSharedFeed.State()
    /** The transcript's and the history's, newest first. */
    private var shared = StemCellsShared()
    private var mediaLimit = MEDIA_PAGE
    private var linksLimit = LINKS_PAGE
    private var asking: Job? = null
    /** What a "Show more" asked while history was being read: asked once it's in. */
    private var pendingMedia = false
    private var pendingLinks = false
    private val reader = StemCellsShared.Reader()
    private val loader = StemCellMediaLoader(session.client, agentId)
    private val opener = StemCellMediaOpener(context, loader, scope, cover = { cover(it) }, uncovered = { stack?.backStateChanged() })
    private var detail: StemCellsSessionDetail? = null
    private var chat = StemCellChatSnapshot()
    private var row: StemCellsRow? = null
    private var family: List<StemCellsRow> = emptyList()
    private var completedOpen = false
    /** How far the settled card is open, 0 to 1: it moves between the two over the fold's animation. */
    private var foldShown = 0.0
    private var folding: android.animation.ValueAnimator? = null
    private var heldName: String? = null
    private var actionRows: List<StemCellsSettingsRow> = emptyList()

    private lateinit var root: Root
    private val bar = StemCellsGlassBarView(context)
    private val scroll = BounceScrollView(context)
    private val mark = StemCellDetailMarkView(context)
    private val name = Label(context, StemCellsFonts.ui(StemCellsFonts.Weight.Medium, 28.0), StemCellsPalette.stemCellsSettingsText, maxLines = 1, alignment = Layout.Alignment.ALIGN_CENTER).apply { followsTextSize = false }
    private var nameField: TextField? = null
    private val subtitle = Label(context, StemCellsFonts.ui(StemCellsFonts.Weight.Regular, 17.0), StemCellsPalette.stemCellsSettingsSecondary, maxLines = 1, alignment = Layout.Alignment.ALIGN_CENTER).apply { followsTextSize = false }
    private val tiles = List(3) { StemCellDetailTileView(context) }
    private val actions = StemCellsSettingsCardView(context)
    private val mediaHeading = StemCellDetailTileView.heading(context, context.getString(R.string.stem_media))
    private val mediaCard = StemCellDetailMediaCardView(context, loader)
    private val linksHeading = StemCellDetailTileView.heading(context, context.getString(R.string.stem_links))
    private val linksCard = StemCellDetailActivityCardView(context, "stem-detail-links")
    private val familyHeading = StemCellDetailTileView.heading(context, context.getString(R.string.stem_family))
    private val familyCard = StemCellDetailCardView(context)
    private val tree = StemCellsSidebarView(context)
    private val activityHeading = StemCellDetailTileView.heading(context, context.getString(R.string.stem_activity))
    private val running = StemCellDetailActivityCardView(context, "stem-detail-running")
    private val completed = StemCellDetailActivityCardView(context, "stem-detail-completed")
    private var unwatch: (() -> Unit)? = null
    private var unwatchModels: (() -> Unit)? = null

    /** What the harness reads. */
    val probe: String
        get() = "detail=${name.text} tiles=${tiles.joinToString("|") { it.shown }} media=${if (mediaCard.visibility == View.VISIBLE) mediaCard.count else 0} " +
            "links=${if (linksCard.visibility == View.VISIBLE) linksCard.count else 0} family=${family.size} " +
            "running=${if (running.visibility == View.VISIBLE) running.count else 0} completed=${detail?.completed?.size ?: 0} open=${if (completedOpen) 1 else 0} " +
            "renaming=${if (nameField != null) 1 else 0} viewer=${opener.probe}"

    override fun createView(): View {
        root = Root(context)
        root.tag = "stem-detail"
        root.addView(scroll)
        val content = scroll.content
        mark.images = session.images
        content.addView(mark)
        name.tag = "stem-detail-name"
        androidx.core.view.ViewCompat.setAccessibilityHeading(name, true)
        name.isClickable = true
        name.setOnClickListener { beginRename() }
        content.addView(name)
        subtitle.tag = "stem-detail-subtitle"
        content.addView(subtitle)
        for ((tile, id) in tiles.zip(listOf("model", "effort", "started"))) {
            tile.tag = "stem-detail-tile:$id"
            content.addView(tile)
        }
        content.addView(actions)
        content.addView(mediaHeading)
        mediaCard.onSelect = { openMedia(it) }
        mediaCard.onMore = { showMore(media = true) }
        content.addView(mediaCard)
        content.addView(linksHeading)
        linksCard.onSelect = { openLink(it) }
        content.addView(linksCard)
        content.addView(familyHeading)
        tree.list.isScrollEnabled = false
        tree.padding = TREE_PADDING to TREE_PADDING
        tree.images = session.images
        tree.onSelect = { open(it) }
        tree.tag = "stem-detail-family"
        familyCard.addView(tree)
        content.addView(familyCard)
        content.addView(activityHeading)
        running.onSelect = { index -> openChild(detail?.running, index) }
        content.addView(running)
        completed.onSelect = { index -> openChild(detail?.completed, index) }
        content.addView(completed)

        bar.hasBack = true
        bar.onBack = { pop() }
        root.addView(bar)

        unwatch = session.watch { follow(it) }
        unwatchModels = session.models.watch { applyNumbers() }
        chatUpdates?.let { updates ->
            chat = updates.value
            scope.launch {
                updates.collect { snapshot ->
                    val items = chat.items
                    chat = snapshot
                    applyNumbers()
                    if (snapshot.items != items) applyShared()
                }
            }
        }
        feed.follow(scope) { detail ->
            this.detail = detail
            applyName()
            applyNumbers()
            applyActivity()
        }
        applyNumbers()
        applyActivity()
        applyShared()
        askShared(media = true, links = true)
        root.applyTheme()
        return root
    }

    override fun onDisappear() {
        endEditingName(keep = true)
    }

    override fun onDestroy() {
        unwatch?.invoke()
        unwatchModels?.invoke()
    }

    override fun onInsetsChanged() {
        super.onInsetsChanged()
        if (::root.isInitialized) root.requestLayout()
    }

    // What it shows

    /** Its row as the sidebar has it now: name, mark, kind, and its family. */
    private fun follow(snapshot: StemCellsSnapshot) {
        val rows = snapshot.allRows
        val found = spaceId?.let { id -> rows.firstOrNull { it.spaceId == id && it.kind == StemCellsRow.Kind.Space } }
            ?: rows.firstOrNull { it.agentId == agentId && (it.kind == StemCellsRow.Kind.Space || it.kind == StemCellsRow.Kind.Session) }
        row = found
        if (heldName != null && found?.label == heldName) heldName = null
        applyName()
        val next = found?.let { StemCellsFamily.of(it.id, rows) } ?: emptyList()
        // Alone, it has no family worth a card.
        family = if (next.size > 1) next else emptyList()
        tree.apply(family)
        tree.selectedRowId = found?.id
        actionRows = if (canRename) listOf(
            StemCellsSettingsRow("rename", context.getString(R.string.stem_rename), icon = "pencil" to StemCellsSettingsRow.Tile.Blue,
                accessory = StemCellsSettingsLayout.Accessory.None, action = { beginRename() }),
        ) else emptyList()
        if (::root.isInitialized) root.requestLayout()
    }

    /** Its row's name, mark and kind; else, for a subagent the sidebar doesn't hold, what its screen and the daemon call it. */
    private fun applyName() {
        name.text = heldName ?: row?.label ?: detail?.title ?: fallbackTitle ?: context.getString(R.string.stem_untitled_session)
        mark.mark = row?.mark ?: StemCellsRow.Mark.Glyph("agents")
        subtitle.text = row?.let { subtitle(it) } ?: subtitle(detail?.subtask == true, detail?.working == true)
    }

    /** A task or a bot is renamed; Happy never. */
    private val canRename: Boolean
        get() {
            val row = row ?: return false
            if (spaceId == null) return false
            return !row.isHappy && (row.targetKind == "task" || row.targetKind == "bot")
        }

    private fun subtitle(subtask: Boolean, working: Boolean): String =
        listOfNotNull(
            context.getString(if (subtask) R.string.stem_subtask else R.string.stem_subagent),
            if (working) context.getString(R.string.stem_phase_working) else null,
        ).joinToString(" · ")

    private fun subtitle(row: StemCellsRow): String {
        val kind = when {
            row.kind == StemCellsRow.Kind.Session -> R.string.stem_subtask
            row.targetKind == "bot" -> R.string.stem_bot
            row.targetKind == "task" -> R.string.stem_task
            else -> R.string.stem_conversation
        }
        val status = when (row.status) {
            StemCellsRow.Status.Working, StemCellsRow.Status.Waiting -> context.getString(R.string.stem_phase_working)
            StemCellsRow.Status.AwaitingInput -> context.getString(R.string.stem_phase_awaiting_answer)
            StemCellsRow.Status.None -> null
        }
        return listOfNotNull(context.getString(kind), status).joinToString(" · ")
    }

    /** The tiles: model, effort, and when it started. */
    private fun applyNumbers() {
        val none = context.getString(R.string.stem_none)
        val mode = detail?.mode ?: chat.mode
        val menus = session.models.catalog?.let { catalog -> mode?.let { StemCellModelMenus(catalog, it) } }
        val values = listOf(
            (menus?.current?.label ?: mode?.modelId ?: none) to context.getString(R.string.stem_model),
            ((menus?.effort ?: mode?.effort)?.let(StemCellModelMenus::effortName) ?: none) to context.getString(R.string.stem_effort),
            (detail?.createdAt?.let { started(StemCellsDetailFormat.started(it, System.currentTimeMillis())) } ?: none) to context.getString(R.string.stem_started),
        )
        for ((tile, pair) in tiles.zip(values)) tile.show(pair.first, pair.second)
    }

    /** "Today, 3:41 AM", "Yesterday, 9:12 PM", "Mon, 9:12 PM", "Oct 3", "Oct 3, 2025". */
    private fun started(started: StemCellsDetailFormat.Started): String {
        val date = Date(started.at)
        val locale = Locale.getDefault()
        fun pattern(skeleton: String) = DateFormat.format(DateFormat.getBestDateTimePattern(locale, skeleton), date).toString()
        val time = DateFormat.getTimeFormat(context).format(date)
        return when (started) {
            is StemCellsDetailFormat.Started.Today -> context.getString(R.string.stem_started_today, time)
            is StemCellsDetailFormat.Started.Yesterday -> context.getString(R.string.stem_started_yesterday, time)
            is StemCellsDetailFormat.Started.Weekday -> pattern(if (DateFormat.is24HourFormat(context)) "EEEHHmm" else "EEEhmma")
            is StemCellsDetailFormat.Started.ThisYear -> pattern("MMMd")
            is StemCellsDetailFormat.Started.Date -> pattern("MMMdyyyy")
        }
    }

    // Shared

    /** What the transcript in hand shows, then what history adds behind it. */
    private fun applyShared(force: Boolean = false) {
        val next = reader.read(chat.items).merged(sharedState.shared)
        if (!force && next == shared) return
        shared = next
        // Every picture, recording and file, for the viewer and the player to find.
        loader.register(listOf(StemCellChatItem("shared", StemCellChatItem.Role.Agent, shared.media.map { one ->
            when (one) {
                is StemCellsShared.Media.Picture -> StemCellChatItem.Piece.Picture(one.id, one.mimeType, one.data)
                is StemCellsShared.Media.Tool -> if (one.media.isVisual) StemCellChatItem.Piece.Gallery(listOf(one.media), 0) else StemCellChatItem.Piece.File(one.media)
            }
        }, StemCellChatItem.Delivery.Sent)))
        mediaCard.show(shownMedia)
        linksCard.show(
            shownLinks.map { link ->
                StemCellDetailActivityCardView.Item("link-outline", StemCellsSettingsRow.Tile.Blue, link.title ?: link.host, address(link.url), true, "stem-detail-link:${link.url}")
            },
            disclosure = null,
            more = StemCellsSettingsRow("links-more", context.getString(R.string.stem_show_more), accessory = StemCellsSettingsLayout.Accessory.None, action = { showMore(media = false) }),
        )
        if (::root.isInitialized) root.requestLayout()
    }

    private val shownMedia: List<StemCellsShared.Media> get() = shared.media.take(mediaLimit)
    private val shownLinks: List<StemCellsShared.Link> get() = shared.links.take(linksLimit)

    /** More than is shown, or history still unread that may hold more. */
    private fun hasMore(count: Int, limit: Int) = count > limit || sharedState.hasMore

    /**
     * History back far enough for one more than the asked sections show,
     * so each knows whether there is more. Asked while reading, it asks
     * again once that is in.
     */
    private fun askShared(media: Boolean, links: Boolean) {
        if (asking != null) {
            pendingMedia = pendingMedia || media
            pendingLinks = pendingLinks || links
            return
        }
        val mediaTarget = if (media) mediaLimit + 1 else null
        val linksTarget = if (links) linksLimit + 1 else null
        asking = scope.launch {
            val state = sharedFeed.load(mediaTarget, linksTarget)
            asking = null
            sharedState = state
            applyShared(force = true)
            val nextMedia = pendingMedia
            val nextLinks = pendingLinks
            pendingMedia = false
            pendingLinks = false
            if (nextMedia || nextLinks) askShared(nextMedia, nextLinks)
        }
    }

    private fun showMore(media: Boolean) {
        StemCellsHaptics.play(root, StemCellsHaptics.Moment.Fold)
        if (media) mediaLimit += MEDIA_PAGE else linksLimit += 2 * LINKS_PAGE
        applyShared(force = true)
        askShared(media = media, links = !media)
    }

    private fun applyActivity() {
        val detail = detail ?: return
        running.show(
            detail.running.map { child ->
                StemCellDetailActivityCardView.Item("agents", StemCellsSettingsRow.Tile.Purple, child.title ?: context.getString(R.string.stem_subagent), meta(child), true)
            } + detail.terminals.map { terminal ->
                val age = terminal.startedAt?.let { age(StemCellsDetailFormat.age(it, System.currentTimeMillis().toDouble())) }
                StemCellDetailActivityCardView.Item("terminal", StemCellsSettingsRow.Tile.Gray, terminal.command,
                    listOfNotNull(context.getString(R.string.stem_terminal_running), age).joinToString(" · "), false)
            },
            disclosure = null,
        )
        completed.show(
            detail.completed.map { child ->
                StemCellDetailActivityCardView.Item("agents", StemCellsSettingsRow.Tile.Gray, child.title ?: context.getString(R.string.stem_subagent), meta(child), true)
            },
            disclosure = StemCellsSettingsRow(
                "completed", context.getString(R.string.stem_completed), icon = "checkbox-outline" to StemCellsSettingsRow.Tile.Green,
                value = "${detail.completed.size}", accessory = StemCellsSettingsLayout.Accessory.Chevron, action = { toggleCompleted() },
            ),
        )
        completed.open = completedOpen
        if (::root.isInitialized) root.requestLayout()
    }

    private fun meta(child: StemCellsSessionDetail.Child): String =
        listOfNotNull(activityStatus(child.status, child.archived), child.tokens?.let { context.getString(R.string.stem_token_count, StemCellsDetailFormat.tokens(it)) }).joinToString(" · ")

    /** A child's state, as desktop's Activity words it. */
    private fun activityStatus(status: String, archived: Boolean): String = context.getString(
        when {
            archived -> R.string.stem_activity_closed
            status == "idle" -> R.string.stem_activity_done
            status == "thinking" -> R.string.stem_activity_thinking
            status == "generating_tools" || status == "running_tools" -> R.string.stem_activity_running_tools
            else -> R.string.stem_activity_working
        },
    )

    private fun age(age: StemCellsDetailFormat.Age): String {
        val r = context.resources
        return when (age) {
            StemCellsDetailFormat.Age.JustNow -> r.getString(R.string.stem_detail_just_now)
            is StemCellsDetailFormat.Age.Minutes -> r.getString(R.string.stem_detail_minutes, age.count)
            is StemCellsDetailFormat.Age.Hours -> r.getQuantityString(R.plurals.stem_detail_hours, age.count, age.count)
            is StemCellsDetailFormat.Age.Days -> r.getQuantityString(R.plurals.stem_detail_days, age.count, age.count)
            is StemCellsDetailFormat.Age.Months -> r.getQuantityString(R.plurals.stem_detail_months, age.count, age.count)
            is StemCellsDetailFormat.Age.Years -> r.getQuantityString(R.plurals.stem_detail_years, age.count, age.count)
        }
    }

    // Acts

    /** Another conversation of the family, over this one; this one's own row goes back to it. */
    private fun open(row: StemCellsRow) {
        val id = row.agentId ?: return
        if (!(row.kind == StemCellsRow.Kind.Space || row.kind == StemCellsRow.Kind.Session) || row.targetKind == "surface") return
        if (id == agentId) return pop()
        openConversation?.invoke(id, row.label, row.spaceId, row)
    }

    private fun openChild(children: List<StemCellsSessionDetail.Child>?, index: Int) {
        val child = children?.getOrNull(index) ?: return
        openConversation?.invoke(child.id, child.title ?: context.getString(R.string.stem_subagent), null, null)
    }

    /** A picture in the viewer, paging through every picture shared; a recording in the player; a file in an app for it. */
    private fun openMedia(index: Int) {
        val one = shownMedia.getOrNull(index) ?: return
        val pictures = shared.media.filter { it.isPicture }.map { picture ->
            when (picture) {
                is StemCellsShared.Media.Picture -> StemCellPicture(picture.key, null, context.getString(R.string.stem_picture))
                is StemCellsShared.Media.Tool -> StemCellPicture(picture.key, picture.media.item.thumbhash, picture.media.item.name)
            }
        }
        opener.open(one.key, pictures)
    }

    private fun openLink(index: Int) {
        val link = shownLinks.getOrNull(index) ?: return
        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(link.url))) }
    }

    private fun toggleCompleted() {
        StemCellsHaptics.play(root, StemCellsHaptics.Moment.Fold)
        completedOpen = !completedOpen
        completed.open = completedOpen
        // iOS's 0.25 s ease-in-out: the rows are placed where they open, and
        // the card grows over them (or shrinks back over them, then they go).
        folding?.cancel()
        folding = android.animation.ValueAnimator.ofFloat(foldShown.toFloat(), if (completedOpen) 1f else 0f).apply {
            duration = 250
            interpolator = android.view.animation.PathInterpolator(0.42f, 0f, 0.58f, 1f)
            addUpdateListener {
                foldShown = (it.animatedValue as Float).toDouble()
                root.requestLayout()
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    if (folding === animation) folding = null
                    root.requestLayout()
                }
            })
            start()
        }
    }

    private fun cover(view: View) {
        endEditingName(keep = true)
        view.translationZ = 64 * context.resources.displayMetrics.density
        root.addView(view)
        view.setFrame(Rect(0.0, 0.0, px(root.width), px(root.height)))
        stack?.backStateChanged()
    }

    override val interceptsBack: Boolean get() = opener.shown != null

    override fun onBackPressed(): Boolean = opener.dismiss()

    // Rename

    /** The name, in place, as a field, as Settings' is; Done or a tap away keeps it. */
    private fun beginRename() {
        val row = row
        if (nameField != null || !canRename || row == null) return
        val field = TextField(context, name.style, StemCellsPalette.stemCellsSettingsText)
        field.gravity = android.view.Gravity.CENTER
        field.isSingleLine = true
        field.imeOptions = EditorInfo.IME_ACTION_DONE
        field.inputType = EditorInfo.TYPE_CLASS_TEXT or EditorInfo.TYPE_TEXT_FLAG_CAP_SENTENCES
        field.tag = "stem-detail-rename"
        val initial = if (row.label == context.getString(R.string.stem_untitled)) "" else heldName ?: row.label
        field.setText(initial)
        field.onReturn = { finishRename(field.string, initial) }
        scroll.content.addView(field)
        nameField = field
        fieldInitial = initial
        root.requestLayout()
        field.focus()
        field.selectAll()
    }

    private var fieldInitial = ""

    private fun endEditingName(keep: Boolean) {
        val field = nameField ?: return
        finishRename(if (keep) field.string else null, fieldInitial)
    }

    private fun finishRename(kept: String?, initial: String) {
        val field = nameField ?: return
        nameField = null
        field.onReturn = null
        field.dismissKeyboard()
        scroll.content.frames.remove(field)
        scroll.content.removeView(field)
        root.requestLayout()
        val spaceId = spaceId ?: return
        val title = kept?.trim().orEmpty()
        if (title.isEmpty() || title == initial) return
        // Shown at once and kept until the computer says the same.
        heldName = title
        name.text = title
        StemCellsHaptics.play(root, StemCellsHaptics.Moment.Renamed)
        onRenamed?.invoke()
        session.store.rename(spaceId, title)
    }

    // Layout

    private inner class Root(context: Context) : ManualLayout(context), ThemeAware {
        init {
            // Where focus goes when the field lets it go, so its cursor goes too.
            isFocusableInTouchMode = true
            defaultFocusHighlightEnabled = false
        }

        override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
            // A tap away from the field keeps what it says; the tap goes on.
            val field = nameField
            if (ev.actionMasked == MotionEvent.ACTION_DOWN && field != null) {
                val at = IntArray(2).also { field.getLocationOnScreen(it) }
                val inside = ev.rawX >= at[0] && ev.rawX < at[0] + field.width && ev.rawY >= at[1] && ev.rawY < at[1] + field.height
                if (!inside) endEditingName(keep = true)
            }
            return false
        }

        override fun layoutSubviews(width: Double, height: Double) {
            bar.safeTop = safeArea.top
            bar.setFrame(Rect(0.0, 0.0, width, height))
            val barLayout = bar.layout
            // At the top, it stays at the top as the bar's room changes.
            val atTop = scroll.contentOffset <= -scroll.insetTop
            scroll.setInsets(barLayout.contentTop, safeArea.bottom + keyboard)
            if (atTop) scroll.setContentOffset(-barLayout.contentTop)
            scroll.setFrame(Rect(0.0, 0.0, width, height))
            val side = StemCellSessionDetailLayout.Metrics.side
            val cardWidth = max(0.0, width - 2 * side)
            val familyHeight = if (family.isEmpty()) null else StemCellsSidebarLayout.layout(
                StemCellsSidebarView.layoutRows(family), cardWidth, top = TREE_PADDING, bottom = TREE_PADDING, scale = Theme.density.toDouble(),
            ).contentHeight
            val workCount = (detail?.running?.size ?: 0) + (detail?.terminals?.size ?: 0)
            val settled = detail?.completed?.size ?: 0
            fun layout(open: Boolean) = StemCellSessionDetailLayout(
                width, ceil(name.lineHeight), ceil(subtitle.lineHeight),
                tiles = tiles.size, actions = actionRows.size,
                media = StemCellSessionDetailLayout.Shown(shownMedia.size, hasMore(shared.media.size, mediaLimit)),
                links = StemCellSessionDetailLayout.Shown(shownLinks.size, hasMore(shared.links.size, linksLimit)),
                family = familyHeight, running = workCount, completed = settled,
                countWidth = StemCellsSettingsCardView.valueWidth("$settled") ?: 0.0, open = open,
            )
            if (folding == null) foldShown = if (completedOpen) 1.0 else 0.0
            val opened = layout(true)
            // Folding, the rows stand where they open, under a card between its two heights.
            val layout = if (folding == null) layout(completedOpen) else {
                val closed = layout(false)
                fun between(a: Double, b: Double) = a + (b - a) * foldShown
                opened.copy(
                    completed = opened.completed?.let { card ->
                        card.copy(frame = card.frame.copy(height = between(closed.completed?.frame?.height ?: card.frame.height, card.frame.height)))
                    },
                    contentHeight = between(closed.contentHeight, opened.contentHeight),
                )
            }
            val frames = scroll.content.frames
            frames[mark] = layout.mark
            // Renaming, the field stands in its place.
            frames[name] = if (nameField != null) null else layout.name
            nameField?.let { frames[it] = layout.name }
            frames[subtitle] = layout.subtitle
            for ((tile, frame) in tiles.zip(layout.tiles)) {
                frames[tile] = frame.frame
                tile.place(frame)
            }
            frames[actions] = if (actionRows.isEmpty()) null else layout.actions.frame
            actions.show(actionRows, layout.actions)
            frames[mediaHeading] = layout.mediaHeading
            frames[mediaCard] = layout.media?.frame
            layout.media?.let { mediaCard.place(it) }
            frames[linksHeading] = layout.linksHeading
            frames[linksCard] = layout.links?.frame
            layout.links?.let { linksCard.place(it.frame, null, it.rows, it.more) }
            frames[familyHeading] = layout.familyHeading
            frames[familyCard] = layout.family
            frames[activityHeading] = layout.activityHeading
            frames[running] = layout.running?.frame
            layout.running?.let { running.place(it) }
            frames[completed] = layout.completed?.frame
            layout.completed?.let { completed.place(it) }
            scroll.contentHeight = layout.contentHeight
            scroll.content.setFrame(Rect(0.0, 0.0, width, layout.contentHeight))
            scroll.content.layoutSubviews(width, layout.contentHeight)
            opener.safeArea = safeArea
            opener.place(Rect(0.0, 0.0, width, height))
        }

        override fun applyTheme() {
            setBackgroundColor(Theme.color(StemCellsPalette.stemCellsSettingsGround))
            bar.applyTheme()
        }
    }

    private companion object {
        /** Four rows of media, five links; each "Show more" as many again. */
        const val MEDIA_PAGE = 12
        const val LINKS_PAGE = 5
        const val TREE_PADDING = 8.0

        /** A link as it reads under its title: no scheme, no `www.`, no slash at its end. */
        fun address(url: String): String {
            var text = url
            for (prefix in listOf("https://", "http://")) if (text.startsWith(prefix)) text = text.removePrefix(prefix)
            text = text.removePrefix("www.")
            return text.removeSuffix("/")
        }
    }
}
