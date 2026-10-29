package com.slopus.happy.harness

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.slopus.happy.direct.DirectAgentClient
import com.slopus.happy.direct.DirectEvent
import com.slopus.happy.layout.LayoutInsets
import com.slopus.happy.layout.Palette
import com.slopus.happy.layout.HomeScreenLayout
import com.slopus.happy.layout.Rect
import com.slopus.happy.layout.Size
import com.slopus.happy.layout.stemcells.StemCellsHomeLayout
import com.slopus.happy.net.ConnectionStatus
import com.slopus.happy.stories.StoriesSnapshot
import com.slopus.happy.ui.home.HomeHeaderView
import com.slopus.happy.stories.StoryOverlay
import com.slopus.happy.stories.StoryStatus
import com.slopus.happy.ui.stories.StoriesEditorPresenter
import com.slopus.happy.ui.stories.StoriesHaptics
import com.slopus.happy.ui.stories.StoriesProviding
import com.slopus.happy.ui.stories.StoryInlineView
import com.slopus.happy.layout.stories.StoryCardLayout
import kotlinx.coroutines.MainScope
import com.slopus.happy.ui.stories.StoriesMediaChooser
import com.slopus.happy.ui.stories.StoriesHomeController
import com.slopus.happy.ui.stories.StoriesSession
import com.slopus.happy.ui.stories.StoriesViewerPresenter
import com.slopus.happy.ui.stories.StoryViewerController
import com.slopus.happy.ui.theme.Theme
import com.slopus.happy.ui.view.BounceScrollView
import com.slopus.happy.ui.view.ManualLayout
import com.slopus.happy.ui.view.setFrame
import com.slopus.happy.ui.view.toLayoutInsets
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.math.cos
import kotlin.math.sin

/**
 * Stories over a computer of the harness's own (happy-ios
 * `StoriesHarness`, `-screen stories`): three authors, one of them the
 * person, with pictures drawn here, so the viewer can be driven by a finger
 * where a standalone daemon has nobody else's stories to show.
 *
 * ```
 * adb shell am start -n com.slopus.happy.dev/com.slopus.happy.harness.StoriesHarnessActivity \
 *     --ez fresh true --el epoch 1767000000000 --ef showScale 4
 * ```
 *
 * Extras, as the iOS launch arguments: `fresh` (never put away), `kept`
 * (as the last run left it; else put away long ago), `tooltip` (the
 * one-time tooltip shows), `epoch` (the stories' clock, ms), `showScale`
 * (each frame shows N times as long), `newer` (a story posted since the
 * row was put away), `slowFrames`, `compose` (the editor opens on a
 * picture of the harness's own, as a conversation's draft would), `card`
 * (a conversation's story card alone, on the draft d1; the probe reads
 * `card=draft viewer=0`, and `harness-delete-card` deletes d1).
 *
 * The view tagged `stories-probe` reports the screen in its content
 * description: `viewer=1 author=1 position=0 paused=0 chrome=1 reaction=🔥
 * haptics=open,react`, or `viewer=0 header=open stories=4 …` on home. The
 * view tagged `harness-new-story` (bottom right) posts another of Ana's,
 * unseen, as a `story.posted` event brings it; `harness-edit-draft`
 * (bottom left) opens the editor on the newest draft. Over the editor the
 * probe reads `editor=1 frames=1 selected=0 overlays=sticker,text top=…
 * trail=… saved=draft:2`.
 */
