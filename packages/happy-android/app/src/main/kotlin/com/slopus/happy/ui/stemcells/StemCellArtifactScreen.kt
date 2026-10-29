package com.slopus.happy.ui.stemcells

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.net.Uri
import android.text.Layout
import android.view.View
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import com.slopus.happy.R
import com.slopus.happy.direct.DirectAgentClient
import com.slopus.happy.direct.DirectAgentError
import com.slopus.happy.direct.DirectHttpResponse
import com.slopus.happy.layout.Measurable
import com.slopus.happy.layout.Palette
import com.slopus.happy.layout.Rect
import com.slopus.happy.layout.StemCellsPalette
import com.slopus.happy.layout.stemcells.StemCellsSurfaceBodyLayout
import com.slopus.happy.stemcells.DirectRanges
import com.slopus.happy.stemcells.StemCellArtifact
import com.slopus.happy.stemcells.StemCellChatItem
import com.slopus.happy.stemcells.artifactFile
import com.slopus.happy.stemcells.artifactVersion
import com.slopus.happy.ui.chat.ChatRowPlan
import com.slopus.happy.ui.chat.ChatRowView
import com.slopus.happy.ui.components.Label
import com.slopus.happy.ui.nav.BarScreen
import com.slopus.happy.ui.tablet.ContentWidth
import com.slopus.happy.ui.text.TextLayouts
import com.slopus.happy.ui.theme.Theme
import com.slopus.happy.ui.theme.ThemeAware
import com.slopus.happy.ui.view.BounceScrollView
import com.slopus.happy.ui.view.ManualLayout
import com.slopus.happy.ui.view.px
import com.slopus.happy.ui.view.setFrame
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream

/**
 * An artifact opened from its card, kept minimal (happy-ios
 * `StemCellArtifactViewController`): a Markdown entry drawn as the
 * conversation draws Markdown; any other file entry (a page, a picture, a
 * recording, a PDF) in a web view that reads each file of the version
 * through the paired computer, never from the network. A live page runs on
 * the computer and says so; a deleted artifact says so.
 */
