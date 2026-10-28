package com.slopus.happy.ui.settings

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Outline
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.text.Layout
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.ImageView
import android.widget.Switch
import coil3.request.ImageRequest
import coil3.request.allowHardware
import coil3.toBitmap
import com.slopus.happy.R
import com.slopus.happy.attachments.AttachmentPictures
import com.slopus.happy.icons.Icon
import com.slopus.happy.images.HappyImageLoader
import com.slopus.happy.layout.BubblePalette
import com.slopus.happy.layout.BubblePreviewLayout
import com.slopus.happy.layout.ColorToken
import com.slopus.happy.layout.DropdownValueLayout
import com.slopus.happy.layout.OptionListLayout
import com.slopus.happy.layout.OptionRowLayout
import com.slopus.happy.layout.Palette
import com.slopus.happy.layout.Rect
import com.slopus.happy.layout.SecretKeyCardLayout
import com.slopus.happy.layout.Size
import com.slopus.happy.layout.glass
import com.slopus.happy.layout.optionPressed
import com.slopus.happy.layout.SettingsHeaderLayout
import com.slopus.happy.layout.SettingsProfileHeaderLayout
import com.slopus.happy.layout.TextStyles
import com.slopus.happy.ui.components.AvatarStyle
import com.slopus.happy.ui.components.AvatarView
import com.slopus.happy.ui.components.IconView
import com.slopus.happy.ui.components.ItemIcon
import com.slopus.happy.ui.components.ItemRowView
import com.slopus.happy.ui.components.Label
import com.slopus.happy.ui.components.measurable
import com.slopus.happy.ui.theme.Theme
import com.slopus.happy.ui.theme.ThemeAware
import com.slopus.happy.ui.view.ManualLayout
import com.slopus.happy.ui.view.dp
import com.slopus.happy.ui.view.setFrame
import com.slopus.happy.ui.view.viewScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.max

// The pieces the settings pages are built from, beyond Item and ItemGroup
// (happy-ios Components/SettingsViews.swift). Each measures itself and
// applies a layout from :layout.

/** The surface card with the hairline border both Settings headers sit on. */
private class SettingsCardView(context: Context, private val radius: Double) : View(context), ThemeAware {
    private val shape = GradientDrawable()

    init {
        background = shape
        applyTheme()
    }

    override fun applyTheme() {
        shape.cornerRadius = dp(radius)
        shape.setColor(Theme.color(Palette.surface))
        // 0.5 on iOS: a hairline, never under a device pixel.
        shape.setStroke(max(1, dp(0.5).toInt()), Theme.color(Palette.divider))
    }
}

/** The logotype card at the top of Settings while the account has no name (components/SettingsView.tsx, logo branch). */
class SettingsHeaderView(context: Context) : ManualLayout(context), ThemeAware {
    private val card = SettingsCardView(context, SettingsHeaderLayout.Metrics.cornerRadius)
    private val logoView = ImageView(context).apply {
        scaleType = ImageView.ScaleType.FIT_CENTER
        contentDescription = "Happy"
    }

    init {
        addView(card)
        addView(logoView)
        applyTheme()
    }

    override fun sizeThatFits(width: Double, height: Double) = SettingsHeaderLayout(width).size

    override fun layoutSubviews(width: Double, height: Double) {
        val layout = SettingsHeaderLayout(width)
        card.setFrame(layout.card)
        logoView.setFrame(layout.logo.offsetBy(layout.card.x, layout.card.y))
    }

    override fun applyTheme() {
        // logotype_dark is the dark artwork for light backgrounds.
        logoView.setImageResource(if (Theme.isDark) R.drawable.logotype_light else R.drawable.logotype_dark)
    }
}

/** The card at the top of Settings once the account has a first name: the avatar, the display name and the GitHub bio, centred. */
class SettingsProfileHeaderView(context: Context) : ManualLayout(context) {
    data class Content(
        val id: String,
        val name: String,
        val bio: String?,
        val avatarUrl: String?,
        val thumbhash: String?,
        val style: AvatarStyle,
        val monochrome: Boolean,
    )

