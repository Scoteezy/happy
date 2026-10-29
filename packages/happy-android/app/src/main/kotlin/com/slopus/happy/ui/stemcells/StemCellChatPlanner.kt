package com.slopus.happy.ui.stemcells

import android.content.res.Resources
import android.graphics.BitmapFactory
import android.media.ExifInterface
import android.text.Layout
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextPaint
import android.text.style.MetricAffectingSpan
import com.slopus.happy.R
import com.slopus.happy.chat.markdown.parseMarkdown
import com.slopus.happy.icons.Icon
import com.slopus.happy.layout.ChatTextStyles
import com.slopus.happy.layout.ColorToken
import com.slopus.happy.layout.FontFace
import com.slopus.happy.layout.Palette
import com.slopus.happy.layout.Rect
import com.slopus.happy.layout.Size
import com.slopus.happy.layout.StemCellsPalette
import com.slopus.happy.layout.TextScale
import com.slopus.happy.layout.TextStyle
import com.slopus.happy.layout.stemCellsActivityBody
import com.slopus.happy.layout.stemCellsActivityHeader
import com.slopus.happy.layout.stemCellsActivityText
import com.slopus.happy.layout.stemCellsActivityVerb
import com.slopus.happy.layout.stemCellsHeader
import com.slopus.happy.layout.stemCellsText
import com.slopus.happy.layout.stemcells.StemCellAgentMessageLayout
import com.slopus.happy.layout.stemcells.StemCellMediaFlow
import com.slopus.happy.layout.stemcells.StemCellMediaLayout
import com.slopus.happy.layout.stemcells.StemCellMediaTreeLayout
import com.slopus.happy.layout.stemcells.StemCellMessageLayout
import com.slopus.happy.layout.stemcells.StemCellResultCardLayout
import com.slopus.happy.layout.userMessageText
import com.slopus.happy.stemcells.HappyReferences
import com.slopus.happy.stemcells.StemCellArtifact
import com.slopus.happy.stemcells.StemCellChatItem
import com.slopus.happy.stemcells.StemCellChatItem.Piece
import com.slopus.happy.stemcells.StemCellMarkdown
import com.slopus.happy.stemcells.StemCellMedia
import com.slopus.happy.stemcells.StemCellMediaTree
import com.slopus.happy.stemcells.StemCellReferences
import com.slopus.happy.ui.chat.ChatBox
import com.slopus.happy.ui.chat.ChatDrawItem
import com.slopus.happy.ui.chat.ChatHit
import com.slopus.happy.ui.chat.ChatMedia
import com.slopus.happy.ui.chat.ChatMermaidHeights
import com.slopus.happy.ui.chat.ChatRowAction
import com.slopus.happy.ui.chat.ChatRowPlan
import com.slopus.happy.ui.chat.ChatStrings
import com.slopus.happy.ui.chat.ChatTextEnv
import com.slopus.happy.ui.chat.FaceSpan
import com.slopus.happy.ui.chat.MarkdownPiece
import com.slopus.happy.ui.chat.MeasuredText
import com.slopus.happy.ui.chat.TokenColorSpan
import com.slopus.happy.ui.text.TextLayouts
import java.io.ByteArrayInputStream
import kotlin.math.max
import kotlin.math.min

/**
 * Plans a Stem Cells conversation's rows off the main thread (happy-ios
 * `StemCellChatPlanner`), with the chat's own pieces: a person's message in
 * its bubble on the right with its photos, the agent's markdown and the
 * pictures, recordings and cards it presented, and another agent's message
 * on one line. A row is planned again only when its item, the width or the
 * text size changed.
 */
class StemCellChatPlanner(private val resources: Resources) {
    data class Key(
        val width: Double,
        val maxContentWidth: Double,
        val env: ChatTextEnv,
        /** What the references messages make read as now. */
        val references: StemCellReferences = StemCellReferences(),
        /** The agents this one spawned, by ID, as titled. */
        val subagentTitles: Map<String, String> = emptyMap(),
    )

    private class Cached(val item: StemCellChatItem, val key: Key, val copied: Boolean, val opened: Boolean, val plan: ChatRowPlan)

    private var cache = HashMap<String, Cached>()