class StemCellArtifactScreen(
    context: Context,
    private val session: StemCellsSession,
    private val artifact: StemCellArtifact,
) : BarScreen(context) {
    private val content = Content(context)
    private val scroll = BounceScrollView(context)
    private val message = Label(context, StemCellsFonts.ui(StemCellsFonts.Weight.Regular, 15.0), StemCellsPalette.stemCellsRowText, alignment = Layout.Alignment.ALIGN_CENTER)
    private val planner = StemCellChatPlanner(context.resources)
    private var document: ChatRowView? = null
    private var documentPlan: ChatRowPlan? = null
    private var documentText: String? = null
    /** The width the document was last planned at, or is being. */
    private var plannedWidth: Double? = null
    private var web: WebView? = null
    private var loading: Job? = null

    override val contentUnderBar: Boolean get() = false

    /** What the probe reads: message, document, web, or loading. */
    val shows: String
        get() = when {
            message.text.isNotEmpty() -> "message"
            document != null -> "document"
            web != null -> "web"
            else -> "loading"
        }

    override fun createContent(): View {
        bar.title = artifact.title.ifEmpty { context.getString(R.string.stem_untitled_artifact) }
        content.tag = "stem-artifact"
        message.tag = "stem-artifact-message"
        scroll.content.addView(message)
        content.addView(scroll)
        loading = scope.launch { load() }
        return content
    }

    override fun onDestroy() {
        loading?.cancel()
        web?.destroy()
        web = null
    }

    private suspend fun load() {
        val client = session.client
        try {
            val version = client.artifactVersion(artifact.id, if (artifact.opensLatest) null else artifact.version)
            if (version.title.isNotEmpty()) bar.title = version.title
            if (version.liveUrl != null) return show(context.getString(R.string.stem_artifact_live))
            val entry = version.entry ?: return show(context.getString(R.string.stem_artifact_unavailable))
            if (entry.mimeType.startsWith("text/markdown")) {
                val body = client.artifactFile(artifact.id, version.number, entry.path).body
                showMarkdown(body.decodeToString())
            } else {
                showWeb(version.number, entry.path)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: DirectAgentError.Status) {
            show(context.getString(if (e.status == 404) R.string.stem_artifact_deleted else R.string.stem_artifact_unavailable))
        } catch (e: Exception) {
            show(context.getString(R.string.stem_artifact_unavailable))
        }
    }

    private fun show(text: String) {
        message.text = text
        content.requestLayout()
    }

    /** The Markdown planned off the main thread, as a conversation's agent text, at the view's width; layout plans again when that changes. */
    private suspend fun showMarkdown(text: String) {
        documentText = text
        val width = px(content.width)
        if (width <= 0) return content.requestLayout()
        plannedWidth = width
        val key = StemCellChatPlanner.Key(width, ContentWidth.maxWidth(context), TextLayouts.Env(Theme.density, Theme.textScale))
        val item = StemCellChatItem("artifact:${artifact.id}", StemCellChatItem.Role.Agent, listOf(StemCellChatItem.Piece.Text(text)), StemCellChatItem.Delivery.Sent)
        val plan = withContext(Dispatchers.Default) { planner.plan(item, key) }
        // A later width's plan is on its way.
        if (plannedWidth != width) return
        documentPlan = plan
        val view = document ?: ChatRowView(context).also {
            it.tag = "stem-artifact-document"
            scroll.content.addView(it)
            document = it
        }
        view.bind(plan)
        view.contentDescription = text
        content.requestLayout()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun showWeb(version: Int, entry: String) {
        val web = WebView(context)
        web.tag = "stem-artifact-web"
        web.setBackgroundColor(Color.TRANSPARENT)
        web.settings.apply {
            javaScriptEnabled = true
            // Nothing an artifact stores outlives the view, and its files come only through the computer.
            cacheMode = WebSettings.LOAD_NO_CACHE
            domStorageEnabled = false
            allowContentAccess = false
            allowFileAccess = false
            javaScriptCanOpenWindowsAutomatically = false
            setSupportMultipleWindows(false)
            setGeolocationEnabled(false)
            mediaPlaybackRequiresUserGesture = true
        }
        web.webViewClient = ArtifactClient(StemCellArtifactFiles(session.client, artifact.id, scope))
        content.addView(web)
        this.web = web
        val path = entry.split("/").joinToString("/") { Uri.encode(it) }
        web.loadUrl("${StemCellArtifactFiles.ORIGIN}/$version/$path")
        content.requestLayout()
    }

    /** Keeps a page on its own files: nothing it does leaves for the network. */
    private class ArtifactClient(private val files: StemCellArtifactFiles) : WebViewClient() {
        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) = !StemCellArtifactFiles.owns(request.url)

        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse = files.answer(request)
    }

    private inner class Content(context: Context) : ManualLayout(context), ThemeAware {
        init {
            applyTheme()
        }

        override fun applyTheme() {
            setBackgroundColor(Theme.color(Palette.surface))
        }

        override fun layoutSubviews(width: Double, height: Double) {
            scroll.setFrame(Rect(0.0, 0.0, width, height))
            web?.setFrame(Rect(0.0, 0.0, width, height))
            val text = documentText
            if (text != null && width > 0 && plannedWidth != width) scope.launch { showMarkdown(text) }
            val showsMessage = message.text.isNotEmpty()
            val body = if (showsMessage) message.measurable else Measurable.fixed(width, documentPlan?.size?.height ?: 0.0)
            val layout = StemCellsSurfaceBodyLayout(width, body, if (showsMessage) 16.0 else 0.0)
            scroll.content.frames[message] = if (showsMessage) layout.body else null
            document?.let { scroll.content.frames[it] = if (showsMessage) null else layout.body }
            scroll.contentHeight = layout.contentHeight
            scroll.content.setFrame(Rect(0.0, 0.0, width, layout.contentHeight))
            scroll.content.layoutSubviews(width, layout.contentHeight)
        }
    }
}

/**
 * A version's files for the web view, which can't send the computer's token
 * itself (happy-ios `StemCellArtifactFiles`): `ORIGIN/<number>/<path>` is
 * answered here from the version's files on the computer, ranges and all, with the daemon's own content type and sandbox. Anything
 * else the page asks for is refused. Called on the web view's own threads.
 */
class StemCellArtifactFiles(
    private val client: DirectAgentClient,
    private val artifactId: String,
    private val scope: CoroutineScope,
) {
    fun answer(request: WebResourceRequest): WebResourceResponse {
        val (version, path) = file(request.url) ?: return refused()
        val range = request.requestHeaders.entries.firstOrNull { it.key.equals("range", ignoreCase = true) }?.value?.let(DirectRanges::bounded)
        return try {
            if (range != null) {
                val response = runBlocking { client.artifactFile(artifactId, version, path, range) }
                head(response, response.status, null, ByteArrayInputStream(response.body))
            } else {
                // The whole file, a range at a time, as one answer.
                val first = CompletableDeferred<Pair<DirectHttpResponse, Int>>()
                val pieces = Channel<ByteArray>(1)
                scope.launch(Dispatchers.IO) {
                    try {
                        DirectRanges.read({ range -> client.artifactFile(artifactId, version, path, range?.let { "bytes=${it.first}-${it.last}" }) }) { piece, head, total ->
                            if (head != null) first.complete(head to total)
                            pieces.send(piece)
                        }
                        pieces.close()
                    } catch (e: Throwable) {
                        first.completeExceptionally(e)
                        pieces.close(e)
                    }
                }
                val (response, total) = runBlocking { first.await() }
                head(response, 200, total, PiecesStream(pieces))
            }
        } catch (e: Exception) {
            WebResourceResponse("text/plain", null, 502, "Bad Gateway", emptyMap(), ByteArrayInputStream(ByteArray(0)))
        }
    }

    private fun head(response: DirectHttpResponse, status: Int, length: Int?, body: InputStream): WebResourceResponse {
        val headers = HashMap<String, String>()
        for ((key, value) in response.headers) if (key.lowercase() in KEPT) headers[key.lowercase()] = value
        if (length != null) {
            headers["content-length"] = length.toString()
            headers.remove("content-range")
        }
        val type = headers["content-type"] ?: "application/octet-stream"
        val mime = type.substringBefore(';').trim()
        val charset = type.substringAfter("charset=", "").trim().trim('"').ifEmpty { null }
        return WebResourceResponse(mime, charset, status, if (status == 206) "Partial Content" else "OK", headers, body)
    }

    /** The pieces as one stream, read on the web view's thread. */
    private class PiecesStream(private val pieces: ReceiveChannel<ByteArray>) : InputStream() {
        private var current = ByteArray(0)
        private var at = 0

        override fun read(): Int {
            val one = ByteArray(1)
            return if (read(one, 0, 1) < 0) -1 else one[0].toInt() and 0xFF
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (len == 0) return 0
            while (at >= current.size) {
                val next = runBlocking { pieces.receiveCatching() }
                if (next.isClosed) next.exceptionOrNull()?.let { throw IOException(it) } ?: return -1
                current = next.getOrThrow()
                at = 0
            }
            val n = minOf(len, current.size - at)
            System.arraycopy(current, at, b, off, n)
            at += n
            return n
        }

        override fun close() {
            pieces.cancel()
        }
    }

    companion object {
        /** A reserved name: nothing on the network answers it, so only this does. */
        const val ORIGIN = "https://artifact.happy.invalid"
        private val KEPT = setOf("content-type", "content-range", "content-length", "accept-ranges", "content-security-policy")

        fun owns(url: Uri): Boolean = url.scheme == "https" && url.host == "artifact.happy.invalid"

        /** `/<number>/<path>`, the path's segments decoded. */
        fun file(url: Uri): Pair<Int, String>? {
            if (!owns(url)) return null
            val segments = url.pathSegments
            val version = segments.firstOrNull()?.toIntOrNull() ?: return null
            val path = segments.drop(1).joinToString("/")
            return if (path.isEmpty()) null else version to path
        }

        private fun refused() = WebResourceResponse("text/plain", null, 403, "Forbidden", emptyMap(), ByteArrayInputStream(ByteArray(0)))
    }
}
