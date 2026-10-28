package com.slopus.happy.ui.diff

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.drawable.GradientDrawable
import android.text.Layout
import android.text.TextUtils
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.ProgressBar
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.slopus.happy.R
import com.slopus.happy.diff.DiffDocument
import com.slopus.happy.diff.DiffFileItem
import com.slopus.happy.diff.DiffFilesCollapse
import com.slopus.happy.diff.DiffSource
import com.slopus.happy.diff.FileChangeKind
import com.slopus.happy.icons.Icon
import com.slopus.happy.icons.IconCatalog
import com.slopus.happy.layout.ColorToken
import com.slopus.happy.layout.DiffPalette
import com.slopus.happy.layout.DiffTextStyles
import com.slopus.happy.layout.LayoutInsets
import com.slopus.happy.layout.Measurable
import com.slopus.happy.layout.Palette
import com.slopus.happy.layout.Rect
import com.slopus.happy.layout.Size
import com.slopus.happy.layout.TextStyle
import com.slopus.happy.layout.diff.DiffFileHeaderLayout
import com.slopus.happy.layout.diff.DiffFilesListLayout
import com.slopus.happy.layout.diff.DiffImageLayout
import com.slopus.happy.layout.diff.DiffSectionLayout
import com.slopus.happy.layout.diff.DiffTextBox
import com.slopus.happy.ui.components.FileIconView
import com.slopus.happy.ui.components.IconView
import com.slopus.happy.ui.components.Label
import com.slopus.happy.ui.text.TextLayouts
import com.slopus.happy.ui.theme.Theme
import com.slopus.happy.ui.theme.ThemeAware
import com.slopus.happy.ui.view.dp
import com.slopus.happy.ui.view.setFrame
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.Base64
import kotlin.math.roundToInt

// happy-ios HappyUI/Diff/DiffFilesListView.swift and DiffSectionViews.swift:
// components/diff/DiffFilesList.tsx, every changed file one after another,
// each a FileSection measured off the main thread.

/** What a section's body says in place of a diff, read where the plan is made. */
interface DiffSectionWords : DiffWords {
    val imageBefore: String
    val imageAfter: String
    val badgeNew: String
    val badgeDeleted: String
    val badgeRenamed: String
    fun tapToExpand(count: Int): String

    companion object {
        fun of(context: Context): DiffSectionWords {
            val resources = context.resources
            return object : DiffSectionWords {
                override fun showMoreLines(count: Int) = resources.getQuantityString(R.plurals.diff_show_more_lines, count, count)
                override fun unchangedLines(count: Int) = resources.getString(R.string.diff_unchanged_lines, count)
                override val binaryFile: String = resources.getString(R.string.diff_binary_file)
                override val noChanges: String = resources.getString(R.string.diff_no_changes)
                override val imageBefore: String = resources.getString(R.string.diff_image_before)
                override val imageAfter: String = resources.getString(R.string.diff_image_after)
                override val badgeNew: String = resources.getString(R.string.diff_badge_new)
                override val badgeDeleted: String = resources.getString(R.string.diff_badge_deleted)
                override val badgeRenamed: String = resources.getString(R.string.diff_badge_renamed)
                override fun tapToExpand(count: Int) = resources.getQuantityString(R.plurals.diff_tap_to_expand, count, count)
            }
        }
    }
}

/** One measured line of a header: its text, style and colour. */
class DiffHeaderText(val text: String, val style: TextStyle, val color: ColorToken, val size: Size)

/**
 * DiffFileHeader.tsx's content, measured. `toggles: false` is a header
 * without `onToggle`, so without the chevron.
 */
