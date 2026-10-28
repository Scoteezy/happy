package com.slopus.happy.ui.chat

import com.slopus.happy.chat.AgentQuestionOption
import com.slopus.happy.chat.InlineQuestion
import com.slopus.happy.chat.InlineQuestionForm
import com.slopus.happy.chat.InlineQuestionSelections
import com.slopus.happy.icons.Icon
import com.slopus.happy.layout.ChatTextStyles
import com.slopus.happy.layout.LayoutInsets
import com.slopus.happy.layout.Measurable
import com.slopus.happy.layout.Palette
import com.slopus.happy.layout.Rect
import com.slopus.happy.layout.Size
import com.slopus.happy.layout.chat.ChatAccessoryRowLayout
import com.slopus.happy.layout.chat.ChatStackLayout
import com.slopus.happy.layout.onAccent
import kotlin.math.max

/** A question tool's form, with what the reader has done to it (happy-ios `ChatQuestionState`). */
data class ChatQuestionState(
    val form: InlineQuestionForm,
    val selections: InlineQuestionSelections = InlineQuestionSelections(),
    /** The answers sent from here, before the agent's own record of them. */
    val sent: Map<String, List<String>>? = null,
    val submitting: Boolean = false,
) {
    /** What the summary shows once answered. */
    val submitted: Map<String, List<String>>? get() = form.submitted ?: sent
    val canInteract: Boolean get() = form.canInteract && submitted == null
}

/**
 * InlineQuestionForm.tsx (happy-ios `ChatQuestionRenderer`): the questions
 * with their options and a submit button while the tool waits, the
 * "Header: answer" summary once answered.
 */
internal class ChatQuestionRenderer(private val submitTitle: String) {
    fun form(state: ChatQuestionState, toolId: String, env: ChatTextEnv): ChatPiece {
        val submitted = state.submitted
        if (submitted != null) {
            return ChatPiece.stack(state.form.questions.map { submittedLine(it, submitted[it.id], env) }, gap = 8.0)
        }
        val sections = state.form.questions.map { questionSection(it, state, toolId, env) }.toMutableList()
        if (state.canInteract) sections += submitButton(state, toolId, env)
        return ChatPiece.stack(sections, gap = 16.0)
    }

    /** "Header: answer", the header in secondary semibold. */
    private fun submittedLine(question: InlineQuestion, answer: List<String>?, env: ChatTextEnv): ChatStackEntry {
        val header = MeasuredText(ChatStrings.string("${question.header}:", Palette.textSecondary), ChatTextStyles.inlineSubmittedHeader, env, maxLines = 1)
        val joined = answer?.joinToString(", ").orEmpty()
        val value = MeasuredText(ChatStrings.string(joined.ifEmpty { "—" }, Palette.text), ChatTextStyles.inlineSubmittedValue, env)
        fun layout(width: Double) = ChatAccessoryRowLayout(width, header.size(width / 2), value.measurable, alignment = ChatAccessoryRowLayout.Alignment.Top)
        val row = ChatPiece(Measurable { layout(it).size }) { frame ->
            val layout = layout(frame.width)
            val headerFrame = layout.leading!!.offsetBy(frame.minX, frame.minY)
            val valueFrame = layout.text.offsetBy(frame.minX, frame.minY)
            listOf(ChatDrawItem.Text(header.layout(headerFrame), headerFrame), ChatDrawItem.Text(value.layout(valueFrame), valueFrame)) to emptyList()
        }
        return ChatStackEntry(row)
    }

    /** The header chip, the question, then its options 4 apart. */
    private fun questionSection(question: InlineQuestion, state: ChatQuestionState, toolId: String, env: ChatTextEnv): ChatStackEntry {
        val chip = MeasuredText(ChatStrings.string(question.header.uppercase(), Palette.textSecondary), ChatTextStyles.inlineQuestionHeader.copy(uppercase = false), env, maxLines = 1)
        val text = MeasuredText(ChatStrings.string(question.question, Palette.text), ChatTextStyles.inlineQuestionText, env)
        val options = question.options.mapIndexed { index, option ->
            optionButton(
                option, state.selections.isSelected(question, index), question.multiSelect, state.canInteract,
                ChatRowAction.QuestionOption(toolId, question.id, index), env,
            )
        }
        val entries = listOf(
            ChatStackEntry(
                ChatPiece.text(chip), marginBottom = 4.0, padding = LayoutInsets(4, 8, 4, 8), placement = ChatStackLayout.Placement.Leading,
                background = ChatBox(Rect.Zero, Palette.surfaceHighest, radius = 4.0),
            ),
            ChatStackEntry(ChatPiece.text(text), marginBottom = 8.0),
            ChatStackEntry(ChatPiece.stack(options, gap = 4.0)),
        )
        return ChatStackEntry(ChatPiece.stack(entries, gap = 8.0))
    }

