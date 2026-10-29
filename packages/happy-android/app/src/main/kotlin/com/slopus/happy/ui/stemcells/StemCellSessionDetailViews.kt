package com.slopus.happy.ui.stemcells

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Outline
import android.text.Layout
import android.text.TextUtils
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.ImageView
import com.slopus.happy.R
import com.slopus.happy.icons.Icon
import com.slopus.happy.layout.Rect
import com.slopus.happy.layout.StemCellsPalette
import com.slopus.happy.layout.Palette
import com.slopus.happy.layout.surfacePressed
import com.slopus.happy.layout.stemcells.StemCellSessionDetailLayout
import com.slopus.happy.layout.stemcells.StemCellsSettingsLayout
import com.slopus.happy.stemcells.StemCellMedia
import com.slopus.happy.stemcells.StemCellsRow
import com.slopus.happy.stemcells.StemCellsShared
import com.slopus.happy.ui.chat.ChatMediaSource
import com.slopus.happy.ui.components.IconView
import com.slopus.happy.ui.components.Label
import com.slopus.happy.ui.theme.Theme
import com.slopus.happy.ui.theme.ThemeAware
import com.slopus.happy.ui.view.ManualLayout
import com.slopus.happy.ui.view.dp
import com.slopus.happy.ui.view.setFrame
import com.slopus.happy.ui.view.viewScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.roundToInt

/*
 * The pieces of a conversation's detail (happy-ios
 * `StemCellSessionDetailViewController.swift` and
 * `StemCellDetailMediaCardView.swift`): Settings' white cards, the tiles,
 * the shared media's grid, Activity's cards and the large mark.
 */

/** A white card, Settings' rounding. */
class StemCellDetailCardView(context: Context) : ManualLayout(context), ThemeAware {
    private val fill = RoundFill(StemCellsPalette.stemCellsSettingsCard, StemCellSessionDetailLayout.Metrics.radius)

    init {
        background = fill
        clipToOutline = true
    }

    override fun layoutSubviews(width: Double, height: Double) {
        for (index in 0 until childCount) getChildAt(index).setFrame(Rect(0.0, 0.0, width, height))
    }

    override fun applyTheme() = fill.apply()
}

/** One number: large, over what it counts; a value too wide shrinks to fit, to 0.6 of its size. */
class StemCellDetailTileView(context: Context) : ManualLayout(context), ThemeAware {
    private val fill = RoundFill(StemCellsPalette.stemCellsSettingsCard, StemCellSessionDetailLayout.Metrics.tileRadius)
    private val value = Label(context, VALUE, StemCellsPalette.stemCellsSettingsText, maxLines = 1).apply { followsTextSize = false }
    private val label = Label(context, StemCellsFonts.ui(StemCellsFonts.Weight.Regular, 13.0), StemCellsPalette.stemCellsSettingsSecondary, maxLines = 1).apply { followsTextSize = false }
    private var tile: StemCellSessionDetailLayout.Tile? = null

    init {
        background = fill
        for (view in listOf(value, label)) {
            view.importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
            addView(view)
        }
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
    }

    val shown: String get() = value.text.toString()

    fun show(value: String, label: String) {
        this.value.text = value
        this.label.text = label
        contentDescription = "$label, $value"
        requestLayout()
    }

    fun place(tile: StemCellSessionDetailLayout.Tile) {
        this.tile = tile
        setFrame(tile.frame)
        requestLayout()
    }

    override fun layoutSubviews(width: Double, height: Double) {
        val tile = tile ?: return
        // UILabel's adjustsFontSizeToFitWidth: the largest size that fits.
        var size = VALUE.size
        value.style = VALUE
        while (size > VALUE.size * 0.6 && value.measure(100_000.0).width > tile.value.width) {
            size -= 1
            value.style = StemCellsFonts.ui(StemCellsFonts.Weight.SemiBold, size)
        }
        val line = value.lineHeight
        value.setFrame(Rect(tile.value.minX, tile.value.midY - line / 2, tile.value.width, line))
        label.setFrame(tile.label)
    }

