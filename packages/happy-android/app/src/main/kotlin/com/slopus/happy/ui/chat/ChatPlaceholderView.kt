package com.slopus.happy.ui.chat

import android.content.Context
import android.content.res.ColorStateList
import android.text.Layout
import android.widget.ProgressBar
import com.slopus.happy.R
import com.slopus.happy.chat.ChatEmptyState
import com.slopus.happy.chat.ChatOlderPaging
import com.slopus.happy.chat.PendingChatDetail
import com.slopus.happy.icons.Icon
import com.slopus.happy.layout.ChatTextStyles
import com.slopus.happy.layout.ColorToken
import com.slopus.happy.layout.Palette
import com.slopus.happy.layout.RoundButtonDisplay
import com.slopus.happy.layout.RoundButtonSize
import com.slopus.happy.layout.Size
import com.slopus.happy.layout.TextStyle
import com.slopus.happy.layout.chat.ChatPlaceholderLayout
import com.slopus.happy.model.details.DuplicateSheetModel
import com.slopus.happy.ui.components.IconView
import com.slopus.happy.ui.components.Label
import com.slopus.happy.ui.components.RoundButton
import com.slopus.happy.ui.home.ShimmerLabel
import com.slopus.happy.ui.theme.Theme
import com.slopus.happy.ui.theme.ThemeAware
import com.slopus.happy.ui.view.ManualLayout
import com.slopus.happy.ui.view.setFrame

/**
 * EmptyMessages for a chat with no messages, PendingChatPlaceholder for one
 * that hasn't started yet, or the deleted state (SessionView.tsx; happy-ios
 * `ChatPlaceholderView`), centred in the frame it is given. Its few lines
 * are measured as it lays out; the glyphs keep their size.
 */
class ChatPlaceholderView(context: Context) : ManualLayout(context), ThemeAware {
    sealed interface Content {
        data object Loading : Content
        /** The chat's first page failed: Retry in the spinner's place. */
        data object LoadFailed : Content
        /** EmptyMessages, with what shows under the path: "No messages yet", the spinner while it pages back, Retry or Load more. */
        data class Empty(val state: ChatEmptyState, val status: ChatOlderPaging.EmptyStatus) : Content
        data class PendingChat(val detail: PendingChatDetail?) : Content
        data object Deleted : Content
    }

    private data class Line(
        val text: String,
        val style: TextStyle,
        val color: ColorToken,
        /** 0: as many as it takes. */
        val maxLines: Int = 0,
        /** The space above it (the previous line's marginBottom) and its own paddingHorizontal. */
        val spacing: Double,
        val padding: Double,
    )

    /** EmptyMessages' status among the lines: Retry or Load more, or the paging spinner without a [button]. */
    private data class Status(val index: Int, val spacing: Double, val button: String? = null)

    private data class Model(
        val loading: Boolean = false,
        val icon: Pair<Icon, Double>? = null,
        val title: String? = null,
        val lines: List<Line> = emptyList(),
        val status: Status? = null,
        /** The first load's Retry, alone in the middle. */
        val button: String? = null,
    )

    /** Retry or Load more was pressed; which one is [content]. */
    var onAction: (() -> Unit)? = null

    var content: Content? = null
        private set
    private var model = Model()
    private val spinner = ProgressBar(context)
    /** EmptyMessages' and the first load's Retry and Load more (RoundButton, small and inverted). */
    private val button = RoundButton(context, context.getString(R.string.common_retry), RoundButtonSize.Small, RoundButtonDisplay.Inverted)
    private val pagingSpinner = ProgressBar(context, null, android.R.attr.progressBarStyleSmall)
    private val icon = IconView(context, null, 72.0, Palette.textSecondary)
    /** PendingChatPlaceholder's "Starting a new chat…". */
    private val shimmer = ShimmerLabel(context, ChatTextStyles.pendingChatTitle)
    private val labels = mutableListOf<Label>()

    init {
        addView(spinner)
        addView(icon)
        addView(shimmer)
        button.onTap = { onAction?.invoke() }
        addView(button)
        addView(pagingSpinner)
        applyTheme()
        apply()
    }

    fun show(content: Content) {
        if (content == this.content) return
        this.content = content
        model = model(content)
        apply()
        requestLayout()
    }

    override fun applyTheme() {
        val tint = ColorStateList.valueOf(Theme.color(Palette.textSecondary))
        spinner.indeterminateTintList = tint
        pagingSpinner.indeterminateTintList = tint
    }

