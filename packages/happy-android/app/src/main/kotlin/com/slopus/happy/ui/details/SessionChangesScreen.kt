package com.slopus.happy.ui.details

import com.slopus.happy.ui.nav.SessionRoute
import android.content.Context
import android.content.res.ColorStateList
import android.text.Layout
import android.view.View
import android.widget.FrameLayout
import android.widget.ProgressBar
import com.slopus.happy.AppGraph
import com.slopus.happy.R
import com.slopus.happy.files.AgentChanges
import com.slopus.happy.files.LegacyChanges
import com.slopus.happy.files.SyncSessionFileOps
import com.slopus.happy.layout.CenteredStackLayout
import com.slopus.happy.layout.DiffTextStyles
import com.slopus.happy.layout.Measurable
import com.slopus.happy.layout.Palette
import com.slopus.happy.layout.Rect
import com.slopus.happy.layout.Size
import com.slopus.happy.model.SessionMetadata
import com.slopus.happy.model.gitStatusKey
import com.slopus.happy.model.isRig
import com.slopus.happy.model.parseJsonObject
import com.slopus.happy.sync.HappyAgentGit
import com.slopus.happy.ui.components.Label
import com.slopus.happy.ui.diff.DiffFilesListView
import com.slopus.happy.ui.nav.BarScreen
import com.slopus.happy.ui.theme.Theme
import com.slopus.happy.ui.theme.ThemeAware
import com.slopus.happy.ui.view.ManualLayout
import com.slopus.happy.ui.view.setFrame
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * app/(app)/session/[id]/changes.tsx and components/AllFilesDiffView.tsx
 * (happy-ios `SessionChangesViewController`): the session's changes as one
 * scrolling diff. A Happy Agent session compares its workspace with the
 * branch base (HappyAgentDiffView); a CLI session shows its uncommitted
 * files from git status.
 */
class SessionChangesScreen(context: Context, private val graph: AppGraph, override val sessionId: String) : BarScreen(context), SessionRoute {
    private val root = FrameLayout(context)
    private val list = DiffFilesListView(context, scope)
    private var stateView: View? = null
    private var appeared = false

    private enum class Mode { Legacy, HappyAgent }
    private var mode: Mode? = null
    private var metadata: SessionMetadata? = null

    private var legacy: LegacyChanges? = null
    private var agent: AgentChanges? = null
    private var stateJob: Job? = null
    /** The Happy Agent snapshot on screen; a new one resets the list. */
    private var revision = -1

    override fun createContent(): View {
        list.visibility = View.GONE
        root.addView(list)
        return root
    }

    override fun viewCreated(view: View) {
        super.viewCreated(view)
        // Header.tsx's title, which scales with the text size (happy-ios setScaledTitleView).
        bar.plainTitle = true
        bar.title = string(R.string.session_info_view_changes)
        // Only what the viewers read from the metadata counts; streaming never re-scans Git.
        val store = graph.store
        scope.launch {
            store.changes.map { store.session(sessionId)?.metadataJson }
                .distinctUntilChanged()
                .map { json -> parseJsonObject(json)?.let(SessionMetadata::parse) }
                .flowOn(Dispatchers.IO)
                .collect(::metadataChanged)
        }
    }

    override fun onAppear() {
        super.onAppear()
        // useFocusEffect: coming back to the screen reads the workspace (or
        // git status) afresh. The first visit already started its read.
        if (appeared) {
            when (mode) {
                Mode.HappyAgent -> agent?.refresh()
                Mode.Legacy -> legacy?.refresh()
                null -> Unit
            }
        }
        appeared = true
    }

    override fun onInsetsChanged() {
        super.onInsetsChanged()
        list.insets = contentSafeArea
        stateView?.requestLayout()
    }

    private fun string(id: Int) = context.getString(id)

    private fun metadataChanged(metadata: SessionMetadata?) {
        if (metadata == null) {
            if (mode == null && stateView == null) showSpinner()
            return
        }
        val old = this.metadata
        this.metadata = metadata
        val next = if (metadata.isRig()) Mode.HappyAgent else Mode.Legacy
        if (next != mode) {
            mode = next
            when (next) {
                Mode.Legacy -> startLegacy(metadata)
                Mode.HappyAgent -> startAgent(metadata)
            }
            return
        }
        when {
            next == Mode.Legacy && metadata.path != old?.path -> startLegacy(metadata)
            next == Mode.HappyAgent && HappyAgentGit.supportsHappyAgentGit(metadata) != HappyAgentGit.supportsHappyAgentGit(old) -> startAgent(metadata)
        }
    }

    // Legacy

    private fun startLegacy(metadata: SessionMetadata) {
        stateJob?.cancel()
        val account = graph.account.value ?: return showSpinner()
        val changes = LegacyChanges(
            scope,
            SyncSessionFileOps(account),
            sessionId,
            metadata.path,
            account.fileCache,
            gitStatusKey(metadata.machineId, metadata.path),
        )
        legacy = changes
        list.onRequestContent = null
        list.onExpandContext = { path -> changes.expandContext(path) }
        list.reset()
        stateJob = scope.launch { changes.state.collect(::renderLegacy) }
        changes.refresh()
    }

    private fun renderLegacy(state: LegacyChanges.State) {
        when {
            state.files == null || state.loading -> showSpinner()
            state.files.isEmpty() -> showMessage(string(R.string.files_no_changes), retry = null)
            else -> {
                showList()
                list.update(state.items, emptyText = string(R.string.files_no_changes), defaultCollapsed = true)
            }
        }
    }