    /** `opened` are the other agents' messages opened to read. */
    @Synchronized
    fun plans(items: List<StemCellChatItem>, key: Key, copied: String?, opened: Set<String> = emptySet()): List<ChatRowPlan> {
        val next = HashMap<String, Cached>()
        val plans = ArrayList<ChatRowPlan>()
        for (item in items) {
            val isCopied = copied == item.id
            val isOpened = item.id in opened
            val cached = cache[item.id]
            val plan = if (cached != null && cached.item == item && cached.key == key && cached.copied == isCopied && cached.opened == isOpened) {
                cached.plan
            } else {
                plan(item, key, isOpened)
            }
            next[item.id] = Cached(item, key, isCopied, isOpened, plan)
            if (plan.size.height > 0) plans += plan
        }
        cache = next
        return plans
    }

    fun plan(item: StemCellChatItem, key: Key, opened: Boolean = false): ChatRowPlan {
        val role = item.role
        if (role is StemCellChatItem.Role.Collaborator) return collaborator(item, role.agentId, role.explanation, key, opened)
        if (item.pieces.isEmpty()) return ChatRowPlan(item.id, Size(key.width, 0.0), emptyList())
        val text = HappyReferences.plain(item.text, key.references::view)
        return when (role) {
            StemCellChatItem.Role.User -> user(item, text, key)
            else -> agent(item, text, key)
        }
    }

    private sealed interface Draft {
        class Markdown(val piece: MarkdownPiece) : Draft
        class Gallery(val media: List<StemCellMedia>) : Draft
        class Tree(val tree: StemCellChatMediaPlan.Tree) : Draft
        class Card(val card: StemCellChatMediaPlan.Card) : Draft
        class Story(val id: String) : Draft
        /** Words the planner writes, read but not selectable. */
        class Line(val piece: MarkdownPiece) : Draft
    }