    private fun model(content: Content): Model = when (content) {
        Content.Loading -> Model(loading = true)
        Content.LoadFailed -> Model(button = context.getString(R.string.common_retry))
        is Content.Empty -> {
            val state = content.state
            val lines = ArrayList<Line>()
            // EmptyMessages' margins: each line sits the previous line's marginBottom below it (icon 12, host 4, path 40, status 8).
            var spacing = 12.0
            state.host?.let {
                lines += Line(it, ChatTextStyles.emptyHost, Palette.text, spacing = spacing, padding = 48.0)
                spacing = 4.0
            }
            state.path?.let {
                lines += Line(it, ChatTextStyles.emptyPath, Palette.textSecondary, spacing = spacing, padding = 48.0)
                spacing = 40.0
            }
            // The title and the spinner keep 8 under them; RoundButton has no margin.
            var createdSpacing = 8.0
            var status: Status? = null
            when (content.status) {
                ChatOlderPaging.EmptyStatus.NoMessages ->
                    lines += Line(context.getString(R.string.chat_no_messages), ChatTextStyles.emptyTitle, Palette.textSecondary, spacing = spacing, padding = 48.0)
                ChatOlderPaging.EmptyStatus.Loading -> status = Status(lines.size, spacing)
                ChatOlderPaging.EmptyStatus.Error, ChatOlderPaging.EmptyStatus.LoadMore -> {
                    val title = if (content.status == ChatOlderPaging.EmptyStatus.Error) R.string.common_retry else R.string.common_load_more
                    status = Status(lines.size, spacing, context.getString(title))
                    createdSpacing = 0.0
                }
            }
            lines += Line(
                context.getString(R.string.chat_created, age(state.created)), ChatTextStyles.emptyCreated, Palette.textSecondary,
                spacing = createdSpacing, padding = 48.0,
            )
            Model(icon = Icon.ionicons(state.osIcon) to 72.0, lines = lines, status = status)
        }
        is Content.PendingChat -> Model(
            icon = Icon.ionicons("chatbubbles-outline") to 64.0,
            title = context.getString(R.string.session_starting_chat),
            lines = when (val detail = content.detail) {
                is PendingChatDetail.Queued -> listOf(Line(detail.text, ChatTextStyles.pendingQueued, Palette.text, maxLines = 6, spacing = 10.0, padding = 48.0))
                is PendingChatDetail.Path -> listOf(Line(detail.path, ChatTextStyles.emptyPath, Palette.textSecondary, maxLines = 1, spacing = 6.0, padding = 48.0))
                null -> emptyList()
            },
        )
        Content.Deleted -> Model(
            icon = Icon.ionicons("trash-outline") to 48.0,
            lines = listOf(
                Line(context.getString(R.string.errors_session_deleted), ChatTextStyles.deletedTitle, Palette.text, spacing = 16.0, padding = 0.0),
                Line(context.getString(R.string.errors_session_deleted_description), ChatTextStyles.deletedBody, Palette.textSecondary, spacing = 8.0, padding = 32.0),
            ),
        )
    }

    private fun age(age: DuplicateSheetModel.Age): String = when (age) {
        DuplicateSheetModel.Age.JustNow -> context.getString(R.string.time_just_now)
        is DuplicateSheetModel.Age.Minutes -> resources.getQuantityString(R.plurals.time_minutes_ago, age.count, age.count)
        is DuplicateSheetModel.Age.Hours -> resources.getQuantityString(R.plurals.time_hours_ago, age.count, age.count)
        is DuplicateSheetModel.Age.Days -> resources.getQuantityString(R.plurals.time_days_ago, age.count, age.count)
    }

    private fun apply() {
        val model = model
        spinner.visibility = if (model.loading) VISIBLE else GONE
        icon.visibility = if (model.icon == null) GONE else VISIBLE
        model.icon?.let { (glyph, size) ->
            icon.icon = glyph
            icon.size = size
        }
        shimmer.visibility = if (model.title == null) GONE else VISIBLE
        shimmer.isShimmering = model.title != null
        model.title?.let { shimmer.text = it }
        while (labels.size < model.lines.size) {
            val label = Label(context, ChatTextStyles.emptyPath, Palette.text, alignment = Layout.Alignment.ALIGN_CENTER)
            addView(label)
            labels += label
        }
        labels.forEachIndexed { index, label ->
            val line = model.lines.getOrNull(index)
            label.visibility = if (line == null) GONE else VISIBLE
            if (line != null) {
                label.style = line.style
                label.color = line.color
                label.maxLines = line.maxLines
                label.text = line.text
            }
        }
        val buttonTitle = model.status?.button ?: model.button
        button.visibility = if (buttonTitle == null) GONE else VISIBLE
        buttonTitle?.let { button.title = it }
        pagingSpinner.visibility = if (model.status != null && model.status.button == null) VISIBLE else GONE
    }

    override fun layoutSubviews(width: Double, height: Double) {
        val model = model
        val size = Size(width, height)
        if (model.loading) {
            spinner.setFrame(com.slopus.happy.layout.Rect((width - SPINNER) / 2, (height - SPINNER) / 2, SPINNER, SPINNER))
            return
        }
        if (model.button != null) {
            button.setFrame(ChatPlaceholderLayout.button(size, button.sizeThatFits(width, Double.MAX_VALUE)).status ?: return)
            return
        }
        val padding = ChatPlaceholderLayout.Metrics.padding
        fun inside(padding: Double) = maxOf(0.0, width - padding * 2)
        val lines = model.lines.mapIndexed { index, line -> ChatPlaceholderLayout.Line(labels[index].measure(inside(line.padding)), line.spacing, line.padding) }
        val status = model.status?.let {
            val statusSize = if (it.button != null) button.sizeThatFits(inside(padding), Double.MAX_VALUE) else Size(PAGING_SPINNER, PAGING_SPINNER)
            ChatPlaceholderLayout.Status(it.index, statusSize, it.spacing)
        }
        val layout = ChatPlaceholderLayout(
            size,
            icon = model.icon?.let { Size(it.second, it.second) },
            title = model.title?.let { shimmer.sizeThatFits(inside(padding), Double.MAX_VALUE) },
            lines = lines,
            status = status,
        )
        layout.icon?.let(icon::setFrame)
        layout.title?.let(shimmer::setFrame)
        layout.lines.forEachIndexed { index, frame -> labels[index].setFrame(frame) }
        layout.status?.let { if (model.status?.button != null) button.setFrame(it) else pagingSpinner.setFrame(it) }
    }

    private companion object {
        const val SPINNER = 32.0
        /** UIActivityIndicatorView's medium style. */
        const val PAGING_SPINNER = 20.0
    }
}
