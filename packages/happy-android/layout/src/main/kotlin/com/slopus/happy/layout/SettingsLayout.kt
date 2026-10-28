package com.slopus.happy.layout

import kotlin.math.max
import kotlin.math.min

// The settings pages (happy-ios HappyLayout/SettingsLayout.swift and
// SettingsPageLayout.swift).

/**
 * The card at the top of Settings once the account has a name
 * (components/SettingsView.tsx, profile branch): the avatar, the name and
 * the GitHub bio, centred.
 *
 *     ╭──────────── card, 16 from the edges, 16 from the top ────────────╮
 *     │ 24                                                               │
 *     │                          ( avatar 90 )                           │
 *     │ 12                                                               │
 *     │                             Name                                 │
 *     │ 4 (8 without a bio)                                              │
 *     │                 bio, 16 in from the card's sides                 │
 *     │ 8, then 24                                                       │
 *     ╰──────────────────────────────────────────────────────────────────╯
 */
data class SettingsProfileHeaderLayout(
    val size: Size,
    val card: Rect,
    /** The rest are in the card's coordinates. */
    val avatar: Rect,
    val name: Rect,
    val bio: Rect?,
) {
    object Metrics {
        const val marginTop = 16.0
        const val marginHorizontal = 16.0
        const val paddingVertical = 24.0
        const val avatarSize = 90.0
        const val avatarMarginBottom = 12.0
        const val nameMarginBottom = 8.0
        const val nameMarginBottomWithBio = 4.0
        const val bioPaddingHorizontal = 16.0
        const val bioMarginBottom = 8.0
        const val cornerRadius = 16.0
    }

    companion object {
        operator fun invoke(width: Double, name: Measurable, bio: Measurable?): SettingsProfileHeaderLayout {
            val m = Metrics
            val w = max(0.0, width)
            val cardWidth = max(0.0, w - m.marginHorizontal * 2)
            var y = m.paddingVertical
            val avatar = Rect((cardWidth - m.avatarSize) / 2, y, m.avatarSize, m.avatarSize)
            y = avatar.maxY + m.avatarMarginBottom
            val nameSize = name.size(cardWidth)
            val nameFrame = Rect((cardWidth - nameSize.width) / 2, y, nameSize.width, nameSize.height)
            y = nameFrame.maxY
            var bioFrame: Rect? = null
            if (bio != null) {
                y += m.nameMarginBottomWithBio
                val bioWidth = max(0.0, cardWidth - m.bioPaddingHorizontal * 2)
                val bioSize = bio.size(bioWidth)
                bioFrame = Rect((cardWidth - bioSize.width) / 2, y, bioSize.width, bioSize.height)
                y = bioFrame.maxY + m.bioMarginBottom
            } else {
                y += m.nameMarginBottom
            }
            val card = Rect(m.marginHorizontal, m.marginTop, cardWidth, y + m.paddingVertical)
            return SettingsProfileHeaderLayout(Size(w, card.maxY), card, avatar, nameFrame, bioFrame)
        }
    }
}

/**
 * A dropdown's current value in a row's trailing slot
 * (settings/appearance.tsx `dropdownValue`): a preview, the value's name
 * and an up/down chevron, 8 apart, at most 184 wide. The name shrinks first.
 */
data class DropdownValueLayout(val size: Size, val preview: Rect, val label: Rect, val chevron: Rect) {
    object Metrics {
        const val gap = 8.0
        const val maxWidth = 184.0
        const val chevronSize = 18.0
    }

    companion object {
        operator fun invoke(preview: Size, label: Measurable, chevron: Size = Size(Metrics.chevronSize, Metrics.chevronSize)): DropdownValueLayout {
            val m = Metrics
            val fixedWidth = preview.width + chevron.width + m.gap * 2
            val text = label.size(max(0.0, m.maxWidth - fixedWidth))
            val height = max(preview.height, max(text.height, chevron.height))
            val previewFrame = Rect(0.0, (height - preview.height) / 2, preview.width, preview.height)
            val labelFrame = Rect(previewFrame.maxX + m.gap, (height - text.height) / 2, text.width, text.height)
            val chevronFrame = Rect(labelFrame.maxX + m.gap, (height - chevron.height) / 2, chevron.width, chevron.height)
            return DropdownValueLayout(Size(chevronFrame.maxX, height), previewFrame, labelFrame, chevronFrame)
        }
    }
}

/**
 * One choice under an open dropdown (settings/appearance.tsx
 * `bubbleColorOption`): preview, name, and a checkmark slot that is
 * reserved even when empty so names line up.
 *
 *     |16| preview |12| name ……………………… |12| ✓ 20 |16|    min 48 tall
 */
data class OptionRowLayout(val size: Size, val preview: Rect, val title: Rect, val check: Rect) {
    object Metrics {
        const val minHeight = 48.0
        const val paddingHorizontal = 16.0
        const val gap = 12.0
        const val checkSize = 20.0
    }