class StoriesHarnessActivity : ComponentActivity(), StoriesProviding {
    private lateinit var transport: StoriesHarnessTransport
    private lateinit var session: StoriesSession
    private lateinit var home: Home
    private lateinit var probe: TextView
    private var card: Card? = null
    override val stories: StoriesSession? get() = if (::session.isInitialized) session else null
    private val events = MutableSharedFlow<DirectEvent>(extraBufferCapacity = 16)
    private val played = ArrayList<String>()
    private var snapshot = StoriesSnapshot()
    private var watch: Job? = null
    private val handler = Handler(Looper.getMainLooper())
    private val report = object : Runnable {
        override fun run() {
            report()
            handler.postDelayed(this, 100)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        Theme.install(this)
        val extras = intent.extras ?: Bundle()
        val preferences = getSharedPreferences(StoriesHomeController.PREFERENCES, Context.MODE_PRIVATE).edit()
        // The one-time tooltip only for the run that looks at it.
        preferences.putBoolean(StoriesHomeController.TOOLTIP_SHOWN_KEY, !extras.getBoolean("tooltip"))
        // The row starts closed, as put away long ago, but for the runs of
        // how it starts: `fresh` never put away, `kept` as the last run left it.
        if (extras.getBoolean("fresh")) {
            preferences.remove(StoriesHomeController.PUT_AWAY_KEY)
        } else if (!extras.getBoolean("kept")) {
            preferences.putString(StoriesHomeController.PUT_AWAY_KEY, JsonObject(mapOf(ACCOUNT to JsonPrimitive(Long.MAX_VALUE))).toString())
        }
        preferences.commit()
        if (extras.containsKey("showScale")) StoryViewerController.showScale = extras.getFloat("showScale").toDouble()
        val epoch = extras.getLong("epoch", System.currentTimeMillis())
        transport = StoriesHarnessTransport(epoch, newer = extras.getBoolean("newer"), slowFrames = extras.getBoolean("slowFrames"))
        transport.drawFrames(frames())
        transport.drawStickers(stickers())
        StoriesHaptics.onPlay = { moment ->
            played += moment.name.replaceFirstChar { it.lowercase() }
            if (played.size > 16) played.removeAt(0)
        }
        val client = DirectAgentClient(transport, "harness")
        session = StoriesSession(ACCOUNT, client, null, File(cacheDir, "stories-harness-stickers"), { events })
        home = Home(this, session)
        home.onNewStory = {
            lifecycleScope.launch {
                val event = transport.postNew() ?: return@launch
                events.emit(DirectEvent.Event(event))
            }
        }
        home.onEditDraft = {
            val newest = snapshot.stories.values.filter { it.status == StoryStatus.Draft }.maxByOrNull { it.updatedAt }
            if (newest != null) StoriesEditorPresenter.present(session, newest, home)
        }
        probe = TextView(this).apply {
            tag = "stories-probe"
            alpha = 0.02f
            textSize = 1f
        }
        home.addView(probe)
        setContentView(home)
        ViewCompat.setOnApplyWindowInsetsListener(home) { _, insets ->
            home.safeArea = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()).toLayoutInsets()
            insets
        }
        watch = session.watch { snapshot = it }
        handler.post(report)
        if (extras.getBoolean("compose")) handler.postDelayed({ compose() }, 600)
        // A conversation's story card over home, on a draft of the person's.
        if (extras.getBoolean("card")) {
            val card = Card(this, session)
            this.card = card
            addContentView(card, android.view.ViewGroup.LayoutParams(android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.MATCH_PARENT))
        }
    }

    private fun compose() {
        lifecycleScope.launch {
            val picked = withContext(Dispatchers.Default) { picture(this@StoriesHarnessActivity) } ?: return@launch
            StoriesEditorPresenter.present(session, listOf(picked), home)
        }
    }

    override fun onDestroy() {
        handler.removeCallbacks(report)
        watch?.cancel()
        StoriesHaptics.onPlay = null
        session.close()
        super.onDestroy()
    }

    /** What the row remembers of being put away, for the probe. */
    private val putAway: String
        get() {
            val stored = getSharedPreferences(StoriesHomeController.PREFERENCES, Context.MODE_PRIVATE).getString(StoriesHomeController.PUT_AWAY_KEY, null) ?: return "none"
            val value = runCatching { (kotlinx.serialization.json.Json.parseToJsonElement(stored) as JsonObject)[ACCOUNT] as? JsonPrimitive }.getOrNull()?.longOrNull ?: return "none"
            return if (value == Long.MAX_VALUE) "always" else value.toString()
        }

