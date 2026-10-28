package com.slopus.happy.ui.composer

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Outline
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.HorizontalScrollView
import android.widget.ImageView
import com.slopus.happy.R
import com.slopus.happy.icons.Icon
import com.slopus.happy.layout.Palette
import com.slopus.happy.layout.Rect
import com.slopus.happy.layout.Size
import com.slopus.happy.layout.composer.AgentInputCardLayout
import com.slopus.happy.ui.components.IconView
import com.slopus.happy.ui.theme.Theme
import com.slopus.happy.ui.theme.ThemeAware
import com.slopus.happy.ui.view.ManualLayout
import com.slopus.happy.ui.view.dp
import com.slopus.happy.ui.view.setFrame
import com.slopus.happy.ui.view.viewScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.max
import kotlin.math.min

/** A picture waiting in the composer, as the strip shows it. */
data class AgentInputAttachmentPreview(val id: String, val file: File)

/**
 * AgentInputAttachmentStrip (happy-ios `AttachmentStripView`): 64dp
 * thumbnails in a horizontal scroll, each with a remove button hanging over
 * its corner. The scroll view clips, as React Native's does; [insets] leave
 * the buttons room inside it.
 */
class AttachmentStripView(context: Context) : HorizontalScrollView(context), ThemeAware {
    var onRemove: ((String) -> Unit)? = null

    var insets: AgentInputCardLayout.AttachmentInsets = AgentInputCardLayout.AttachmentInsets.panel
        set(value) { if (field != value) { field = value; content.requestLayout() } }

    var attachments: List<AgentInputAttachmentPreview> = emptyList()
        set(value) {
            if (field == value) return
            field = value
            rebuild()
        }

    private var thumbnails: List<AttachmentThumbnailView> = emptyList()

    private val content = object : ManualLayout(context) {
        override fun sizeThatFits(width: Double, height: Double): Size =
            Size(AgentInputCardLayout.attachmentThumbnails(thumbnails.size, insets).second, height)

        override fun layoutSubviews(width: Double, height: Double) {
            val (frames, _) = AgentInputCardLayout.attachmentThumbnails(thumbnails.size, insets)
            // Each thumbnail's view takes in the remove button's overhang, so the button is inside it to be touched.
            val overhang = AgentInputCardLayout.Metrics.attachmentRemoveOverhang
            for ((view, frame) in thumbnails.zip(frames)) {
                view.setFrame(Rect(frame.minX, frame.minY - overhang, frame.width + overhang, frame.height + overhang))
            }
        }
    }

    init {
        isHorizontalScrollBarEnabled = false
        overScrollMode = OVER_SCROLL_NEVER
        clipChildren = true
        content.clipChildren = false
        addView(content)
    }

    private fun rebuild() {
        val existing = thumbnails.associateBy { it.attachment.id }
        val next = attachments.map { attachment ->
            existing[attachment.id]?.takeIf { it.attachment == attachment }
                ?: AttachmentThumbnailView(context, attachment).also { view ->
                    view.onRemove = { id -> onRemove?.invoke(id) }
                    content.addView(view)
                }
        }
        val kept = next.toSet()
        for (view in thumbnails) if (view !in kept) content.removeView(view)
        thumbnails = next
        content.requestLayout()
    }

    override fun applyTheme() {
        for (view in thumbnails) view.applyTheme()
    }
}

/**
 * One thumbnail: the picture (cover, radius 8) in a 1dp divider ring, and an
 * 18dp remove button. Its frame reaches the button's overhang above and to
 * the right of the picture.
 */
class AttachmentThumbnailView(context: Context, val attachment: AgentInputAttachmentPreview) : ManualLayout(context), ThemeAware {
    var onRemove: ((String) -> Unit)? = null
    private val imageView = ImageView(context)
    /** The ring is a view of its own under the remove button, so it never crosses the button. */
    private val ring = View(context)
    private val removeButton = RemoveButton(context)

    init {
        clipChildren = false
        val radius = dp(AgentInputCardLayout.Metrics.attachmentRadius)
        imageView.scaleType = ImageView.ScaleType.CENTER_CROP
        imageView.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) = outline.setRoundRect(0, 0, view.width, view.height, radius)
        }
        imageView.clipToOutline = true
        imageView.alpha = 0f
        addView(imageView)
        ring.importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        addView(ring)
        removeButton.contentDescription = context.getString(R.string.composer_remove_attachment)
        removeButton.setOnClickListener { onRemove?.invoke(attachment.id) }
        addView(removeButton)
        applyTheme()
        load()
    }

    override fun applyTheme() {
        ring.background = GradientDrawable().apply {
            cornerRadius = dp(AgentInputCardLayout.Metrics.attachmentRadius)
            setStroke(max(1, dp(AgentInputCardLayout.Metrics.attachmentBorder).toInt()), Theme.color(Palette.divider))
        }
        removeButton.applyTheme()
    }

    private fun load() {
        val side = dp(AgentInputCardLayout.Metrics.attachmentSize).toInt()
        viewScope.launch {
            val image = withContext(Dispatchers.IO) { thumbnail(attachment.file, side) } ?: return@launch
            imageView.setImageBitmap(image)
            // expo-image's `transition={150}`.
            imageView.animate().alpha(1f).setDuration(150).start()
        }
    }

    override fun layoutSubviews(width: Double, height: Double) {
        val overhang = AgentInputCardLayout.Metrics.attachmentRemoveOverhang
        val picture = Rect(0.0, overhang, width - overhang, height - overhang)
        imageView.setFrame(picture)
        ring.setFrame(picture)
        removeButton.setFrame(AgentInputCardLayout.attachmentRemoveButton.offsetBy(0.0, overhang))
    }

    /** The 18dp round button with the 10dp close glyph, on `surfaceHigh`. */
    private class RemoveButton(context: Context) : ManualLayout(context), ThemeAware {
        private val icon = IconView(context, Icon.ionicons("close"), AgentInputCardLayout.Metrics.attachmentRemoveIconSize, Palette.text)

        init {
            isClickable = true
            isFocusable = true
            addView(icon)
            applyTheme()
        }

        override fun applyTheme() {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Theme.color(Palette.surfaceHigh))
            }
            icon.applyTheme()
        }

        override fun setPressed(pressed: Boolean) {
            super.setPressed(pressed)
            icon.alpha = if (pressed) 0.5f else 1f
        }

        override fun layoutSubviews(width: Double, height: Double) = icon.setFrame(Rect(0.0, 0.0, width, height))
    }

    private companion object {
        /** Decoded at no more than the thumbnail's shorter side needs. */
        fun thumbnail(file: File, side: Int): Bitmap? {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.path, bounds)
            val shorter = min(bounds.outWidth, bounds.outHeight)
            if (shorter <= 0) return null
            var sample = 1
            while (shorter / (sample * 2) >= side) sample *= 2
            return BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })
        }
    }
}
