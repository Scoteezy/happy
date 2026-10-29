package com.slopus.happy.ui.stemcells

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import com.slopus.happy.icons.Icon
import com.slopus.happy.layout.FontFace
import com.slopus.happy.layout.TextStyle
import com.slopus.happy.stemcells.ClayIcons

/**
 * Desktop's faces for the Stem Cells screens (happy-ios `StemCellsFonts`):
 * Figtree for the interface and JetBrains Mono for section titles
 * (`--happy-font-ui`, `--happy-font-mono`).
 */
object StemCellsFonts {
    enum class Weight(val face: FontFace) {
        Regular(FontFace.Figtree),
        Medium(FontFace.FigtreeMedium),
        SemiBold(FontFace.FigtreeSemiBold),
        Bold(FontFace.FigtreeBold),
    }

    fun ui(weight: Weight, size: Double, lineHeight: Double? = null) = TextStyle(weight.face, size, lineHeight)

    fun mono(bold: Boolean, size: Double, letterSpacing: Double = 0.0) =
        TextStyle(if (bold) FontFace.JetBrainsMonoBold else FontFace.JetBrainsMono, size, letterSpacing = letterSpacing)
}

/** The library's clay icons and Happy's mark (happy-ios `ClayIconImages`), bundled at 64px in assets/clay. */
object ClayIconImages {
    private val cache = LruCache<String, Bitmap>(48)

    fun image(context: Context, slug: String): Bitmap? {
        if (slug !in ClayIcons.slugs) return null
        return load(context, "clay-$slug")
    }

    fun happy(context: Context): Bitmap? = load(context, "clay-happy-mark")

    private fun load(context: Context, name: String): Bitmap? {
        cache.get(name)?.let { return it }
        val bitmap = runCatching { context.assets.open("clay/$name.png").use { BitmapFactory.decodeStream(it) } }.getOrNull() ?: return null
        cache.put(name, bitmap)
        return bitmap
    }
}

/**
 * Desktop's icon names (`IconName`) as happy-app's icons (happy-ios
 * `StemCellsGlyphs`): the glyphs a row wears when it has nothing of its
 * own, and the ones its menus and states use.
 */
object StemCellsGlyphs {
    fun icon(name: String): Icon = when (name) {
        "search" -> Icon.ionicons("search-outline")
        "compose" -> Icon.ionicons("create-outline")
        "tasks" -> Icon.ionicons("checkbox-outline")
        "agents" -> Icon.ionicons("person-circle-outline")
        "doc" -> Icon.ionicons("document-text-outline")
        "calendar" -> Icon.ionicons("calendar-outline")
        "pulse" -> Icon.ionicons("pulse-outline")
        "users" -> Icon.ionicons("people-outline")
        "chevron-right" -> Icon.ionicons("chevron-forward")
        "chevron-down" -> Icon.ionicons("chevron-down")
        "open" -> Icon.ionicons("chevron-forward")
        "pencil" -> Icon.octicons("pencil")
        "close" -> Icon.ionicons("close-outline")
        "archive" -> Icon.ionicons("archive-outline")
        "trash" -> Icon.ionicons("trash-outline")
        "list" -> Icon.ionicons("list-outline")
        "question" -> Icon.ionicons("help-circle")
        "clock" -> Icon.ionicons("time-outline")
        "layers" -> Icon.ionicons("layers-outline")
        "eye" -> Icon.ionicons("eye-outline")
        "eye-off" -> Icon.ionicons("eye-off-outline")
        "more" -> Icon.materialIcons("more-horiz")
        "reorder" -> Icon.ionicons("swap-vertical-outline")
        "terminal" -> Icon.ionicons("terminal-outline")
        else -> Icon.ionicons(name)
    }
}