    private fun report() {
        // Over the viewer too, so it reads whatever is up.
        val root = window.decorView as? android.view.ViewGroup
        val parent = probe.parent as? android.view.ViewGroup
        if (root != null && parent !== root) {
            parent?.removeView(probe)
            root.addView(probe, 4, 4)
        } else if (root != null && root.indexOfChild(probe) != root.childCount - 1) {
            probe.bringToFront()
        }
        // The harness's own stories aren't made here.
        val made = snapshot.stories.values.filter { it.id !in OWN && !it.id.startsWith("new-") }
            .sortedBy { it.createdAt }
            .joinToString(",") { "${it.status.wire}:${it.artifactVersion}:${it.frames.firstOrNull()?.overlays?.size ?: 0}" }
        val haptics = played.joinToString(",")
        val viewer = StoriesViewerPresenter.presented(home)
        val editor = StoriesEditorPresenter.presented(home)
        val card = card
        val value = if (editor == null && card != null) {
            "card=${card.state} viewer=${if (viewer != null) 1 else 0}"
        } else if (editor != null) {
            val overlays = editor.draft.current?.overlays ?: emptyList()
            val kinds = overlays.joinToString(",") { overlay ->
                when (overlay) {
                    is StoryOverlay.Text -> "text"
                    is StoryOverlay.Link -> "link"
                    is StoryOverlay.Sticker -> "sticker"
                    is StoryOverlay.Emoji -> "emoji"
                    is StoryOverlay.Reaction -> "reaction"
                    is StoryOverlay.Unknown -> "unknown"
                }
            }
            val top = overlays.lastOrNull()?.transform?.let { "%.3f,%.3f,%.2f,%.3f".format(it.x, it.y, it.scale, it.rotation) } ?: "nil"
            "editor=1 frames=${editor.draft.frames.size} selected=${editor.draft.selected} overlays=$kinds top=$top trail=${editor.trail.joinToString(";")} saved=$made haptics=$haptics"
        } else if (viewer == null) {
            val count = snapshot.feed?.authors?.sumOf { it.storyIds.size } ?: 0
            "viewer=0 editor=0 header=${if (home.stories.unlocked) "open" else "closed"} putAway=$putAway stories=$count title=${"%.0f".format(home.header.titleLeft)} saved=$made haptics=$haptics"
        } else {
            val reaction = viewer.current?.first?.myReaction ?: "nil"
            val sound = viewer.page.soundButton.let { if (it.visibility != View.VISIBLE) "none" else it.contentDescription?.toString() ?: "none" }
            "viewer=1 author=${viewer.authorIndex} position=${viewer.position} paused=${if (viewer.isPaused) 1 else 0} sound=$sound " +
                "chrome=${if (viewer.page.chromeHidden) 0 else 1} reaction=$reaction trail=${viewer.trail.joinToString(",")} haptics=$haptics"
        }
        if (probe.contentDescription != value) probe.contentDescription = value
    }

    /**
     * Home for the harness: Stem Cells home's header with the stories in
     * it, over a list of rows of its own, wired as Stem Cells home wires it.
     */
    private class Home(context: Context, session: StoriesSession) : ManualLayout(context), BounceScrollView.Listener {
        val header = HomeHeaderView(context)
        val list = BounceScrollView(context)
        val stories = StoriesHomeController(this)
        private val rows = (0 until 30).map { index ->
            TextView(context).apply {
                text = "  Session ${index + 1}"
                setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16f)
                gravity = Gravity.CENTER_VERTICAL
                setTextColor(Theme.color(Palette.text))
                setBackgroundColor(Theme.color(Palette.groupedBackground))
            }
        }
        private val newStory = View(context)
        private val editDraft = View(context)
        var onNewStory: (() -> Unit)? = null
        var onEditDraft: (() -> Unit)? = null
        private var change: StoriesHomeController.Change? = null

        var safeArea = LayoutInsets.Zero
            set(value) { if (field != value) { field = value; requestLayout() } }