    /**
     * Desktop's agent text: Figtree 16/24 markdown, soft breaks as spaces,
     * blocks 8 apart, no copy button (a long press selects instead); its
     * pictures and recordings where they were presented.
     */
    private fun agent(item: StemCellChatItem, text: String, key: Key): ChatRowPlan {
        val env = key.env
        val parts = ArrayList<StemCellMessageLayout.Part>()
        val drafts = ArrayList<Draft>()
        val spoken = ArrayList<String>()
        if (text.isNotEmpty()) spoken += text
        for (piece in item.pieces) {
            when (piece) {
                is Piece.Text -> {
                    val resolved = StemCellMarkdown.joiningSoftBreaks(HappyReferences.markdown(piece.text, key.references::view))
                    val markdown = markdown(resolved, Palette.text, item.id, env, selectable = true)
                    drafts += Draft.Markdown(markdown)
                    parts += StemCellMessageLayout.Part.Markdown(markdown.blocks)
                }
                is Piece.Gallery -> {
                    drafts += Draft.Gallery(piece.media)
                    parts += StemCellMessageLayout.Part.Media(piece.media.map(::layoutItem), piece.columns)
                }
                is Piece.Tree -> {
                    val planned = StemCellChatMediaPlan.tree(piece.media, piece.tree, env)
                    drafts += Draft.Tree(planned)
                    parts += StemCellMessageLayout.Part.Tree(planned.boxes)
                }
                is Piece.File -> {
                    drafts += Draft.Card(StemCellChatMediaPlan.card(piece.media, resources))
                    parts += StemCellMessageLayout.Part.Card
                }
                is Piece.Artifact -> {
                    drafts += Draft.Card(StemCellChatMediaPlan.card(piece.artifact, resources))
                    parts += StemCellMessageLayout.Part.Card
                }
                is Piece.Story -> {
                    drafts += Draft.Story(piece.id)
                    parts += StemCellMessageLayout.Part.Story
                }
                is Piece.SidebarProposal -> {
                    // The proposal's own card is not drawn here yet: its line says it is on its way.
                    val line = resources.getString(R.string.stem_proposal_loading)
                    val markdown = markdown(line, StemCellsPalette.stemCellsCardDetail, item.id, env, selectable = false)
                    drafts += Draft.Line(markdown)
                    parts += StemCellMessageLayout.Part.Markdown(markdown.blocks)
                    spoken += line
                }
                is Piece.Picture -> Unit
            }
        }
        val layout = StemCellMessageLayout.agent(key.width, key.maxContentWidth, parts)
        val items = ArrayList<ChatDrawItem>()
        val hits = ArrayList<ChatHit>()
        fun media(one: StemCellMedia, frame: Rect) {
            items += ChatDrawItem.Media(ChatMedia(one.key, one.isVideo, one.item.thumbhash, mediaLabel(one)), frame)
            hits.add(0, ChatHit(frame, ChatRowAction.OpenMedia(one.key)))
        }
        fun card(card: StemCellChatMediaPlan.Card, frame: Rect) {
            val (drawn, hit) = StemCellChatMediaPlan.draw(card, frame, env)
            items += drawn
            hits.add(0, hit)
        }
        for ((placed, draft) in layout.parts.zip(drafts)) {
            when {
                placed is StemCellMessageLayout.Placed.Markdown && draft is Draft.Markdown -> {
                    val (drawn, drawnHits) = draft.piece.draw(placed.layout, placed.origin.x, placed.origin.y)
                    items += drawn
                    hits += drawnHits
                }
                placed is StemCellMessageLayout.Placed.Markdown && draft is Draft.Line -> {
                    items += draft.piece.draw(placed.layout, placed.origin.x, placed.origin.y).first
                }
                placed is StemCellMessageLayout.Placed.Media && draft is Draft.Gallery -> {
                    for ((one, frame) in draft.media.zip(placed.frames)) media(one, frame)
                }
                placed is StemCellMessageLayout.Placed.Tree && draft is Draft.Tree -> {
                    val origin = placed.origin
                    for ((index, frame) in placed.layout.items.entries.sortedBy { it.key }) {
                        val one = draft.tree.media.getOrNull(index) ?: continue
                        val shifted = frame.offsetBy(origin.x, origin.y)
                        if (one.isVisual) media(one, shifted) else card(StemCellChatMediaPlan.card(one, resources), shifted)
                    }
                    for ((id, frame) in placed.layout.texts.entries.sortedBy { it.key }) {
                        val measured = draft.tree.texts[id] ?: continue
                        val shifted = frame.offsetBy(origin.x, origin.y)
                        items += ChatDrawItem.Text(measured.layout(shifted), shifted)
                    }
                }
                placed is StemCellMessageLayout.Placed.Card && draft is Draft.Card -> card(draft.card, placed.frame)
                placed is StemCellMessageLayout.Placed.Story && draft is Draft.Story -> items += ChatDrawItem.Story(draft.id, placed.frame)
                else -> Unit
            }
        }
        return ChatRowPlan(item.id, layout.size, items, hits, accessibilityLabel = spoken.joinToString("\n\n"))
    }

    /**
     * Desktop's own message: the text as written (Figtree 16/24), lines and
     * all, in the accent bubble at the right, its photos under it.
     */
    private fun user(item: StemCellChatItem, text: String, key: Key): ChatRowPlan {
        val env = key.env
        val body = if (text.isEmpty()) null else MeasuredText(ChatStrings.string(text, Palette.userMessageText), ChatTextStyles.stemCellsText, env)
        val pictures = item.pieces.filterIsInstance<Piece.Picture>().map { it.id to pictureSize(it.data) }
        val failure = item.delivery is StemCellChatItem.Delivery.Failed
        val layout = StemCellMessageLayout.user(key.width, key.maxContentWidth, body?.measurable, pictures.map { it.second }, failure)
        val items = ArrayList<ChatDrawItem>()
        val hits = ArrayList<ChatHit>()
        layout.bubble?.let { bubble ->
            items += ChatDrawItem.Box(ChatBox(bubble, StemCellsPalette.stemCellsUserBubble, radius = StemCellMessageLayout.Metrics.bubbleRadius))
            hits += ChatHit(bubble, ChatRowAction.CopyMenu(text), hold = true)
        }
        val textFrame = layout.text
        if (body != null && textFrame != null) items += ChatDrawItem.Text(body.layout(textFrame), textFrame)
        val pictureLabel = resources.getString(R.string.stem_picture)
        for ((picture, frame) in pictures.zip(layout.pictures)) {
            val mediaKey = StemCellMediaLoader.sentKey(picture.first)
            items += ChatDrawItem.Media(ChatMedia(mediaKey, false, null, pictureLabel), frame)
            hits += ChatHit(frame, ChatRowAction.OpenMedia(mediaKey))
        }
        val status = layout.status
        val failed = resources.getString(R.string.stem_send_failed)
        if (failure && status != null) {
            val line = MeasuredText(ChatStrings.string(failed, Palette.statusError), ChatTextStyles.pendingStatus, env, maxLines = 1, alignment = Layout.Alignment.ALIGN_OPPOSITE)
            items += ChatDrawItem.Text(line.layout(status), status)
            hits.add(0, ChatHit(status, ChatRowAction.Retry(item.id)))
        }
        // On its way, or waiting for the agent to take it: dimmed, never moved.
        val dimmed = item.delivery == StemCellChatItem.Delivery.Sending || item.delivery == StemCellChatItem.Delivery.Pending
        val label = listOf(text, if (failure) failed else "").filter { it.isNotEmpty() }.joinToString(", ")
        return ChatRowPlan(item.id, layout.size, items, hits, alpha = if (dimmed) 0.6 else 1.0, accessibilityLabel = label)
    }

