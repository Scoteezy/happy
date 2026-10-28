package com.slopus.happy.ui.chat

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Outline
import android.util.Base64
import android.util.LruCache
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.ImageView
import coil3.request.ImageRequest
import coil3.request.allowHardware
import coil3.size.Precision
import coil3.toBitmap
import com.slopus.happy.images.HappyImageLoader
import com.slopus.happy.layout.Size
import com.slopus.happy.layout.chat.MarkdownLayout
import com.slopus.happy.ui.view.dp
import com.slopus.happy.ui.view.viewScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * RenderImageBlock's picture (happy-ios `ChatWebImageView`): an http(s) or
 * base64 `data:` image, `resizeMode="contain"` in its box, which the plan
 * draws in `surfaceHighest` behind it and rounds 12. Nothing more when it
 * can't be loaded, as React Native's Image shows nothing. It loads once it
 * is on screen, decoded at no more than it is drawn.
 */
class ChatWebImageView(context: Context) : ImageView(context) {
    private var url: String? = null
    private var job: Job? = null

    init {
        scaleType = ScaleType.FIT_CENTER
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, dp(MarkdownLayout.Metrics.imageRadius))
            }
        }
        clipToOutline = true
    }

    fun apply(url: String, label: String) {
        contentDescription = label.ifEmpty { null }
        if (url == this.url) return
        job?.cancel()
        job = null
        this.url = url
        setImageDrawable(null)
        loadIfNeeded()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        loadIfNeeded()
    }

    private fun loadIfNeeded() {
        val url = url ?: return
        if (!isAttachedToWindow || drawable != null || job?.isActive == true) return
        cache.get(url)?.let {
            setImageBitmap(it)
            return
        }
        job = viewScope.launch {
            val bitmap = load(context, url)
            if (this@ChatWebImageView.url == url && bitmap != null) setImageBitmap(bitmap)
        }
    }

    companion object {
        /** The largest box a picture is drawn in, on a 3x screen ([MarkdownLayout.decodedImageSize]). */
        private val decoded = Size(3 * MarkdownLayout.Metrics.imageMaxWidth, 3 * MarkdownLayout.Metrics.imageHeight)
        private val cache = object : LruCache<String, Bitmap>(32 * 1024 * 1024) {
            override fun sizeOf(key: String, value: Bitmap) = value.allocationByteCount
        }

        private suspend fun load(context: Context, url: String): Bitmap? {
            val scheme = url.substringBefore(':', "").lowercase()
            val bitmap = when (scheme) {
                "http", "https" -> {
                    val request = ImageRequest.Builder(context).data(url).allowHardware(false)
                        .size(decoded.width.toInt(), decoded.height.toInt()).precision(Precision.INEXACT).build()
                    runCatching { HappyImageLoader.get(context).execute(request).image?.toBitmap() }.getOrNull()
                }
                // data:[<type>][;base64],<payload>
                "data" -> withContext(Dispatchers.IO) { dataUri(url) }
                else -> null
            }
            return bitmap?.also { cache.put(url, it) }
        }

        private fun dataUri(url: String): Bitmap? {
            val comma = url.indexOf(',')
            if (comma < 0 || !url.substring(0, comma).endsWith(";base64")) return null
            val bytes = runCatching { Base64.decode(url.substring(comma + 1), Base64.DEFAULT) }.getOrNull() ?: return null
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            val target = MarkdownLayout.decodedImageSize(Size(bounds.outWidth.toDouble(), bounds.outHeight.toDouble()))
            var sample = 1
            while (bounds.outWidth / (sample * 2) >= target.width && bounds.outHeight / (sample * 2) >= target.height) sample *= 2
            return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
        }
    }
}