    var content: Content? = null
        set(value) {
            if (field == value) return
            field = value
            apply()
        }

    private val card = SettingsCardView(context, SettingsProfileHeaderLayout.Metrics.cornerRadius)
    private val avatarView = AvatarView(context)
    private val photo = RemoteImageView(context)
    private val nameLabel = Label(context, TextStyles.profileName, Palette.text, alignment = Layout.Alignment.ALIGN_CENTER)
    private val bioLabel = Label(context, TextStyles.profileBio, Palette.textSecondary, alignment = Layout.Alignment.ALIGN_CENTER)

    init {
        listOf(card, avatarView, photo, nameLabel, bioLabel).forEach(::addView)
    }

    fun layout(width: Double) = SettingsProfileHeaderLayout(width, nameLabel.measurable, if (content?.bio == null) null else bioLabel.measurable)

    override fun sizeThatFits(width: Double, height: Double) = layout(width).size

    override fun layoutSubviews(width: Double, height: Double) {
        val layout = layout(width)
        val dx = layout.card.x
        val dy = layout.card.y
        card.setFrame(layout.card)
        avatarView.setFrame(layout.avatar.offsetBy(dx, dy))
        photo.setFrame(layout.avatar.offsetBy(dx, dy))
        nameLabel.setFrame(layout.name.offsetBy(dx, dy))
        layout.bio?.let { bioLabel.setFrame(it.offsetBy(dx, dy)) }
    }

    private fun apply() {
        val content = content ?: return
        nameLabel.text = content.name
        bioLabel.text = content.bio.orEmpty()
        bioLabel.visibility = if (content.bio == null) GONE else VISIBLE
        // Avatar.tsx: a picture replaces the generated avatar outright.
        avatarView.visibility = if (content.avatarUrl != null) GONE else VISIBLE
        avatarView.content = AvatarView.Content(content.id, content.style, content.monochrome)
        photo.show(content.avatarUrl, content.thumbhash)
        requestLayout()
    }
}

/**
 * A round picture fetched from a URL (expo-image with a disk cache), through
 * the app's image loader. The placeholder, a decoded thumbhash, shows until
 * the picture arrives (happy-ios `RemoteImageView`).
 */
class RemoteImageView(context: Context) : ImageView(context) {
    var url: String? = null
        private set
    private var thumbhash: String? = null
    private var job: Job? = null

    init {
        scaleType = ScaleType.CENTER_CROP
        clipToOutline = true
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) = outline.setOval(0, 0, view.width, view.height)
        }
        isClickable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        // No URL yet: hidden, as [show] leaves it for none.
        visibility = GONE
    }

    /** Shows [url]'s picture, the thumbhash until it comes. */
    fun show(url: String?, thumbhash: String?) {
        if (url == this.url && thumbhash == this.thumbhash) return
        job?.cancel()
        this.url = url
        this.thumbhash = thumbhash
        visibility = if (url == null) GONE else VISIBLE
        setImageDrawable(null)
        if (url == null) return
        job = viewScope.launch {
            if (thumbhash != null) {
                val placeholder = withContext(Dispatchers.Default) { AttachmentPictures.thumbhashBitmap(thumbhash) }
                if (this@RemoteImageView.url == url && drawable == null && placeholder != null) setImageBitmap(placeholder)
            }
            val request = ImageRequest.Builder(context).data(url).allowHardware(false).build()
            val picture: Bitmap = HappyImageLoader.get(context).execute(request).image?.toBitmap() ?: return@launch
            if (this@RemoteImageView.url == url) setImageBitmap(picture)
        }
    }
}

/** A user-bubble swatch (settings/appearance.tsx `BubbleColorPreview`): a glass pill with two lines in the preset's indicator colour. */
class BubblePreviewView(context: Context, color: BubblePalette) : ManualLayout(context), ThemeAware {
    var color: BubblePalette = color
        set(value) { if (field != value) { field = value; applyTheme() } }

