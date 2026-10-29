package com.slopus.happy.ui.theme

import android.content.Context
import android.content.res.Configuration
import android.graphics.Typeface
import android.view.View
import android.view.ViewGroup
import androidx.core.content.res.ResourcesCompat
import com.slopus.happy.R
import com.slopus.happy.config.ThemePreference
import com.slopus.happy.layout.ColorToken
import com.slopus.happy.layout.FontFace
import com.slopus.happy.layout.RGBA
import com.slopus.happy.layout.TextScale

/**
 * The window's appearance, as happy-ios `ThemeController` applies it: light
 * or dark from the account's `themePreference` (adaptive follows the
 * system), and the text scale from the system font size. Views read colours
 * through [color] when they draw or are configured, and anything that caches
 * a colour implements [ThemeAware] to be told when the appearance changes.
 * Main thread only.
 */
object Theme {
    var isDark: Boolean = false
        private set

    /** React Native's `fontSizeMultiplier`: the system font scale, applied linearly. */
    var textScale: TextScale = TextScale.Standard
        private set

    /** Device pixels per dp. */
    var density: Float = 1f
        private set

    private var preference = ThemePreference.Adaptive
    private var systemDark = false
    private val listeners = LinkedHashSet<() -> Unit>()

    /**
     * [config] is the system's: the activity's own resources keep the one
     * it was created with, since it runs in a configuration context for its
     * language (MainActivity.attachBaseContext).
     */
    fun install(context: Context, config: Configuration = context.resources.configuration) {
        density = if (config.densityDpi != Configuration.DENSITY_DPI_UNDEFINED) config.densityDpi / 160f else context.resources.displayMetrics.density
        systemDark = (config.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        textScale = TextScale(config.fontScale.toDouble())
        isDark = resolve()
        Fonts.load(context)
    }

    /** The system configuration changed (dark mode, font size, density). True if anything drawn changed. */
    fun configurationChanged(context: Context, config: Configuration): Boolean {
        val before = Triple(isDark, textScale, density)
        install(context, config)
        val changed = before != Triple(isDark, textScale, density)
        if (changed) notifyChanged()
        return changed
    }

    fun setPreference(value: ThemePreference) {
        preference = value
        val dark = resolve()
        if (dark != isDark) {
            isDark = dark
            notifyChanged()
        }
    }

    private fun resolve() = when (preference) {
        ThemePreference.Light -> false
        ThemePreference.Dark -> true
        ThemePreference.Adaptive -> systemDark
    }

    fun color(token: ColorToken): Int = token.resolve(isDark).argb
    fun rgba(token: ColorToken): RGBA = token.resolve(isDark)

    fun addListener(listener: () -> Unit) { listeners += listener }
    fun removeListener(listener: () -> Unit) { listeners -= listener }

    private fun notifyChanged() {
        listeners.toList().forEach { it() }
    }

    /** Re-applies colours to every [ThemeAware] view under [root]. */
    /**
     * Every view under [root] re-reads its colours; with [relayout] (the
     * text size or density changed) each is measured again too, since a
     * view whose content didn't change keeps its last measure otherwise.
     */
    fun applyTree(root: View, relayout: Boolean = false) {
        if (root is ThemeAware) root.applyTheme()
        if (root is ViewGroup) for (i in 0 until root.childCount) applyTree(root.getChildAt(i), relayout)
        if (relayout) root.requestLayout()
        root.invalidate()
    }
}

/** A view that caches resolved colours and re-reads them when the appearance changes. */
interface ThemeAware {
    fun applyTheme()
}

/** The bundled faces, loaded once on the main thread and safe to read from any thread after. */
object Fonts {
    @Volatile private var faces: Map<FontFace, Typeface> = emptyMap()

    fun load(context: Context) {
        if (faces.isNotEmpty()) return
        faces = FontFace.entries.associateWith { face ->
            system(face)?.let { return@associateWith it }
            val id = when (face) {
                FontFace.Regular -> R.font.ibm_plex_sans_regular
                FontFace.Italic -> R.font.ibm_plex_sans_italic
                FontFace.SemiBold -> R.font.ibm_plex_sans_semibold
                FontFace.Mono -> R.font.ibm_plex_mono_regular
                FontFace.MonoItalic -> R.font.ibm_plex_mono_italic
                FontFace.MonoSemiBold -> R.font.ibm_plex_mono_semibold
                FontFace.Logo -> R.font.bricolage_grotesque_bold
                else -> error("not bundled")
            }
            ResourcesCompat.getFont(context, id) ?: Typeface.DEFAULT
        }
    }

    fun typeface(face: FontFace): Typeface = faces[face] ?: Typeface.DEFAULT

    /** The platform's own faces, for text happy-app draws in the system font or Menlo. */
    private fun system(face: FontFace): Typeface? = when (face) {
        FontFace.System -> Typeface.create("sans-serif", Typeface.NORMAL)
        FontFace.SystemMedium -> weighted(500, "sans-serif-medium")
        FontFace.SystemSemiBold -> weighted(600, "sans-serif-medium")
        FontFace.SystemBold -> weighted(700, "sans-serif")
        FontFace.SystemItalic -> Typeface.create("sans-serif", Typeface.ITALIC)
        FontFace.Menlo -> Typeface.MONOSPACE
        FontFace.MenloBold -> Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        FontFace.MenloItalic -> Typeface.create(Typeface.MONOSPACE, Typeface.ITALIC)
        else -> null
    }

    /** A CSS weight of the platform's sans-serif; before API 28, the nearest named family. */
    private fun weighted(weight: Int, family: String): Typeface =
        if (android.os.Build.VERSION.SDK_INT >= 28) Typeface.create(Typeface.SANS_SERIF, weight, false)
        else Typeface.create(family, if (weight >= 700) Typeface.BOLD else Typeface.NORMAL)
}
