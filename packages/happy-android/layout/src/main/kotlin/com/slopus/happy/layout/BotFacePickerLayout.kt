package com.slopus.happy.layout

import kotlin.math.max
import kotlin.math.min

/** HappyLayout/BotFacePickerLayout: four shrinking faces, a die, and the artist's credit. */
data class BotFacePickerLayout(
    val size: Size,
    val heading: Rect,
    val faces: List<Rect>,
    val die: Rect,
    val credit: Rect,
    val creditLink: Rect,
) {
    object Metrics {
        const val faceCount = 4
        const val faceSize = 52.0
        const val faceRadius = 14.0
        const val ringWidth = 2.0
        const val imageRadius = faceRadius - ringWidth
        const val dieSize = 40.0
        const val dieIconSize = 22.0
        const val gap = 6.0
        const val facesGap = 10.0
    }

    data class Text(val heading: Measurable, val credit: Measurable, val creditLead: Measurable)

    companion object {
        operator fun invoke(width: Double, text: Text): BotFacePickerLayout {
            val m = Metrics
            val width = max(0.0, width)
            val headingSize = text.heading.size(width)
            val heading = Rect(0.0, 0.0, min(headingSize.width, width), headingSize.height)
            val face = max(0.0, min(m.faceSize, (width - m.dieSize - m.faceCount * m.facesGap) / m.faceCount))
            val top = heading.maxY + m.gap
            val rowHeight = max(face, m.dieSize)
            val faces = List(m.faceCount) { Rect(it * (face + m.facesGap), top + (rowHeight - face) / 2, face, face) }
            val die = Rect(m.faceCount * (face + m.facesGap), top + (rowHeight - m.dieSize) / 2, m.dieSize, m.dieSize)
            val creditSize = text.credit.size(width)
            val credit = Rect(0.0, top + rowHeight + m.gap, min(creditSize.width, width), creditSize.height)
            val lead = min(text.creditLead.size(Double.MAX_VALUE).width, credit.width)
            val link = Rect(credit.minX + lead, credit.minY, credit.width - lead, credit.height)
            return BotFacePickerLayout(Size(width, credit.maxY), heading, faces, die, credit, link)
        }
    }
}

val TextStyles.botFaceHeading get() = TextStyle(FontFace.SemiBold, 12)
val TextStyles.botFaceCredit get() = TextStyle(FontFace.Regular, 11)