    private val shape = GradientDrawable()
    private val lines = BubblePreviewLayout.lines.map { View(context) }
    private val lineShapes = lines.map { GradientDrawable() }

    init {
        background = shape
        isClickable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        lines.zip(lineShapes).forEach { (line, drawable) ->
            line.background = drawable
            addView(line)
        }
        applyTheme()
    }

    override fun sizeThatFits(width: Double, height: Double) = BubblePreviewLayout.size

    override fun layoutSubviews(width: Double, height: Double) {
        lines.zip(BubblePreviewLayout.lines).forEach { (line, frame) -> line.setFrame(frame) }
    }

    override fun applyTheme() {
        val glass = color.glass
        shape.cornerRadius = dp(BubblePreviewLayout.cornerRadius)
        shape.setColor(Theme.color(glass.background))
        shape.setStroke(dp(BubblePreviewLayout.borderWidth).toInt().coerceAtLeast(1), Theme.color(glass.border))
        lineShapes.forEach {
            it.cornerRadius = dp(BubblePreviewLayout.lineHeight / 2)
            it.setColor(Theme.color(color.indicator))
        }
    }
}

/** A generated avatar of a fixed size, for a dropdown's preview. */
class AvatarPreviewView(context: Context, content: AvatarView.Content, private val size: Size) : ManualLayout(context) {
    private val avatar = AvatarView(context).also { it.content = content }

    init {
        addView(avatar)
        isClickable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    override fun sizeThatFits(width: Double, height: Double) = size

    override fun layoutSubviews(width: Double, height: Double) = avatar.setFrame(Rect(0.0, 0.0, size.width, size.height))
}

/** A dropdown's current value in a row's trailing slot: a preview, the value's name and an up or down chevron. */
class DropdownValueView(context: Context, private val preview: View, private val previewSize: Size, text: String) : ManualLayout(context) {
    var isExpanded = false
        set(value) { field = value; chevron.icon = Icon.ionicons(if (value) "chevron-up" else "chevron-down") }

    private val label = Label(context, TextStyles.dropdownValue, Palette.textSecondary, maxLines = 1)
    private val chevron = IconView(context, Icon.ionicons("chevron-down"), DropdownValueLayout.Metrics.chevronSize, Palette.groupedChevron)

    init {
        label.text = text
        listOf(preview, label, chevron).forEach(::addView)
        // The row takes the tap.
        isClickable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    fun layout() = DropdownValueLayout(previewSize, label.measurable)

    override fun sizeThatFits(width: Double, height: Double) = layout().size

    override fun layoutSubviews(width: Double, height: Double) {
        val layout = layout()
        preview.setFrame(layout.preview)
        label.setFrame(layout.label)
        chevron.setFrame(layout.chevron)
    }
}

/** One choice under an open dropdown: preview, name, and a check on the selected one. */
class OptionRowView(
    context: Context,
    private val preview: View,
    private val previewSize: Size,
    title: String,
    private val selected: Boolean,
    onTap: () -> Unit,
) : ManualLayout(context), ThemeAware {
    private val titleLabel = Label(context, TextStyles.optionTitle, Palette.text, maxLines = 1)
    private val check = IconView(context, Icon.ionicons("checkmark-circle"), OptionRowLayout.Metrics.checkSize, Palette.statusConnecting)

    init {
        titleLabel.text = title
        check.visibility = if (selected) VISIBLE else INVISIBLE
        listOf(preview, titleLabel, check).forEach {
            it.isClickable = false
            it.importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
            addView(it)
        }
        contentDescription = title
        isSelected = selected
        setOnClickListener { onTap() }
        applyTheme()
    }

    fun layout(width: Double) = OptionRowLayout(width, previewSize, titleLabel.measurable)

    override fun sizeThatFits(width: Double, height: Double) = layout(width).size

    override fun layoutSubviews(width: Double, height: Double) {
        val layout = layout(width)
        preview.setFrame(layout.preview)
        titleLabel.setFrame(layout.title)
        check.setFrame(layout.check)
    }

    /** The selected option keeps its tint; pressed, any shows the platform's ripple over it. */
    override fun applyTheme() {
        val fill = if (selected) ColorDrawable(Theme.color(Palette.optionSelected)) else null
        background = RippleDrawable(ColorStateList.valueOf(Theme.color(Palette.optionPressed)), fill, ColorDrawable(Color.WHITE))
    }
}

/** An open dropdown's options, stacked. */
class OptionListView(context: Context, private val options: List<OptionRowView>) : ManualLayout(context) {
    init {
        options.forEach(::addView)
    }