class DiffHeaderPlan(
    path: String,
    kind: FileChangeKind,
    additions: Int,
    deletions: Int,
    collapsed: Boolean,
    width: Double,
    env: TextLayouts.Env,
    words: DiffSectionWords,
    toggles: Boolean = true,
) {
    /** The last path component, which also picks the file icon. */
    val name: String
    val dir: DiffHeaderText
    val fileName: DiffHeaderText
    val badge: DiffHeaderText?
    val additions: DiffHeaderText?
    val deletions: DiffHeaderText?
    val chevron: Icon = Icon.ionicons(if (collapsed) "chevron-forward" else "chevron-down")
    val layout: DiffFileHeaderLayout

    init {
        val tail = path.substringAfterLast('/')
        name = tail.ifEmpty { path }
        fun text(string: String, style: TextStyle, color: ColorToken) =
            DiffHeaderText(string, style, color, if (string.isEmpty()) Size(0.0, 0.0) else TextLayouts.measure(string, style, Double.MAX_VALUE, env, 1))
        dir = text(path.dropLast(name.length), DiffTextStyles.headerDir, Palette.textSecondary)
        fileName = text(name, DiffTextStyles.headerFileName, Palette.text)
        badge = when (kind) {
            FileChangeKind.Added -> text(words.badgeNew, DiffTextStyles.headerBadge, DiffPalette.markerAdded)
            FileChangeKind.Deleted -> text(words.badgeDeleted, DiffTextStyles.headerBadge, DiffPalette.markerRemoved)
            FileChangeKind.Renamed -> text(words.badgeRenamed, DiffTextStyles.headerBadge, Palette.textSecondary)
            else -> null
        }
        this.additions = if (additions > 0) text("+$additions", DiffTextStyles.headerCount, DiffPalette.markerAdded) else null
        this.deletions = if (deletions > 0) text("−$deletions", DiffTextStyles.headerCount, DiffPalette.markerRemoved) else null
        val chevronSize = IconCatalog.glyph(chevron)?.layout(DiffFileHeaderLayout.Metrics.chevronSize, env.density.toDouble())?.size
            ?: Size(DiffFileHeaderLayout.Metrics.chevronSize, DiffFileHeaderLayout.Metrics.chevronSize)
        fun box(text: DiffHeaderText): DiffTextBox {
            val ascent = -TextLayouts.paint(text.style, env, 0).fontMetrics.ascent / env.density
            return DiffTextBox(Size(text.size.width, maxOf(text.size.height, TextLayouts.lineHeight(text.style, env))), ascent.toDouble())
        }
        layout = DiffFileHeaderLayout(
            DiffFileHeaderLayout.Input(
                width = width,
                chevron = if (toggles) chevronSize else null,
                dir = box(dir),
                fileName = box(fileName),
                badge = badge?.size,
                additions = this.additions?.size,
                deletions = this.deletions?.size,
            ),
        )
    }
}

/** What one FileSection shows, as its owner decided it. */
data class DiffSectionInput(
    val item: DiffFileItem,
    val collapsed: Boolean,
    /** A collapsed big file's "N changed lines — tap to expand"; null for none. */
    val sizeHint: String?,
    val canExpandContext: Boolean,
    /** Built files the reader opened with "show more", by index. */
    val expandedFiles: Set<Int>,
)

/**
 * DiffFilesList.tsx's FileSection, built and measured off the main thread:
 * the header, then the size hint, a message, a spinner, the image panes or
 * each built file's plan.
 */
