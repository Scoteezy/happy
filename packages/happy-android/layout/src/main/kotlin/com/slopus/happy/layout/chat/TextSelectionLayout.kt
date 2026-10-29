package com.slopus.happy.layout.chat

import com.slopus.happy.layout.LayoutInsets
import com.slopus.happy.layout.Rect
import com.slopus.happy.layout.Size
import kotlin.math.max

// Ported from happy-ios Sources/HappyLayout/Chat/MessageDetailLayout.swift (TextSelectionLayout).

/**
 * text-selection.tsx: the card 16 in from the safe area's sides and top,
 * running to 16 above the screen's foot; the text 16 inside it, clearing
 * the home indicator at the bottom.
 */
data class TextSelectionLayout(val card: Rect, val textInsets: LayoutInsets) {
    object Metrics {
        const val margin = 16.0
        const val radius = 22.0
        const val padding = 16.0
    }

    companion object {
        operator fun invoke(size: Size, safeArea: LayoutInsets): TextSelectionLayout {
            val m = Metrics
            val card = Rect(
                safeArea.left + m.margin,
                safeArea.top + m.margin,
                max(0.0, size.width - safeArea.left - safeArea.right - 2 * m.margin),
                max(0.0, size.height - safeArea.top - 2 * m.margin),
            )
            return TextSelectionLayout(card, LayoutInsets(m.padding, m.padding, m.padding + safeArea.bottom + m.padding, m.padding))
        }
    }
}