        init {
            // The open stories hang below the header.
            clipChildren = false
            setBackgroundColor(Theme.color(Palette.groupedBackground))
            list.listener = this
            list.tag = "harness-list"
            addView(list)
            for (row in rows) list.content.addView(row)
            header.title = "Harness"
            header.status = ConnectionStatus.CONNECTED
            header.stemCellsActions = true
            addView(header)
            stories.onInsetChange = { moved(it) }
            stories.list = list
            stories.attach(session)
            stories.install(header)
            newStory.tag = "harness-new-story"
            newStory.contentDescription = "harness-new-story"
            newStory.setBackgroundColor(0x05000000)
            newStory.setOnClickListener { onNewStory?.invoke() }
            addView(newStory)
            editDraft.tag = "harness-edit-draft"
            editDraft.contentDescription = "harness-edit-draft"
            editDraft.setBackgroundColor(0x05000000)
            editDraft.setOnClickListener { onEditDraft?.invoke() }
            addView(editDraft)
        }

        override fun dispatchTouchEvent(ev: android.view.MotionEvent): Boolean =
            header.dispatchFromParent(ev) || super.dispatchTouchEvent(ev)

        private fun moved(change: StoriesHomeController.Change) {
            this.change = change
            try {
                layoutSubviews(com.slopus.happy.ui.view.px(width), com.slopus.happy.ui.view.px(height))
            } finally {
                this.change = null
            }
        }

        override fun layoutSubviews(width: Double, height: Double) {
            val layout = HomeScreenLayout(Size(width, height), safeArea, 0.0)
            header.safeTop = safeArea.top
            header.setFrame(layout.header)
            for ((index, row) in rows.withIndex()) list.content.frames[row] = Rect(0.0, index * 52.0, width, 51.0)
            list.contentHeight = rows.size * 52.0
            list.setFrame(Rect(0.0, 0.0, width, height))
            newStory.setFrame(Rect(width - 44, height - 60, 44.0, 44.0))
            editDraft.setFrame(Rect(0.0, height - 60, 44.0, 44.0))
            val home = StemCellsHomeLayout(Size(width, height), layout.insets.contentTop, layout.insets.chromeTop, layout.insets.listBottom, 0.0, stories.inset)
            val insets = home.listInsets
            if (list.insetTop == insets.top && list.insetBottom == insets.bottom) return
            val atTop = list.contentOffset <= -list.insetTop + 1
            list.setInsets(insets.top, insets.bottom)
            when (change) {
                StoriesHomeController.Change.Held -> {}
                StoriesHomeController.Change.Animated -> list.setContentOffset(-insets.top, animated = true)
                StoriesHomeController.Change.Immediate, null ->
                    if (atTop || (!list.isDragging && list.contentOffset < -insets.top)) list.setContentOffset(-insets.top)
            }
        }

