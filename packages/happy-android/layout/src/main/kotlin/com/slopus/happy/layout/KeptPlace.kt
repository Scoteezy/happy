package com.slopus.happy.layout

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.sign

/** HappyLayout/KeptPlace.swift: avoid accumulating pixel-rounding drift across text sizes. */
class KeptPlace<Place> {
    private var kept: Pair<Place, Double>? = null

    fun place(offset: Double, find: (Double) -> Place?): Place? =
        kept?.takeIf { it.second == offset }?.first ?: find(offset)

    fun put(place: Place, offset: Double) { kept = place to offset }
    fun forget() { kept = null }
}

object ScrollPlace {
    /** Nearest display pixel, then inside the scroll range, as native ScrollPlace.offset. */
    fun offset(top: Double, insetTop: Double, insetBottom: Double, viewport: Double, contentHeight: Double, scale: Double): Double {
        val raw = top - insetTop
        val offset = if (scale > 0) sign(raw) * floor(abs(raw) * scale + 0.5) / scale else raw
        val lowest = -insetTop
        val highest = maxOf(lowest, contentHeight + insetBottom - viewport)
        return offset.coerceIn(lowest, highest)
    }
}