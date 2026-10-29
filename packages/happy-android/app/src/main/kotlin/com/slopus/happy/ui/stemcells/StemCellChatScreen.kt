package com.slopus.happy.ui.stemcells

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.view.HapticFeedbackConstants
import android.view.View
import com.slopus.happy.R
import com.slopus.happy.chat.AgentInputPrimaryAction
import com.slopus.happy.direct.ClientId
import com.slopus.happy.layout.HeaderScrim
import com.slopus.happy.layout.Palette
import com.slopus.happy.layout.Rect
import com.slopus.happy.layout.Size
import com.slopus.happy.layout.stemcells.StemCellChatFootLayout
import com.slopus.happy.layout.stemcells.StemCellChatScreenLayout
import com.slopus.happy.layout.stemcells.StemCellChatStackLayout
import com.slopus.happy.layout.stemcells.StemCellSendLift
import com.slopus.happy.layout.stemcells.StemCellWorkingLayout
import com.slopus.happy.stemcells.HappyReferences
import com.slopus.happy.stemcells.OutgoingImage
import com.slopus.happy.stemcells.StemCellActivity
import com.slopus.happy.stemcells.StemCellChatSnapshot
import com.slopus.happy.stemcells.StemCellConversation
import com.slopus.happy.stemcells.StemCellCreation
import com.slopus.happy.stemcells.StemCellReferences
import com.slopus.happy.stemcells.StemCellsRow
import com.slopus.happy.stemcells.StemCellsSnapshot
import com.slopus.happy.ui.chat.ChatRowAction
import com.slopus.happy.ui.chat.ChatRowPlan
import com.slopus.happy.ui.chat.ChatRowView
import com.slopus.happy.ui.chat.TextSelectionScreen
import com.slopus.happy.ui.composer.AgentInputAttachmentPreview
import com.slopus.happy.ui.composer.AgentInputCardView
import com.slopus.happy.ui.composer.ComposerImagePicker
import com.slopus.happy.ui.home.HeaderScrimView
import com.slopus.happy.ui.nav.BackInterceptor
import com.slopus.happy.ui.components.ChoiceMenu
import com.slopus.happy.ui.nav.Screen
import com.slopus.happy.ui.tablet.ContentWidth
import com.slopus.happy.ui.text.TextLayouts
import com.slopus.happy.ui.theme.Theme
import com.slopus.happy.ui.theme.ThemeAware
import com.slopus.happy.ui.view.BounceScrollView
import com.slopus.happy.ui.view.ManualLayout
import com.slopus.happy.ui.view.px
import com.slopus.happy.ui.view.dp
import com.slopus.happy.stemcells.AgentMode
import com.slopus.happy.stemcells.StemCellModelMenus
import com.slopus.happy.ui.composer.AgentInputChip
import com.slopus.happy.ui.view.setFrame
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.max

