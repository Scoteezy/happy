package com.slopus.happy.ui.stemcells

import android.content.Context
import android.view.View
import com.slopus.happy.R
import com.slopus.happy.direct.Wire
import com.slopus.happy.layout.Palette
import com.slopus.happy.layout.Rect
import com.slopus.happy.stemcells.StemCellArtifact
import com.slopus.happy.stemcells.artifacts
import com.slopus.happy.ui.components.ItemRowView
import com.slopus.happy.ui.nav.BarScreen
import com.slopus.happy.ui.theme.Theme
import com.slopus.happy.ui.theme.ThemeAware
import com.slopus.happy.ui.view.BounceScrollView
import com.slopus.happy.ui.view.ManualLayout
import com.slopus.happy.ui.view.px
import com.slopus.happy.ui.view.setFrame
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * Desktop's fixed Artifacts row opens the same catalog, with its last page
 * available offline (happy-ios `DirectArtifactsViewController`). The artifact
 * viewer handles each entry; the list pages on as its last row shows.
 */
class DirectArtifactsScreen(context: Context, private val session: StemCellsSession) : BarScreen(context) {
    private val content = Content(context)
    private val scroll = BounceScrollView(context)
    private var artifacts: List<StemCellArtifact> = emptyList()
    private val rows = HashMap<String, ItemRowView>()
    private var nextPage: String? = null
    private var reading: Job? = null

    override val contentUnderBar: Boolean get() = false

    override fun createContent(): View {
        bar.title = context.getString(R.string.stem_artifacts)
        content.tag = "direct-artifacts"
        content.addView(scroll)
        scroll.listener = object : BounceScrollView.Listener {
            override fun didScroll() = pageOnAtTheEnd()
        }
        scope.launch {
            session.engine.cachedResource(KEY)?.let { apply(it, append = false) }
            read()
            session.store.events(listOf("artifact.")).collect { read() }
        }
        return content
    }

    private fun read(append: Boolean = false) {
        if (reading != null) return
        val cursor = if (append) nextPage else null
        reading = scope.launch {
            try {
                val value = session.client.artifacts(cursor)
                if (!append) session.engine.keepResource(KEY, value)
                apply(value, append)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
            } finally {
                reading = null
            }
        }
    }

    private suspend fun apply(value: JsonElement, append: Boolean) {
        val listed = withContext(Dispatchers.Default) {
            ((value as? JsonObject)?.get("artifacts") as? JsonArray).orEmpty().mapNotNull { item ->
                val o = item as? JsonObject ?: return@mapNotNull null
                val id = Wire.stringOrNull(o, "id") ?: return@mapNotNull null
                StemCellArtifact(id, Wire.doubleOrNull(o, "latestVersion")?.toInt() ?: 1, Wire.stringOrNull(o, "type") ?: "", Wire.stringOrNull(o, "title") ?: "Untitled", followsLatest = true)
            }
        }
        val known = artifacts.map { it.id }.toSet()
        val next = if (append) artifacts + listed.filter { it.id !in known } else listed
        nextPage = (value as? JsonObject)?.let { Wire.stringOrNull(it, "nextPageCursor") }
        if (next == artifacts) return
        artifacts = next
        val shown = next.map { it.id }.toSet()
        for (id in rows.keys - shown) rows.remove(id)?.let { scroll.content.removeView(it); scroll.content.frames.remove(it) }
        for (artifact in next) {
            val row = rows.getOrPut(artifact.id) {
                ItemRowView(context, artifact.title).also {
                    it.showsDivider = true
                    scroll.content.addView(it)
                }
            }
            row.title = artifact.title
            row.onTap = { push(StemCellArtifactScreen(context, session, artifacts.first { it.id == artifact.id })) }
        }
        content.requestLayout()
    }

    /** The last row on screen asks for the next page. */
    private fun pageOnAtTheEnd() {
        if (nextPage == null || reading != null) return
        if (scroll.contentOffset + px(scroll.height) >= scroll.contentHeight - ROW_SLACK) read(append = true)
    }

    private inner class Content(context: Context) : ManualLayout(context), ThemeAware {
        init {
            applyTheme()
        }

        override fun applyTheme() {
            setBackgroundColor(Theme.color(Palette.surface))
        }

        override fun layoutSubviews(width: Double, height: Double) {
            scroll.setInsets(0.0, safeArea.bottom)
            scroll.setFrame(Rect(0.0, 0.0, width, height))
            var y = 0.0
            for (artifact in artifacts) {
                val row = rows[artifact.id] ?: continue
                val rowHeight = row.layout(width).size.height
                scroll.content.frames[row] = Rect(0.0, y, width, rowHeight)
                y += rowHeight
            }
            scroll.contentHeight = y
            scroll.content.setFrame(Rect(0.0, 0.0, width, y))
            scroll.content.layoutSubviews(width, y)
            pageOnAtTheEnd()
        }
    }

    private companion object {
        const val KEY = "artifacts"
        const val ROW_SLACK = 60.0
    }
}
