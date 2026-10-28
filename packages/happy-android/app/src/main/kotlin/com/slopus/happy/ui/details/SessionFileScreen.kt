package com.slopus.happy.ui.details

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.widget.ProgressBar
import com.slopus.happy.AppGraph
import com.slopus.happy.R
import com.slopus.happy.files.FileViewer
import com.slopus.happy.files.SyncSessionFileOps
import com.slopus.happy.layout.chat.ChatScreenLayout
import com.slopus.happy.layout.FileViewerLayout
import com.slopus.happy.layout.FileViewerPalette
import com.slopus.happy.layout.FileViewerTextStyles
import com.slopus.happy.layout.Palette
import com.slopus.happy.layout.Rect
import com.slopus.happy.layout.SecondaryPalette
import com.slopus.happy.layout.Size
import com.slopus.happy.model.SessionMetadata
import com.slopus.happy.sync.AccountSync
import com.slopus.happy.sync.SessionFileCache
import com.slopus.happy.ui.components.Alerts
import com.slopus.happy.ui.components.FileIconView
import com.slopus.happy.ui.components.Label
import com.slopus.happy.ui.diff.FileLinesPlan
import com.slopus.happy.ui.diff.FileLinesView
import com.slopus.happy.ui.nav.BarScreen
import com.slopus.happy.ui.text.TextLayouts
import com.slopus.happy.ui.theme.Theme
import com.slopus.happy.ui.theme.ThemeAware
import com.slopus.happy.ui.view.ManualLayout
import com.slopus.happy.ui.view.dp
import com.slopus.happy.ui.view.px
import com.slopus.happy.ui.view.setFrame
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.min

/**
 * app/(app)/session/[id]/file.tsx (happy-ios `SessionFileViewController`):
 * one file of the session, as its uncommitted diff or its syntax-coloured
 * text. A revisit draws the last read at once and reads again behind it.
 *
 * The session's root is read off the main thread. Until it is, the file is
 * resolved against the root it had when one of its files was last shown,
 * which is where its cached copy is. A copy kept on disk (after a
 * relaunch) is read off the main thread too, before the computer is asked;
 * offline, it stays on screen under the error alert.
 *
 * Follows the person's text size: the lines are measured at it off the main
 * thread, and a new size measures them again with the line at the top of
 * the screen kept there.
 */