/**
 * One Stem Cells conversation (happy-ios `StemCellChatViewController`),
 * simplified from desktop's: the work's intro at the top (its stem cell,
 * or the clay icon it grew into, over "Say what it is — it'll find its
 * place."), what the person wrote and what the agent said with the pictures
 * and recordings it presented, and a composer that sends photos too. Tool
 * calls are never shown.
 *
 * New work opens here before the daemon has made it: the intro and the
 * composer are up at once, and a first message waits for the work to exist.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class StemCellChatScreen(
    context: Context,
    private val session: StemCellsSession,
    agentId: String,
    title: String,
    private val spaceId: String?,
    private var intro: StemCellIntroView.Mark?,
    creation: StemCellCreation? = null,
    /** New work made from the dock's "+": the sheet comes up over it as it opens. */
    attaching: Boolean = false,
    /** A held-row preview may read history, but never reads, edits drafts or discards work. */
    private val previewing: Boolean = false,
) : Screen(context), BackInterceptor, com.slopus.happy.ui.nav.NavigationTransitionProviding {
    /** The conversation's own line of work, one coroutine at a time, as the store's. */
    private val conversationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default.limitedParallelism(1))
    private val conversation = StemCellConversation(
        session.client, session.store, agentId, creation?.created,
        // New work goes with the dock's pick; work already going, with its own.
        mode = if (creation != null) session.models.newWorkMode else null,
        persistence = session.engine, scope = conversationScope,
    )
    private val planner = StemCellChatPlanner(context.resources)
    private val media = StemCellMediaLoader(session.client, agentId)
    private val pictures = ComposerImagePicker(context, scope)
    private val opensFocused = creation != null
    /** New work nobody named or wrote in, as its row says. */
    private var untouched = creation != null
    /** Named or written in here: kept, whatever the row says until the store catches up. */
    private var kept = false
    /** New work the daemon hasn't made yet: it has no detail to open. */
    private var creating = creation != null
    /** The keyboard new work opens with, not yet asked for. */
    private var focusPending = creation != null
    /** Pictures in the viewer, recordings in the player, files in an app for them. */
    private val opener = StemCellMediaOpener(context, media, scope, cover = { cover(it) }, uncovered = { stack?.backStateChanged() })
    /** The picture viewer or the player while one is up. */
    private val viewer: android.view.View? get() = opener.shown
    /** The "+" sheet asked for as the screen opens. */
    private var attachPending = attaching
    /** The "+" sheet while it is up. */
    private var attachSheet: StemCellAttachSheet? = null
    /** The keyboard was up as the sheet came: it comes back once the sheet is done. */
    private var keyboardOwed = false

    var title: String = title
        private set

    /** A row was opened from here: the host pushes its screen. */
    var openRow: ((StemCellsRow) -> Unit)? = null
    /** A picture, recording or file was tapped. */
    var onOpenMedia: ((String) -> Unit)? = null
    /** An artifact's card was tapped. */
    var onOpenArtifact: ((com.slopus.happy.stemcells.StemCellArtifact) -> Unit)? = null

    private lateinit var root: Root
    /** The card's height while it moves to the one its text makes; null at rest. */
    private var cardShown: Double? = null
    /** The steering last shown waiting: one gone from it was taken up. */
    private var queuedShown: Set<String> = emptySet()
    private var cardGrowth: android.animation.ValueAnimator? = null
    private lateinit var scroll: BounceScrollView
    private lateinit var introView: StemCellIntroView
    private lateinit var working: StemCellWorkingView
    private lateinit var composerScrim: HeaderScrimView
    private lateinit var jump: StemCellChatJumpButton
    private lateinit var card: AgentInputCardView
    private lateinit var header: StemCellChatHeaderView
    private val previewTitle by lazy {
        com.slopus.happy.ui.components.Label(context, com.slopus.happy.layout.TextStyle(com.slopus.happy.layout.FontFace.SystemSemiBold, 17.0), Palette.text, maxLines = 1).apply {
            text = title
            followsTextSize = false
            tag = "stem-preview-title"
            elevation = dp(3.0)
        }
    }
    private val previewPill by lazy {
        View(context).apply {
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(Theme.color(com.slopus.happy.layout.StemCellsPalette.stemCellsHoldMenu))
                cornerRadius = dp(22.0)
            }
            elevation = dp(2.0)
        }
    }
    private val previewFade by lazy {
        object : View(context) {
            val paint = android.graphics.Paint()
            override fun onDraw(canvas: android.graphics.Canvas) {
                if (height <= 0) return
                val color = Theme.rgba(Palette.surface)
                paint.shader = android.graphics.LinearGradient(0f, 0f, 0f, height.toFloat(),
                    intArrayOf(color.argb, color.argb, color.withAlpha(0.0).argb), floatArrayOf(0f, 54f / 78f, 1f), android.graphics.Shader.TileMode.CLAMP)
                canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
            }
        }
    }

    private val rowViews = HashMap<String, ChatRowView>()
    private var plans: List<ChatRowPlan> = emptyList()
    private var chat = StemCellChatSnapshot()
    private var planning: Job? = null
    private var planned: Planned? = null
    private var plansQueued = emptySet<String>()
    /** Other agents' messages opened to read, for this screen's life. */
    private val opened = HashSet<String>()
    private var references = StemCellReferences()
    private var sidebarRows: List<StemCellsRow> = emptyList()
    private var unwatch: (() -> Unit)? = null
    private var copied: Pair<String, Job>? = null
    /** The newest row is in view: what arrives keeps it there. */
    private var followsNewest = true
    private var jumping = false
    private var jumpShown = false
    private var newWorkAlpha = 1f
    private var draftArrivals = -1
    private var showingDraft = false
    private var lastDraftEdit = 0.0
    /** A message sent while the agent was idle, not yet drawn: as it lands it is lifted to desktop's line. */
    private var liftPending: String? = null
    /** The lifted send and its line, while the reply hasn't filled the room under it. */
    private var lift: Pair<String, Double>? = null
    /** Where the rows end, as last laid out; null while they stand off the top. */
    private var rowsEnd: Double? = null
    /** Where the transcript's drawn content starts and ends: what the scrims measure by. */
    private var contentSpan = 0.0 to 0.0
    private var live = true
    private var planShown = false
    private var putAway = false

    // Model

    /** A pick made here, until the conversation's own mode says it back. */
    private var picked: AgentMode? = null
    /** The model or effort list while it is open. */
    private var picker: StemCellPickerPresenter? = null
    private var unwatchModels: (() -> Unit)? = null

    /** What the next message goes with: the conversation's, else what new work starts on, else the computer's default. */
    private val mode: AgentMode?
        get() = picked ?: chat.mode ?: (if (opensFocused) session.models.newWorkMode else null) ?: session.models.catalog?.defaultMode()

    private val menus: StemCellModelMenus?
        get() {
            val catalog = session.models.catalog ?: return null
            return mode?.let { StemCellModelMenus(catalog, it) }
        }

    /** What the harness reads: the rows drawn, whether the agent works, and how far from the end the reader is. */
    val probe: String
        get() = "chat=${spaceId ?: media.agentId} msgs=${plans.size} working=${chat.activity?.phase?.let { it::class.simpleName } ?: "none"} " +
            "follows=${if (followsNewest) 1 else 0} jump=${if (jumpShown) 1 else 0} lift=${if (lift != null) 1 else 0} " +
            "model=${card.configuration.model?.label?.replace(' ', '_') ?: "none"} effort=${card.configuration.effort?.label ?: "none"} " +
            "picker=${picker?.probe?.ifEmpty { null } ?: "none"} " +
            "viewer=${opener.probe}"

    private data class Planned(
        val items: List<com.slopus.happy.stemcells.StemCellChatItem>,
        val key: StemCellChatPlanner.Key,
        val copied: String?,
        val opened: Set<String>,
        var queued: Set<String>,
    )

    init {
        if (creation != null) {
            // New work's media is under the ID the daemon gives it.
            scope.launch {
                val id = runCatching { creation.created.await() }.getOrNull() ?: return@launch
                media.agentId = id
                creating = false
            }
        }
    }

    private inner class Root(context: Context) : ManualLayout(context), ThemeAware {
        // Clearing the only focusable editor otherwise immediately focuses it again.
        init { isFocusableInTouchMode = true }
        override fun layoutSubviews(width: Double, height: Double) = layoutScreen(width, height)
        override fun onInterceptTouchEvent(event: android.view.MotionEvent): Boolean = previewing || super.onInterceptTouchEvent(event)

        override fun applyTheme() {
            setBackgroundColor(androidx.core.graphics.ColorUtils.setAlphaComponent(Theme.color(Palette.surface), (newWorkAlpha * 255).toInt()))
            for (row in rowViews.values) row.applyTheme()
            introView.applyTheme()
            working.applyTheme()
            composerScrim.applyTheme()
            jump.applyTheme()
            card.applyTheme()
            header.applyTheme()
        }
    }

    override fun createView(): View {
        root = Root(context)
        root.tag = "stem-chat"
        root.setTag(R.id.chat_media, media)
        scroll = BounceScrollView(context).apply { tag = "stem-transcript" }
        introView = StemCellIntroView(context)
        working = StemCellWorkingView(context).apply { visibility = View.GONE }
        composerScrim = HeaderScrimView(context, fromTop = false)
        jump = StemCellChatJumpButton(context).apply {
            visibility = View.GONE
            alpha = 0f
            setOnClickListener { jumpToLatest() }
        }
        card = AgentInputCardView(context)
        header = StemCellChatHeaderView(context)
        root.addView(scroll)
        scroll.content.addView(introView)
        scroll.content.addView(working)
        root.addView(composerScrim)
        root.addView(jump)
        root.addView(card)
        root.addView(header)
        if (previewing) {
            root.tag = "stem-preview"
            for (child in listOf(card, composerScrim, header, jump)) child.visibility = View.GONE
            scroll.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            root.addView(previewFade)
            root.addView(previewPill)
            root.addView(previewTitle)
        }
        root.applyTheme()
        return root
    }

    override fun viewCreated(view: View) {
        header.text = title
        header.onBack = {
            card.blur()
            pop()
        }
        header.onOpen = { openDetail() }
        // New work asks what it is; a conversation already going asks for the next message.
        val asks = intro is StemCellIntroView.Mark.StemCell
        card.configuration = card.configuration.copy(
            placeholder = context.getString(if (asks) R.string.stem_composer_placeholder else R.string.session_input_placeholder),
            showsAdd = true,
        )
        card.textView.tag = "stem-composer"
        card.tag = "stem-input"
        card.onTextChanged = {
            updatePrimary()
            if (!previewing && !showingDraft) draftEdited(card.text)
        }
        card.onHeightChanged = {
            if (card.animatesHeightChange && card.height > 0) growCard() else root.requestLayout()
        }
        card.onPrimary = {
            if (card.text.isBlank() && pictures.images.isEmpty()) {
                if (chat.working) conversationScope.launch { conversation.abort() }
            } else {
                send()
            }
        }
        card.onSend = { send() }
        card.onStop = { conversationScope.launch { conversation.abort() } }
        card.onAdd = { presentAttach() }
        card.onRemoveAttachment = { pictures.remove(it) }
        card.onReceiveImages = { pictures.receive(it) }
        card.onPressChip = { openPicker(it) }
        unwatchModels = session.models.watch { updateChips() }
        session.models.load()
        updateChips()
        pictures.onChange = {
            card.configuration = card.configuration.copy(attachments = pictures.images.map { AgentInputAttachmentPreview(it.id, it.image.file) })
            updatePrimary()
        }
        scroll.listener = object : BounceScrollView.Listener {
            override fun willBeginDragging() {
                jumping = false
            }

            override fun didScroll() = scrolled()
        }
        applyIntro()
        updatePrimary()
        unwatch = session.watch { follow(it) }
        scope.launch { conversation.updates.collect { apply(it) } }
        conversation.start()
        if (!previewing) conversationScope.launch { conversation.useDrafts(session.drafts) }
    }

    override fun onAppear() {
        if (previewing) return
        conversation.setVisible(true)
        // New work: the keyboard comes up with the screen.
        if (attachPending) {
            // From the dock's "+": the sheet, and the keyboard once it is done.
            attachPending = false
            focusPending = false
            keyboardOwed = true
            root.post { presentAttach() }
        } else if (focusPending) {
            focusPending = false
            root.post { card.focus() }
        }
    }

    override fun onDisappear() {
        if (previewing) return
        conversation.setVisible(false)
        conversation.flushDraft()
    }

    override fun navigationTransition(other: Screen, pushing: Boolean): com.slopus.happy.ui.nav.ScreenTransition? {
        val home = other as? StemCellsDirectHomeScreen ?: return null
        if (!opensFocused || previewing || home.session !== session) return null
        return StemCellsNewWorkTransition(home.stemCells.dock, this, pushing)
    }

    internal val composerIsEmpty: Boolean get() = card.text.isEmpty() && pictures.images.isEmpty()

    internal fun newWorkFade(alpha: Float) {
        newWorkAlpha = alpha.coerceIn(0f, 1f)
        root.setBackgroundColor(androidx.core.graphics.ColorUtils.setAlphaComponent(Theme.color(Palette.surface), (newWorkAlpha * 255).toInt()))
        scroll.alpha = newWorkAlpha
        header.alpha = newWorkAlpha
        jump.animate().cancel()
        jump.alpha = if (jumpShown) newWorkAlpha else 0f
        card.alpha = if (composerIsEmpty) 1f else newWorkAlpha
    }

    internal fun blurForNewWorkPop(): Boolean {
        val focused = card.textView.hasFocus() && keyboard > 0
        root.requestFocus()
        card.blur()
        return focused
    }

    internal fun restoreNewWorkFocus() { card.focus() }

    override val interceptsBack: Boolean get() = picker != null || attachSheet != null || viewer != null

    override fun onBackPressed(): Boolean {
        if (opener.dismiss()) return true
        attachSheet?.let {
            it.dismiss()
            return true
        }
        val picker = picker ?: return false
        picker.outside()
        return true
    }

    override fun onDestroy() {
        putAwayIfEmpty()
        unwatch?.invoke()
        unwatchModels?.invoke()
        picker?.close(animated = false)
        planning?.cancel()
        pictures.close()
        val scope = conversationScope
        scope.launch { conversation.stop() }.invokeOnCompletion { scope.cancel() }
    }

    /** New work left with nothing in it goes: off the sidebar and off the computer. */
    private fun putAwayIfEmpty() {
        if (previewing) return
        val spaceId = spaceId ?: return
        if (!untouched || kept || !chat.loaded || chat.items.isNotEmpty() || card.hasContent || pictures.images.isNotEmpty()) return
        putAway = true
        session.store.putAway(spaceId, true)
        session.store.discard(spaceId)
    }

    // Detail

    /** The conversation's detail, over it; new work has none until the daemon made it. */
    private fun openDetail() {
        if (creating || media.agentId.isEmpty()) return
        card.blur()
        onOpenDetail?.invoke(media.agentId)
    }

    /** The capsule was tapped: the host opens the detail for this agent. */
    var onOpenDetail: ((String) -> Unit)? = null

    // State

    /** The row's name and mark as the sidebar has them: the stem cell gives way to its clay icon. */
    private fun follow(snapshot: StemCellsSnapshot) {
        if (snapshot.live && !live) {
            media.retryFailed()
            for (row in rowViews.values) row.retryMedia()
        }
        live = snapshot.live
        if (snapshot.references != references) {
            references = snapshot.references
            replan()
        }
        sidebarRows = snapshot.allRows
        val row = spaceId?.let { id -> snapshot.allRows.firstOrNull { it.spaceId == id } } ?: return
        untouched = row.isUntouchedStemCell
        if (title != row.label) {
            title = row.label
            header.text = row.label
            if (previewing) previewTitle.text = row.label
        }
        val next = when (val mark = row.mark) {
            is StemCellsRow.Mark.StemCell -> StemCellIntroView.Mark.StemCell(mark.seed)
            is StemCellsRow.Mark.Clay -> StemCellIntroView.Mark.Clay(mark.slug)
            else -> intro
        }
        if (next != intro) {
            intro = next
            applyIntro()
        }
    }

    private fun applyIntro() {
        showIntro()
        intro?.let { introView.configure(it, settled(chat)) }
        root.requestLayout()
    }

    /** The intro shows with what is under it: not alone while history is read or its rows are first planned. */
    private fun showIntro() {
        val hidden = intro == null || reading(chat) || (chat.items.isNotEmpty() && !planShown)
        introView.visibility = if (hidden) View.INVISIBLE else View.VISIBLE
    }

    /** History still being read: nothing shows yet. New work has none to read. */
    private fun reading(chat: StemCellChatSnapshot) = !chat.loaded && !opensFocused

    /** The intro's "say what it is" line is for work with nothing in it yet. */
    private fun settled(chat: StemCellChatSnapshot) = reading(chat) || chat.items.isNotEmpty()

    private fun apply(snapshot: StemCellChatSnapshot) {
        val wasSettled = settled(chat)
        chat = snapshot
        if (picked == snapshot.mode) picked = null
        media.register(snapshot.items)
        if (snapshot.draftArrivals != draftArrivals) {
            draftArrivals = snapshot.draftArrivals
            // Typed here since, on its way to the conversation: that stands.
            if (snapshot.draftUpdatedAt >= lastDraftEdit) showDraft(snapshot.draft)
        }
        working.show(snapshot.activity)
        header.status = status(snapshot.activity)
        if (wasSettled != settled(snapshot)) intro?.let { introView.configure(it, settled(snapshot)) }
        showIntro()
        updatePrimary()
        updateChips()
        replan()
        root.requestLayout()
    }

    private fun updateChips() {
        if (reading(chat) && picked == null) {
            card.configuration = card.configuration.copy(model = null, effort = null)
        } else {
            StemCellModelPickers.configure(card, menus)
            picker?.update(menus, session.models.usage)
        }
        attachSheet?.let {
            it.permission = permissionName
            it.permissionReady = menus != null
        }
    }

    // Media

    /**
     * A tile tapped: a file opens in an app for it, a recording plays over
     * the conversation, and a picture opens among all the conversation's,
     * swiped between in its order.
     */
    private fun openMedia(key: String) {
        onOpenMedia?.invoke(key)
        opener.open(key, StemCellPicture.all(chat.items, context.getString(R.string.stem_picture)))
    }

    /** A viewer over everything, the keyboard down under it. */
    private fun cover(view: android.view.View) {
        card.blur()
        picker?.close(animated = false)
        raise(view)
        root.addView(view)
        view.setFrame(Rect(0.0, 0.0, px(root.width), px(root.height)))
        stack?.backStateChanged()
    }

    /** Over everything the conversation lifts on a shadow, the jump button and the header among them. */
    private fun raise(view: View) {
        view.translationZ = 64 * context.resources.displayMetrics.density
    }

    // Attach

    /** The mode the next message runs with, by its name. */
    private val permissionName: String
        get() = StemCellModelPickers.permissionName(context, mode?.permissionMode ?: session.models.catalog?.defaultPermissionMode ?: "auto")

    /**
     * The "+" sheet over the screen. What it adds waits in the composer,
     * which then takes the keyboard; the keyboard goes while the sheet is up.
     */
    private fun presentAttach() {
        if (attachSheet != null) return
        picker?.close(animated = false)
        val sheet = StemCellAttachSheet(context, pictures.canTakePhoto, permissionName)
        sheet.permissionReady = menus != null
        if (session.models.catalog == null) session.models.load()
        keyboardOwed = keyboardOwed || card.isTextFocused
        card.blur()
        sheet.onGone = {
            attachSheet = null
            stack?.backStateChanged()
            settleFocus(added = false)
        }
        sheet.onAction = { action ->
            if (action != StemCellAttachSheet.Action.Permission) {
                attachSheet = null
                stack?.backStateChanged()
            }
            when (action) {
                StemCellAttachSheet.Action.Photos -> pictures.pickLibrary { settleFocus(added = it) }
                StemCellAttachSheet.Action.Camera -> pictures.takePhoto { settleFocus(added = it) }
                StemCellAttachSheet.Action.Paste -> pasteFromClipboard()
                StemCellAttachSheet.Action.Files -> pictures.pickFiles { settleFocus(added = it) }
                StemCellAttachSheet.Action.Permission -> openPermissionMenu(sheet)
            }
        }
        sheet.safeBottom = safeArea.bottom
        attachSheet = sheet
        raise(sheet)
        root.addView(sheet)
        sheet.setFrame(Rect(0.0, 0.0, px(root.width), px(root.height)))
        sheet.present()
        stack?.backStateChanged()
    }

    /** The permission the next message runs with, each with what it allows, over the sheet. */
    private fun openPermissionMenu(sheet: StemCellAttachSheet) {
        val menus = menus ?: return session.models.load()
        val options = StemCellModelPickers.permissionModes.map { mode ->
            ChoiceMenu.Option(
                StemCellModelPickers.key(StemCellModelPickers.Kind.Permission, mode),
                StemCellModelPickers.permissionName(context, mode),
                subtitle = StemCellModelPickers.permissionDetail(context, mode),
            )
        }
        val selected = StemCellModelPickers.key(StemCellModelPickers.Kind.Permission, menus.selection.permissionMode)
        ChoiceMenu.show(sheet.permissionRow, listOf(ChoiceMenu.Section(null, options)), selected) { choose(it) }
    }

    /**
     * The clipboard: a picture attaches, text goes in where the cursor is,
     * anything else is said, not sent.
     */
    private fun pasteFromClipboard() {
        val clipboard = context.getSystemService(android.content.ClipboardManager::class.java)
        val clip = clipboard?.primaryClip
        val hasPicture = clip != null && (0 until clip.itemCount).any { index ->
            val uri = clip.getItemAt(index).uri ?: return@any false
            (context.contentResolver.getType(uri) ?: "").startsWith("image/") || clip.description.hasMimeType("image/*")
        }
        val text = if (hasPicture) null else clip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)?.toString()?.takeIf { it.isNotEmpty() }
        when {
            hasPicture -> {
                pictures.paste()
                settleFocus(added = true)
            }
            text != null -> {
                keyboardOwed = false
                val selection = card.selection
                val current = card.text
                val start = selection.start.coerceIn(0, current.length)
                val end = selection.end.coerceIn(start, current.length)
                card.setText(current.substring(0, start) + text + current.substring(end), start + text.length)
                card.focus()
            }
            else -> {
                // Nothing a message can carry: said, as a paste of nothing is.
                pictures.paste()
                settleFocus(added = false)
            }
        }
    }

    /** Back from the sheet or what it opened: the keyboard returns if it was owed, or once a picture is in. */
    private fun settleFocus(added: Boolean) {
        val focuses = keyboardOwed || added
        keyboardOwed = false
        if (focuses && root.isAttachedToWindow) root.post { card.focus() }
    }

    /** A chip opens its list; with no models yet, they are asked for again. */
    private fun openPicker(chip: AgentInputChip) {
        val menus = menus
        if (menus == null || (reading(chat) && picked == null)) return session.models.load()
        picker?.close(animated = false)
        val list = if (chip == AgentInputChip.Effort) StemCellPickerPresenter.List.Effort else StemCellPickerPresenter.List.Model
        val picker = StemCellPickerPresenter(list, card, menus, session.models.usage) { choose(it) }
        picker.onClose = {
            if (this.picker === picker) this.picker = null
            stack?.backStateChanged()
        }
        // Under the header's row, which the safe area doesn't know of.
        picker.top = { header.layout(px(root.width)).listTop }
        this.picker = picker
        picker.show(root)
        stack?.backStateChanged()
        StemCellModelPickers.opened(session.models, mode)
    }

    /** A pick from the model control. */
    private fun choose(key: String) {
        StemCellModelPickers.choose(context, key, session.models, mode) { next ->
            // Held here at once, so the chips read it before the conversation says so.
            picked = next
            updateChips()
            conversation.setMode(next)
        }
    }

    /** The header's grey line: what the agent is at, in a word, while it is. */
    private fun status(activity: StemCellActivity?): String? = when (activity?.phase) {
        null -> null
        StemCellActivity.Phase.Thinking, StemCellActivity.Phase.WaitingForModel -> context.getString(R.string.stem_phase_thinking)
        StemCellActivity.Phase.AwaitingAnswer -> context.getString(R.string.stem_phase_awaiting_answer)
        else -> context.getString(R.string.stem_phase_working)
    }

    private val planKey: StemCellChatPlanner.Key
        get() = StemCellChatPlanner.Key(
            px(root.width), ContentWidth.maxWidth(context), TextLayouts.Env(Theme.density, Theme.textScale),
            references.copy(subagents = chat.subagentTitles), chat.subagentTitles,
        )

    /** Plans the rows off the main thread, then shows them in one frame. */
    private fun replan() {
        if (!::root.isInitialized || root.width <= 0 || reading(chat)) return
        val key = planKey
        val items = chat.items
        // Artifacts the messages name are read for their titles, once each.
        val unread = items.flatMap { references.unread(it.text) }.distinct()
        if (unread.isNotEmpty()) session.store.readArtifacts(unread)
        val copiedId = copied?.first
        val openedNow = opened.toSet()
        val queued = items.takeLast(chat.queued).map { it.id }.toSet()
        val previous = planned
        if (previous != null && previous.items == items && previous.key == key && previous.copied == copiedId && previous.opened == openedNow) {
            // Only where the steering stands moved: the rows are the same.
            if (previous.queued != queued && planning == null) {
                previous.queued = queued
                plansQueued = queued
                root.requestLayout()
            } else {
                previous.queued = queued
            }
            return
        }
        planned = Planned(items, key, copiedId, openedNow, queued)
        planning?.cancel()
        planning = scope.launch {
            val plans = withContext(Dispatchers.Default) { planner.plans(items, key, copiedId, openedNow) }
            planning = null
            plansQueued = planned?.queued ?: queued
            show(plans)
        }
    }

    private fun show(plans: List<ChatRowPlan>) {
        this.plans = plans
        planShown = true
        showIntro()
        val seen = HashSet<String>()
        for (plan in plans) {
            seen += plan.id
            val row = rowViews.getOrPut(plan.id) {
                ChatRowView(context).also { view ->
                    view.tag = "stem-message"
                    view.onAction = { action, _, hold -> act(plan.id, action, hold) }
                    scroll.content.addView(view)
                }
            }
            // The id is the plan's; its action closure keeps it.
            row.onAction = { action, _, hold -> act(plan.id, action, hold) }
            row.bind(plan)
        }
        for (id in rowViews.keys.toList()) {
            if (id in seen) continue
            val row = rowViews.remove(id) ?: continue
            scroll.content.frames.remove(row)
            scroll.content.removeView(row)
        }
        layoutTranscript()
    }

    /**
     * The card goes to the height its typed text makes over 0.2 s, and all
     * that sits on it (the transcript's foot, the jump button, the scrim)
     * with it, as iOS's `agentInputCardDidInvalidateHeight` animates the
     * layout; from where it is if it was already moving.
     */
    private fun growCard() {
        val from = cardShown ?: px(card.height)
        cardGrowth?.cancel()
        card.animatingHeight = true
        cardShown = from
        cardGrowth = android.animation.ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 200
            interpolator = android.view.animation.PathInterpolator(0.42f, 0f, 0.58f, 1f)
            addUpdateListener {
                val to = card.height(px(card.width))
                cardShown = from + (to - from) * it.animatedFraction
                root.requestLayout()
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    if (cardGrowth !== animation) return
                    cardGrowth = null
                    cardShown = null
                    card.animatingHeight = false
                    root.requestLayout()
                }
            })
            start()
        }
        root.requestLayout()
    }

    // Layout

    private fun layoutScreen(width: Double, height: Double) {
        replan()
        if (previewing) {
            val layout = com.slopus.happy.layout.stemcells.StemCellsPreviewLayout(Size(width, height), previewTitle.measure(width))
            previewPill.setFrame(layout.pill)
            previewTitle.setFrame(layout.title)
            scroll.setInsets(layout.listTop, layout.listBottom)
            scroll.setFrame(Rect(0.0, 0.0, width, height))
            layoutTranscript()
            previewFade.setFrame(Rect(0.0, 0.0, width, layout.fadeOpaque))
            return
        }
        header.safeTop = safeArea.top
        val headerLayout = header.layout(width)
        header.setFrame(Rect(0.0, 0.0, width, headerLayout.scrim.height))
        val keyboardTop = if (keyboard > 0) height - safeArea.bottom - keyboard else Double.MAX_VALUE
        val screen = StemCellChatScreenLayout(Size(width, height), safeArea, keyboardTop) { cardShown ?: card.height(it) }
        card.setFrame(screen.card)
        val foot = StemCellChatFootLayout(Size(width, height), screen.card)
        composerScrim.setFrame(foot.scrim)
        jump.setFrame(foot.jump)
        scroll.setInsets(headerLayout.listTop, screen.listBottom)
        scroll.setFrame(Rect(0.0, 0.0, width, height))
        layoutTranscript()
        picker?.follow()
        attachSheet?.let {
            it.safeBottom = safeArea.bottom
            it.setFrame(Rect(0.0, 0.0, width, height))
        }
        opener.safeArea = safeArea
        opener.place(Rect(0.0, 0.0, width, height))
    }

    private fun layoutTranscript() {
        if (!::root.isInitialized || root.width <= 0) return
        val width = px(root.width)
        val viewport = max(0.0, px(root.height) - scroll.insetTop - scroll.insetBottom)
        val heights = ArrayList<Double>()
        val introLayout = intro?.let { introView.layout(width) }
        if (introLayout != null) heights += introLayout.size.height
        // Steering the turn hasn't taken waits under the working line, with
        // room under it for the line that follows it once taken.
        val queuedIds = plansQueued
        val settled = plans.filter { it.id !in queuedIds }
        val waiting = plans.filter { it.id in queuedIds }
        heights += settled.map { it.size.height }
        heights += if (chat.activity == null) 0.0 else StemCellWorkingLayout.Metrics.height
        heights += waiting.map { it.size.height }
        if (waiting.isNotEmpty()) heights += StemCellWorkingLayout.Metrics.height
        // The intro stays under the bar, as desktop's leading header.
        val pins = introLayout != null && !previewing
        val first = if (introLayout == null) 0 else 1
        fun row(id: String): Int? {
            val index = settled.indexOfFirst { it.id == id }
            if (index >= 0) return first + index
            val queued = waiting.indexOfFirst { it.id == id }
            return if (queued >= 0) first + settled.size + 1 + queued else null
        }
        fun stacked(): StemCellChatStackLayout {
            val held = lift?.let { (id, line) -> row(id)?.let { StemCellChatStackLayout.Lift(it, line) } }
            return StemCellChatStackLayout(heights, width, viewport, pins, held)
        }
        var stack = stacked()
        val pending = liftPending
        val sent = pending?.let(::row)
        if (pending != null && sent != null) {
            liftPending = null
            // Where it lands following the end; lifted from there if below the line.
            val line = StemCellSendLift(viewport, introLayout?.line?.maxY)
            lift = if (line.lifts(stack.frames[sent].minY - max(0.0, stack.contentHeight - viewport))) pending to line.line else null
            stack = stacked()
        }
        if (!stack.holding) lift = null
        rowsEnd = if (stack.frames.firstOrNull()?.minY == 0.0) stack.frames.lastOrNull()?.maxY else null
        // The reserve under waiting steering is room, not content.
        val drawn = (if (waiting.isEmpty()) stack.frames else stack.frames.dropLast(1)).filter { it.height > 0 }
        val top = drawn.firstOrNull()?.minY ?: 0.0
        contentSpan = top to max(top, drawn.lastOrNull()?.maxY ?: 0.0)
        val frames = scroll.content.frames
        if (introLayout != null) {
            frames[introView] = stack.frames[0]
            introView.place(introLayout)
        } else {
            frames[introView] = null
        }
        // Steering taken up goes into the transcript above the line, moving
        // there over 0.25 s as the line moves under it (iOS's `taken`).
        val taken = (queuedShown - queuedIds).isNotEmpty() && root.isAttachedToWindow
        queuedShown = queuedIds
        val before = if (taken) frames.filterKeys { it === working || it in rowViews.values } else emptyMap()
        for (plan in plans) {
            val view = rowViews[plan.id] ?: continue
            frames[view] = row(plan.id)?.let { stack.frames[it] }
        }
        frames[working] = if (chat.activity == null) null else stack.frames[first + settled.size]
        for ((view, was) in before) {
            val now = frames[view] ?: continue
            if (was == null || was.minY == now.minY) continue
            view.animate().cancel()
            view.translationY = dp(was.minY - now.minY).toFloat()
            view.animate().translationY(0f).setDuration(250).setInterpolator(android.view.animation.PathInterpolator(0.42f, 0f, 0.58f, 1f)).start()
        }
        val previous = scroll.contentHeight
        scroll.contentHeight = stack.contentHeight
        scroll.content.setFrame(Rect(0.0, 0.0, width, stack.contentHeight))
        scroll.content.layoutSubviews(width, stack.contentHeight)
        val newest = newestOffset
        when {
            jumping -> if (previous != stack.contentHeight) scroll.setContentOffset(newest, animated = true)
            followsNewest -> if (previous != stack.contentHeight || scroll.contentOffset != newest) scroll.setContentOffset(newest)
            // Shorter than the place it was read at (a section folded): no gap under the end.
            scroll.contentOffset > newest && !scroll.isDragging && !scroll.isDecelerating -> scroll.setContentOffset(newest)
        }
        if (previewing) {
            val last = (listOf(introView) + plans.mapNotNull { rowViews[it.id] }).lastOrNull { (frames[it]?.height ?: 0.0) > 0 }
            val frame = last?.let { frames[it] }
            if (frame != null && frame.minY - scroll.contentOffset < scroll.insetTop) scroll.setContentOffset(frame.minY - scroll.insetTop)
            previewFade.visibility = if (scroll.contentOffset <= -scroll.insetTop + 0.5) View.GONE else View.VISIBLE
        }
        updateJump()
        updateScrims()
    }

    /** The scrims at both edges come in as the transcript's content goes under them. */
    private fun updateScrims() {
        val top = scroll.contentOffset + scroll.insetTop
        val foot = scroll.contentOffset + px(root.height) - scroll.insetBottom
        header.scrolledUnder = top - contentSpan.first
        composerScrim.presence = HeaderScrim.presence(contentSpan.second - foot).toFloat()
    }

    private val newestOffset: Double
        get() = max(-scroll.insetTop, scroll.contentHeight + scroll.insetBottom - px(root.height))

    /**
     * The way back shows while the reader is more than a third of the list's
     * height from the newest message, and shimmers while the working line spins.
     */
    private fun updateJump() {
        if (previewing) return
        val viewport = px(root.height) - scroll.insetTop - scroll.insetBottom
        val away = StemCellChatFootLayout.showsJump(newestOffset - scroll.contentOffset, viewport)
        jump.working = away && StemCellChatJumpButton.spins(chat.activity)
        if (away == jumpShown) return
        jumpShown = away
        if (away) jump.visibility = View.VISIBLE
        jump.animate().cancel()
        jump.animate().alpha(if (away) newWorkAlpha else 0f).setDuration(160).withEndAction {
            // Shown again meanwhile, it stays.
            if (!jumpShown) jump.visibility = View.GONE
        }.start()
    }

    /** Back to the newest message, following it from there. */
    private fun jumpToLatest() {
        followsNewest = true
        jumping = true
        scroll.setContentOffset(newestOffset, animated = true)
        root.postDelayed({ jumping = false }, 320)
    }

    private fun scrolled() {
        updateJump()
        updateScrims()
        if (!scroll.isDragging && !scroll.isDecelerating) return
        followsNewest = scroll.contentOffset >= newestOffset - 24
        // Read away from the end: room held under it, wholly out of view, goes.
        val end = rowsEnd
        if (!followsNewest && lift != null && end != null && scroll.contentOffset + px(root.height) - scroll.insetBottom <= end) {
            lift = null
            root.requestLayout()
        }
        if (scroll.contentOffset < 200 - scroll.insetTop && chat.hasMore) conversationScope.launch { conversation.loadOlder() }
    }

    // Acts

    private fun updatePrimary() {
        val hasContent = card.text.isNotBlank() || pictures.images.isNotEmpty()
        card.configuration = card.configuration.copy(
            primaryAction = AgentInputPrimaryAction.resolve(
                hasComposerContent = hasContent, isSendBlocked = false, isSendDisabled = false, showAbortButton = chat.working, canAbort = true,
            ),
        )
    }

    private fun send() {
        val text = card.text.trim()
        val picked = pictures.images
        if (text.isEmpty() && picked.isEmpty()) return
        kept = true
        card.setText("")
        StemCellsHaptics.play(root, StemCellsHaptics.Moment.Send)
        followsNewest = true
        // Mid-turn, a send waits under the reply at the end instead.
        val id = ClientId.make()
        liftPending = if (chat.working) null else id
        val store = session.store
        conversationScope.launch {
            // Read off the main thread; the staged files go once they are read.
            val photos = withContext(Dispatchers.IO) {
                picked.mapNotNull { pick -> runCatching { OutgoingImage(pick.image.mimeType, pick.image.file.readBytes()) }.getOrNull() }
            }
            withContext(Dispatchers.Main) { pictures.clear() }
            store.attentionInput()
            conversation.send(text, photos, id)
        }
    }

    /** The draft, as typed here: kept and saved once the keystrokes pause. */
    private fun draftEdited(text: String) {
        val at = max(Math.round(System.currentTimeMillis().toDouble()).toDouble(), lastDraftEdit + 1)
        lastDraftEdit = at
        conversation.setDraft(text, at)
    }

    /** A draft from elsewhere in the composer, the caret at its end; never over a word being composed. */
    private fun showDraft(text: String) {
        if (card.text == text) return
        showingDraft = true
        card.setText(text, text.length, animatesHeight = false)
        showingDraft = false
        updatePrimary()
    }

    private fun act(rowId: String, action: ChatRowAction, hold: Boolean) {
        if (hold) {
            when (action) {
                is ChatRowAction.CopyMenu -> copy(action.text)
                is ChatRowAction.MarkdownSelectable -> push(TextSelectionScreen(context, action.markdown.ifEmpty { action.paragraph }))
                else -> Unit
            }
            return
        }
        when (action) {
            is ChatRowAction.OpenLink -> openLink(action.url)
            is ChatRowAction.CopyMessage -> {
                copy(action.text)
                showCopied(rowId)
            }
            is ChatRowAction.Retry -> conversation.retry(action.localId)
            is ChatRowAction.OpenMedia -> openMedia(action.key)
            is ChatRowAction.OpenArtifact -> onOpenArtifact?.invoke(action.artifact)
            is ChatRowAction.ToggleTool -> toggle(action.id)
            else -> Unit
        }
    }

    private fun copy(text: String) {
        val clipboard = context.getSystemService(ClipboardManager::class.java) ?: return
        clipboard.setPrimaryClip(ClipData.newPlainText(null, text))
        root.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
    }

    /** Opens or closes another agent's message where it is. */
    private fun toggle(messageId: String) {
        if (!opened.remove(messageId)) opened += messageId
        // It opens where the finger is, not pulled up after the newest line.
        followsNewest = false
        replan()
    }

    /** What a message names, opened over this one: a conversation; anything else in the browser. */
    private fun openLink(url: String) {
        val reference = HappyReferences.reference(url)
        if (reference == null) {
            runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
            return
        }
        if (reference.kind == "artifact") {
            val known = references.artifacts[reference.id] as? StemCellReferences.Artifact.Active
            if (known == null) session.store.readArtifacts(listOf(reference.id))
            val artifact = com.slopus.happy.stemcells.StemCellArtifact(
                reference.id, reference.version ?: known?.latestVersion ?: 1, known?.type ?: "", known?.title ?: "",
                followsLatest = reference.version == null, pinned = reference.version != null,
            )
            onOpenArtifact?.invoke(artifact)
            return
        }
        val entity = references.entity(reference) ?: return
        val row = sidebarRows.firstOrNull { it.kind == StemCellsRow.Kind.Space && it.agentId == entity.agentId }
        val label = references.view(reference).label
        openRow?.invoke(row ?: StemCellsRow(id = "agent:${entity.agentId}", nodeId = entity.agentId, kind = StemCellsRow.Kind.Session, label = label, agentId = entity.agentId))
    }

    private fun showCopied(id: String) {
        copied?.second?.cancel()
        val until = scope.launch {
            delay(2000)
            copied = null
            replan()
        }
        copied = id to until
        replan()
    }

    companion object {
        /** A row's conversation, as home opens it: its name in the bar, its mark in the intro. Null for rows without one. */
        fun forRow(context: Context, session: StemCellsSession, row: StemCellsRow, previewing: Boolean = false): StemCellChatScreen? {
            if (row.kind != StemCellsRow.Kind.Space && row.kind != StemCellsRow.Kind.Session) return null
            if (row.targetKind == "surface" || row.targetKind == "artifacts") return null
            val agentId = row.agentId ?: return null
            val space = row.spaceId?.let { id -> session.snapshot.rows.firstOrNull { it.spaceId == id } }
            val intro = when (val mark = space?.mark) {
                is StemCellsRow.Mark.StemCell -> StemCellIntroView.Mark.StemCell(mark.seed)
                is StemCellsRow.Mark.Clay -> StemCellIntroView.Mark.Clay(mark.slug)
                else -> null
            }
            return StemCellChatScreen(context, session, agentId, row.label, row.spaceId, intro, previewing = previewing).also { if (!previewing) it.wire() }
        }

        /** New work as the dock made it: up at once, before the daemon has made it, the keyboard with it. */
        fun forNewWork(context: Context, session: StemCellsSession, creation: StemCellCreation, attaching: Boolean = false): StemCellChatScreen =
            StemCellChatScreen(
                context, session, creation.agentId, context.getString(R.string.stem_untitled), creation.spaceId,
                StemCellIntroView.Mark.StemCell(creation.spaceId), creation, attaching,
            ).also { it.wire() }
    }

    /** A conversation another one names opens over it, as its row would. */
    private fun wire() {
        openRow = { row -> forRow(context, session, row)?.let { push(it) } }
        onOpenArtifact = { push(StemCellArtifactScreen(context, session, it)) }
        onOpenDetail = { id ->
            val detail = StemCellSessionDetailScreen(context, session, id, title, spaceId, conversation.updates)
            detail.onRenamed = { kept = true }
            detail.openConversation = { agentId, title, spaceId, row ->
                val screen = row?.let { forRow(context, session, it) }
                    ?: StemCellChatScreen(context, session, agentId, title, spaceId, null).also { it.wire() }
                detail.push(screen)
            }
            push(detail)
        }
    }
}