class DiffSectionPlan(
    val input: DiffSectionInput,
    val width: Double,
    val env: TextLayouts.Env,
    words: DiffSectionWords,
    document: (DiffSource) -> DiffDocument,
    syntaxDeadlineNanos: Long? = DiffChunkPlan.syntaxDeadline(),
) {
    class Side(val label: String, val tint: ColorToken, val image: Bitmap?)

    sealed interface Body {
        data object None : Body
        class Hint(val text: String) : Body
        class Message(val text: String) : Body
        data object Spinner : Body
        class Image(val sides: List<Side>, val layout: DiffImageLayout) : Body
        class Files(val plans: List<DiffFilePlan>) : Body
    }

    val header: DiffHeaderPlan
    val body: Body
    val layout: DiffSectionLayout

    val id: String get() = input.item.id

    init {
        val item = input.item
        header = DiffHeaderPlan(item.path, item.kind, item.additions, item.deletions, input.collapsed, width, env, words)
        fun size(text: String) = TextLayouts.measure(text, DiffTextStyles.sectionMessage, width, env)
        var sized: DiffSectionLayout.Body
        if (input.collapsed) {
            val hint = input.sizeHint
            if (hint != null) {
                body = Body.Hint(hint)
                sized = DiffSectionLayout.Body.Hint(size(hint))
            } else {
                body = Body.None
                sized = DiffSectionLayout.Body.None
            }
        } else {
            val builds = item.error == null && item.message == null && item.image == null
            val built = if (builds) item.source?.let(document) ?: DiffDocument.Empty else DiffDocument.Empty
            fun message(text: String): Pair<Body, DiffSectionLayout.Body> = Body.Message(text) to DiffSectionLayout.Body.Message(size(text))
            val made: Pair<Body, DiffSectionLayout.Body> = when (val shown = item.body(built)) {
                is DiffFileItem.Body.Error -> message(shown.message)
                is DiffFileItem.Body.Message -> message(shown.message)
                DiffFileItem.Body.NoChanges -> message(words.noChanges)
                DiffFileItem.Body.Loading -> Body.Spinner to DiffSectionLayout.Body.Spinner(SPINNER_SIZE)
                is DiffFileItem.Body.Image -> {
                    val sides = buildList {
                        shown.images.before?.let { add(Side(words.imageBefore, DiffPalette.markerRemoved, decode(it))) }
                        shown.images.after?.let { add(Side(words.imageAfter, DiffPalette.markerAdded, decode(it))) }
                    }
                    if (sides.isEmpty()) {
                        message(words.binaryFile)
                    } else {
                        val layout = DiffImageLayout(width, sides.size, TextLayouts.lineHeight(DiffTextStyles.imageLabel, env))
                        Body.Image(sides, layout) to DiffSectionLayout.Body.Image(layout.size.height)
                    }
                }
                is DiffFileItem.Body.Files -> {
                    val options = DiffViewOptions(collapseAfter = COLLAPSE_AFTER, canExpandContext = input.canExpandContext)
                    val plans = shown.files.mapIndexed { index, file ->
                        DiffFilePlan(file, width, env, words, options, index in input.expandedFiles, syntaxDeadlineNanos)
                    }
                    Body.Files(plans) to DiffSectionLayout.Body.Files(plans.map { it.height })
                }
            }
            body = made.first
            sized = made.second
        }
        layout = DiffSectionLayout(width, headerHeight = header.layout.size.height, body = sized)
    }

    companion object {
        /** FileSection's DiffFileView: 600 rows before "show more". */
        const val COLLAPSE_AFTER = 600
        /** UIActivityIndicatorView's medium spinner. */
        val SPINNER_SIZE = Size(20.0, 20.0)

        /** A `data:` URI's picture, or null when it isn't one Android decodes. */
        fun decode(uri: String): Bitmap? {
            val comma = uri.indexOf(',')
            if (!uri.startsWith("data:") || comma < 0) return null
            val bytes = runCatching { Base64.getMimeDecoder().decode(uri.substring(comma + 1)) }.getOrNull() ?: return null
            return BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        }
    }
}

/** What the list draws around its sections, measured with them: the notices' frames and height, and the empty text's size. */
class DiffListChrome(notices: List<String>, empty: String, width: Double, env: TextLayouts.Env) {
    val notices: Pair<List<Rect>, Double>? = if (notices.isEmpty()) null else DiffFilesListLayout.notices(
        width,
        notices.map { notice -> Measurable { w -> TextLayouts.measure(notice, DiffTextStyles.body, w, env) } },
    )
    val emptySize: Size = TextLayouts.measure(empty, DiffTextStyles.body, maxOf(0.0, width - DiffFilesListLayout.Metrics.emptyPadding * 2), env)
}

