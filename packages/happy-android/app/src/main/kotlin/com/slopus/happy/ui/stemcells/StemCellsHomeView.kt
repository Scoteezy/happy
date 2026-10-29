package com.slopus.happy.ui.stemcells

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.text.Layout
import android.view.View
import com.slopus.happy.R
import com.slopus.happy.chat.AgentInputPrimaryAction
import com.slopus.happy.direct.DirectHomeSnapshot
import com.slopus.happy.layout.HomeContentInsets
import com.slopus.happy.layout.LayoutInsets
import com.slopus.happy.layout.Palette
import com.slopus.happy.layout.StemCellsPalette
import com.slopus.happy.layout.Size
import com.slopus.happy.layout.stemcells.StemCellsBannerLayout
import com.slopus.happy.layout.stemcells.StemCellsDockLayout
import com.slopus.happy.layout.stemcells.StemCellsEmptyLayout
import com.slopus.happy.layout.stemcells.StemCellsHomeLayout
import com.slopus.happy.stemcells.StemCellCreation
import com.slopus.happy.stemcells.StemCellsRow
import com.slopus.happy.stemcells.StemCellsRow.Kind
import com.slopus.happy.stemcells.StemCellsSnapshot
import com.slopus.happy.ui.components.Label
import com.slopus.happy.ui.components.RoundButton
import com.slopus.happy.ui.composer.AgentInputCardView
import com.slopus.happy.ui.home.HeaderScrimView
import com.slopus.happy.ui.theme.Theme
import com.slopus.happy.ui.theme.ThemeAware
import com.slopus.happy.ui.view.ManualLayout
import com.slopus.happy.ui.view.dp
import com.slopus.happy.ui.view.setFrame
import com.slopus.happy.ui.view.setFrameOrHide
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Home in Stem Cells mode (happy-ios `StemCellsHomeViewController`): one
 * paired computer's sidebar, as desktop's `HappyAgentSpacesSidebar` draws
 * it, under home's header and over the dock. The dock is the new-work
 * screen's own composer, drawn the same and in the same place: a tap in it
 * makes new work, and its "+" makes new work with the library open over
 * it. Desktop's Search and Create rows aren't drawn: search is the
 * header's button and create is the dock. What opens from a row is the
 * host's: [onOpen] and [onNewWork].
 */
class StemCellsHomeView(context: Context) : ManualLayout(context) {
    val sidebar = StemCellsSidebarView(context)
    private val banner = StemCellsBannerView(context)
    private val empty = StemCellsEmptyView(context)
    private val dockScrim = HeaderScrimView(context, fromTop = false)
    /** The dock, which new work's own composer stands in for as it opens. */
    val dock = AgentInputCardView(context)

    /** A row was opened: its conversation, its Surface, or the artifacts. */
    var onOpen: ((StemCellsRow) -> Unit)? = null
    /** New work exists: its conversation opens over home, with the library when asked. */
    var onNewWork: ((StemCellCreation, Boolean) -> Unit)? = null
    var onPair: (() -> Unit)? = null
    /** Every snapshot shown, for the screen around this one. */
    var onSnapshot: ((StemCellsSnapshot) -> Unit)? = null

    /** The header and dock home lays its content between. */
    var contentInsets = HomeContentInsets(LayoutInsets.Zero, 0.0)
        set(value) { if (field != value) { field = value; requestLayout() } }
    var safeArea = LayoutInsets.Zero
        set(value) { if (field != value) { field = value; requestLayout() } }

    var session: StemCellsSession? = null
        private set
    private var snapshot = StemCellsSnapshot()
    private var directHome = DirectHomeSnapshot()
    private var watch: (() -> Unit)? = null
    private var directWatch: Job? = null
    private val scope = MainScope()
    private var creating = false
    private val usesSpaces get() = directHome.usesSpaces && session?.snapshot?.availability != StemCellsSnapshot.Availability.Unsupported

    init {
        // The conversation's own ground, light and dark.
        setBackgroundColor(Theme.color(Palette.surface))
        addView(sidebar)
        addView(empty)
        addView(banner)
        addView(dockScrim)
        dock.configuration = dock.configuration.copy(
            placeholder = context.getString(R.string.stem_composer_placeholder),
            showsAdd = true,
            primaryAction = AgentInputPrimaryAction.resolve(
                hasComposerContent = false, isSendBlocked = false, isSendDisabled = false, showAbortButton = false, canAbort = false, canVoice = false,
            ),
        )
        // Nothing is typed here: the field is where new work begins.
        dock.textView.isFocusable = false
        dock.textView.isFocusableInTouchMode = false
        dock.textView.isClickable = false
        dock.textView.isLongClickable = false
        dock.textView.contentDescription = context.getString(R.string.stem_composer_placeholder)
        dock.textView.tag = "stem-new-work"
        dock.textView.setOnClickListener { makeNewWork() }
        dock.setOnClickListener { makeNewWork() }
        dock.onPrimary = { makeNewWork() }
        dock.onSend = { makeNewWork() }
        dock.onAdd = { makeNewWork(library = true) }
        dock.onHeightChanged = { requestLayout() }
        addView(dock)
        sidebar.onSelect = { open(it) }
        sidebar.onFold = { fold(it) }
        sidebar.onAdd = { row -> makeNewWork(sectionId = if (usesSpaces) row.nodeId else null) }
        empty.onPair = { onPair?.invoke() }
        applyChrome()
    }

