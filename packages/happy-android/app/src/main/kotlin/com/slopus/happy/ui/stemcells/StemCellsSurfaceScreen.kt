package com.slopus.happy.ui.stemcells

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.text.Layout
import android.text.SpannableString
import android.text.Spanned
import android.text.style.StrikethroughSpan
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.ProgressBar
import com.slopus.happy.R
import com.slopus.happy.direct.ClientId
import com.slopus.happy.icons.Icon
import com.slopus.happy.layout.Measurable
import com.slopus.happy.layout.Palette
import com.slopus.happy.layout.Rect
import com.slopus.happy.layout.StemCellsPalette
import com.slopus.happy.layout.stemcells.StemCellsSurfaceBodyLayout
import com.slopus.happy.layout.stemcells.StemCellsTodoLayout
import com.slopus.happy.stemcells.StemCellChatItem
import com.slopus.happy.stemcells.Todo
import com.slopus.happy.stemcells.addTodo
import com.slopus.happy.stemcells.document
import com.slopus.happy.stemcells.todos
import com.slopus.happy.ui.chat.ChatRowPlan
import com.slopus.happy.ui.chat.ChatRowView
import com.slopus.happy.ui.components.IconView
import com.slopus.happy.ui.components.Label
import com.slopus.happy.ui.components.TextField
import com.slopus.happy.ui.nav.BarScreen
import com.slopus.happy.ui.tablet.ContentWidth
import com.slopus.happy.ui.text.TextLayouts
import com.slopus.happy.ui.theme.Theme
import com.slopus.happy.ui.theme.ThemeAware
import com.slopus.happy.ui.view.BounceScrollView
import com.slopus.happy.ui.view.ManualLayout
import com.slopus.happy.ui.view.dp
import com.slopus.happy.ui.view.px
import com.slopus.happy.ui.view.setFrame
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * A Surface on the phone, kept minimal (happy-ios
 * `StemCellsSurfaceViewController`): a todo list shows its todos live and
 * takes a new one; a document shows its Markdown, read-only, read again on
 * a pull; any other kind says to open it on the computer. Desktop's editors
 * stay desktop's.
 */
