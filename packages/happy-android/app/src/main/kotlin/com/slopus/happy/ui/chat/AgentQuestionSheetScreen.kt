package com.slopus.happy.ui.chat

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.text.InputType
import android.view.View
import android.widget.ProgressBar
import com.slopus.happy.R
import com.slopus.happy.chat.AgentQuestion
import com.slopus.happy.chat.AgentQuestionAnswer
import com.slopus.happy.chat.AgentQuestionDraft
import com.slopus.happy.chat.PendingAgentCommunication
import com.slopus.happy.chat.buildAnswers
import com.slopus.happy.chat.canSubmit
import com.slopus.happy.chat.isQuestionAnswered
import com.slopus.happy.chat.toggleOption
import com.slopus.happy.icons.Icon
import com.slopus.happy.layout.ChatTextStyles
import com.slopus.happy.layout.LayoutInsets
import com.slopus.happy.layout.Measurable
import com.slopus.happy.layout.Palette
import com.slopus.happy.layout.Rect
import com.slopus.happy.layout.Size
import com.slopus.happy.layout.chat.QuestionSheetLayout
import com.slopus.happy.layout.onAccent
import com.slopus.happy.layout.requiredDot
import com.slopus.happy.ui.components.IconView
import com.slopus.happy.ui.components.Label
import com.slopus.happy.ui.components.TextField
import com.slopus.happy.ui.nav.Screen
import com.slopus.happy.ui.text.TextLayouts
import com.slopus.happy.ui.theme.Theme
import com.slopus.happy.ui.theme.ThemeAware
import com.slopus.happy.ui.view.ManualLayout
import com.slopus.happy.ui.view.ScrollContainer
import com.slopus.happy.ui.view.dp
import com.slopus.happy.ui.view.px
import com.slopus.happy.ui.view.setFrame
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * AgentQuestionModal (happy-ios `AgentQuestionSheetController`): the
 * full-screen answer sheet for a form the chat can't show inline, each
 * question's options as checkboxes and a field for a written answer where the
 * agent allows one. It rises over the chat as a modal; Cancel (or back)
 * dismisses the request. A failed send keeps the sheet open with the answers.
 */