/**
 * Builds section plans off the main thread and keeps the last ones, so a
 * list update rebuilds only the sections that changed. A plan is kept for
 * one width and one text size. Built diffs are kept too, by source.
 */
class DiffSectionMeasurer(private val words: DiffSectionWords) {
    private val lock = Mutex()
    private var cache = HashMap<String, DiffSectionPlan>()
    private val documents = object : LinkedHashMap<DiffSource, DiffDocument>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<DiffSource, DiffDocument>?) = size > 64
    }

    suspend fun measure(
        inputs: List<DiffSectionInput>,
        notices: List<String>,
        empty: String,
        width: Double,
        env: TextLayouts.Env,
    ): Pair<List<DiffSectionPlan>, DiffListChrome> = lock.withLock {
        withContext(Dispatchers.Default) {
            // The sections of one pass wait for colour together.
            val deadline = DiffChunkPlan.syntaxDeadline()
            val next = HashMap<String, DiffSectionPlan>()
            val plans = inputs.map { input ->
                val cached = cache[input.item.id]
                val plan = if (cached != null && cached.width == width && cached.env == env && cached.input == input) {
                    cached
                } else {
                    DiffSectionPlan(input, width, env, words, { source -> documents.getOrPut(source) { source.build() } }, deadline)
                }
                next[input.item.id] = plan
                plan
            }
            cache = next
            plans to DiffListChrome(notices, empty, width, env)
        }
    }
}

/** components/diff/DiffFileHeader.tsx: chevron, file icon, path, change badge and counts on `hunkBg`. Tapping it toggles the file. */
class DiffFileHeaderView(context: Context) : ViewGroup(context), ThemeAware {
    private val chevron = IconView(context, null, DiffFileHeaderLayout.Metrics.chevronSize, Palette.textSecondary)
    private val icon = FileIconView(context)
    private val dir = Label(context, DiffTextStyles.headerDir, Palette.textSecondary, maxLines = 1).apply { ellipsize = TextUtils.TruncateAt.START }
    private val fileName = Label(context, DiffTextStyles.headerFileName, Palette.text, maxLines = 1)
    private val badge = Label(context, DiffTextStyles.headerBadge, Palette.textSecondary, maxLines = 1)
    private val additions = Label(context, DiffTextStyles.headerCount, DiffPalette.markerAdded, maxLines = 1)
    private val deletions = Label(context, DiffTextStyles.headerCount, DiffPalette.markerRemoved, maxLines = 1)
    private var plan: DiffHeaderPlan? = null
    private val pressed = Paint()

    init {
        for (view in listOf(chevron, icon, dir, fileName, badge, additions, deletions)) {
            view.importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
            addView(view)
        }
        isClickable = true
        isFocusable = true
        setWillNotDraw(false)
        applyTheme()
    }

    fun apply(plan: DiffHeaderPlan, path: String) {
        this.plan = plan
        chevron.icon = plan.chevron
        icon.fileName = plan.name
        dir.text = plan.dir.text
        fileName.text = plan.fileName.text
        fun Label.show(text: DiffHeaderText?) {
            if (text != null) {
                this.text = text.text
                color = text.color
            }
        }
        badge.show(plan.badge)
        additions.show(plan.additions)
        deletions.show(plan.deletions)
        contentDescription = path
        requestLayout()
    }