    /** Shows `session`'s computer, or the empty state for none. */
    fun attach(session: StemCellsSession?) {
        if (session === this.session) return
        watch?.invoke()
        watch = null
        directWatch?.cancel()
        directWatch = null
        this.session = session
        sidebar.images = session?.images
        sidebar.apply(emptyList())
        snapshot = StemCellsSnapshot()
        directHome = DirectHomeSnapshot()
        if (session != null) {
            sidebar.selectedRowId = session.selectedRowId
            watch = session.watch { apply(it) }
            directWatch = scope.launch {
                session.engine.updates.collect { home ->
                    directHome = home
                    if (usesSpaces) apply(session.snapshot) else applyClassic()
                }
            }
        }
        applyChrome()
    }

    fun appear() {
        sidebar.selectedRowId = session?.selectedRowId
        session?.store?.attentionVisibleUpdate(true)
    }

    fun disappear() {
        session?.store?.attentionVisibleUpdate(false)
    }

    fun close() {
        attach(null)
        scope.cancel()
    }

    private fun apply(snapshot: StemCellsSnapshot) {
        if (!usesSpaces) return applyClassic()
        val previous = this.snapshot
        this.snapshot = snapshot
        onSnapshot?.invoke(snapshot)
        if (previous.rows != snapshot.rows) sidebar.apply(drawn(snapshot.rows))
        if (previous.availability != snapshot.availability || previous.error != snapshot.error || previous.stemCellError != snapshot.stemCellError) applyChrome()
    }

    private fun applyClassic() {
        val previous = snapshot.rows
        snapshot = StemCellsSnapshot(
            availability = if (directHome.loaded) StemCellsSnapshot.Availability.Available else StemCellsSnapshot.Availability.Loading,
            live = directHome.live,
            rows = directHome.rows,
            allRows = directHome.allRows,
            error = directHome.error,
        )
        onSnapshot?.invoke(snapshot)
        if (previous != snapshot.rows) sidebar.apply(snapshot.rows)
        applyChrome()
    }

    private fun applyChrome() {
        val hasComputer = session != null
        val shown = hasComputer && snapshot.availability != StemCellsSnapshot.Availability.Unsupported
        empty.visibility = if (shown) GONE else VISIBLE
        empty.message = context.getString(if (!hasComputer) R.string.stem_no_computer else R.string.stem_unsupported)
        empty.showsPair = !hasComputer
        sidebar.visibility = if (shown) VISIBLE else GONE
        fun reason(text: String) = text.ifEmpty { context.getString(R.string.stem_unanswered) }
        val notice = snapshot.stemCellError?.let { context.getString(R.string.stem_new_work_failed, reason(it)) } ?: snapshot.error?.let(::reason)
        // Prioritizing being off is Settings' business: home says nothing of it.
        banner.configure(notice)
        dock.visibility = if (shown) VISIBLE else GONE
        dockScrim.visibility = dock.visibility
        requestLayout()
    }

    override fun layoutSubviews(width: Double, height: Double) {
        val size = Size(width, height)
        val bannerHeight = if (banner.isEmpty) 0.0 else banner.layout(width).size.height
        val docked = StemCellsDockLayout(size, safeArea) { dock.height(it) }
        dock.setFrame(docked.card)
        dockScrim.setFrame(docked.scrim)
        val listBottom = if (dock.visibility == VISIBLE) docked.listBottom else contentInsets.listBottom
        val layout = StemCellsHomeLayout(size, contentInsets.contentTop, contentInsets.chromeTop, listBottom, bannerHeight)
        banner.setFrameOrHide(if (banner.isEmpty) null else layout.banner)
        sidebar.setFrame(com.slopus.happy.layout.Rect(0.0, 0.0, width, height))
        val list = sidebar.list
        val insets = layout.listInsets
        if (list.insetTop != insets.top || list.insetBottom != insets.bottom) {
            val atTop = list.contentOffset <= -list.insetTop + 1
            list.setInsets(insets.top, insets.bottom)
            // A shrinking top leaves nothing scrolled past it.
            if (atTop || (!list.isDragging && list.contentOffset < -insets.top)) list.setContentOffset(-insets.top)
        }
        empty.setFrame(layout.empty)
    }

    // Acts