    /** An outlined option: a radio or a checkbox, then the label and its description. 12 in, 10 between, at least 44 tall. */
    private fun optionButton(
        option: AgentQuestionOption, selected: Boolean, multiSelect: Boolean, enabled: Boolean, action: ChatRowAction, env: ChatTextEnv,
    ): ChatStackEntry {
        val label = MeasuredText(ChatStrings.string(option.label, Palette.text), ChatTextStyles.inlineOptionLabel, env)
        val description = option.description?.takeIf { it.isNotEmpty() }?.let {
            MeasuredText(ChatStrings.string(it, Palette.textSecondary), ChatTextStyles.inlineOptionDescription, env)
        }
        val column = ChatPiece.stack(listOfNotNull(ChatStackEntry(ChatPiece.text(label)), description?.let { ChatStackEntry(ChatPiece.text(it), marginTop = 2.0) }))
        val mark = Size(20.0, 22.0)
        val alpha = if (enabled) 1.0 else 0.6
        fun layout(width: Double) = ChatAccessoryRowLayout(width, mark, column.measurable, gap = 10.0, alignment = ChatAccessoryRowLayout.Alignment.Top)
        val row = ChatPiece(Measurable { layout(it).size }) { frame ->
            val layout = layout(frame.width)
            // The mark sits 2 down (marginTop: 2).
            val markFrame = Rect(frame.minX, frame.minY + 2, 20.0, 20.0)
            val items = ArrayList<ChatDrawItem>()
            if (multiSelect) {
                items += ChatDrawItem.Box(
                    ChatBox(markFrame, if (selected) Palette.radioActive else null, if (selected) Palette.radioActive else Palette.textSecondary, 2.0, 4.0, alpha = alpha),
                )
                if (selected) items += ChatDrawItem.Glyph(Icon.ionicons("checkmark"), 14.0, markFrame, Palette.onAccent)
            } else {
                items += ChatDrawItem.Box(ChatBox(markFrame, null, if (selected) Palette.radioActive else Palette.textSecondary, 2.0, 10.0, alpha = alpha))
                if (selected) items += ChatDrawItem.Box(ChatBox(markFrame.insetBy(5.0, 5.0), Palette.radioActive, radius = 5.0, alpha = alpha))
            }
            val (drawn, _) = column.draw(layout.text.offsetBy(frame.minX, frame.minY))
            items += if (enabled) drawn else drawn.map { it.dimmed(alpha) }
            items to emptyList()
        }
        val background = ChatBox(
            Rect.Zero, if (selected) Palette.surfaceHighest else Palette.surface, if (selected) Palette.radioActive else Palette.divider, 1.0, 8.0, alpha = alpha,
        )
        val piece = ChatPiece(Measurable { width ->
            val size = row.measurable.size(width)
            // minHeight 44 with 12 above and below: 20 inside.
            Size(size.width, max(size.height, 44.0 - 24))
        }) { frame ->
            val (drawn, _) = row.draw(frame)
            drawn to if (enabled) listOf(ChatHit(frame.insetBy(-12.0, -12.0), action)) else emptyList()
        }
        return ChatStackEntry(piece, padding = LayoutInsets(12, 12, 12, 12), background = background)
    }

    /** "Submit Answer", right-aligned; outlined in blue once every required question has an answer, a spinner while it goes. */
    private fun submitButton(state: ChatQuestionState, toolId: String, env: ChatTextEnv): ChatStackEntry {
        val ready = state.selections.allAnswered(state.form.questions) && !state.submitting
        val title = MeasuredText(ChatStrings.string(submitTitle, Palette.text), ChatTextStyles.inlineSubmit, env, maxLines = 1)
        val alpha = if (ready) 1.0 else 0.5
        val button = ChatPiece(Measurable { width ->
            val size = title.size(width)
            Size(size.width, max(size.height, 44.0 - 24))
        }) { frame ->
            val items = ArrayList<ChatDrawItem>()
            if (state.submitting) {
                items += ChatDrawItem.Spinner(Rect(frame.midX - 10, frame.midY - 10, 20.0, 20.0), Palette.text)
            } else {
                val size = title.size(frame.width)
                val textFrame = Rect(frame.minX, frame.midY - size.height / 2, frame.width, size.height)
                items += ChatDrawItem.Text(title.layout(textFrame), textFrame, alpha)
            }
            items to if (ready) listOf(ChatHit(frame.insetBy(-20.0, -12.0), ChatRowAction.QuestionSubmit(toolId))) else emptyList()
        }
        val background = ChatBox(Rect.Zero, Palette.surfaceHighest, if (ready) Palette.radioActive else Palette.divider, 1.0, 8.0, alpha = alpha)
        val entry = ChatStackEntry(button, padding = LayoutInsets(12, 20, 12, 20), placement = ChatStackLayout.Placement.Leading, background = background)
        val row = ChatPiece(Measurable { ChatStackLayout(it, listOf(entry.item)).size }) { frame ->
            // Right-aligned (justifyContent: 'flex-end').
            val width = ChatStackLayout(frame.width, listOf(entry.item)).boxes[0].width
            ChatPiece.stack(listOf(entry)).draw(Rect(frame.maxX - width, frame.minY, width, frame.height))
        }
        return ChatStackEntry(row, marginTop = 8.0)
    }
}

/** The item at a lower opacity (a form that can no longer be answered). */
internal fun ChatDrawItem.dimmed(alpha: Double): ChatDrawItem = when (this) {
    is ChatDrawItem.Text -> ChatDrawItem.Text(layout, frame, this.alpha * alpha)
    is ChatDrawItem.Box -> ChatDrawItem.Box(box.copy(alpha = box.alpha * alpha))
    else -> this
}
