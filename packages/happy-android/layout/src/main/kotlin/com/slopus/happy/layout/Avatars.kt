package com.slopus.happy.layout

import kotlin.math.abs

/**
 * Generated avatars, picked from a seed exactly as happy-app picks them
 * (components/AvatarBrutalist.tsx, AvatarGradient.tsx, AvatarSkia.tsx;
 * happy-ios `GeneratedAvatar`), so a session has the same face everywhere.
 */
object GeneratedAvatar {
    /**
     * The `hashCode` all three renderers share: Java's string hash over UTF-16
     * code units in 32-bit arithmetic, then `Math.abs`, which leaves Int.MIN
     * positive.
     */
    fun hashCode(string: String): Long {
        var hash = 0
        for (unit in string) hash = (hash shl 5) - hash + unit.code
        return abs(hash.toLong())
    }

    data class Brutalist(
        /** An image in assets/avatars/brutalist. */
        val imageName: String,
        val tint: RGBA,
        val background: RGBA,
    )

    private const val abstractCount = 262
    private const val bauhausCount = 40
    private const val brutalismCount = 118
    const val brutalistImageCount = abstractCount + bauhausCount + brutalismCount

    private val brutalistColors = listOf(
        "#FFA617" to "#0056B3",
        "#59C9DF" to "#DC2626",
        "#C678FF" to "#16A34A",
        "#FF79D7" to "#047857",
        "#FFD800" to "#4C1D95",
        "#84E600" to "#C026D3",
    )

    fun brutalist(seed: String, monochrome: Boolean): Brutalist {
        val index = (hashCode(seed) % brutalistImageCount).toInt()
        val colors = brutalistColors[(hashCode(seed + "color") % brutalistColors.size).toInt()]
        return Brutalist(
            imageName = brutalistImageName(index),
            tint = RGBA.hex(if (monochrome) "#999999" else colors.first) ?: RGBA.Black,
            background = RGBA.hex(if (monochrome) "#F0F0F0" else colors.second) ?: RGBA.Black,
        )
    }

    /** `allImages` is Abstract 1–262, then Bauhaus 1–40, then Brutalism 1–118. */
    fun brutalistImageName(index: Int): String = when {
        index < abstractCount -> "Abstract-${index + 1}"
        index < abstractCount + bauhausCount -> "Bauhaus-${index - abstractCount + 1}"
        else -> "Brutalism-${index - abstractCount - bauhausCount + 1}"
    }

    /** The brutalist glyph is 80% of the disc. */
    const val brutalistImageScale = 0.8

    const val gradientImageCount = 100

    /** `gradients/01.png` … `100.png`, named `gradient-01` and so on. */
    fun gradientImageName(seed: String): String =
        "gradient-" + "%02d".format((hashCode(seed) % gradientImageCount).toInt() + 1)

    const val pixelGridSize = 8
    private val pixelColors = listOf("#0a0310", "#49007e", "#ff005b", "#ff7d10", "#ffb238")
    private val pixelGrays = listOf("#070707", "#242424", "#575757", "#979797", "#bbbbbb")

    /** 64 cells, row by row: cell `i` takes palette entry `hash % (i + 1)`. */
    fun pixels(seed: String, monochrome: Boolean): List<RGBA> {
        val hash = hashCode(seed)
        val palette = (if (monochrome) pixelGrays else pixelColors).map { RGBA.hex(it) ?: RGBA.Black }
        return (0 until pixelGridSize * pixelGridSize).map { index -> palette[((hash % (index + 1)) % palette.size).toInt()] }
    }

    /** The 64 cells, row by row, in an avatar [size] across. */
    fun pixelFrames(size: Double): List<Rect> {
        val cell = size / pixelGridSize
        return (0 until pixelGridSize * pixelGridSize).map { index ->
            Rect((index % pixelGridSize) * cell, (index / pixelGridSize) * cell, cell, cell)
        }
    }

    /** The brutalist image, 80% of the disc, centred. */
    fun brutalistGlyphFrame(avatarSize: Double): Rect = centredSquare(avatarSize * brutalistImageScale, avatarSize)

    /** Avatar.tsx: a bot without a picture shows a chip, its icon size rounded to whole points. */
    fun botGlyphFrame(avatarSize: Double): Rect = centredSquare(swiftRound(avatarSize * 0.55), avatarSize)

    private fun centredSquare(side: Double, size: Double) = Rect((size - side) / 2, (size - side) / 2, side, side)

    /** `harnessBadgeSizes`: each harness mark keeps clear air inside the circle. */
    fun badgeGlyphRatio(harness: String): Double = when (harness) {
        "rig" -> 0.26
        "codex" -> 0.3
        "claude" -> 0.34
        else -> 0.42
    }

    /** Avatar.tsx `harnessBadgeSizes`: the circle in the bottom-right corner, and the glyph inside it. */
    fun badge(avatarSize: Double, glyphRatio: Double): Pair<Rect, Rect> {
        val circle = swiftRound(avatarSize * 0.42)
        val glyph = swiftRound(avatarSize * glyphRatio)
        val frame = Rect(avatarSize + 2 - circle, avatarSize + 2 - circle, circle, circle)
        return frame to Rect(frame.midX - glyph / 2, frame.midY - glyph / 2, glyph, glyph)
    }
}