    override fun applyTheme() {
        setBackgroundColor(Theme.color(DiffPalette.hunkBackground))
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), MeasureSpec.getSize(heightMeasureSpec))
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val layout = plan?.layout ?: return
        place(chevron, layout.chevron)
        icon.setFrame(layout.icon)
        dir.setFrame(layout.dir)
        fileName.setFrame(layout.fileName)
        place(badge, layout.badge)
        place(additions, layout.additions)
        place(deletions, layout.deletions)
    }

    private fun place(view: View, frame: Rect?) {
        view.visibility = if (frame == null) GONE else VISIBLE
        frame?.let(view::setFrame)
    }

    override fun dispatchDraw(canvas: Canvas) {
        super.dispatchDraw(canvas)
        if (isPressed) {
            pressed.color = Theme.color(Palette.text)
            pressed.alpha = 20
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), pressed)
        }
    }

    override fun drawableStateChanged() {
        super.drawableStateChanged()
        invalidate()
    }
}

/** components/diff/DiffImageView.tsx's panes: Before and After side by side, each a label over a bordered 160-tall box with the picture fitted. */
class DiffImagePanesView(context: Context) : ViewGroup(context), ThemeAware {
    private val labels = ArrayList<Label>()
    private val boxes = ArrayList<FrameLayout>()
    private val images = ArrayList<ImageView>()
    private var layout: DiffImageLayout? = null

    init {
        applyTheme()
    }

    fun apply(sides: List<DiffSectionPlan.Side>, layout: DiffImageLayout) {
        this.layout = layout
        while (labels.size < sides.size) {
            val label = Label(context, DiffTextStyles.imageLabel, Palette.textSecondary, maxLines = 1)
            val box = FrameLayout(context).apply { clipToOutline = true }
            val image = ImageView(context).apply { scaleType = ImageView.ScaleType.FIT_CENTER }
            box.addView(image)
            addView(label)
            addView(box)
            labels += label
            boxes += box
            images += image
        }
        labels.indices.forEach { index ->
            val side = sides.getOrNull(index)
            labels[index].visibility = if (side == null) GONE else VISIBLE
            boxes[index].visibility = if (side == null) GONE else VISIBLE
            if (side != null) {
                labels[index].text = side.label
                labels[index].color = side.tint
                images[index].setImageBitmap(side.image)
            }
        }
        applyTheme()
        requestLayout()
    }

    override fun applyTheme() {
        setBackgroundColor(Theme.color(DiffSurface))
        for (box in boxes) {
            box.background = GradientDrawable().apply {
                setColor(Theme.color(DiffPalette.hunkBackground))
                cornerRadius = dp(DiffImageLayout.Metrics.boxRadius)
                setStroke(dp(DiffImageLayout.Metrics.boxBorder).roundToInt().coerceAtLeast(1), Theme.color(Palette.divider))
            }
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), MeasureSpec.getSize(heightMeasureSpec))
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val layout = layout ?: return
        layout.panes.forEachIndexed { index, pane ->
            if (index >= labels.size) return@forEachIndexed
            labels[index].setFrame(pane.label)
            boxes[index].setFrame(pane.box)
            val inset = DiffImageLayout.Metrics.boxBorder
            images[index].setFrame(Rect(inset, inset, pane.image.width, pane.image.height))
        }
    }
}

/** One FileSection on screen. */
class DiffSectionView(context: Context) : ViewGroup(context), ThemeAware {
    var onToggle: (() -> Unit)? = null
    var onShowMore: ((Int) -> Unit)? = null
    var onExpandContext: (() -> Unit)? = null

    private val header = DiffFileHeaderView(context).apply { setOnClickListener { onToggle?.invoke() } }
    private val hint = View(context).apply {
        isClickable = true
        setOnClickListener { onToggle?.invoke() }
    }
    private val hintLabel = Label(context, DiffTextStyles.sectionMessage, Palette.textSecondary, alignment = Layout.Alignment.ALIGN_CENTER)
    private val message = Label(context, DiffTextStyles.sectionMessage, Palette.textSecondary, alignment = Layout.Alignment.ALIGN_CENTER)
    private val spinner = ProgressBar(context, null, android.R.attr.progressBarStyleSmall)
    private val images = DiffImagePanesView(context)
    private val fileViews = ArrayList<DiffFileView>()
    private val border = View(context)
    var plan: DiffSectionPlan? = null
        private set