    /**
     * happy-desktop's `AgentMessageActivity`: who it came from on one line,
     * the message itself behind it. With the sender's one line on what it
     * is, the line is the sender's name and that; without, "Message from"
     * the sender. A sender nobody here can name shows by its ID's start.
     */
    private fun collaborator(item: StemCellChatItem, agentId: String, explanation: String?, key: Key, opened: Boolean): ChatRowPlan {
        val env = key.env
        val name = key.references.agents[agentId]?.let { it.label.ifEmpty { resources.getString(R.string.stem_untitled_agent) } }
            ?: key.subagentTitles[agentId]?.let { it.ifEmpty { resources.getString(R.string.stem_subagent) } }
            ?: if (agentId.length > 10) "${agentId.take(8)}…" else agentId
        val verb = when {
            explanation == null -> resources.getString(R.string.stem_agent_message)
            // A line that names its sender already says who it is from.
            agentId in explanation -> null
            else -> name
        }
        // Desktop names a bare ID in the line by its agent's title.
        val line = explanation?.let { HappyReferences.plain(it, key.references::view).replace(agentId, name) }
            ?: resources.getString(R.string.stem_agent_message_from, name)
        val body = item.text
        val opens = body.isNotEmpty()
        val verbText = verb?.let { MeasuredText(ChatStrings.string(it, Palette.textSecondary), ChatTextStyles.stemCellsActivityVerb, env, maxLines = 1) }
        val lineText = MeasuredText(ChatStrings.string(line, Palette.text), ChatTextStyles.stemCellsActivityText, env, maxLines = 1)
        val markdown = if (opened && opens) {
            MarkdownPiece(
                parseMarkdown(StemCellMarkdown.joiningSoftBreaks(HappyReferences.markdown(body, key.references::view))), Palette.textSecondary, env,
                diagrams = ChatMermaidHeights(item.id), body = ChatTextStyles.stemCellsActivityBody, header = ChatTextStyles::stemCellsActivityHeader,
            )
        } else {
            null
        }
        val layout = StemCellAgentMessageLayout(
            key.width, key.maxContentWidth,
            verbWidth = verbText?.unwrapped()?.second?.width,
            textWidth = lineText.unwrapped().second.width,
            opens = opens, body = markdown?.blocks,
        )
        val items = ArrayList<ChatDrawItem>()
        items += ChatDrawItem.Glyph(StemCellsGlyphs.icon("agents"), StemCellAgentMessageLayout.Metrics.glyph, layout.glyph, Palette.textSecondary)
        val verbFrame = layout.verb
        if (verbText != null && verbFrame != null) items += ChatDrawItem.Text(verbText.layout(verbFrame), verbFrame)
        items += ChatDrawItem.Text(lineText.layout(layout.text), layout.text)
        layout.chevron?.let {
            items += ChatDrawItem.Glyph(StemCellsGlyphs.icon(if (opened) "chevron-down" else "chevron-right"), StemCellAgentMessageLayout.Metrics.chevron, it, Palette.textSecondary)
        }
        val hits = ArrayList<ChatHit>()
        val placed = layout.body
        if (markdown != null && placed != null) {
            val (drawn, drawnHits) = markdown.draw(placed, layout.bodyOrigin.x, layout.bodyOrigin.y)
            items += drawn
            hits += drawnHits
        }
        // The whole line opens and closes it, a finger's room above and below.
        if (opens) {
            val pad = StemCellAgentMessageLayout.Metrics.paddingV
            val header = layout.header
            hits.add(0, ChatHit(Rect(header.minX, header.minY - pad, header.width, header.height + 2 * pad), ChatRowAction.ToggleTool(item.id)))
        }
        val spoken = listOfNotNull(verb, line).joinToString(" ") + if (opened && opens) "\n\n" + HappyReferences.plain(body, key.references::view) else ""
        return ChatRowPlan(item.id, layout.size, items, hits, accessibilityLabel = spoken)
    }