class StemCellsSurfaceScreen(
    context: Context,
    private val session: StemCellsSession,
    private val surfaceId: String,
    private val type: String,
    name: String,
) : BarScreen(context) {
    private val content = Content(context)
    private val scroll = BounceScrollView(context)
    private val field = TextField(context, StemCellsFonts.ui(StemCellsFonts.Weight.Regular, 16.0))
    private val empty = Label(context, StemCellsFonts.ui(StemCellsFonts.Weight.Regular, 15.0), StemCellsPalette.stemCellsRowText, alignment = Layout.Alignment.ALIGN_CENTER)
    private val refreshing = ProgressBar(context)
    private val planner = StemCellChatPlanner(context.resources)
    private val rows = HashMap<String, TodoRowView>()
    private var todos: List<Todo> = emptyList()
    private var document: ChatRowView? = null
    private var documentPlan: ChatRowPlan? = null
    private var documentText: String? = null
    private var unwatch: (() -> Unit)? = null

    init {
        bar.title = name
    }

    override val contentUnderBar: Boolean get() = false

    /** What the probe reads: the kind, then its todos or whether the document is shown. */
    val probe: String
        get() = "surface=$type todos=${todos.size} done=${todos.count { it.status == "completed" }} document=${if (document != null) 1 else 0}"

    override fun createContent(): View {
        content.tag = "stem-surface"
        content.addView(scroll)
        scroll.content.addView(empty)
        empty.tag = "stem-surface-empty"
        when (type) {
            "todo" -> {
                field.hint = context.getString(R.string.stem_add_todo)
                field.setPadding(dp(12.0).toInt(), 0, dp(12.0).toInt(), 0)
                field.isSingleLine = true
                field.imeOptions = EditorInfo.IME_ACTION_DONE
                // Return ends editing, as the field's Done does on iOS.
                field.onReturn = {
                    add()
                    field.dismissKeyboard()
                }
                field.tag = "stem-todo-field"
                scroll.content.addView(field)
                empty.text = context.getString(R.string.stem_no_todos)
                scope.launch {
                    reload()
                    session.store.conversationEvents(surfaceId).collect { reload() }
                }
            }
            "document" -> {
                refreshing.visibility = View.GONE
                content.addView(refreshing)
                scroll.listener = object : BounceScrollView.Listener {
                    override fun didEndDragging(decelerate: Boolean) {
                        if (scroll.contentOffset < -PULL && refreshing.visibility != View.VISIBLE) refresh()
                    }
                }
                scope.launch { reload() }
            }
            else -> empty.text = context.getString(R.string.stem_open_on_computer)
        }
        unwatch = session.watch { snapshot ->
            val row = snapshot.allRows.firstOrNull { it.targetKind == "surface" && it.targetId == surfaceId } ?: return@watch
            if (bar.title != row.label) bar.title = row.label
        }
        content.applyTheme()
        return content
    }

    override fun onDestroy() {
        unwatch?.invoke()
    }

    // Reads

    private suspend fun reload() {
        try {
            when (type) {
                "todo" -> show(session.client.todos(surfaceId))
                "document" -> showDocument(session.client.document(surfaceId))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
        }
    }

    private fun refresh() {
        refreshing.visibility = View.VISIBLE
        scope.launch {
            reload()
            refreshing.visibility = View.GONE
        }
    }

    private fun show(todos: List<Todo>) {
        if (todos == this.todos && rows.isEmpty() == todos.isEmpty()) return
        this.todos = todos
        val seen = HashSet<String>()
        for (todo in todos) {
            seen += todo.id
            rows.getOrPut(todo.id) { TodoRowView(context).also { scroll.content.addView(it) } }.configure(todo)
        }
        for (id in rows.keys - seen) rows.remove(id)?.let {
            scroll.content.frames.remove(it)
            scroll.content.removeView(it)
        }
        content.requestLayout()
    }

    /** The Markdown planned off the main thread, as a conversation's agent text. */
    private suspend fun showDocument(text: String) {
        documentText = text
        val width = px(content.width)
        if (width <= 0) return content.requestLayout()
        val key = StemCellChatPlanner.Key(width, ContentWidth.maxWidth(context), TextLayouts.Env(Theme.density, Theme.textScale))
        val item = StemCellChatItem("document:$surfaceId", StemCellChatItem.Role.Agent, listOf(StemCellChatItem.Piece.Text(text)), StemCellChatItem.Delivery.Sent)
        val plan = withContext(Dispatchers.Default) { planner.plan(item, key) }
        documentPlan = plan
        val view = document ?: ChatRowView(context).also {
            it.tag = "stem-document"
            scroll.content.addView(it)
            document = it
        }
        view.bind(plan)
        view.contentDescription = text
        empty.text = if (text.isBlank()) context.getString(R.string.stem_empty_document) else ""
        content.requestLayout()
    }

    // Acts

    private fun add() {
        val text = field.text.toString().trim()
        if (text.isEmpty()) return
        field.setText("")
        scope.launch {
            runCatching { session.client.addTodo(surfaceId, ClientId.make(), text) }
            reload()
        }
    }

    private inner class Content(context: Context) : ManualLayout(context), ThemeAware {
        init {
            // Where focus goes when the field lets it go, so its cursor goes too.
            isFocusableInTouchMode = true
            defaultFocusHighlightEnabled = false
        }

        override fun applyTheme() {
            setBackgroundColor(Theme.color(Palette.surface))
            field.background = GradientDrawable().apply {
                setColor(Theme.color(StemCellsPalette.stemCellsButton))
                cornerRadius = dp(10.0)
            }
        }

        override fun layoutSubviews(width: Double, height: Double) {
            scroll.setInsets(0.0, safeArea.bottom + keyboard)
            scroll.setFrame(Rect(0.0, 0.0, width, height))
            refreshing.setFrame(Rect(width / 2 - 14, 12.0, 28.0, 28.0))
            val frames = scroll.content.frames
            val contentHeight = when (type) {
                "todo" -> {
                    val layout = StemCellsTodoLayout(width, todos.map { rows[it.id]?.textMeasurable ?: Measurable.fixed(0, 0) }, empty.measurable)
                    frames[field] = layout.field
                    todos.forEachIndexed { index, todo ->
                        val row = rows[todo.id] ?: return@forEachIndexed
                        frames[row] = layout.rows[index].frame
                        row.place(layout.rows[index])
                    }
                    frames[empty] = layout.empty
                    layout.contentHeight
                }
                "document" -> {
                    val text = documentText
                    if (text != null && documentPlan?.size?.width != width) scope.launch { showDocument(text) }
                    val showsEmpty = empty.text.isNotEmpty()
                    val body = if (showsEmpty) empty.measurable else Measurable.fixed(width, documentPlan?.size?.height ?: 0.0)
                    val layout = StemCellsSurfaceBodyLayout(width, body, if (showsEmpty) 16.0 else 0.0)
                    document?.let { frames[it] = if (showsEmpty) null else layout.body }
                    frames[empty] = if (showsEmpty) layout.body else null
                    layout.contentHeight
                }
                else -> {
                    val layout = StemCellsSurfaceBodyLayout(width, empty.measurable, 16.0)
                    frames[empty] = layout.body
                    layout.contentHeight
                }
            }
            scroll.contentHeight = contentHeight
            scroll.content.setFrame(Rect(0.0, 0.0, width, contentHeight))
            scroll.content.layoutSubviews(width, contentHeight)
        }
    }

    /** One todo: its box, ticked once done, and its text, faded and struck through then. */
    private class TodoRowView(context: Context) : ManualLayout(context) {
        private val box = IconView(context, null, 20.0, StemCellsPalette.stemCellsRowText)
        private val label = Label(context, StemCellsFonts.ui(StemCellsFonts.Weight.Regular, 16.0), Palette.text)

        init {
            addView(box)
            addView(label)
            tag = "stem-todo"
        }

        val textMeasurable: Measurable get() = label.measurable

        fun configure(todo: Todo) {
            val done = todo.status == "completed"
            box.icon = Icon.ionicons(if (done) "checkmark-circle" else "ellipse-outline")
            label.color = if (done) StemCellsPalette.stemCellsRowText else Palette.text
            label.text = if (done) SpannableString(todo.text).apply { setSpan(StrikethroughSpan(), 0, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE) } else todo.text
            contentDescription = if (done) "${todo.text}, ${context.getString(R.string.stem_todo_done)}" else todo.text
        }

        fun place(layout: StemCellsTodoLayout.Row) {
            box.setFrame(layout.box)
            label.setFrame(layout.text)
        }

        override fun layoutSubviews(width: Double, height: Double) {}
    }

    private companion object {
        /** How far past the top a released pull reads the document again. */
        const val PULL = 64.0
    }
}