    init {
        for (view in listOf(header, hint, hintLabel, message, spinner, images, border)) addView(view)
        hintLabel.importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        applyTheme()
    }

    fun apply(plan: DiffSectionPlan) {
        if (plan === this.plan) return
        this.plan = plan
        header.apply(plan.header, plan.input.item.path)
        for (view in listOf(hint, hintLabel, message, spinner, images)) view.visibility = GONE
        var files: List<DiffFilePlan> = emptyList()
        when (val body = plan.body) {
            DiffSectionPlan.Body.None -> Unit
            is DiffSectionPlan.Body.Hint -> {
                hint.visibility = VISIBLE
                hint.contentDescription = body.text
                hintLabel.visibility = VISIBLE
                hintLabel.text = body.text
            }
            is DiffSectionPlan.Body.Message -> {
                message.visibility = VISIBLE
                message.text = body.text
            }
            DiffSectionPlan.Body.Spinner -> spinner.visibility = VISIBLE
            is DiffSectionPlan.Body.Image -> {
                images.visibility = VISIBLE
                images.apply(body.sides, body.layout)
            }
            is DiffSectionPlan.Body.Files -> files = body.plans
        }
        while (fileViews.size < files.size) {
            val index = fileViews.size
            val view = DiffFileView(context)
            view.onShowMore = { onShowMore?.invoke(index) }
            view.onExpandContext = { onExpandContext?.invoke() }
            addView(view, indexOfChild(border))
            fileViews += view
        }
        while (fileViews.size > files.size) removeView(fileViews.removeAt(fileViews.lastIndex))
        fileViews.zip(files).forEach { (view, file) -> view.apply(file) }
        requestLayout()
    }

    override fun applyTheme() {
        border.setBackgroundColor(Theme.color(Palette.divider))
        spinner.indeterminateTintList = ColorStateList.valueOf(Theme.color(Palette.textSecondary))
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), dp(plan?.layout?.height ?: 0.0).roundToInt())
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val layout = plan?.layout ?: return
        val origin = layout.frame
        fun place(view: View, frame: Rect?) {
            if (frame != null && view.visibility != GONE) view.setFrame(frame.offsetBy(origin.x, origin.y))
        }
        place(header, layout.header)
        place(hint, layout.hintRow)
        place(hintLabel, layout.content)
        place(message, layout.content)
        place(spinner, layout.content)
        place(images, layout.image)
        fileViews.zip(layout.files).forEach { (view, frame) -> place(view, frame) }
        place(border, layout.border)
    }
}

/**
 * components/diff/DiffFilesList.tsx: every changed file, one after another,
 * each a FileSection. Sections, the notices and the empty text are measured
 * off the main thread ([DiffSectionMeasurer]); a file's diff is built only
 * once it is open.
 */
class DiffFilesListView(context: Context, private val scope: CoroutineScope) : FrameLayout(context), ThemeAware {
    /** Asks for the body of an opened file that has none yet. */
    var onRequestContent: ((String) -> Unit)? = null
    /** An "N unchanged" separator was tapped in this file. */
    var onExpandContext: ((String) -> Unit)? = null

    private val words = DiffSectionWords.of(context)
    private val measurer = DiffSectionMeasurer(words)
    private val recycler = RecyclerView(context)
    private val adapter = Adapter()

    private var items: List<DiffFileItem> = emptyList()
    private var notices: List<String> = emptyList()
    private var emptyText = context.getString(R.string.diff_no_changes)
    private var defaultCollapsed = false

    /** The reader's open/close toggles, by path. */
    private val overrides = HashMap<String, Boolean>()
    /** Built files opened with "show more", by item. */
    private val expandedFiles = HashMap<String, Set<Int>>()
    /** Paths whose body was asked for during this opening. */
    private val requested = HashSet<String>()