    companion object {
        operator fun invoke(width: Double, preview: Size, title: Measurable): OptionRowLayout {
            val m = Metrics
            val w = max(0.0, width)
            val titleX = m.paddingHorizontal + preview.width + m.gap
            val titleWidth = max(0.0, w - titleX - m.gap - m.checkSize - m.paddingHorizontal)
            val titleHeight = title.height(titleWidth)
            val height = maxOf(m.minHeight, preview.height, titleHeight, m.checkSize)
            return OptionRowLayout(
                Size(w, height),
                Rect(m.paddingHorizontal, (height - preview.height) / 2, preview.width, preview.height),
                Rect(titleX, (height - titleHeight) / 2, titleWidth, titleHeight),
                Rect(w - m.paddingHorizontal - m.checkSize, (height - m.checkSize) / 2, m.checkSize, m.checkSize),
            )
        }
    }
}

/** The open dropdown's options, stacked with 6 above and below (`bubbleColorDropdown`). */
data class OptionListLayout(val size: Size, val rows: List<Rect>) {
    object Metrics {
        const val paddingVertical = 6.0
    }

    companion object {
        operator fun invoke(width: Double, rows: List<Measurable>): OptionListLayout {
            val w = max(0.0, width)
            var y = Metrics.paddingVertical
            val frames = rows.map { row ->
                val height = row.height(w)
                Rect(0.0, y, w, height).also { y += height }
            }
            return OptionListLayout(Size(w, y + Metrics.paddingVertical), frames)
        }
    }
}

/**
 * A user-bubble swatch (settings/appearance.tsx `bubblePreview`): a 46×28
 * pill with two short lines flush right, as a message bubble reads from
 * across the room.
 */
object BubblePreviewLayout {
    val size = Size(46.0, 28.0)
    const val cornerRadius = 14.0
    const val borderWidth = 1.0
    const val paddingHorizontal = 9.0
    const val lineHeight = 3.0
    const val lineGap = 4.0
    val lineWidths = listOf(18.0, 26.0)

    val lines: List<Rect>
        get() {
            val stack = lineWidths.size * lineHeight + (lineWidths.size - 1) * lineGap
            var y = (size.height - stack) / 2
            return lineWidths.map { width ->
                Rect(size.width - paddingHorizontal - width, y, width, lineHeight).also { y += lineHeight + lineGap }
            }
        }
}

/**
 * Settings › Connected Accounts › Claude Code (settings/connect/claude.tsx,
 * `OAuthViewUnsupported`): a title, one line of instruction and the
 * command in a terminal card, centred on the screen.
 */
data class ConnectCommandLayout(
    val title: Rect,
    val body: Rect,
    val terminal: Rect,
    /** In the terminal card's coordinates. */
    val command: Rect,
) {
    object Metrics {
        const val padding = 20.0
        const val titleMarginBottom = 20.0
        const val bodyMarginBottom = 24.0
        const val terminalPadding = 16.0
        const val terminalMinWidth = 280.0
        const val terminalCornerRadius = 18.0
    }

    companion object {
        operator fun invoke(size: Size, safeArea: LayoutInsets, title: Measurable, body: Measurable, command: Measurable): ConnectCommandLayout {
            val m = Metrics
            val width = max(0.0, size.width - safeArea.horizontal)
            val inner = max(0.0, width - m.padding * 2)
            val titleSize = title.size(inner)
            val bodySize = body.size(inner)
            val commandSize = command.size(max(0.0, inner - m.terminalPadding * 2))
            val terminalWidth = min(inner, max(m.terminalMinWidth, commandSize.width + m.terminalPadding * 2))
            val terminalHeight = commandSize.height + m.terminalPadding * 2
            val height = titleSize.height + m.titleMarginBottom + bodySize.height + m.bodyMarginBottom + terminalHeight

            val available = max(0.0, size.height - safeArea.vertical - m.padding * 2)
            var y = safeArea.top + m.padding + (available - height) / 2
            fun centred(size: Size) = Rect(safeArea.left + (width - size.width) / 2, y, size.width, size.height)
            val titleFrame = centred(titleSize)
            y = titleFrame.maxY + m.titleMarginBottom
            val bodyFrame = centred(bodySize)
            y = bodyFrame.maxY + m.bodyMarginBottom
            val terminal = centred(Size(terminalWidth, terminalHeight))
            return ConnectCommandLayout(titleFrame, bodyFrame, terminal, Rect(m.terminalPadding, m.terminalPadding, commandSize.width, commandSize.height))
        }
    }
}

/**
 * One What's New card (app/(app)/changelog.tsx `cardContent` with
 * MarkdownView): an optional summary line, then paragraphs and bullet
 * lists. Consecutive bullets form one list, 6 apart, with 8 after it;
 * a paragraph keeps 8 above and 10 below.
 */