    override fun applyTheme() {
        fill.apply()
        value.applyTheme()
        label.applyTheme()
    }

    companion object {
        private val VALUE = StemCellsFonts.ui(StemCellsFonts.Weight.SemiBold, 24.0)

        /** A section's heading over its card: Settings' footnote caps. */
        fun heading(context: Context, text: String) = Label(context, StemCellsFonts.ui(StemCellsFonts.Weight.Regular, 13.0), StemCellsPalette.stemCellsSettingsSecondary, maxLines = 1).apply {
            followsTextSize = false
            this.text = text.uppercase()
            androidx.core.view.ViewCompat.setAccessibilityHeading(this, true)
        }
    }
}

/**
 * Activity's cards and the links' (happy-ios `StemCellDetailActivityCardView`):
 * a row per child, terminal or link, what it is over its facts; the settled
 * card opens and closes under its first row, and the links' ends in "Show
 * more".
 */
class StemCellDetailActivityCardView(context: Context, private val id: String) : ManualLayout(context), ThemeAware {
    data class Item(
        val glyph: String,
        val tile: StemCellsSettingsRow.Tile,
        val title: String,
        val meta: String,
        val opens: Boolean,
        /** Its tag; else `stem-detail-activity:<title>`. */
        val id: String? = null,
    )

    var onSelect: ((Int) -> Unit)? = null
    var open = false
        set(value) {
            field = value
            disclosureView.chevronTurned = value
            disclosureView.stateDescription = context.getString(if (value) R.string.stem_expanded else R.string.stem_collapsed)
        }

    private val fill = RoundFill(StemCellsPalette.stemCellsSettingsCard, StemCellSessionDetailLayout.Metrics.radius)
    private val rows = ArrayList<RowView>()
    /** The settled card's first row: Settings' row, its chevron turned down while open. */
    private val disclosureView = StemCellsSettingsCardView.RowView(context)
    private var disclosure: StemCellsSettingsRow? = null
    /** "Show more" under the rows. */
    private val moreView = StemCellsSettingsCardView.RowView(context)
    private var more: StemCellsSettingsRow? = null
    private var placed: Placed? = null

    private data class Placed(val frame: Rect, val disclosure: StemCellsSettingsLayout.Row?, val rows: List<StemCellSessionDetailLayout.ActivityRow>, val more: StemCellsSettingsLayout.Row?)

    init {
        tag = id
        background = fill
        clipToOutline = true
        disclosureView.visibility = GONE
        addView(disclosureView)
        moreView.visibility = GONE
        addView(moreView)
    }

    val count: Int get() = rows.size

    fun show(items: List<Item>, disclosure: StemCellsSettingsRow?, more: StemCellsSettingsRow? = null) {
        this.disclosure = disclosure
        this.more = more
        while (rows.size < items.size) {
            val row = RowView(context)
            val index = rows.size
            row.setOnClickListener { if (row.opens) onSelect?.invoke(index) }
            addView(row)
            rows += row
        }
        while (rows.size > items.size) removeView(rows.removeAt(rows.size - 1))
        for ((row, item) in rows.zip(items)) row.show(item)
        requestLayout()
    }

    fun place(card: StemCellSessionDetailLayout.ActivityCard) = place(card.frame, card.disclosure, card.rows, null)

    fun place(frame: Rect, disclosure: StemCellsSettingsLayout.Row?, rows: List<StemCellSessionDetailLayout.ActivityRow>, more: StemCellsSettingsLayout.Row?) {
        placed = Placed(frame, disclosure, rows, more)
        setFrame(frame)
        requestLayout()
    }

