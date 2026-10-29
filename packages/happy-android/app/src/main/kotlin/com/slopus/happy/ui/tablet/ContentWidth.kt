package com.slopus.happy.ui.tablet

import android.content.Context
import android.content.res.Configuration
import com.slopus.happy.layout.ContentWidthLimit
import com.slopus.happy.layout.DeviceCalculations
import com.slopus.happy.layout.Size

/**
 * components/layout.ts (happy-ios `ContentWidth`): how wide content may grow.
 * happy-app works it out once, when the module loads, from the screen and
 * the window of that moment; so does this, the first time it is read. Only a
 * screen over 9 inches across on a tablet is a tablet there (800); every
 * other device is unlimited.
 *
 * A tablet is a device whose smallest side is 600 dp or more, Android's own
 * line for one (`smallestScreenWidthDp`); its screen is measured in dp at
 * iOS's 163 points per inch.
 */
object ContentWidth {
    private var cached: Pair<Double, Double>? = null

    /** `layout.maxWidth`: messages, item groups, the session list. */
    fun maxWidth(context: Context): Double = limits(context).first

    /** `layout.headerMaxWidth`: header rows. */
    fun headerMaxWidth(context: Context): Double = limits(context).second

    /** What [DeviceCalculations.headerHeight] calls a tablet. */
    fun isPad(context: Context): Boolean = context.resources.configuration.smallestScreenWidthDp >= TABLET_SMALLEST_WIDTH

    private fun limits(context: Context): Pair<Double, Double> {
        cached?.let { return it }
        val config: Configuration = context.resources.configuration
        val metrics = context.resources.displayMetrics
        // Before the window has a size there is nothing to measure yet: no limit, and nothing kept.
        if (config.screenWidthDp <= 0 || metrics.widthPixels <= 0) return Double.MAX_VALUE to Double.MAX_VALUE
        val screen = Size(metrics.widthPixels / metrics.density.toDouble(), metrics.heightPixels / metrics.density.toDouble())
        val window = Size(config.screenWidthDp.toDouble(), config.screenHeightDp.toDouble())
        val type = DeviceCalculations.deviceType(screen, isPad(context))
        val limits = ContentWidthLimit.maxWidth(type, window) to ContentWidthLimit.headerMaxWidth(type, window)
        cached = limits
        return limits
    }

    private const val TABLET_SMALLEST_WIDTH = 600
}