        override fun willBeginDragging() = stories.listWillBeginDragging()
        override fun didScroll() = stories.listDidScroll()
        override fun willEndDragging(velocity: Double, target: DoubleArray) = stories.listWillEndDragging(velocity, target)
        override fun didEndDragging(decelerate: Boolean) = stories.listDidEndDragging(decelerate)
        override fun didEndDecelerating() = stories.listDidEndDecelerating()
    }

    /**
     * A conversation's story card alone (iOS's `-storiesCard`), on the
     * draft d1: Post, a delete as the viewer's would make, and the viewer
     * it opens.
     */
    private class Card(context: Context, private val session: StoriesSession) : ManualLayout(context) {
        private val card = StoryInlineView(context)
        private val delete = View(context)
        var safeTop = 0.0

        /** What the card shows, for the probe. */
        val state: String get() = card.state.substringBefore('#')

        init {
            setBackgroundColor(Theme.color(Palette.groupedBackground))
            isClickable = true
            addView(card)
            card.apply("d1")
            delete.tag = "harness-delete-card"
            delete.contentDescription = "harness-delete-card"
            delete.setBackgroundColor(0x05000000)
            delete.setOnClickListener {
                MainScope().launch { runCatching { session.store.delete("d1") } }
            }
            addView(delete)
            ViewCompat.setOnApplyWindowInsetsListener(this) { _, insets ->
                safeTop = com.slopus.happy.ui.view.px(insets.getInsets(WindowInsetsCompat.Type.systemBars()).top)
                requestLayout()
                insets
            }
        }

        override fun layoutSubviews(width: Double, height: Double) {
            val size = StoryCardLayout.size(width - 32)
            card.setFrame(Rect(16.0, safeTop + 24, size.width, size.height))
            delete.setFrame(Rect(width - 44, height - 60, 44.0, 44.0))
        }
    }

    companion object {
        const val ACCOUNT = "stories-harness"
        private val OWN = setOf("m1", "s1", "s2", "a1", "a2", "d1")

        // iOS 26's system colours, as the iOS harness draws with.
        private const val INDIGO = 0xFF6155F5.toInt()
        private const val TEAL = 0xFF00C3D0.toInt()
        private const val ORANGE = 0xFFFF8D28.toInt()
        private const val PINK = 0xFFFF2D55.toInt()
        private const val GREEN = 0xFF34C759.toInt()
        private const val BLUE = 0xFF0088FF.toInt()
        private const val PURPLE = 0xFFCB30E0.toInt()
        private const val RED = 0xFFFF383C.toInt()
        private const val YELLOW = 0xFFFFCC00.toInt()
        private const val BROWN = 0xFFAC7F5E.toInt()

        /** The story's first picture: tall, with a grid to read placement by. */
        fun picture(context: Context): StoriesMediaChooser.Picked? {
            val width = 1080
            val height = 1440
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG)
            paint.shader = LinearGradient(0f, 0f, 0f, height.toFloat(), TEAL, INDIGO, Shader.TileMode.CLAMP)
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
            paint.shader = null
            paint.color = 0x26FFFFFF
            for (x in 0 until width step 120) canvas.drawRect(x.toFloat(), 0f, x + 2f, height.toFloat(), paint)
            for (y in 0 until height step 120) canvas.drawRect(0f, y.toFloat(), width.toFloat(), y + 2f, paint)
            paint.color = 0xD9FFFFFF.toInt()
            paint.typeface = Typeface.create(Typeface.SANS_SERIF, 800, false)
            paint.textSize = 150f
            val measured = paint.measureText("Picked")
            canvas.drawText("Picked", (width - measured) / 2, height * 0.6f - paint.fontMetrics.ascent, paint)
            val data = png(bitmap).also { bitmap.recycle() }
            return StoriesMediaChooser.image(context, data, "image/png")
        }

        private fun png(bitmap: Bitmap): ByteArray = ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()

        /** Every frame's picture: a gradient, its name large, bands to read placement by. */
        fun frames(): Map<String, ByteArray> {
            val names = listOf(
                Triple("f-m1a", INDIGO to TEAL, 720 to 1280),
                Triple("f-m1b", ORANGE to PINK, 1280 to 720),
                Triple("f-s1", GREEN to BLUE, 720 to 1280),
                Triple("f-s2", PURPLE to RED, 720 to 1280),
                Triple("f-a1", YELLOW to BROWN, 720 to 1280),
                Triple("f-d1", TEAL to INDIGO, 720 to 1280),
            )
            return names.associate { (name, colors, size) ->
                val (width, height) = size
                val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(bitmap)
                val paint = Paint(Paint.ANTI_ALIAS_FLAG)
                paint.shader = LinearGradient(0f, 0f, 0f, height.toFloat(), colors.first, colors.second, Shader.TileMode.CLAMP)
                canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
                paint.shader = null
                paint.color = 0x1FFFFFFF
                for (y in 0 until height step 160) canvas.drawRect(0f, y.toFloat(), width.toFloat(), y + 80f, paint)
                paint.color = 0xD9FFFFFF.toInt()
                paint.typeface = Typeface.create(Typeface.SANS_SERIF, 900, false)
                paint.textSize = width / 7f
                val measured = paint.measureText(name)
                // At its top-left, as UIKit draws a string at a point.
                canvas.drawText(name, (width - measured) / 2, height * 0.55f - paint.fontMetrics.ascent, paint)
                name to png(bitmap).also { bitmap.recycle() }
            }
        }

        /** The packs' files: each a shape in its colour, and the animated one its Lottie (as `?as=lottie` serves a `tgs`). */
        fun stickers(): Map<String, ByteArray> {
            val files = HashMap<String, ByteArray>()
            for (pack in StoriesHarnessTransport.packs) {
                for (sticker in pack.stickers) {
                    if (sticker.shape == "lottie") {
                        files["${pack.id}/${sticker.id}"] = SPIN_LOTTIE.toByteArray()
                        continue
                    }
                    val bitmap = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888)
                    val canvas = Canvas(bitmap)
                    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = sticker.color }
                    val box = RectF(24f, 24f, 232f, 232f)
                    when (sticker.shape) {
                        "circle" -> canvas.drawOval(box, paint)
                        "star" -> {
                            val path = Path()
                            for (point in 0 until 10) {
                                val radius = if (point % 2 == 0) 104.0 else 44.0
                                val angle = point * Math.PI / 5 - Math.PI / 2
                                val x = (128 + radius * cos(angle)).toFloat()
                                val y = (128 + radius * sin(angle)).toFloat()
                                if (point == 0) path.moveTo(x, y) else path.lineTo(x, y)
                            }
                            path.close()
                            canvas.drawPath(path, paint)
                        }
                        "heart" -> {
                            canvas.drawOval(RectF(30f, 40f, 140f, 150f), paint)
                            canvas.drawOval(RectF(116f, 40f, 226f, 150f), paint)
                            val tip = Path().apply { moveTo(36f, 118f); lineTo(220f, 118f); lineTo(128f, 226f); close() }
                            canvas.drawPath(tip, paint)
                        }
                        else -> canvas.drawRoundRect(box, 36f, 36f, paint)
                    }
                    files["${pack.id}/${sticker.id}"] = png(bitmap)
                    bitmap.recycle()
                }
            }
            return files
        }

        /** A pink rounded square that turns and breathes, with a white dot to see it turn (`scripts/generate-test-sticker-packs.py`'s). */
        const val SPIN_LOTTIE = """{"v":"5.7.4","fr":30,"ip":0,"op":60,"w":512,"h":512,"nm":"spin","ddd":0,"assets":[],"layers":[{"ddd":0,"ind":1,"ty":4,"nm":"spin","sr":1,"ao":0,"ip":0,"op":60,"st":0,"bm":0,"ks":{"o":{"a":0,"k":100},"r":{"a":1,"k":[{"t":0,"s":[0],"i":{"x":[0.5],"y":[1]},"o":{"x":[0.5],"y":[0]}},{"t":60,"s":[360]}]},"p":{"a":0,"k":[256,256,0]},"a":{"a":0,"k":[0,0,0]},"s":{"a":1,"k":[{"t":0,"s":[100,100,100],"i":{"x":[0.5,0.5,0.5],"y":[1,1,1]},"o":{"x":[0.5,0.5,0.5],"y":[0,0,0]}},{"t":30,"s":[70,70,100],"i":{"x":[0.5,0.5,0.5],"y":[1,1,1]},"o":{"x":[0.5,0.5,0.5],"y":[0,0,0]}},{"t":60,"s":[100,100,100]}]}},"shapes":[{"ty":"gr","nm":"dot","it":[{"ty":"el","nm":"e","d":1,"s":{"a":0,"k":[70,70]},"p":{"a":0,"k":[0,-90]}},{"ty":"fl","nm":"f","c":{"a":0,"k":[1,1,1,1]},"o":{"a":0,"k":100},"r":1},{"ty":"tr","p":{"a":0,"k":[0,0]},"a":{"a":0,"k":[0,0]},"s":{"a":0,"k":[100,100]},"r":{"a":0,"k":0},"o":{"a":0,"k":100}}]},{"ty":"gr","nm":"square","it":[{"ty":"rc","nm":"r","d":1,"s":{"a":0,"k":[300,300]},"p":{"a":0,"k":[0,0]},"r":{"a":0,"k":80}},{"ty":"fl","nm":"f","c":{"a":0,"k":[1,0.216,0.373,1]},"o":{"a":0,"k":100},"r":1},{"ty":"tr","p":{"a":0,"k":[0,0]},"a":{"a":0,"k":[0,0]},"s":{"a":0,"k":[100,100]},"r":{"a":0,"k":0},"o":{"a":0,"k":100}}]}]}]}"""
    }
}