    override fun layoutSubviews(width: Double, height: Double) {
        val placed = placed ?: return
        val more = more
        moreView.visibility = if (placed.more != null && more != null) VISIBLE else GONE
        if (placed.more != null && more != null) {
            moreView.show(more, placed.more)
            moreView.tag = "$id-more"
        }
        val disclosure = disclosure
        disclosureView.visibility = if (placed.disclosure != null && disclosure != null) VISIBLE else GONE
        if (placed.disclosure != null && disclosure != null) {
            disclosureView.show(disclosure, placed.disclosure)
            disclosureView.tag = "stem-detail-completed-toggle"
            disclosureView.stateDescription = context.getString(if (open) R.string.stem_expanded else R.string.stem_collapsed)
        }
        // Folded rows are hidden; they come back where they open.
        for ((index, row) in rows.withIndex()) {
            val frames = placed.rows.getOrNull(index)
            row.visibility = if (frames == null) GONE else VISIBLE
            if (frames != null) row.place(frames)
        }
    }

    override fun applyTheme() {
        fill.apply()
        disclosureView.applyTheme()
        moreView.applyTheme()
        for (row in rows) row.applyTheme()
    }

    class RowView(context: Context) : ManualLayout(context), ThemeAware {
        private val tileFill = android.graphics.drawable.GradientDrawable().apply { cornerRadius = dp(StemCellsSettingsLayout.Metrics.tileRadius) }
        private val tile = View(context).apply { background = tileFill }
        private val glyph = IconView(context, null, StemCellsSettingsLayout.Metrics.glyph, StemCellsPalette.stemCellsSettingsGlyph)
        private val title = Label(context, StemCellsFonts.ui(StemCellsFonts.Weight.Regular, 17.0), StemCellsPalette.stemCellsSettingsText, maxLines = 1).apply { followsTextSize = false }
        private val meta = Label(context, StemCellsFonts.ui(StemCellsFonts.Weight.Regular, 13.0), StemCellsPalette.stemCellsSettingsSecondary, maxLines = 1).apply { followsTextSize = false }
        private val separator = View(context)
        private var item: Item? = null
        private var frames: StemCellSessionDetailLayout.ActivityRow? = null
        var opens = false
            private set

        init {
            for (view in listOf(tile, glyph, title, meta, separator)) {
                view.importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
                addView(view)
            }
            applyTheme()
        }

        fun show(item: Item) {
            this.item = item
            opens = item.opens
            tileFill.setColor(Theme.color(item.tile.color))
            glyph.icon = StemCellsGlyphs.icon(item.glyph)
            title.text = item.title
            meta.text = item.meta
            contentDescription = "${item.title}, ${item.meta}"
            isClickable = item.opens
            isFocusable = true
            tag = item.id ?: "stem-detail-activity:${item.title}"
        }

        fun place(frames: StemCellSessionDetailLayout.ActivityRow) {
            this.frames = frames
            setFrame(frames.box)
            requestLayout()
        }

        override fun layoutSubviews(width: Double, height: Double) {
            val frames = frames ?: return
            val origin = frames.box
            fun local(rect: Rect) = Rect(rect.minX - origin.minX, rect.minY - origin.minY, rect.width, rect.height)
            tile.setFrame(local(frames.icon))
            glyph.setFrame(local(frames.icon))
            title.setFrame(local(frames.title))
            meta.setFrame(local(frames.meta))
            separator.visibility = if (frames.separator == null) GONE else VISIBLE
            frames.separator?.let { separator.setFrame(local(it)) }
        }

        override fun setPressed(pressed: Boolean) {
            super.setPressed(pressed)
            applyPressed()
        }

        private fun applyPressed() = setBackgroundColor(if (isPressed && opens) Theme.color(StemCellsPalette.stemCellsRowPressed) else 0)

        override fun applyTheme() {
            item?.let { tileFill.setColor(Theme.color(it.tile.color)) }
            separator.setBackgroundColor(Theme.color(StemCellsPalette.stemCellsSettingsSeparator))
            glyph.applyTheme()
            title.applyTheme()
            meta.applyTheme()
            applyPressed()
        }
    }
}