    fun layout(width: Double) = OptionListLayout(width, options.map { it.measurable })

    override fun sizeThatFits(width: Double, height: Double) = layout(width).size

    override fun layoutSubviews(width: Double, height: Double) {
        options.zip(layout(width).rows).forEach { (option, frame) -> option.setFrame(frame) }
    }
}

/** The revealed secret key under Settings › Account › Backup: tap to copy. */
class SecretKeyCardView(context: Context, formattedKey: String, onCopy: () -> Unit) : ManualLayout(context) {
    var copiedRecently = false
        set(value) {
            field = value
            icon.icon = Icon.ionicons(if (value) "checkmark-circle" else "copy-outline")
            icon.tint = if (value) Palette.iconGreen else Palette.textSecondary
        }

    private val label = Label(context, TextStyles.secretKeyLabel, Palette.textSecondary, maxLines = 1)
    private val icon = IconView(context, Icon.ionicons("copy-outline"), SecretKeyCardLayout.Metrics.iconSize, Palette.textSecondary)
    private val value = Label(context, TextStyles.secretKeyValue, Palette.text)

    init {
        label.text = context.getString(R.string.settings_account_secret_key_label)
        value.text = formattedKey
        listOf(label, icon, value).forEach {
            it.importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
            addView(it)
        }
        contentDescription = label.text
        background = RippleDrawable(ColorStateList.valueOf(Theme.color(Palette.surfacePressedOverlay)), null, ColorDrawable(Color.WHITE))
        setOnClickListener { onCopy() }
    }

    fun layout(width: Double) = SecretKeyCardLayout(width, label.measurable, value.measurable)

    override fun sizeThatFits(width: Double, height: Double) = layout(width).size

    override fun layoutSubviews(width: Double, height: Double) {
        val layout = layout(width)
        label.setFrame(layout.label)
        icon.setFrame(layout.icon)
        value.setFrame(layout.value)
    }
}

/** A checkmark in a row's trailing slot: the choice that is picked. */
object SettingsCheckmark {
    fun view(context: Context, tint: ColorToken) = IconView(context, Icon.ionicons("checkmark"), 20.0, tint).apply {
        contentDescription = "selected"
    }
}

/**
 * Switch rows that survive a rebuild of their page, so a switch the person
 * just flipped finishes its animation instead of being replaced
 * (happy-ios `SettingsToggleRows`).
 */
class SettingsToggleRows(private val context: Context) {
    private val rows = HashMap<String, Pair<ItemRowView, Switch>>()
    private val handlers = HashMap<String, (Boolean) -> Unit>()

    fun row(key: String, title: String, subtitle: String? = null, icon: ItemIcon? = null, isOn: Boolean, onChange: (Boolean) -> Unit): ItemRowView {
        handlers[key] = onChange
        val existing = rows[key]
        if (existing != null) {
            val (row, toggle) = existing
            if (row.title != title) row.title = title
            if (row.subtitle != subtitle) row.subtitle = subtitle
            if (row.icon != icon) row.icon = icon
            if (toggle.isChecked != isOn) {
                updating = true
                toggle.isChecked = isOn
                updating = false
            }
            return row
        }
        val toggle = ItemRowView.switch(context).apply {
            isChecked = isOn
            setOnCheckedChangeListener { _, on -> if (!updating) handlers[key]?.invoke(on) }
        }
        val row = ItemRowView(context, title, subtitle = subtitle, icon = icon).apply { trailingView = toggle }
        rows[key] = row to toggle
        return row
    }

    private var updating = false
}