    private fun markdown(text: String, color: ColorToken, row: String, env: ChatTextEnv, selectable: Boolean) = MarkdownPiece(
        parseMarkdown(text), color, env, selectable = selectable, diagrams = ChatMermaidHeights(row), source = text,
        body = ChatTextStyles.stemCellsText, header = ChatTextStyles::stemCellsHeader,
    )

    private fun mediaLabel(media: StemCellMedia) =
        resources.getString(R.string.stem_media_label, resources.getString(if (media.isVideo) R.string.stem_video else R.string.stem_picture), media.item.name)

    companion object {
        /** What the flow knows of a presented item before its bytes arrive. */
        fun layoutItem(media: StemCellMedia): StemCellMediaLayout.Item {
            val item = media.item
            return StemCellMediaLayout.Item(media.isVideo, StemCellMediaFlow.Image(item.width, item.height, item.scale, StemCellMedia.hashedRatio(item.thumbhash)))
        }

        /** A sent photo's upright pixel size, read from its header alone. */
        fun pictureSize(data: ByteArray): StemCellMediaFlow.Image {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(data, 0, data.size, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return StemCellMediaFlow.Image()
            // EXIF orientations 5 to 8 turn the picture on its side.
            val orientation = runCatching { ExifInterface(ByteArrayInputStream(data)).getAttributeInt(ExifInterface.TAG_ORIENTATION, 1) }.getOrDefault(1)
            val width = bounds.outWidth.toDouble()
            val height = bounds.outHeight.toDouble()
            return if (orientation in 5..8) StemCellMediaFlow.Image(height, width) else StemCellMediaFlow.Image(width, height)
        }
    }
}

/**
 * What a conversation draws for media beyond a flow of pictures (happy-ios
 * `StemCellChatMediaPlan`): a layout the agent wrote (`MediaTree`), and
 * desktop's result cards for documents, files and artifacts. Planned off
 * the main thread with the rest of a row.
 */
object StemCellChatMediaPlan {
    /** A tree measured and ready to place: its boxes, and its paragraphs by id. */
    class Tree(val media: List<StemCellMedia>, val boxes: List<StemCellMediaTreeLayout.Box>, val texts: Map<Int, MeasuredText>)

    fun tree(media: List<StemCellMedia>, tree: StemCellMediaTree, env: ChatTextEnv): Tree {
        val texts = HashMap<Int, MeasuredText>()
        fun boxes(blocks: List<StemCellMediaTree.Block>): List<StemCellMediaTreeLayout.Box> = blocks.mapNotNull { block ->
            when (block) {
                is StemCellMediaTree.Block.Container -> StemCellMediaTreeLayout.Box.Container(StemCellMediaTreeLayout.Style.from(block.style), boxes(block.children))
                is StemCellMediaTree.Block.Item -> media.getOrNull(block.index)?.let {
                    StemCellMediaTreeLayout.Box.Item(block.index, StemCellMediaTreeLayout.Style.from(block.style), item(it))
                }
                is StemCellMediaTree.Block.Paragraph -> {
                    val id = texts.size
                    val text = paragraph(block.runs, block.align, env)
                    texts[id] = text
                    StemCellMediaTreeLayout.Box.Text(id, text.measurable)
                }
            }
        }
        return Tree(media, boxes(tree.blocks), texts)
    }