/**
 * The detail's shared media (happy-ios `StemCellDetailMediaCardView`),
 * Telegram's grid in our card: each picture, recording and file a square,
 * a recording's first frame under a play button, a file its glyph and
 * name; "Show more" under them.
 */
class StemCellDetailMediaCardView(context: Context, private val source: ChatMediaSource) : ManualLayout(context), ThemeAware {
    var onSelect: ((Int) -> Unit)? = null
    var onMore: (() -> Unit)? = null
    private val fill = RoundFill(StemCellsPalette.stemCellsSettingsCard, StemCellSessionDetailLayout.Metrics.radius)
    private val cells = ArrayList<StemCellSharedMediaCell>()
    private val moreView = StemCellsSettingsCardView.RowView(context)
    private var placed: StemCellSessionDetailLayout.MediaCard? = null

    init {
        tag = "stem-detail-media"
        background = fill
        clipToOutline = true
        moreView.visibility = GONE
        addView(moreView)
    }

    val count: Int get() = cells.size

    fun show(media: List<StemCellsShared.Media>) {
        while (cells.size < media.size) {
            val cell = StemCellSharedMediaCell(context, source)
            val index = cells.size
            cell.tag = "stem-detail-media:$index"
            cell.setOnClickListener { onSelect?.invoke(index) }
            addView(cell, indexOfChild(moreView))
            cells += cell
        }
        while (cells.size > media.size) removeView(cells.removeAt(cells.size - 1))
        for ((cell, one) in cells.zip(media)) cell.show(one)
    }

    fun place(card: StemCellSessionDetailLayout.MediaCard) {
        placed = card
        setFrame(card.frame)
        requestLayout()
    }

    override fun layoutSubviews(width: Double, height: Double) {
        val card = placed ?: return
        for ((cell, frame) in cells.zip(card.cells)) cell.setFrame(frame)
        moreView.visibility = if (card.more == null) GONE else VISIBLE
        card.more?.let { frames ->
            moreView.show(
                StemCellsSettingsRow("media-more", context.getString(R.string.stem_show_more), accessory = StemCellsSettingsLayout.Accessory.None, action = { onMore?.invoke() }),
                frames,
            )
            moreView.tag = "stem-detail-media-more"
        }
    }

    override fun applyTheme() {
        fill.apply()
        moreView.applyTheme()
        for (cell in cells) cell.applyTheme()
    }
}

/**
 * One square of the grid: the picture filling it over its thumbhash, a
 * recording's first frame under a play button, or a file's glyph over its
 * name. Read at the square's size, off the main thread.
 */
class StemCellSharedMediaCell(context: Context, private val source: ChatMediaSource) : ManualLayout(context), ThemeAware {
    private val placeholder = ImageView(context).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
    private val picture = ImageView(context).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
    private val playFill = android.graphics.drawable.GradientDrawable().apply { shape = android.graphics.drawable.GradientDrawable.OVAL }
    private val play = View(context).apply { background = playFill }
    private val playIcon = IconView(context, Icon.ionicons("play"), 16.0, StemCellsPalette.stemCellsPlayGlyph)
    private val glyph = IconView(context, StemCellsGlyphs.icon("document-outline"), GLYPH, StemCellsPalette.stemCellsSettingsSecondary)
    private val name = Label(context, StemCellsFonts.ui(StemCellsFonts.Weight.Regular, 12.0), StemCellsPalette.stemCellsSettingsSecondary, maxLines = 2, alignment = Layout.Alignment.ALIGN_CENTER).apply {
        followsTextSize = false
        ellipsize = TextUtils.TruncateAt.MIDDLE
    }
    private val dim = View(context)
    private var media: StemCellsShared.Media? = null
    private var loadedPixels = 0
    private var task: Job? = null
    private var placeholderTask: Job? = null
    /** Not asked again until it comes back into a window: the computer may be away. */
    private var failed = false

