package com.slopus.happy.ui.chat

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Outline
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.ImageView
import com.slopus.happy.R
import com.slopus.happy.attachments.AttachmentPictures
import com.slopus.happy.icons.Icon
import com.slopus.happy.layout.Palette
import com.slopus.happy.layout.Rect
import com.slopus.happy.layout.Size
import com.slopus.happy.layout.chat.FileViewLayout
import com.slopus.happy.ui.components.IconView
import com.slopus.happy.ui.theme.Theme
import com.slopus.happy.ui.theme.ThemeAware
import com.slopus.happy.ui.view.dp
import com.slopus.happy.ui.view.px
import com.slopus.happy.ui.view.setFrame
import com.slopus.happy.ui.view.viewScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * useAttachmentImage for one session of one account (happy-ios
 * `ChatAttachmentImages`): its pictures, downloaded and decrypted by [load],
 * decoded off the main thread at no more than FileView draws, and kept in
 * the account's own [AttachmentPictures], never another account's.
 *
 * The screen showing a chat puts one on its view under
 * `R.id.chat_attachments`; a [ChatAttachmentView] finds it up its parents,
 * as the iOS one does up its responder chain.
 */
class ChatAttachmentImages(
    private val pictures: AttachmentPictures,
    private val sessionId: String,
    private val load: suspend (ref: String) -> ByteArray?,
) {
    fun cachedPlaceholder(thumbhash: String): Bitmap? = pictures.cachedPlaceholder(thumbhash)

    suspend fun placeholder(thumbhash: String): Bitmap? = pictures.placeholder(thumbhash)

    fun cached(ref: String): Bitmap? = pictures.cached(AttachmentPictures.Key(sessionId, ref))

    /** The picture, or null when it couldn't be downloaded, decrypted or read. */
    suspend fun image(ref: String): Bitmap? = pictures.picture(AttachmentPictures.Key(sessionId, ref)) {
        val bytes = load(ref) ?: return@picture null
        withContext(Dispatchers.Default) { decode(bytes) }
    }

    private fun decode(bytes: ByteArray): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val target = FileViewLayout.decodedSize(Size(bounds.outWidth.toDouble(), bounds.outHeight.toDouble()))
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= target.width && bounds.outHeight / (sample * 2) >= target.height) sample *= 2
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
    }

    companion object {
        /** The pictures for the chat [view] is in, or null. */
        fun of(view: View): ChatAttachmentImages? {
            var current: View? = view
            while (current != null) {
                (current.getTag(R.id.chat_attachments) as? ChatAttachmentImages)?.let { return it }
                current = current.parent as? View
            }
            return null
        }
    }
}

/**
 * FileView's picture (happy-ios `ChatAttachmentView`): the thumbhash, then
 * the picture faded in over it (expo-image's `transition={150}`,
 * `contentFit="cover"`), rounded 8. One that couldn't be loaded keeps its
 * thumbhash under an alert badge. It loads once it is on screen, from the
 * chat it is in, which is also where it looks for a picture already kept:
 * it holds none of its own.
 */
class ChatAttachmentView(context: Context) : ViewGroup(context), ThemeAware {
    private val placeholder = ImageView(context).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
    private val picture = ImageView(context).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
    private val badge = View(context)
    private val badgeIcon = IconView(context, Icon.ionicons("alert-circle-outline"), FileViewLayout.Metrics.badgeIconSize, Palette.textSecondary)
    var attachment: ChatAttachment? = null
        private set
    private var job: Job? = null
    private var placeholderJob: Job? = null

    init {
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, dp(FileViewLayout.Metrics.radius))
            }
        }
        clipToOutline = true
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        addView(placeholder)
        addView(picture)
        badge.visibility = GONE
        addView(badge)
        badgeIcon.visibility = GONE
        addView(badgeIcon)
        applyTheme()
    }

    /** Null lets go of the picture. */
    fun apply(attachment: ChatAttachment?) {
        if (attachment == this.attachment) return
        job?.cancel()
        job = null
        placeholderJob?.cancel()
        placeholderJob = null
        this.attachment = attachment
        // Only one already decoded; any other is decoded off the main thread.
        // setImageBitmap(null) would leave an empty drawable that reads as loaded.
        placeholder.setImageDrawable(attachment?.thumbhash?.let { source()?.cachedPlaceholder(it) }?.let { BitmapDrawable(resources, it) })
        picture.setImageDrawable(null)
        picture.animate().cancel()
        picture.alpha = 1f
        showBadge(false)
        loadIfNeeded()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        loadIfNeeded()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), MeasureSpec.getSize(heightMeasureSpec))
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val bounds = Rect(0.0, 0.0, px(width.toFloat()), px(height.toFloat()))
        placeholder.setFrame(bounds)
        picture.setFrame(bounds)
        val frame = FileViewLayout.badge(bounds.size)
        badge.setFrame(frame)
        badgeIcon.setFrame(frame)
    }

    override fun applyTheme() {
        badge.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Theme.color(Palette.surfaceHigh))
        }
        badgeIcon.applyTheme()
    }

    private fun showBadge(show: Boolean) {
        badge.visibility = if (show) VISIBLE else GONE
        badgeIcon.visibility = if (show) VISIBLE else GONE
    }

    private val badgeShown get() = badge.visibility == VISIBLE

    private fun loadIfNeeded() {
        val attachment = attachment ?: return
        if (!isAttachedToWindow) return
        val images = source() ?: return
        loadPlaceholderIfNeeded(attachment, images)
        if (job?.isActive == true || picture.drawable != null || badgeShown) return
        // Before the first frame it is drawn in, so no fade.
        images.cached(attachment.ref)?.let {
            picture.setImageBitmap(it)
            return
        }
        job = viewScope.launch {
            val image = images.image(attachment.ref)
            if (this@ChatAttachmentView.attachment != attachment) return@launch
            if (image == null) {
                // `error && !uri`.
                showBadge(true)
                return@launch
            }
            picture.setImageBitmap(image)
            picture.alpha = 0f
            picture.animate().alpha(1f).setDuration((FileViewLayout.Metrics.fadeIn * 1000).toLong()).start()
        }
    }

    /** The thumbhash, under the picture and under the badge when the picture can't be had. */
    private fun loadPlaceholderIfNeeded(attachment: ChatAttachment, images: ChatAttachmentImages) {
        val thumbhash = attachment.thumbhash ?: return
        if (placeholderJob?.isActive == true || placeholder.drawable != null) return
        images.cachedPlaceholder(thumbhash)?.let {
            placeholder.setImageBitmap(it)
            return
        }
        placeholderJob = viewScope.launch {
            val image = images.placeholder(thumbhash)
            if (this@ChatAttachmentView.attachment == attachment && image != null) placeholder.setImageBitmap(image)
        }
    }

    private fun source(): ChatAttachmentImages? = ChatAttachmentImages.of(this)
}