    /**
     * An item's shape and natural size before its bytes arrive: as stated,
     * else its thumbhash's shape; a recording that says nothing is 16:9.
     */
    fun item(media: StemCellMedia): StemCellMediaTreeLayout.Item {
        val item = media.item
        if (!media.isVisual) return StemCellMediaTreeLayout.Item(true, 1.0, null)
        var natural: Size? = null
        var ratio = if (media.isVideo) 16.0 / 9 else 4.0 / 3
        val width = item.width
        val height = item.height
        if (width != null && height != null && width > 0 && height > 0) {
            val scale = item.scale?.takeIf { it > 0 } ?: 1.0
            natural = Size(width / scale, height / scale)
            ratio = width / height
        } else {
            StemCellMedia.hashedRatio(item.thumbhash)?.let { ratio = it }
        }
        return StemCellMediaTreeLayout.Item(false, ratio, natural)
    }

    /** A run slanted, as `em` is. */
    private class ObliqueSpan : MetricAffectingSpan() {
        override fun updateMeasureState(tp: TextPaint) { tp.textSkewX = -0.2f }
        override fun updateDrawState(tp: TextPaint) { tp.textSkewX = -0.2f }
    }

    /**
     * `.happy-media-tree`'s type: 14 on 20, its steps 12/16, 16/24 and a
     * 20/28 semibold title; strong is bold, em slanted, code mono.
     */
    fun paragraph(runs: List<StemCellMediaTree.Run>, align: String?, env: ChatTextEnv): MeasuredText {
        val scale = env.scale.multiplier
        val builder = SpannableStringBuilder()
        var tallest = 0.0
        for (run in runs) {
            val (size, line) = when (run.size) {
                "small" -> 12.0 to 16.0
                "large" -> 16.0 to 24.0
                "title" -> 20.0 to 28.0
                else -> 14.0 to 20.0
            }
            val face = when {
                run.code -> if (run.bold) FontFace.JetBrainsMonoBold else FontFace.JetBrainsMono
                run.bold -> FontFace.FigtreeBold
                run.size == "title" -> FontFace.FigtreeSemiBold
                else -> FontFace.Figtree
            }
            val shown = if (run.code) size * 0.9 else size
            tallest = max(tallest, line)
            val color = when (run.tone) {
                "muted" -> StemCellsPalette.stemCellsTreeMuted
                "accent" -> StemCellsPalette.stemCellsTreeAccent
                else -> StemCellsPalette.stemCellsTreeText
            }
            val start = builder.length
            builder.append(run.text)
            val end = builder.length
            if (end == start) continue
            builder.setSpan(FaceSpan(face, (shown * scale * env.density).toFloat()), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            builder.setSpan(TokenColorSpan(color), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            if (run.italic) builder.setSpan(ObliqueSpan(), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        val alignment = when (align) {
            "center" -> Layout.Alignment.ALIGN_CENTER
            "right", "end" -> Layout.Alignment.ALIGN_OPPOSITE
            else -> Layout.Alignment.ALIGN_NORMAL
        }
        return MeasuredText(builder, TextStyle(FontFace.Figtree, 14.0, if (tallest > 0) tallest else 20.0), env, alignment = alignment)
    }

    // Cards

    class Card(val glyph: Icon, val title: String, val detail: String, val action: ChatRowAction)

    fun card(media: StemCellMedia, resources: Resources) = Card(
        Icon.ionicons("document-outline"), media.item.name,
        fileDetail(media.item.kind, media.item.mimeType, media.item.size, resources),
        ChatRowAction.OpenMedia(media.key),
    )

    fun card(artifact: StemCellArtifact, resources: Resources) = Card(
        glyph(artifact.type),
        artifact.title.ifEmpty { resources.getString(R.string.stem_untitled_artifact) },
        artifactDetail(artifact.type, artifact.version, resources),
        ChatRowAction.OpenArtifact(artifact),
    )

    /** Desktop's `ArtifactTypeGlyph`, from the glyphs the app carries. */
    fun glyph(type: String): Icon = when (type) {
        "markdown" -> Icon.ionicons("document-text-outline")
        "html" -> Icon.ionicons("globe-outline")
        "image" -> Icon.ionicons("image-outline")
        "image_series" -> Icon.ionicons("images")
        "video" -> Icon.ionicons("videocam")
        "document" -> Icon.ionicons("document-outline")
        else -> Icon.ionicons("cube-outline")
    }

    /** What kind of file a presented item is, in a word, from its media type. */
    fun fileKind(kind: String, mimeType: String, resources: Resources): String {
        if (mimeType == "application/pdf") return resources.getString(R.string.stem_file_pdf)
        if (kind == "document") return resources.getString(R.string.stem_file_document)
        val parts = mimeType.split("/", limit = 2)
        return when (parts.first()) {
            "image" -> resources.getString(R.string.stem_file_image)
            "video" -> resources.getString(R.string.stem_file_video)
            "audio" -> resources.getString(R.string.stem_file_audio)
            "text" -> if (parts.size > 1) (if (parts[1] == "plain") resources.getString(R.string.stem_file_text) else parts[1].uppercase()) else resources.getString(R.string.stem_file_file)
            else -> resources.getString(R.string.stem_file_file)
        }
    }

    /** A byte count in the unit a person reads it in (`artifactSizeText`). */
    fun size(bytes: Double): String {
        fun tenth(value: Double): String {
            val rounded = Math.round(value * 10) / 10.0
            return if (rounded == Math.rint(rounded)) rounded.toLong().toString() else rounded.toString()
        }
        return when {
            bytes < 1024 -> "${bytes.toLong()} B"
            bytes < 1024 * 1024 -> "${tenth(bytes / 1024)} KB"
            bytes < 1024.0 * 1024 * 1024 -> "${tenth(bytes / (1024 * 1024))} MB"
            else -> "${tenth(bytes / (1024.0 * 1024 * 1024))} GB"
        }
    }

    fun fileDetail(kind: String, mimeType: String, size: Double?, resources: Resources) =
        listOfNotNull(fileKind(kind, mimeType, resources), size?.let(::size)).joinToString(" · ")

    fun artifactKind(type: String, resources: Resources): String = resources.getString(
        when (type) {
            "markdown" -> R.string.stem_artifact_markdown
            "html" -> R.string.stem_artifact_html
            "image" -> R.string.stem_artifact_image
            "image_series" -> R.string.stem_artifact_image_series
            "video" -> R.string.stem_artifact_video
            "document" -> R.string.stem_artifact_document
            else -> R.string.stem_artifact_other
        },
    )

    /** A creation's card names its kind; a change's, the version it made. */
    fun artifactDetail(type: String, version: Int, resources: Resources) =
        if (version == 1) artifactKind(type, resources) else resources.getString(R.string.stem_artifact_version, artifactKind(type, resources), version)

    /** The card's boxes, glyph, title and detail in `frame`, and its tap. */
    fun draw(card: Card, frame: Rect, env: ChatTextEnv): Pair<List<ChatDrawItem>, ChatHit> {
        val layout = StemCellResultCardLayout(frame, opens = true)
        val scale = min(env.scale.multiplier, 1.2)
        val fixed = TextLayouts.Env(env.density, TextScale.Standard)
        // One line exactly the frame's height, the glyphs centred in it.
        fun line(text: String, face: FontFace, size: Double, color: ColorToken, frame: Rect): ChatDrawItem {
            val measured = MeasuredText(ChatStrings.string(text, color), TextStyle(face, size * scale, frame.height), fixed, maxLines = 1)
            return ChatDrawItem.Text(measured.layout(frame), frame)
        }
        val items = arrayListOf(
            ChatDrawItem.Box(ChatBox(frame, StemCellsPalette.stemCellsCardFill, StemCellsPalette.stemCellsDivider, 1.0, StemCellResultCardLayout.RADIUS)),
            ChatDrawItem.Box(ChatBox(layout.chip, StemCellsPalette.stemCellsCardChip, radius = StemCellResultCardLayout.CHIP_RADIUS)),
            ChatDrawItem.Glyph(card.glyph, StemCellResultCardLayout.GLYPH, layout.glyph, StemCellsPalette.stemCellsCardDetail),
            line(card.title, FontFace.FigtreeSemiBold, 14.0, StemCellsPalette.stemCellsCardTitle, layout.title),
            line(card.detail, FontFace.FigtreeMedium, 12.0, StemCellsPalette.stemCellsCardDetail, layout.detail),
        )
        layout.chevron?.let { items += ChatDrawItem.Glyph(Icon.ionicons("chevron-forward"), 16.0, it, StemCellsPalette.stemCellsCardDetail) }
        return items to ChatHit(frame, card.action)
    }
}