    init {
        for (view in listOf(placeholder, picture, play, playIcon, glyph, name, dim)) {
            view.importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
            addView(view)
        }
        dim.visibility = GONE
        isClickable = true
        isFocusable = true
        applyTheme()
    }

    fun show(media: StemCellsShared.Media) {
        if (media == this.media) return
        task?.cancel()
        task = null
        placeholderTask?.cancel()
        placeholderTask = null
        this.media = media
        loadedPixels = 0
        failed = false
        placeholder.setImageDrawable(null)
        picture.animate().cancel()
        picture.alpha = 1f
        picture.setImageDrawable(null)
        var video = false
        var file: StemCellMedia? = null
        var label = context.getString(R.string.stem_picture)
        var thumbhash: String? = null
        if (media is StemCellsShared.Media.Tool) {
            video = media.media.isVideo
            file = if (media.media.isVisual) null else media.media
            label = if (video) context.getString(R.string.stem_video) else media.media.item.name
            thumbhash = media.media.item.thumbhash
        }
        play.visibility = if (video) VISIBLE else GONE
        playIcon.visibility = play.visibility
        glyph.visibility = if (file == null) GONE else VISIBLE
        name.visibility = glyph.visibility
        name.text = file?.item?.name ?: ""
        contentDescription = label
        if (thumbhash != null) {
            placeholderTask = viewScope.launch {
                val image = source.placeholder(thumbhash) ?: return@launch
                if (this@StemCellSharedMediaCell.media == media && picture.drawable == null) placeholder.setImageBitmap(image)
            }
        }
        requestLayout()
    }

    override fun layoutSubviews(width: Double, height: Double) {
        val all = Rect(0.0, 0.0, width, height)
        placeholder.setFrame(all)
        picture.setFrame(all)
        dim.setFrame(all)
        val playRect = Rect(((width - PLAY) / 2).roundToInt().toDouble(), ((height - PLAY) / 2).roundToInt().toDouble(), PLAY, PLAY)
        play.setFrame(playRect)
        playIcon.setFrame(playRect)
        val nameHeight = ceil(name.lineHeight * 2)
        val block = GLYPH + 6 + nameHeight
        val top = ((height - block) / 2).roundToInt().toDouble()
        glyph.setFrame(Rect(0.0, top, width, GLYPH))
        name.setFrame(Rect(8.0, top + GLYPH + 6, max(0.0, width - 16), nameHeight))
        load()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        failed = false
        load()
    }

    /** The picture at the square's pixels, once; a file has none. */
    private fun load() {
        val media = media ?: return
        if (!isAttachedToWindow || width <= 0 || task != null || failed) return
        if (media is StemCellsShared.Media.Tool && !media.media.isVisual) return
        val pixels = max(width, height)
        if (pixels <= loadedPixels) return
        source.cachedImage(media.key, pixels)?.let {
            loadedPixels = pixels
            picture.setImageBitmap(it)
            return
        }
        task = viewScope.launch {
            val image: Bitmap? = source.image(media.key, pixels)
            if (this@StemCellSharedMediaCell.media != media) return@launch
            task = null
            if (image == null) {
                failed = true
                return@launch
            }
            val first = picture.drawable == null
            loadedPixels = pixels
            picture.setImageBitmap(image)
            if (first) {
                picture.alpha = 0f
                picture.animate().alpha(1f).setDuration(200).start()
            }
            // Grown while it read: the larger one now.
            load()
        }
    }

    override fun setPressed(pressed: Boolean) {
        super.setPressed(pressed)
        dim.visibility = if (pressed) VISIBLE else GONE
    }