    // Happy Agent

    private fun startAgent(metadata: SessionMetadata) {
        stateJob?.cancel()
        val supported = HappyAgentGit.supportsHappyAgentGit(metadata)
        if (!supported) {
            agent = null
            showMessage(string(R.string.changes_update_happy_agent), retry = null)
            return
        }
        val account = graph.account.value ?: return showSpinner()
        val changes = AgentChanges(scope, SyncSessionFileOps(account), sessionId, supported = true)
        agent = changes
        revision = -1
        list.onRequestContent = { path -> changes.requestContent(path) }
        list.onExpandContext = { path -> changes.expandContext(path) }
        stateJob = scope.launch { changes.state.collect { renderAgent(changes, it) } }
        changes.refresh()
    }

    private fun renderAgent(changes: AgentChanges, state: AgentChanges.State) {
        when (val load = state.load) {
            AgentChanges.Load.Loading -> showSpinner()
            is AgentChanges.Load.Error -> showMessage(load.message) { changes.refresh() }
            is AgentChanges.Load.Ready -> {
                val git = load.git
                if (git.comparison != "ready" || git.base.isNullOrEmpty()) {
                    showMessage(string(R.string.changes_comparison_unavailable)) { changes.refresh() }
                    return
                }
                // A new snapshot gets its own reads and context.
                if (load.revision != revision) {
                    revision = load.revision
                    list.reset()
                }
                showList()
                list.update(
                    state.items(string(R.string.files_file_empty)),
                    notices = state.notices,
                    emptyText = string(if (state.clean) R.string.files_no_changes else R.string.changes_no_file_details),
                    defaultCollapsed = true,
                )
            }
        }
    }

    // States

    private fun showSpinner() {
        if (stateView is SpinnerState) return
        show(SpinnerState(context))
    }

    private fun showMessage(message: String, retry: (() -> Unit)?) {
        show(ChangesMessageView(context, message, retry, string(R.string.common_retry)))
    }

    private fun show(state: View) {
        stateView?.let(root::removeView)
        stateView = state
        list.visibility = View.GONE
        root.addView(state, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
    }

    private fun showList() {
        stateView?.let(root::removeView)
        stateView = null
        list.visibility = View.VISIBLE
    }

    /** The screen's surface under the bar, with the medium spinner centred (DetailMessageView's lead, padding 20). */
    private inner class SpinnerState(context: Context) : ManualLayout(context), ThemeAware {
        private val spinner = ProgressBar(context, null, android.R.attr.progressBarStyleSmall)

        init {
            addView(spinner)
            applyTheme()
        }

        override fun applyTheme() {
            setBackgroundColor(Theme.color(Palette.surface))
            spinner.indeterminateTintList = ColorStateList.valueOf(Theme.color(Palette.textSecondary))
        }

        override fun layoutSubviews(width: Double, height: Double) {
            val area = contentSafeArea
            val s = 20.0
            val inner = Size(width, height - area.top - area.bottom)
            val frames = CenteredStackLayout(CenteredStackLayout.Input(inner, 20.0, listOf(CenteredStackLayout.Item(Measurable.fixed(s, s))))).items
            spinner.setFrame(frames[0].offsetBy(0.0, area.top))
        }
    }

    /**
     * HappyAgentDiffView's ChangesMessage (and the legacy viewer's empty
     * text): a centred message, with Retry under it when there is something
     * to retry.
     */
    private inner class ChangesMessageView(context: Context, message: String, private val onRetry: (() -> Unit)?, retryTitle: String) : ManualLayout(context), ThemeAware {
        private val label = Label(context, DiffTextStyles.body, Palette.textSecondary, alignment = Layout.Alignment.ALIGN_CENTER).apply { text = message }
        private val retryLabel = Label(context, DiffTextStyles.retry, Palette.text, maxLines = 1).apply {
            text = retryTitle
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        private val retry = FrameLayout(context).apply {
            contentDescription = retryTitle
            setOnClickListener { onRetry?.invoke() }
            addView(retryLabel)
        }

        init {
            addView(label)
            if (onRetry != null) addView(retry)
            applyTheme()
        }

        override fun applyTheme() {
            setBackgroundColor(Theme.color(Palette.surface))
            retry.foreground = android.graphics.drawable.RippleDrawable(ColorStateList.valueOf(Theme.color(Palette.textSecondary) and 0x33FFFFFF), null, null)
        }

        override fun layoutSubviews(width: Double, height: Double) {
            val area = contentSafeArea
            val items = mutableListOf(CenteredStackLayout.Item(label.measurable))
            val retrySize = retryLabel.measure(Double.MAX_VALUE)
            if (onRetry != null) {
                items += CenteredStackLayout.Item(Measurable.fixed(retrySize.width + RETRY_PADDING * 2, retrySize.height + RETRY_PADDING * 2), spacingBefore = RETRY_SPACING)
            }
            val inner = Size(width, height - area.top - area.bottom)
            val frames = CenteredStackLayout(CenteredStackLayout.Input(inner, PADDING, items)).items.map { it.offsetBy(0.0, area.top) }
            label.setFrame(frames[0])
            if (frames.size > 1) {
                retry.setFrame(frames[1])
                retryLabel.setFrame(Rect(RETRY_PADDING, RETRY_PADDING, retrySize.width, retrySize.height))
            }
        }
    }

    private companion object {
        /** `<Pressable style={{ padding: 12, marginTop: 8 }}>`. */
        const val RETRY_PADDING = 12.0
        const val RETRY_SPACING = 8.0
        const val PADDING = 20.0
    }
}