    private var plans: List<DiffSectionPlan> = emptyList()
    private var model = DiffFilesListLayout(0.0, null, emptyList(), Size(0.0, 0.0))
    private var noticeFrames: List<Rect> = emptyList()
    private var emptyFrame = Rect.Zero
    private var measuredWidth = -1.0
    private var measuredEnv: TextLayouts.Env? = null
    private var generation = 0
    private var measureJob: Job? = null

    /** Space the list scrolls under: the bar above, the home indicator below. */
    var insets: LayoutInsets = LayoutInsets.Zero
        set(value) {
            field = value
            // The layout's 32 of room after the last section.
            recycler.setPadding(0, dp(value.top).roundToInt(), 0, dp(value.bottom + DiffFilesListLayout.Metrics.paddingBottom).roundToInt())
        }

    init {
        recycler.layoutManager = LinearLayoutManager(context)
        recycler.itemAnimator = null
        recycler.clipToPadding = false
        recycler.adapter = adapter
        recycler.overScrollMode = OVER_SCROLL_IF_CONTENT_SCROLLS
        addView(recycler, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        applyTheme()
    }

    override fun applyTheme() {
        setBackgroundColor(Theme.color(DiffSurface))
        Theme.applyTree(recycler)
    }

    /** DiffFilesList's props. */
    fun update(items: List<DiffFileItem>, notices: List<String> = emptyList(), emptyText: String? = null, defaultCollapsed: Boolean) {
        this.items = items
        this.notices = notices
        this.emptyText = emptyText ?: context.getString(R.string.diff_no_changes)
        this.defaultCollapsed = defaultCollapsed
        requestMissingContent()
        remeasure()
    }

    /** A fresh list, as a remount is in happy-app: every file back to its default, scrolled to the top. */
    fun reset() {
        overrides.clear()
        expandedFiles.clear()
        requested.clear()
        plans = emptyList()
        generation++
        model = DiffFilesListLayout(maxOf(0.0, measuredWidth), null, emptyList(), Size(0.0, 0.0))
        emptyFrame = Rect.Zero
        adapter.notifyDataSetChanged()
        recycler.scrollToPosition(0)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (com.slopus.happy.ui.view.px(w) != measuredWidth) post { remeasure() }
    }

    private fun collapsed(item: DiffFileItem) = DiffFilesCollapse.isCollapsed(item, overrides[item.path], defaultCollapsed)

    /** FileSection's effect: an opened file with no body asks for it once per opening; closing it, or a body arriving, resets that. */
    private fun requestMissingContent() {
        for (item in items) {
            if (collapsed(item) || !DiffFilesCollapse.needsContent(item, false)) {
                requested -= item.path
                continue
            }
            val request = onRequestContent ?: continue
            if (!requested.add(item.path)) continue
            request(item.path)
        }
    }

    private fun remeasure() {
        val width = com.slopus.happy.ui.view.px(this.width)
        if (width <= 0) return
        val env = TextLayouts.Env(Theme.density, Theme.textScale)
        measuredWidth = width
        measuredEnv = env
        val current = ++generation
        val inputs = items.map { item ->
            val collapsed = collapsed(item)
            DiffSectionInput(
                item = item,
                collapsed = collapsed,
                sizeHint = if (DiffFilesCollapse.showsSizeHint(item, collapsed)) words.tapToExpand(item.additions + item.deletions) else null,
                canExpandContext = onExpandContext != null,
                expandedFiles = expandedFiles[item.id].orEmpty(),
            )
        }
        val notices = notices
        val empty = emptyText
        measureJob = scope.launch {
            val (plans, chrome) = measurer.measure(inputs, notices, empty, width, env)
            if (current != generation) return@launch
            apply(plans, width, chrome)
        }
    }

    private fun apply(plans: List<DiffSectionPlan>, width: Double, chrome: DiffListChrome) {
        // The same files in the same order update in place, so open sections keep their views.
        val inPlace = plans.isNotEmpty() && plans.map { it.id } == this.plans.map { it.id } && (chrome.notices != null) == (model.header != null)
        this.plans = plans
        noticeFrames = chrome.notices?.first.orEmpty()
        model = DiffFilesListLayout(width, chrome.notices?.second, plans.map { it.layout.height }, chrome.emptySize)
        model.empty?.let { emptyFrame = it.offsetBy(0.0, -(it.minY - DiffFilesListLayout.Metrics.emptyPadding)) }
        if (inPlace) adapter.notifyItemRangeChanged(0, adapter.itemCount) else adapter.notifyDataSetChanged()
    }

    private fun toggle(path: String) {
        val item = items.firstOrNull { it.path == path } ?: return
        overrides[path] = !collapsed(item)
        requestMissingContent()
        remeasure()
    }

    private fun showMore(id: String, file: Int) {
        expandedFiles[id] = expandedFiles[id].orEmpty() + file
        remeasure()
    }

    // The rows: the notices, then the sections or the empty text, each as tall as the layout says.

    private val hasHeader get() = model.header != null

    private inner class Adapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        init {
            setHasStableIds(true)
        }

        override fun getItemCount() = (if (hasHeader) 1 else 0) + if (plans.isEmpty()) (if (model.empty == null) 0 else 1) else plans.size

        override fun getItemViewType(position: Int): Int = when {
            hasHeader && position == 0 -> NOTICES
            position - (if (hasHeader) 1 else 0) < plans.size -> SECTION
            else -> EMPTY
        }

        override fun getItemId(position: Int): Long = when (getItemViewType(position)) {
            NOTICES -> Long.MIN_VALUE
            EMPTY -> Long.MIN_VALUE + 1
            else -> plans[position - (if (hasHeader) 1 else 0)].id.hashCode().toLong()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder = object : RecyclerView.ViewHolder(
            when (viewType) {
                NOTICES -> TextsRow(context)
                EMPTY -> TextsRow(context)
                else -> DiffSectionView(context)
            }.apply { layoutParams = RecyclerView.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT) },
        ) {}

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            when (val view = holder.itemView) {
                is DiffSectionView -> {
                    val plan = plans.getOrNull(position - (if (hasHeader) 1 else 0)) ?: return
                    val item = plan.input.item
                    view.apply(plan)
                    view.onToggle = { toggle(item.path) }
                    view.onShowMore = { file -> showMore(item.id, file) }
                    view.onExpandContext = onExpandContext?.let { expand -> { expand(item.path) } }
                    Theme.applyTree(view)
                }
                is TextsRow -> if (getItemViewType(position) == NOTICES) {
                    view.apply(notices, noticeFrames, model.header?.height ?: 0.0)
                } else {
                    val empty = model.empty ?: return
                    view.apply(listOf(emptyText), listOf(emptyFrame), empty.height + DiffFilesListLayout.Metrics.emptyPadding * 2)
                }
            }
        }
    }

    /** HappyAgentDiffView's notices, or the empty text: labels at measured frames. */
    private class TextsRow(context: Context) : ViewGroup(context) {
        private val labels = ArrayList<Label>()
        private var frames: List<Rect> = emptyList()
        private var height = 0.0

        fun apply(texts: List<String>, frames: List<Rect>, height: Double) {
            while (labels.size < texts.size) Label(context, DiffTextStyles.body, Palette.textSecondary).also { labels += it; addView(it) }
            labels.forEachIndexed { index, label ->
                label.visibility = if (index < texts.size) VISIBLE else GONE
                if (index < texts.size) label.text = texts[index]
            }
            this.frames = frames
            this.height = height
            requestLayout()
        }

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), dp(height).roundToInt())
        }

        override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
            labels.zip(frames).forEach { (label, frame) -> label.setFrame(frame) }
        }
    }

    private companion object {
        const val NOTICES = 0
        const val SECTION = 1
        const val EMPTY = 2
    }
}