data class ChangelogBodyLayout(
    val size: Size,
    val summary: Rect?,
    /** One per item, in order. */
    val texts: List<Rect>,
    /** The bullet glyph beside each bulleted item; null for paragraphs. */
    val bullets: List<Rect?>,
) {
    object Metrics {
        const val paddingHorizontal = 16.0
        const val paddingTop = 14.0
        const val paddingBottom = 6.0
        const val summaryMarginBottom = 16.0
        const val paragraphMarginTop = 8.0
        const val paragraphMarginBottom = 10.0
        const val listGap = 6.0
        const val listMarginBottom = 8.0
        const val bulletMarginRight = 8.0
        const val bulletMarginTop = 1.0
    }

    class Item(val text: Measurable, val bulleted: Boolean)

    companion object {
        operator fun invoke(width: Double, summary: Measurable?, items: List<Item>, bullet: Size): ChangelogBodyLayout {
            val m = Metrics
            val w = max(0.0, width)
            val inner = max(0.0, w - m.paddingHorizontal * 2)
            var y = m.paddingTop
            var summaryFrame: Rect? = null
            if (summary != null) {
                val height = summary.height(inner)
                summaryFrame = Rect(m.paddingHorizontal, y, inner, height)
                y += height + m.summaryMarginBottom
            }
            val texts = mutableListOf<Rect>()
            val bullets = mutableListOf<Rect?>()
            items.forEachIndexed { index, item ->
                if (item.bulleted) {
                    val textX = m.paddingHorizontal + bullet.width + m.bulletMarginRight
                    val textWidth = max(0.0, w - textX - m.paddingHorizontal)
                    val height = item.text.height(textWidth)
                    bullets += Rect(m.paddingHorizontal, y + m.bulletMarginTop, bullet.width, bullet.height)
                    texts += Rect(textX, y, textWidth, height)
                    y += max(height, m.bulletMarginTop + bullet.height)
                    val listContinues = index + 1 < items.size && items[index + 1].bulleted
                    y += if (listContinues) m.listGap else m.listMarginBottom
                } else {
                    y += m.paragraphMarginTop
                    val height = item.text.height(inner)
                    bullets += null
                    texts += Rect(m.paddingHorizontal, y, inner, height)
                    y += height + m.paragraphMarginBottom
                }
            }
            return ChangelogBodyLayout(Size(w, y + m.paddingBottom), summaryFrame, texts, bullets)
        }
    }
}

/**
 * One What's New release (changelog.tsx's ItemGroup holding `cardContent`):
 * the title over the card, and the card's body, placed together so a plan
 * made off the main thread has every frame. Each text is measured once.
 */
data class ChangelogEntryLayout(
    /** The title and the card, in the entry's coordinates. */
    val group: ItemGroupLayout,
    /** The summary, texts and bullets, in the card's coordinates. */
    val body: ChangelogBodyLayout,
) {
    val size: Size get() = group.size

    companion object {
        operator fun invoke(
            width: Double,
            title: Measurable,
            summary: Measurable?,
            items: List<ChangelogBodyLayout.Item>,
            bullet: Size,
            maxWidth: Double? = null,
        ): ChangelogEntryLayout {
            val cardWidth = ItemGroupLayout.cardWidth(width, maxWidth)
            val body = ChangelogBodyLayout(cardWidth, summary, items, bullet)
            val group = ItemGroupLayout(ItemGroupLayout.Input(width = width, title = title, rows = listOf(Measurable.fixed(body.size)), maxWidth = maxWidth))
            return ChangelogEntryLayout(group, body)
        }
    }
}

/**
 * What's New's navigation title (app/(app)/_layout.tsx, `changelog`):
 * the title, then the mouse-on-the-phone picture, 6 apart, centred on one
 * line.
 *
 *     What's New |6| 🐭 40×40
 */
data class ChangelogTitleLayout(val size: Size, val title: Rect, val image: Rect) {
    object Metrics {
        const val gap = 6.0
        const val imageSize = 40.0
    }

    companion object {
        /** [maxWidth] is what the bar offers; the title gives way. */
        operator fun invoke(title: Measurable, maxWidth: Double): ChangelogTitleLayout {
            val m = Metrics
            val titleSize = title.size(max(0.0, maxWidth - m.gap - m.imageSize))
            val height = max(titleSize.height, m.imageSize)
            val titleFrame = Rect(0.0, (height - titleSize.height) / 2, titleSize.width, titleSize.height)
            val image = Rect(titleFrame.maxX + m.gap, (height - m.imageSize) / 2, m.imageSize, m.imageSize)
            return ChangelogTitleLayout(Size(image.maxX, height), titleFrame, image)
        }
    }
}

/** A message centred on an otherwise empty screen, 40 in from every edge (changelog.tsx `emptyState`). */
data class CenteredMessageLayout(val message: Rect) {
    object Metrics {
        const val padding = 40.0
    }

    companion object {
        operator fun invoke(size: Size, safeArea: LayoutInsets, message: Measurable): CenteredMessageLayout {
            val padding = Metrics.padding
            val width = max(0.0, size.width - safeArea.horizontal - padding * 2)
            val height = max(0.0, size.height - safeArea.vertical - padding * 2)
            val text = message.size(width)
            return CenteredMessageLayout(
                Rect(
                    safeArea.left + padding + (width - text.width) / 2,
                    safeArea.top + padding + (height - text.height) / 2,
                    text.width,
                    text.height,
                ),
            )
        }
    }
}
