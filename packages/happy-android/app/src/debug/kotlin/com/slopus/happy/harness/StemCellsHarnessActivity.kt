package com.slopus.happy.harness

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import com.slopus.happy.direct.DirectAgentClient
import com.slopus.happy.direct.DirectSyncEngine
import com.slopus.happy.ui.nav.ScreenStack
import com.slopus.happy.ui.stemcells.StemCellChatScreen
import com.slopus.happy.ui.stemcells.StemCellsDirectHomeScreen
import com.slopus.happy.ui.stemcells.StemCellsHaptics
import com.slopus.happy.ui.stemcells.StemCellsSession
import com.slopus.happy.ui.theme.Theme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.File

/**
 * Stem Cells over the harness's computer (happy-ios's Stem Cells reference
 * host in `.context/`, on the same fixture): a paired computer's home, its
 * sidebar, folds and new work, followed by the real sync engine and store
 * against [StemCellsHarnessTransport], so it can be driven by a finger
 * without a second daemon.
 *
 * ```
 * adb shell am start -n com.slopus.happy.dev/com.slopus.happy.harness.StemCellsHarnessActivity \
 *     --el epoch 1767000000000
 * ```
 *
 * Extras: `epoch` (the computer's clock, ms; the fixture's times are moved
 * to it), `kept` (keep what the last run saved on this phone; otherwise
 * it starts from nothing), `latency` (ms each request takes).
 *
 * The view tagged `stem-probe` reports the screen in its content
 * description: `live=1 rows=12 selected=space:sp1 opened=space:sp1
 * created=0 haptics=fold,newWork`.
 */
class StemCellsHarnessActivity : ComponentActivity() {
    @OptIn(ExperimentalCoroutinesApi::class)
    private val engineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default.limitedParallelism(1))
    private lateinit var transport: StemCellsHarnessTransport
    private lateinit var engine: DirectSyncEngine
    private lateinit var session: StemCellsSession
    private lateinit var home: StemCellsDirectHomeScreen
    private lateinit var probe: TextView
    private lateinit var stack: ScreenStack
    private val played = ArrayList<String>()
    private var opened = "none"
    private var created = 0
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
        val epoch = extras.getLong("epoch", System.currentTimeMillis())
        val started = System.currentTimeMillis()
        val directory = File(cacheDir, "stem-cells-harness")
        if (!extras.getBoolean("kept")) directory.deleteRecursively()
        directory.mkdirs()
        val fixture = assets.open("harness/stem-cells-computer.json").use { it.readBytes().decodeToString() }
        transport = StemCellsHarnessTransport(
            fixture, engineScope,
            now = { epoch + (System.currentTimeMillis() - started) },
            drawMedia = ::drawMedia,
            asset = { path -> runCatching { assets.open(path).use { it.readBytes() } }.getOrNull() },
            latencyMs = extras.getLong("latency", 0),
            classic = extras.getBoolean("classic"),
        )
        engine = DirectSyncEngine(DirectAgentClient(transport, "harness"), directory, engineScope)
        session = StemCellsSession.session(ACCOUNT, engine)
        StemCellsHaptics.onPlay = { moment ->
            played += moment.name.replaceFirstChar { it.lowercase() }
            if (played.size > 16) played.removeAt(0)
        }
        stack = ScreenStack(this)
        onBackPressedDispatcher.addCallback(this, stack.backCallback)
        home = StemCellsDirectHomeScreen(this, session, COMPUTER)
        home.open = { row -> opened = row.id }
        home.newWork = { creation, _ -> created++; opened = "space:${creation.spaceId}" }
        stack.setScreens(listOf(home))
        setContentView(stack)
        probe = TextView(this).apply {
            tag = "stem-probe"
            alpha = 0.02f
            textSize = 1f
        }
        addContentView(probe, android.view.ViewGroup.LayoutParams(4, 4))
        engineScope.launch { engine.start() }
        handler.post(report)
    }

    private fun report() {
        val snapshot = session.snapshot
        val value = "live=${if (snapshot.live) 1 else 0} rows=${home.stemCells.sidebar.rows.size} selected=${session.selectedRowId ?: "none"} " +
            "opened=$opened created=$created haptics=${played.joinToString(",")} ${home.stemCells.probe}" +
            ((stack.top as? StemCellChatScreen)?.let { " " + it.probe } ?: "") +
            ((stack.top as? com.slopus.happy.ui.stemcells.StemCellArtifactScreen)?.let { " artifact=" + it.shows } ?: "") +
            ((stack.top as? com.slopus.happy.ui.stemcells.StemCellsSurfaceScreen)?.let { " " + it.probe } ?: "") +
            ((stack.top as? com.slopus.happy.ui.stemcells.StemCellsSearchScreen)?.let { " " + it.probe } ?: "")
        if (probe.contentDescription != value) probe.contentDescription = value
    }

    /** A colour and a label, white and bold at an eighth of the shorter side (the iOS reference host draws the same). */
    private fun drawMedia(color: Int, label: String, width: Int, height: Int): ByteArray {
        val bitmap = android.graphics.Bitmap.createBitmap(maxOf(1, width), maxOf(1, height), android.graphics.Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bitmap)
        canvas.drawColor(color)
        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            this.color = android.graphics.Color.WHITE
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            textSize = minOf(bitmap.width, bitmap.height) / 8f
            textAlign = android.graphics.Paint.Align.CENTER
        }
        val metrics = paint.fontMetrics
        canvas.drawText(label, bitmap.width / 2f, bitmap.height / 2f - (metrics.ascent + metrics.descent) / 2, paint)
        val out = java.io.ByteArrayOutputStream()
        bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out)
        bitmap.recycle()
        return out.toByteArray()
    }

    override fun onDestroy() {
        handler.removeCallbacks(report)
        StemCellsHaptics.onPlay = null
        StemCellsSession.forget(ACCOUNT)
        engineScope.launch { engine.stop() }.invokeOnCompletion { engineScope.cancel() }
        super.onDestroy()
    }

    companion object {
        const val ACCOUNT = "stem-cells-harness"
        /** The fixture's `config.node.name`, as the identity loop would read it. */
        const val COMPUTER = "Studio Mac"
    }
}
