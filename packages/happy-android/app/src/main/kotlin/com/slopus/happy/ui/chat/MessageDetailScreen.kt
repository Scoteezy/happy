package com.slopus.happy.ui.chat

import com.slopus.happy.ui.nav.SessionRoute
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.PopupMenu
import android.widget.ProgressBar
import android.widget.ScrollView
import com.slopus.happy.AppGraph
import com.slopus.happy.BuildConfig
import com.slopus.happy.R
import com.slopus.happy.chat.ChatFeed
import com.slopus.happy.chat.ChatMessage
import com.slopus.happy.chat.ChatState
import com.slopus.happy.chat.NoLivePresence
import com.slopus.happy.chat.StoreChatMessagesSource
import com.slopus.happy.chat.StoreChatSessionSource
import com.slopus.happy.chat.SyncChatMessagesSource
import com.slopus.happy.chat.SyncChatPresenceSource
import com.slopus.happy.chat.tools.ToolFullPresentation
import com.slopus.happy.layout.Palette
import com.slopus.happy.layout.Rect
import com.slopus.happy.layout.chat.ChatScreenLayout
import com.slopus.happy.model.Settings
import com.slopus.happy.ui.nav.BarScreen
import com.slopus.happy.ui.text.TextLayouts
import com.slopus.happy.ui.theme.Theme
import com.slopus.happy.ui.theme.ThemeAware
import com.slopus.happy.ui.view.ManualLayout
import com.slopus.happy.ui.view.px
import com.slopus.happy.ui.view.setFrame
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * session/[id]/message/[messageId].tsx: one message on its own screen. A
 * tool shows all of ToolFullView, output included and nothing collapsed; a
 * text message shows its whole text. Titled by ToolHeader.
 *
 * A spinner until the session and its messages have loaded; once they have,
 * a message that isn't there sends the reader back. Ported from happy-ios
 * Chat/MessageDetail/MessageDetailViewController.swift.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MessageDetailScreen(context: Context, private val graph: AppGraph, override val sessionId: String, private val messageId: String) : BarScreen(context), SessionRoute {
    private val renderer = MessageDetailRenderer(context.resources, ChatRowRenderer(context.resources))
    private val scroll = ScrollView(context)
    private val canvas = ChatRowView(context)
    private val spinner = ProgressBar(context)
    private val content = Content(context)

    private var state = ChatState()
    private var settings = Settings()
    private var message: ChatMessage? = null
    /** The files the reader opened past "Show N more lines", by diff. */
    private var diffExpanded = mapOf<Int, Set<Int>>()
    private var lastInput: Pair<MessageDetailInput, Double>? = null
    private var leaving = false

    override val contentUnderBar: Boolean get() = false

    override fun createContent(): View {
        // ToolHeader: one line, cut in the middle, so both ends of a long path or command stay readable.
        // Header.tsx's title, which scales with the text size (happy-ios setScaledTitleView).
        bar.plainTitle = true
        bar.titleEllipsize = TextUtils.TruncateAt.MIDDLE
        bar.title = context.getString(R.string.common_message)
        scroll.isFillViewport = false
        scroll.clipToPadding = false
        scroll.addView(canvas)
        scroll.visibility = View.GONE
        canvas.onAction = ::perform
        content.addView(scroll)
        content.addView(spinner)

        val messages = graph.account.value?.let { SyncChatMessagesSource(it.messages, it.sender) } ?: StoreChatMessagesSource(graph.store)
        val feed = graph.account.flatMapLatest { account ->
            val presence = account?.let { SyncChatPresenceSource(it.presence) } ?: NoLivePresence
            ChatFeed(messages, StoreChatSessionSource(graph.store), presence).state(sessionId)
        }
        scope.launch {
            combine(feed, graph.settings.settings) { state, settings -> state to settings }.collect { (state, settings) -> apply(state, settings) }
        }
        return content
    }

    override fun onInsetsChanged() {
        super.onInsetsChanged()
        scroll.setPadding(0, 0, 0, com.slopus.happy.ui.view.dp(safeArea.bottom).toInt())
    }

    private fun apply(state: ChatState, settings: Settings) {
        this.state = state
        this.settings = settings
        val found = state.snapshot.rows.firstOrNull { it.message.id == messageId }?.message
        if (found != message) {
            message = found
            updateTitle()
        }
        scheduleMeasure()
        updateState()
        leaveIfGone()
    }

    /** Messages have loaded and this one isn't among them (router.back()). */
    private fun leaveIfGone() {
        if (leaving || !state.snapshot.loaded || message != null) return
        leaving = true
        pop()
    }

    /** ToolHeader: the tool's title, or "Message", including while loading. */
    private fun updateTitle() {
        val call = message as? ChatMessage.ToolCallMessage
        bar.title = call?.let { ToolFullPresentation.title(it.tool, state.session?.metadata?.path, renderer.toolText, it.children) }
            ?: context.getString(R.string.common_message)
    }

    private val loaded get() = state.sessionLoaded && state.snapshot.loaded && message != null

    private fun updateState() {
        val shown = loaded && canvas.plan != null
        scroll.visibility = if (shown) View.VISIBLE else View.GONE
        spinner.visibility = if (shown) View.GONE else View.VISIBLE
    }

    private fun scheduleMeasure() {
        val message = message ?: return
        val width = px(content.width)
        if (width <= 0) return
        val input = MessageDetailInput(
            message = message,
            root = state.session?.metadata?.path,
            showLineNumbers = settings.showLineNumbersInToolViews,
            devMode = BuildConfig.DEBUG || graph.localSettings.values.value.devModeEnabled,
            diffExpanded = diffExpanded,
            maxContentWidth = ChatScreenLayout.Metrics.regularMaxContentWidth,
            env = TextLayouts.Env(Theme.density, Theme.textScale),
        )
        if (lastInput == input to width) return
        lastInput = input to width
        scope.launch {
            val plan = withContext(Dispatchers.Default) { renderer.plan(input, width) }
            if (lastInput != input to width) return@launch
            canvas.bind(plan)
            updateState()
        }
    }

    private fun perform(action: ChatRowAction, frame: Rect, hold: Boolean) {
        when (action) {
            is ChatRowAction.DiffShowMore -> {
                diffExpanded = diffExpanded + (action.chunk to diffExpanded[action.chunk].orEmpty() + action.file)
                scheduleMeasure()
            }
            is ChatRowAction.CopyMenu -> showCopyMenu(action.text, frame)
            else -> Unit
        }
    }

    /** React Native's selectable text: the platform's popup with Copy. */
    private fun showCopyMenu(text: String, frame: Rect) {
        val anchor = View(context)
        content.addView(anchor)
        val top = frame.minY - px(scroll.scrollY)
        anchor.setFrame(Rect(frame.midX, top.coerceAtLeast(0.0), 1.0, 1.0))
        val popup = PopupMenu(context, anchor, Gravity.CENTER)
        popup.menu.add(context.getString(R.string.common_copy))
        popup.setOnMenuItemClickListener {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText(null, text))
            true
        }
        popup.setOnDismissListener { content.removeView(anchor) }
        popup.show()
    }

    private inner class Content(context: Context) : ManualLayout(context), ThemeAware {
        init {
            applyTheme()
        }

        override fun applyTheme() {
            setBackgroundColor(Theme.color(Palette.groupedBackground))
        }

        override fun layoutSubviews(width: Double, height: Double) {
            scroll.setFrame(Rect(0.0, 0.0, width, height))
            spinner.setFrame(Rect(width / 2 - 16, height / 2 - 16, 32.0, 32.0))
            scheduleMeasure()
        }
    }
}
