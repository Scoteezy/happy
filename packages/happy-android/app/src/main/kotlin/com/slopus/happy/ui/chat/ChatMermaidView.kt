package com.slopus.happy.ui.chat

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.Outline
import android.view.View
import android.view.ViewOutlineProvider
import android.webkit.JavascriptInterface
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import com.slopus.happy.layout.Palette
import com.slopus.happy.layout.chat.MarkdownLayout
import com.slopus.happy.ui.theme.Theme
import com.slopus.happy.ui.view.dp
import org.json.JSONObject
import java.io.ByteArrayInputStream

/**
 * MermaidRenderer on Android (happy-ios `ChatMermaidView`): the diagram drawn
 * by mermaid in a web page, on the theme's `surfaceHighest` with 16 around
 * it, in a box rounded by 8 that scrolls inside once the diagram is taller
 * than the box.
 *
 * The page and mermaid come with the app (`assets/mermaid`, mermaid 11.12.2
 * as happy-app's lockfile pins it), never from a CDN. It is loaded once per
 * view; each diagram after that is handed to the page's `render` as a value,
 * never written into the page. The page says how tall it is through the
 * `HappyMermaid` bridge; the row is planned again off the main thread with
 * that height, and this view keeps its page through the rebind.
 */
class ChatMermaidView(context: Context) : FrameLayout(context) {
    /** The page's height for [diagram], in dp, as it reported it. */
    var onHeight: ((diagram: ChatMermaid, height: Double) -> Unit)? = null
    var diagram: ChatMermaid? = null
        private set
    private var webView: WebView? = null
    private var loaded = false
    /** Each render's number: a report from one a newer render replaced is dropped. */
    private var token = 0
    private var renderedDark: Boolean? = null

    init {
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, dp(MarkdownLayout.Metrics.mermaidRadius))
            }
        }
        clipToOutline = true
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        applyTheme()
    }

    fun applyTheme() {
        setBackgroundColor(Theme.color(Palette.surfaceHighest))
        render(force = false)
    }

    /** Another diagram, or the same one for another row, is rendered and measured again. */
    fun apply(diagram: ChatMermaid) {
        if (diagram == this.diagram) return
        this.diagram = diagram
        if (webView == null) load()
        render(force = true)
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun load() {
        val web = WebView(context)
        web.setBackgroundColor(Color.TRANSPARENT)
        web.isVerticalScrollBarEnabled = false
        web.isHorizontalScrollBarEnabled = false
        web.overScrollMode = OVER_SCROLL_NEVER
        web.settings.apply {
            javaScriptEnabled = true
            // The page reads only the app's own two files.
            blockNetworkLoads = true
            allowContentAccess = false
            allowFileAccess = false
            javaScriptCanOpenWindowsAutomatically = false
            setSupportMultipleWindows(false)
            setGeolocationEnabled(false)
        }
        web.addJavascriptInterface(Bridge(this), "HappyMermaid")
        web.webViewClient = object : WebViewClient() {
            /** The page only, never anywhere a diagram points. */
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) = true

            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
                if (request.url.toString().startsWith(ASSET_DIR)) null else WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))

            override fun onPageFinished(view: WebView, url: String) {
                loaded = true
                render(force = true)
            }

            /** The page's process went away (memory pressure): load it again. */
            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                removeView(view)
                view.destroy()
                webView = null
                loaded = false
                renderedDark = null
                if (diagram != null) {
                    load()
                    render(force = true)
                }
                return true
            }
        }
        addView(web, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        webView = web
        loaded = false
        web.loadUrl(PAGE)
    }

    /** Hands the diagram and the theme to the page. Again when the theme flips; [force] for a new diagram. */
    private fun render(force: Boolean) {
        val web = webView ?: return
        val diagram = diagram ?: return
        if (!loaded) return
        val dark = Theme.isDark
        if (!force && dark == renderedDark) return
        renderedDark = dark
        token += 1
        val background = Theme.color(Palette.surfaceHighest)
        val css = "rgba(${Color.red(background)}, ${Color.green(background)}, ${Color.blue(background)}, ${Color.alpha(background) / 255.0})"
        web.evaluateJavascript(
            "render(${JSONObject.quote(diagram.content)}, ${JSONObject.quote(if (dark) "dark" else "default")}, ${JSONObject.quote(css)}, $token)",
            null,
        )
    }

    private fun received(token: Int, height: Double) {
        val diagram = diagram ?: return
        if (token != this.token || !height.isFinite()) return
        onHeight?.invoke(diagram, height)
    }

    /** The page's reports, on the bridge's thread; handed to the main thread without the page keeping the view alive. */
    private class Bridge(view: ChatMermaidView) {
        private val view = java.lang.ref.WeakReference(view)

        @JavascriptInterface
        fun report(token: Int, height: Double) {
            val target = view.get() ?: return
            target.post { target.received(token, height) }
        }
    }

    companion object {
        private const val ASSET_DIR = "file:///android_asset/mermaid/"
        private const val PAGE = ASSET_DIR + "mermaid.html"
    }
}