class SessionFileScreen(
    context: Context,
    private val graph: AppGraph,
    private val sessionId: String,
    private val rawPath: String,
    private val requestedLine: Int? = null,
    private val requestedColumn: Int? = null,
) : BarScreen(context) {
    private val account: AccountSync? = graph.account.value
    private var target: FileViewer.Target
    private var sessionPath: String?

    private var content: FileViewer.Content? = null
    private var diff: String? = null
    private var mode = FileViewer.DisplayMode.Diff
    private var isLoading: Boolean
    private var error: String? = null
    private var pendingAlert: String? = null
    private var appeared = false
    /** A requested line scrolls into view when text first shows, cached or read, and again each time a read brings new text. */
    private var scrollsToLine = true

    private val root = Root(context)
    private val header = Chrome(context)
    private val icon = FileIconView(context)
    private val pathLabel = Label(context, FileViewerTextStyles.path, Palette.textSecondary)
    private val headerBorder = View(context)
    private val toggleBar = Chrome(context)
    private val diffButton = FileModeButton(context, context.getString(R.string.files_diff))
    private val fileButton = FileModeButton(context, context.getString(R.string.files_file))
    private val toggleBorder = View(context)
    private val lines = FileLinesView(context)
    private var stateView: View? = null

    private var planJob: Job? = null
    /** What the pending plan is being made from. */
    private var planned: PlanKey? = null
    /** The last diff and file plans, so the toggle flips between them at once. */
    private var plans: List<FileLinesPlan> = emptyList()

    private data class PlanKey(val source: FileLinesPlan.Source, val width: Double, val maxWidth: Double, val env: TextLayouts.Env)

    init {
        // This account's cache: the root it last saw.
        val cache = account?.fileCache
        val known = cache?.root(sessionId)
        sessionPath = known?.path
        target = FileViewer.Target(rawPath, known?.path)
        val cached = if (known == null) null else cache.get(sessionId, target.filePath)
        content = cached?.content
        diff = cached?.diff
        isLoading = cached == null
    }

    override fun createContent(): View {
        header.addView(icon)
        header.addView(pathLabel)
        header.addView(headerBorder)
        toggleBar.addView(diffButton)
        toggleBar.addView(fileButton)
        toggleBar.addView(toggleBorder)
        diffButton.setOnClickListener { showMode(FileViewer.DisplayMode.Diff) }
        fileButton.setOnClickListener { showMode(FileViewer.DisplayMode.File) }
        root.addView(lines)
        root.addView(header)
        root.addView(toggleBar)
        return root
    }

    override fun viewCreated(view: View) {
        super.viewCreated(view)
        bar.title = context.getString(R.string.common_file_viewer)
        showTarget()
        root.applyTheme()
        updateMode()
        render()
        load()
    }

    override fun onAppear() {
        super.onAppear()
        appeared = true
        pendingAlert?.let {
            pendingAlert = null
            Alerts.show(context, context.getString(R.string.common_error), it)
        }
    }

    override fun onDisappear() {
        super.onDisappear()
        appeared = false
    }

    override fun onInsetsChanged() {
        super.onInsetsChanged()
        root.requestLayout()
    }

    private fun showTarget() {
        icon.fileName = target.fileName
        pathLabel.text = FileViewer.headerPath(target.filePath, requestedLine, requestedColumn)
    }

    // Loading

    /**
     * file.tsx's loadFile: the spinner only without a cached copy. The
     * session's root is read off the main thread first; a read that ends
     * after another account took over shows and caches nothing.
     */
    private fun load() {
        error = null
        val account = account ?: return
        val store = graph.store
        scope.launch {
            val root = withContext(Dispatchers.IO) { SessionMetadata.parse(store.session(sessionId)?.metadataJson)?.path }
            if (graph.account.value !== account) return@launch
            val cache = account.fileCache
            resolve(root, cache)
            // A copy kept on disk (after a relaunch), read off the main thread before the computer is asked; offline, it stays.
            val read = target
            if (content == null) cache.snapshot(sessionId, read.filePath)?.let { showKept(it, read) }
            val result = FileViewer.load(read, sessionPath, SyncSessionFileOps(account), sessionId, cache)
            if (graph.account.value !== account) return@launch
            apply(result)
        }
    }

    /** Takes the session's root as stored. A root other than the one the screen opened with resolves the file again, with that file's cached copy, if any. */
    private fun resolve(root: String?, cache: SessionFileCache) {
        cache.setRoot(sessionId, SessionFileCache.Root(root))
        if (root == sessionPath) return
        sessionPath = root
        val resolved = FileViewer.Target(rawPath, root)
        if (resolved == target) return
        target = resolved
        val cached = cache.get(sessionId, resolved.filePath)
        content = cached?.content
        diff = cached?.diff
        isLoading = cached == null
        plans = emptyList()
        cancelPlan()
        showTarget()
        updateMode()
        render()
    }

    /** The copy kept on disk, while nothing else shows. */
    private fun showKept(kept: FileViewer.Snapshot, read: FileViewer.Target) {
        if (graph.account.value !== account || read != target || content != null || error != null) return
        content = kept.content
        if (kept.diff != null && diff == null) diff = kept.diff
        isLoading = false
        scrollsToLine = true
        updateMode()
        render()
    }

    private fun apply(result: FileViewer.LoadResult) {
        when (result) {
            is FileViewer.LoadResult.Loaded -> {
                // A diff that has gone stays until the next visit, as in file.tsx.
                result.snapshot.diff?.let { diff = it }
                content = result.snapshot.content
                scrollsToLine = true
            }
            is FileViewer.LoadResult.Failed -> {
                result.diff?.let { diff = it }
                val message = when (val failure = result.failure) {
                    is FileViewer.Failure.Read -> failure.message ?: context.getString(R.string.files_failed_to_read)
                    FileViewer.Failure.Load -> context.getString(R.string.file_viewer_failed_to_load)
                }
                // A kept copy stays on screen under the alert.
                if (content == null) error = message
                // Modal.alert: shown once the screen is on screen.
                if (appeared) Alerts.show(context, context.getString(R.string.common_error), message) else pendingAlert = message
            }
        }
        isLoading = false
        updateMode()
        render()
    }

    private fun updateMode() {
        mode = FileViewer.displayMode(requestedLine, diff != null, content != null, mode)
    }

    // Drawing

    private fun render() {
        val error = error
        when {
            isLoading -> {
                val spinner = ProgressBar(context, null, android.R.attr.progressBarStyleSmall).apply {
                    indeterminateTintList = ColorStateList.valueOf(Theme.color(Palette.textSecondary))
                }
                show(DetailMessageView(context, spinner, listOf(
                    DetailMessageView.Line(context.getString(R.string.files_loading_file, target.fileName), FileViewerTextStyles.body, Palette.textSecondary, spacingBefore = 16.0),
                ), leadSize = Size(SPINNER, SPINNER)))
            }
            error != null -> show(DetailMessageView(context, null, listOf(
                DetailMessageView.Line(context.getString(R.string.common_error), FileViewerTextStyles.stateTitle, Palette.textDestructive),
                DetailMessageView.Line(error, FileViewerTextStyles.body, Palette.textSecondary, spacingBefore = 8.0, centered = true),
            ), STATE_PADDING))
            content?.isBinary == true -> show(DetailMessageView(context, null, listOf(
                DetailMessageView.Line(context.getString(R.string.files_binary_file), FileViewerTextStyles.stateTitle, Palette.textSecondary),
                DetailMessageView.Line(context.getString(R.string.files_cannot_display_binary), FileViewerTextStyles.body, Palette.textSecondary, spacingBefore = 8.0, centered = true),
                DetailMessageView.Line(target.fileName, FileViewerTextStyles.fileName, SecondaryPalette.fileName, spacingBefore = 8.0, centered = true),
            ), STATE_PADDING))
            else -> {
                stateView?.let(root::removeView)
                stateView = null
                header.visibility = View.VISIBLE
                lines.visibility = View.VISIBLE
                diffButton.selectedMode = mode == FileViewer.DisplayMode.Diff
                fileButton.selectedMode = mode == FileViewer.DisplayMode.File
            }
        }
        root.requestLayout()
    }

    private fun show(state: View) {
        stateView?.let(root::removeView)
        stateView = state
        header.visibility = View.GONE
        toggleBar.visibility = View.GONE
        lines.visibility = View.GONE
        root.addView(state)
    }

    /** layout.maxWidth, as the chat's column. */
    private val maxWidth get() = ChatScreenLayout.Metrics.regularMaxContentWidth

    /** Puts the body for the current mode in the list, planning the rows away from the main thread when they are new. */
    private fun updateBody() {
        if (stateView != null) return
        val width = px(lines.width)
        if (width <= 0) return
        val maxWidth = maxWidth
        val env = TextLayouts.Env(Theme.density, Theme.textScale)
        val source: FileLinesPlan.Source = when (FileViewer.Body.of(mode, diff, content)) {
            FileViewer.Body.Diff -> FileLinesPlan.Source.Diff(diff.orEmpty())
            FileViewer.Body.Code -> FileLinesPlan.Source.Code(content?.content.orEmpty(), FileViewer.language(target.filePath))
            FileViewer.Body.FileEmpty -> return showMessage(context.getString(R.string.files_file_empty))
            FileViewer.Body.NoChanges -> return showMessage(context.getString(R.string.files_no_changes))
            FileViewer.Body.None -> {
                cancelPlan()
                lines.copyText = null
                lines.clear()
                return
            }
        }
        val key = PlanKey(source, width, maxWidth, env)
        plans.firstOrNull { PlanKey(it.source, it.width, it.maxWidth, it.env) == key }?.let {
            cancelPlan()
            show(it)
            return
        }
        if (planned == key) return
        planJob?.cancel()
        planned = key
        planJob = scope.launch {
            val plan = withContext(Dispatchers.Default) { FileLinesPlan(source, width, maxWidth, env) }
            planned = null
            // One plan of each kind, at the newest size and text size.
            plans = listOf(plan) + plans.filter { kept ->
                kept.width == width && kept.maxWidth == maxWidth && kept.env == env && (kept.source is FileLinesPlan.Source.Diff) != (source is FileLinesPlan.Source.Diff)
            }
            show(plan)
        }
    }

    private fun showMessage(text: String) {
        cancelPlan()
        lines.copyText = null
        lines.showMessage(text, maxWidth)
    }

    private fun cancelPlan() {
        planJob?.cancel()
        planJob = null
        planned = null
    }

    private fun show(plan: FileLinesPlan) {
        lines.copyText = (plan.source as? FileLinesPlan.Source.Code)?.content
        val previous = lines.plan
        if (plan === previous) return lines.show(plan)
        val offset = lines.contentOffset
        // The same text measured again (another text size or width) keeps the line at the top of the screen there.
        val place = previous?.takeIf { it.source == plan.source }?.layout?.place(offset)
        lines.show(plan)
        // The list keeps its offset across content, inside the new end.
        var y = place?.let(plan.layout::y) ?: offset
        y = min(y, lines.maxOffset)
        if (plan.source is FileLinesPlan.Source.Code && scrollsToLine && requestedLine != null && requestedLine > 0) {
            // file.tsx scrolls two lines above a requested line, at 20 a line.
            scrollsToLine = false
            y = min(lines.maxOffset, FileViewer.scrollOffset(requestedLine))
        }
        lines.contentOffset = y
    }

    // Toggle

    private fun showMode(next: FileViewer.DisplayMode) {
        if (mode == next) return
        mode = next
        render()
    }

    /** The screen: bars on top of the lines, laid out by [FileViewerLayout]. */
    private inner class Root(context: Context) : ManualLayout(context), ThemeAware {
        override fun applyTheme() {
            setBackgroundColor(Theme.color(Palette.groupedBackground))
            headerBorder.setBackgroundColor(Theme.color(Palette.glassBorder))
            toggleBorder.setBackgroundColor(Theme.color(Palette.glassBorder))
            if (lines.plan?.env != TextLayouts.Env(Theme.density, Theme.textScale)) post { updateBody() }
        }

        override fun layoutSubviews(width: Double, height: Double) {
            val top = contentSafeArea.top
            stateView?.setFrame(Rect(0.0, top, width, max(0.0, height - top)))
            val showsToggle = diff != null
            val layout = FileViewerLayout(FileViewerLayout.Input(
                Size(width, height),
                top,
                pathLabel.measurable,
                if (showsToggle) FileViewerLayout.Toggle(diffButton.labelSize, fileButton.labelSize) else null,
            ))
            fun local(rect: Rect, parent: Rect) = rect.offsetBy(-parent.minX, -parent.minY)
            header.setFrame(layout.header)
            icon.setFrame(local(layout.icon, layout.header))
            pathLabel.setFrame(local(layout.path, layout.header))
            headerBorder.setFrame(local(layout.headerBorder, layout.header))
            toggleBar.visibility = if (!showsToggle || stateView != null) View.GONE else View.VISIBLE
            val bar = layout.toggleBar
            if (bar != null) {
                toggleBar.setFrame(bar)
                diffButton.setFrame(local(layout.diffButton!!, bar))
                diffButton.labelFrame = local(layout.diffLabel!!, layout.diffButton!!)
                fileButton.setFrame(local(layout.fileButton!!, bar))
                fileButton.labelFrame = local(layout.fileLabel!!, layout.fileButton!!)
                toggleBorder.setFrame(local(layout.toggleBorder!!, bar))
            }
            lines.bottomInset = contentSafeArea.bottom
            lines.setFrame(layout.content)
            post { updateBody() }
        }
    }

    /** A plain box for the bars; its children are placed by [Root]. */
    private class Chrome(context: Context) : ManualLayout(context) {
        override fun layoutSubviews(width: Double, height: Double) = Unit
    }

    /**
     * file.tsx's Diff / File buttons: semibold 14 on a rounded fill, white on
     * `textLink` at 40% when chosen, secondary text on the subtle glass when
     * not. Pressing draws nothing extra.
     */
    private class FileModeButton(context: Context, title: String) : ManualLayout(context), ThemeAware {
        private val label = Label(context, FileViewerTextStyles.toggle, Palette.textSecondary, maxLines = 1).apply {
            text = title
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        private val fill = GradientDrawable().apply { cornerRadius = dp(FileViewerLayout.Metrics.buttonRadius) }

        var labelFrame = Rect.Zero
            set(value) {
                field = value
                label.setFrame(value)
            }

        val labelSize: Size get() = label.measure(Double.MAX_VALUE)

        var selectedMode = false
            set(value) {
                field = value
                isSelected = value
                applyTheme()
            }

        init {
            addView(label)
            background = fill
            contentDescription = title
            isClickable = true
            applyTheme()
        }

        override fun applyTheme() {
            fill.setColor(Theme.color(if (selectedMode) FileViewerPalette.toggleSelected else Palette.optionSelected))
            label.color = if (selectedMode) FileViewerPalette.toggleSelectedText else Palette.textSecondary
        }

        override fun layoutSubviews(width: Double, height: Double) {
            label.setFrame(labelFrame)
        }
    }

    private companion object {
        /** The error and binary states' `padding: 20`. */
        const val STATE_PADDING = 20.0
        /** UIActivityIndicatorView's medium box. */
        const val SPINNER = 20.0
    }
}