    override fun applyTheme() {
        setBackgroundColor(Theme.color(Palette.surfacePressed))
        playFill.setColor(Theme.color(StemCellsPalette.stemCellsPlayFill))
        dim.setBackgroundColor(Theme.color(StemCellsPalette.stemCellsRowPressed))
        playIcon.applyTheme()
        glyph.applyTheme()
        name.applyTheme()
    }

    private companion object {
        const val PLAY = 36.0
        const val GLYPH = 30.0
    }
}

/**
 * The detail's mark, large: the stem cell, its clay icon, Happy's own, a
 * picture, or a glyph on a tile.
 */
class StemCellDetailMarkView(context: Context) : ManualLayout(context), ThemeAware {
    var images: StemCellsImages? = null
    var mark: StemCellsRow.Mark? = null
        set(value) { if (field != value) { field = value; apply() } }
    private val stemCell = StemCellMarkView(context)
    private val picture = ImageView(context)
    private val glyphFill = android.graphics.drawable.GradientDrawable()
    private val glyph = IconView(context, null, 40.0, StemCellsPalette.stemCellsSettingsGlyph)
    private var job: Job? = null
    /** The picture's rounding, as a share of its side. */
    private var rounding = 0.0

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        addView(stemCell)
        picture.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, (view.width * rounding).toFloat())
            }
        }
        addView(picture)
        glyph.background = glyphFill
        addView(glyph)
    }

    override fun layoutSubviews(width: Double, height: Double) {
        val all = Rect(0.0, 0.0, width, height)
        stemCell.setFrame(all)
        picture.setFrame(all)
        glyph.setFrame(all)
        glyphFill.cornerRadius = dp(width * 0.28)
        picture.invalidateOutline()
    }

    private fun apply() {
        job?.cancel()
        job = null
        stemCell.visibility = GONE
        stemCell.seed = null
        picture.visibility = GONE
        picture.setImageDrawable(null)
        picture.scaleType = ImageView.ScaleType.FIT_CENTER
        picture.clipToOutline = false
        glyph.visibility = GONE
        rounding = 0.0
        when (val mark = mark) {
            is StemCellsRow.Mark.StemCell -> {
                stemCell.visibility = VISIBLE
                stemCell.seed = mark.seed
            }
            StemCellsRow.Mark.Happy -> showPicture(ClayIconImages.happy(context))
            is StemCellsRow.Mark.Clay -> showPicture(ClayIconImages.image(context, mark.slug))
            is StemCellsRow.Mark.Image -> {
                picture.visibility = VISIBLE
                picture.scaleType = ImageView.ScaleType.CENTER_CROP
                picture.clipToOutline = true
                rounding = 0.22
                val pixels = (StemCellSessionDetailLayout.Metrics.mark * 3).toInt()
                val cached = images?.cachedPicture(mark.path, mark.thumbhash, pixels)
                picture.setImageBitmap(cached ?: StemCellsImages.placeholder(mark.thumbhash))
                val images = images
                if (cached == null && images != null) {
                    job = viewScope.launch {
                        val image = images.picture(mark.path, mark.thumbhash, pixels) ?: return@launch
                        picture.setImageBitmap(image)
                    }
                }
            }
            is StemCellsRow.Mark.Glyph -> showGlyph(mark.name)
            StemCellsRow.Mark.None, null -> showGlyph("agents")
        }
        picture.invalidateOutline()
        requestLayout()
    }

    private fun showPicture(bitmap: Bitmap?) {
        picture.visibility = VISIBLE
        picture.setImageBitmap(bitmap)
    }

    private fun showGlyph(name: String) {
        glyph.visibility = VISIBLE
        glyph.icon = StemCellsGlyphs.icon(name)
        glyphFill.setColor(Theme.color(StemCellsPalette.stemCellsSettingsTilePurple))
    }

    override fun applyTheme() {
        glyphFill.setColor(Theme.color(StemCellsPalette.stemCellsSettingsTilePurple))
        glyph.applyTheme()
        stemCell.applyTheme()
    }
}