class AgentQuestionSheetScreen(
    context: Context,
    val pending: PendingAgentCommunication.Form,
    private val submit: suspend (Map<String, AgentQuestionAnswer>) -> Unit,
    /** Dismisses the request; it outlives this screen, so it runs in the chat's scope. */
    private val cancel: () -> Unit,
) : Screen(context) {
    private val questions: List<AgentQuestion> = pending.questions
    private var drafts = mapOf<String, AgentQuestionDraft>()
    private var submitting = false
    private var errorText: String? = null
    private var dismissed = false

    private val cancelButton = TextButton(context)
    private val titleLabel = Label(context, ChatTextStyles.sheetTitle, Palette.text, maxLines = 1)
    private val headerDivider = View(context)
    private val scroll = ScrollContainer(context)
    private val footer = View(context)
    private val footerDivider = View(context)
    private val submitButton = SubmitButton(context)
    private val errorLabel = Label(context, ChatTextStyles.sheetError, Palette.statusError)
    private val blocks: List<QuestionBlock>
    private val root by lazy { Root(context) }

    override val isModal get() = true
    override val canPop get() = false

    /** One question's views. */
    private inner class QuestionBlock(val question: AgentQuestion) {
        val chip = View(context).apply { background = GradientDrawable().apply { cornerRadius = dp(QuestionSheetLayout.Metrics.chipRadius) } }
        val chipText = Label(context, ChatTextStyles.sheetChip, Palette.textSecondary, maxLines = 1).apply { text = question.header.uppercase() }
        val dot = View(context).apply { background = GradientDrawable().apply { shape = GradientDrawable.OVAL } }
        val text = Label(context, ChatTextStyles.sheetQuestion, Palette.text).apply { text = question.question }
        val hint = if (question.multiSelect) Label(context, ChatTextStyles.sheetHint, Palette.textSecondary).apply {
            text = context.getString(R.string.agent_question_choose_multiple)
        } else null
        val options = question.options.map { option -> OptionBox(context, option.label, option.description?.takeIf { it.isNotEmpty() }) }
        val customLabel = if (question.allowCustom != false) Label(context, ChatTextStyles.sheetCustomLabel, Palette.textSecondary).apply {
            text = context.getString(R.string.agent_question_own_answer)
        } else null
        val input = if (question.allowCustom != false) TextField(context, ChatTextStyles.sheetInput, Palette.text, Palette.textSecondary, multiline = true).apply {
            hint = context.getString(R.string.agent_question_own_answer_placeholder)
            val h = dp(QuestionSheetLayout.Metrics.inputPaddingH).toInt()
            val v = dp(QuestionSheetLayout.Metrics.inputPaddingV).toInt()
            setPadding(h, v, h, v)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            background = GradientDrawable().apply { cornerRadius = dp(QuestionSheetLayout.Metrics.inputRadius) }
        } else null

        /** What the person has typed, as the layout measures it. */
        val typed: Measurable
            get() = Measurable { width ->
                val text = input?.string?.ifEmpty { " " } ?: " "
                TextLayouts.measure(text, ChatTextStyles.sheetInput, width, TextLayouts.Env(Theme.density, Theme.textScale))
            }

        fun views(): List<View> = listOfNotNull(chip, chipText, dot, text, hint) + options + listOfNotNull(customLabel, input)
    }

    init {
        cancelButton.label.text = context.getString(R.string.common_cancel)
        titleLabel.text = context.getString(R.string.agent_question_title)
        blocks = questions.map { QuestionBlock(it) }
    }

    override fun createView(): View = root

    override fun viewCreated(view: View) {
        cancelButton.isClickable = true
        cancelButton.isFocusable = true
        cancelButton.setOnClickListener { cancelTapped() }
        submitButton.setOnClickListener { submitTapped() }
        for ((index, block) in blocks.withIndex()) {
            val question = questions[index]
            for ((optionIndex, box) in block.options.withIndex()) {
                box.setOnClickListener {
                    if (submitting) return@setOnClickListener
                    val label = question.options[optionIndex].label
                    drafts = drafts + (question.id to toggleOption(drafts[question.id] ?: AgentQuestionDraft(), label, question.multiSelect))
                    update()
                }
            }
            block.input?.onChange = { text ->
                drafts = drafts + (question.id to (drafts[question.id] ?: AgentQuestionDraft()).copy(custom = text))
                update()
            }
        }
        update()
        // A question with nothing to pick opens with the keyboard ready.
        if (questions.firstOrNull()?.options?.isEmpty() == true) blocks.first().input?.post { blocks.first().input?.focus() }
    }

    /** Applies the drafts, the error and the submitting state to the views. */
    private fun update() {
        for ((index, question) in questions.withIndex()) {
            val block = blocks[index]
            val draft = drafts[question.id] ?: AgentQuestionDraft()
            block.dot.visibility = if (isQuestionAnswered(question, drafts[question.id])) View.GONE else View.VISIBLE
            for ((optionIndex, option) in question.options.withIndex()) {
                val box = block.options[optionIndex]
                box.isEnabled = !submitting
                box.picked = option.label in draft.options
            }
            block.input?.isEnabled = !submitting
        }
        errorLabel.text = errorText.orEmpty()
        errorLabel.visibility = if (errorText == null) View.GONE else View.VISIBLE
        val ready = canSubmit(questions, drafts)
        submitButton.isEnabled = ready && !submitting
        submitButton.alpha = if (ready && !submitting) 1f else 0.4f
        submitButton.busy = submitting
        cancelButton.isEnabled = !submitting
        root.requestLayout()
    }

    private fun submitTapped() {
        if (!canSubmit(questions, drafts) || submitting) return
        submitting = true
        errorText = null
        update()
        val answers = buildAnswers(questions, drafts)
        scope.launch {
            try {
                submit(answers)
                close()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // The form stays open with the answers, so they can retry.
                errorText = e.message?.takeIf { it.isNotBlank() } ?: context.getString(R.string.agent_question_submit_failed)
                submitting = false
                update()
            }
        }
    }

    private fun cancelTapped() {
        if (submitting) return
        close()
        cancel()
    }

    /** Leaves, once: after an answer, a cancel, or when the request is answered elsewhere. */
    fun close() {
        if (dismissed) return
        dismissed = true
        blocks.forEach { it.input?.dismissKeyboard() }
        pop()
    }

    override fun onBackPressed(): Boolean {
        cancelTapped()
        return true
    }

    private inner class Root(context: Context) : ManualLayout(context), ThemeAware {
        init {
            addView(cancelButton)
            addView(titleLabel)
            addView(headerDivider)
            addView(scroll)
            for (block in blocks) block.views().forEach(scroll::addContent)
            scroll.addContent(errorLabel)
            addView(footer)
            addView(footerDivider)
            addView(submitButton)
            applyTheme()
        }

        override fun applyTheme() {
            setBackgroundColor(Theme.color(Palette.groupedBackground))
            footer.setBackgroundColor(Theme.color(Palette.groupedBackground))
            headerDivider.setBackgroundColor(Theme.color(Palette.divider))
            footerDivider.setBackgroundColor(Theme.color(Palette.divider))
            for (block in blocks) {
                (block.chip.background as GradientDrawable).setColor(Theme.color(Palette.surfaceHighest))
                (block.dot.background as GradientDrawable).setColor(Theme.color(Palette.requiredDot))
                (block.input?.background as? GradientDrawable)?.apply {
                    setColor(Theme.color(Palette.surface))
                    setStroke(dp(1), Theme.color(Palette.divider))
                }
            }
        }

        override fun layoutSubviews(width: Double, height: Double) {
            val keyboard = keyboard
            val layout = QuestionSheetLayout(
                QuestionSheetLayout.Input(
                    size = Size(width, height),
                    safeArea = LayoutInsets(safeArea.top, safeArea.left, safeArea.bottom, safeArea.right),
                    keyboardTop = if (keyboard > 0) height - safeArea.bottom - keyboard else null,
                    hairline = px(1),
                    cancel = cancelButton.label.measurable,
                    title = titleLabel.measurable,
                    submit = submitButton.label.measurable,
                    questions = blocks.map { block ->
                        QuestionSheetLayout.Question(
                            chip = block.chipText.measurable,
                            showsDot = block.dot.visibility == View.VISIBLE,
                            text = block.text.measurable,
                            hint = block.hint?.measurable,
                            options = block.options.map { QuestionSheetLayout.Option(it.label.measurable, it.description?.measurable) },
                            custom = block.customLabel?.let { QuestionSheetLayout.Custom(it.measurable, block.typed) },
                        )
                    },
                    error = if (errorText == null) null else errorLabel.measurable,
                ),
            )
            cancelButton.setFrame(layout.cancel.insetBy(-8.0, -8.0))
            titleLabel.setFrame(layout.title)
            headerDivider.setFrame(layout.headerDivider)
            scroll.setFrame(layout.scroll)
            val frames = LinkedHashMap<View, Rect?>()
            for ((block, placed) in blocks.zip(layout.questions)) {
                frames[block.chip] = placed.chip
                frames[block.chipText] = placed.chipText
                frames[block.dot] = placed.dot
                frames[block.text] = placed.text
                block.hint?.let { frames[it] = placed.hint }
                for ((box, option) in block.options.zip(placed.options)) {
                    frames[box] = option.box
                    box.placed = option
                }
                block.customLabel?.let { frames[it] = placed.customLabel }
                block.input?.let { frames[it] = placed.input }
            }
            frames[errorLabel] = layout.error
            scroll.place(layout.contentSize, frames)
            footer.setFrame(layout.footer)
            footerDivider.setFrame(layout.footerDivider)
            submitButton.setFrame(layout.submit)
        }
    }

    /** Cancel: its label 8 in from a rippling touch area. */
    private class TextButton(context: Context) : ManualLayout(context), ThemeAware {
        val label = Label(context, ChatTextStyles.sheetAction, Palette.textLink, maxLines = 1)

        init {
            addView(label)
            isClickable = true
            isFocusable = true
            label.importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
            applyTheme()
        }

        override fun applyTheme() {
            val mask = GradientDrawable().apply { cornerRadius = dp(8.0); setColor(-1) }
            background = RippleDrawable(ColorStateList.valueOf(Theme.color(Palette.surfacePressedOverlay)), null, mask)
        }

        override fun setEnabled(enabled: Boolean) {
            super.setEnabled(enabled)
            contentDescription = label.text
        }

        override fun layoutSubviews(width: Double, height: Double) =
            label.setFrame(Rect(8.0, 8.0, maxOf(0.0, width - 16), maxOf(0.0, height - 16)))
    }

    /** An option: a 12-round box, ringed in the link colour while picked, its checkbox, label and description. */
    private class OptionBox(context: Context, label: String, description: String?) : ManualLayout(context), ThemeAware {
        private val shape = GradientDrawable().apply { cornerRadius = dp(QuestionSheetLayout.Metrics.optionRadius) }
        private val checkbox = View(context)
        private val checkboxShape = GradientDrawable().apply { cornerRadius = dp(QuestionSheetLayout.Metrics.checkboxRadius) }
        private val check = IconView(context, Icon.ionicons("checkmark"), 14.0, Palette.onAccent)
        val label = Label(context, ChatTextStyles.sheetOptionLabel, Palette.text).apply { text = label }
        val description = description?.let { Label(context, ChatTextStyles.sheetOptionDescription, Palette.textSecondary).apply { text = it } }
        var placed: QuestionSheetLayout.PlacedOption? = null

        var picked = false
            set(value) {
                if (field == value) return
                field = value
                isSelected = value
                applyTheme()
            }

        init {
            checkbox.background = checkboxShape
            addView(checkbox)
            addView(check)
            addView(this.label)
            this.description?.let(::addView)
            isClickable = true
            isFocusable = true
            contentDescription = label
            applyTheme()
        }

        override fun applyTheme() {
            shape.setColor(Theme.color(Palette.surface))
            shape.setStroke(dp(1), if (picked) Theme.color(Palette.textLink) else 0)
            val mask = GradientDrawable().apply { cornerRadius = dp(QuestionSheetLayout.Metrics.optionRadius); setColor(-1) }
            background = RippleDrawable(ColorStateList.valueOf(Theme.color(Palette.surfacePressedOverlay)), shape, mask)
            checkboxShape.setColor(if (picked) Theme.color(Palette.textLink) else 0)
            checkboxShape.setStroke(dp(2), Theme.color(if (picked) Palette.textLink else Palette.divider))
            check.visibility = if (picked) VISIBLE else INVISIBLE
        }

        override fun layoutSubviews(width: Double, height: Double) {
            val placed = placed ?: return
            val dx = -placed.box.minX
            val dy = -placed.box.minY
            checkbox.setFrame(placed.checkbox.offsetBy(dx, dy))
            check.setFrame(placed.checkbox.offsetBy(dx, dy))
            label.setFrame(placed.label.offsetBy(dx, dy))
            placed.description?.let { frame -> description?.setFrame(frame.offsetBy(dx, dy)) }
        }
    }

    /** Send answer: 50 tall, 14 round, in the link colour; a spinner while it sends. */
    private class SubmitButton(context: Context) : ManualLayout(context), ThemeAware {
        val label = Label(context, ChatTextStyles.sheetSubmit, Palette.onAccent, maxLines = 1).apply {
            text = context.getString(R.string.agent_question_submit)
        }
        private val spinner = ProgressBar(context, null, android.R.attr.progressBarStyleSmall)
        private val shape = GradientDrawable().apply { cornerRadius = dp(QuestionSheetLayout.Metrics.submitRadius) }

        var busy = false
            set(value) {
                field = value
                label.visibility = if (value) INVISIBLE else VISIBLE
                spinner.visibility = if (value) VISIBLE else GONE
            }

        init {
            addView(label)
            spinner.visibility = GONE
            addView(spinner)
            isClickable = true
            isFocusable = true
            contentDescription = label.text
            label.importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
            applyTheme()
        }

        override fun applyTheme() {
            shape.setColor(Theme.color(Palette.textLink))
            val mask = GradientDrawable().apply { cornerRadius = dp(QuestionSheetLayout.Metrics.submitRadius); setColor(-1) }
            background = RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), shape, mask)
            spinner.indeterminateTintList = ColorStateList.valueOf(Theme.color(Palette.onAccent))
        }

        override fun layoutSubviews(width: Double, height: Double) {
            val size = label.measure(width)
            label.setFrame(Rect((width - size.width) / 2, (height - size.height) / 2, size.width, size.height))
            val s = 20.0
            spinner.setFrame(Rect((width - s) / 2, (height - s) / 2, s, s))
        }
    }
}