    private fun open(row: StemCellsRow) {
        val session = session ?: return
        session.store.attentionInput()
        when {
            row.kind == Kind.Space && (row.targetKind == "project" || row.targetKind == "workspace") -> fold(row)
            row.kind == Kind.Create -> makeNewWork()
            row.kind == Kind.Space || row.kind == Kind.Session -> {
                if (row.targetKind == "surface" && row.targetId == null) return
                if (row.targetKind != "surface" && row.targetKind != "artifacts" && row.agentId == null) return
                if (row.targetKind != "artifacts") {
                    session.selectedRowId = row.id
                    sidebar.selectedRowId = row.id
                }
                onOpen?.invoke(row)
            }
            row.kind == Kind.Search -> onOpen?.invoke(row)
            else -> {}
        }
    }

    private fun fold(row: StemCellsRow) {
        val session = session ?: return
        if (!usesSpaces) {
            scope.launch { runCatching { session.engine.fold(row.id) } }
            return
        }
        StemCellsHaptics.play(this, StemCellsHaptics.Moment.Fold)
        session.store.attentionInput()
        // A row with subtasks the sidebar doesn't hold yet brings them, open.
        if (row.hasHiddenSessions) session.store.revealSessions(row.nodeId, row.sessions) else session.store.fold(row.nodeId)
    }

    /**
     * New work, right below Happy: the row and its empty conversation exist
     * at once, and the host opens the conversation over home.
     */
    fun makeNewWork(sectionId: String? = null, library: Boolean = false) {
        val session = session ?: return
        if (creating) return
        creating = true
        StemCellsHaptics.play(this, StemCellsHaptics.Moment.NewWork)
        scope.launch {
            val creation = try {
                session.store.createStemCell(sectionId)
            } finally {
                creating = false
            }
            val rowId = "space:${creation.spaceId}"
            session.selectedRowId = rowId
            sidebar.selectedRowId = rowId
            onNewWork?.invoke(creation, library)
        }
    }

    companion object {
        /** The rows a phone draws: all but Search and Create, which stay in the document for desktop. */
        fun drawn(rows: List<StemCellsRow>) = rows.filter { it.kind != Kind.Search && it.kind != Kind.Create }
    }
}

/** The note above the list: the last thing the computer refused (happy-ios `StemCellsBannerView`). */
class StemCellsBannerView(context: Context) : ManualLayout(context), ThemeAware {
    private val card = View(context)
    private val cardShape = GradientDrawable()
    private val title = Label(context, StemCellsFonts.ui(StemCellsFonts.Weight.SemiBold, 15.0), StemCellsPalette.stemCellsRowTextActive)
    var isEmpty = true
        private set

    init {
        cardShape.cornerRadius = dp(12.0)
        card.background = cardShape
        addView(card)
        addView(title)
        visibility = GONE
        applyTheme()
    }

    fun configure(notice: String?) {
        isEmpty = notice == null
        visibility = if (isEmpty) GONE else VISIBLE
        title.text = notice ?: ""
        requestLayout()
    }

    fun layout(width: Double) = StemCellsBannerLayout(width, title.measurable, null, null)

    override fun sizeThatFits(width: Double, height: Double) = if (isEmpty) Size.Zero else layout(width).size

    override fun layoutSubviews(width: Double, height: Double) {
        val layout = layout(width)
        card.setFrame(layout.card)
        title.setFrame(layout.title.offsetBy(layout.card.minX, layout.card.minY))
    }

    override fun applyTheme() {
        cardShape.setColor(Theme.color(StemCellsPalette.stemCellsRowSelected))
    }
}

/** No computer paired, or one too old: what to do about it (happy-ios `StemCellsEmptyView`). */
class StemCellsEmptyView(context: Context) : ManualLayout(context) {
    var onPair: (() -> Unit)? = null
    private val mark = StemCellMarkView(context)
    private val label = Label(context, StemCellsFonts.ui(StemCellsFonts.Weight.Medium, 16.0), StemCellsPalette.stemCellsRowText, alignment = Layout.Alignment.ALIGN_CENTER)
    private val button = RoundButton(context, context.getString(R.string.stem_pair_a_computer))

    var message = ""
        set(value) { field = value; label.text = value; requestLayout() }
    var showsPair = true
        set(value) { field = value; button.visibility = if (value) VISIBLE else GONE }

    init {
        mark.seed = "stem-cells"
        button.onTap = { onPair?.invoke() }
        addView(mark)
        addView(label)
        addView(button)
    }

    override fun layoutSubviews(width: Double, height: Double) {
        val buttonSize = if (button.visibility == VISIBLE) button.layout(width).size else null
        val layout = StemCellsEmptyLayout(Size(width, height), label.measurable, buttonSize)
        mark.setFrame(layout.mark)
        label.setFrame(layout.label)
        button.setFrameOrHide(layout.button)
    }
}
