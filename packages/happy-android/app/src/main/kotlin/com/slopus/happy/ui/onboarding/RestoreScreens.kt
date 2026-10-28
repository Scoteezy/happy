package com.slopus.happy.ui.onboarding

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.text.Layout
import android.view.View
import android.widget.ProgressBar
import com.slopus.happy.AppGraph
import com.slopus.happy.R
import com.slopus.happy.auth.AccountLinkRequest
import com.slopus.happy.layout.Measurable
import com.slopus.happy.layout.Palette
import com.slopus.happy.layout.Rect
import com.slopus.happy.layout.RestoreQRLayout
import com.slopus.happy.layout.RestoreSecretKeyLayout
import com.slopus.happy.layout.RoundButtonDisplay
import com.slopus.happy.layout.RoundButtonSize
import com.slopus.happy.layout.Size
import com.slopus.happy.layout.TextStyles
import com.slopus.happy.ui.components.Alerts
import com.slopus.happy.ui.components.Label
import com.slopus.happy.ui.components.QrCodeView
import com.slopus.happy.ui.components.RoundButton
import com.slopus.happy.ui.components.TextField
import com.slopus.happy.ui.nav.BarScreen
import com.slopus.happy.ui.theme.Theme
import com.slopus.happy.ui.theme.ThemeAware
import com.slopus.happy.ui.view.ManualLayout
import com.slopus.happy.ui.view.ScrollContainer
import com.slopus.happy.ui.view.dp
import com.slopus.happy.ui.view.px
import com.slopus.happy.ui.view.setFrame
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Restore by showing a QR code to the phone that has the account.
 * app/(app)/restore/index.tsx (happy-ios `RestoreQRViewController`). The
 * request lives as long as the screen is on the stack: the secret key
 * screen covers it without ending the wait, so either way can finish.
 */
class RestoreQrScreen(context: Context, private val graph: AppGraph) : BarScreen(context) {
    private val content = Content(context)
    private var task: Job? = null

    override fun createContent(): View {
        bar.title = context.getString(R.string.onboarding_restore_title)
        content.secretKeyButton.onTap = { push(RestoreSecretKeyScreen(context, graph)) }
        startLogin()
        return content
    }

    override fun onInsetsChanged() {
        super.onInsetsChanged()
        content.safeArea = contentSafeArea
    }

    private fun startLogin() {
        task = scope.launch {
            try {
                val request = AccountLinkRequest(graph.authApi)
                request.start()
                content.code.payload = request.url
                content.showsCode = true
                val linked = request.awaitApproval() ?: throw IllegalStateException("unreadable approval")
                graph.auth.signIn(linked.token, linked.secret)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                Alerts.show(context, context.getString(R.string.common_error), context.getString(R.string.errors_authentication_failed))
            }
        }
    }

    private class Content(context: Context) : ManualLayout(context), ThemeAware {
        private val scroll = ScrollContainer(context)
        private val instruction = Label(context, TextStyles.restoreInstruction, Palette.text, alignment = Layout.Alignment.ALIGN_CENTER).apply {
            text = context.getString(R.string.onboarding_restore_scan)
        }
        private val path = Label(context, TextStyles.restorePath, Palette.textSecondary, alignment = Layout.Alignment.ALIGN_CENTER).apply {
            text = context.getString(R.string.onboarding_restore_scan_path)
        }
        private val codeContainer = View(context)
        val code = QrCodeView(context)
        private val spinner = ProgressBar(context)
        val secretKeyButton = RoundButton(context, context.getString(R.string.onboarding_restore_use_key), RoundButtonSize.Normal, RoundButtonDisplay.Inverted)

        var safeArea = com.slopus.happy.layout.LayoutInsets.Zero
            set(value) { if (field != value) { field = value; requestLayout() } }

        var showsCode = false
            set(value) {
                field = value
                code.visibility = if (value) VISIBLE else GONE
                spinner.visibility = if (value) GONE else VISIBLE
                applyTheme()
                requestLayout()
            }

        init {
            listOf(instruction, path, codeContainer, code, spinner, secretKeyButton).forEach(scroll::addContent)
            addView(scroll)
            showsCode = false
        }

        override fun layoutSubviews(width: Double, height: Double) {
            val layout = RestoreQRLayout(
                RestoreQRLayout.Input(
                    Size(width, height), safeArea, instruction.measurable, path.measurable, showsCode,
                    Measurable { secretKeyButton.layout(it).size },
                ),
            )
            scroll.setFrame(Rect(0.0, 0.0, width, height))
            val box = layout.codeContainer
            val spin = SPINNER
            scroll.place(
                layout.contentSize,
                mapOf(
                    instruction to layout.instruction,
                    path to layout.path,
                    codeContainer to box,
                    code to layout.code,
                    spinner to Rect(box.midX - spin / 2, box.midY - spin / 2, spin, spin),
                    secretKeyButton to layout.button,
                ),
            )
        }

        override fun applyTheme() {
            setBackgroundColor(Theme.color(Palette.groupedBackground))
            codeContainer.background = GradientDrawable().apply {
                cornerRadius = dp(RestoreQRLayout.Metrics.qrCornerRadius)
                setColor(Theme.color(if (showsCode) Palette.qrBackground else Palette.surfaceHigh))
            }
            spinner.indeterminateTintList = ColorStateList.valueOf(Theme.color(Palette.text))
        }

        companion object {
            /** UIActivityIndicatorView's medium size. */
            const val SPINNER = 20.0
        }
    }
}

/** Restore by pasting the secret key. app/(app)/restore/manual.tsx (happy-ios `RestoreSecretKeyViewController`). */
class RestoreSecretKeyScreen(context: Context, private val graph: AppGraph) : BarScreen(context) {
    private val content = Content(context)

    override fun createContent(): View {
        bar.title = context.getString(R.string.onboarding_secret_key_title)
        content.restoreButton.action = { restore() }
        return content
    }

    override fun onAppear() {
        super.onAppear()
        content.field.focus()
    }

    override fun onInsetsChanged() {
        super.onInsetsChanged()
        content.safeArea = contentSafeArea
        content.keyboard = keyboard
    }

    private suspend fun restore() {
        val key = content.field.string.trim()
        if (key.isEmpty()) {
            Alerts.show(context, context.getString(R.string.common_error), context.getString(R.string.connect_enter_secret_key))
            return
        }
        try {
            graph.auth.restore(key)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            Alerts.show(context, context.getString(R.string.common_error), context.getString(R.string.connect_invalid_secret_key))
        }
    }

    private class Content(context: Context) : ManualLayout(context), ThemeAware {
        private val scroll = ScrollContainer(context)
        private val instruction = Label(context, TextStyles.restoreInstruction, Palette.textSecondary).apply {
            text = context.getString(R.string.onboarding_secret_key_body)
        }
        private val fieldBackground = View(context)
        val field = TextField(context, TextStyles.secretKeyInput, multiline = true).apply {
            hint = "XXXXX-XXXXX-XXXXX..."
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            val padding = dp(RestoreSecretKeyLayout.Metrics.fieldPadding).toInt()
            setPadding(padding, padding, padding, padding)
            contentDescription = context.getString(R.string.onboarding_secret_key_title)
        }
        val restoreButton = RoundButton(context, context.getString(R.string.onboarding_restore_button))

        var safeArea = com.slopus.happy.layout.LayoutInsets.Zero
            set(value) { if (field != value) { field = value; requestLayout() } }

        var keyboard = 0.0
            set(value) { if (field != value) { field = value; requestLayout() } }

        init {
            listOf(instruction, fieldBackground, field, restoreButton).forEach(scroll::addContent)
            addView(scroll)
            field.onChange = { requestLayout() }
            applyTheme()
        }

        override fun layoutSubviews(width: Double, height: Double) {
            val padding = RestoreSecretKeyLayout.Metrics.fieldPadding
            val fieldText = Measurable { w ->
                field.measure(
                    MeasureSpec.makeMeasureSpec(dp(w + padding * 2).toInt(), MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED),
                )
                Size(w, px(field.measuredHeight) - padding * 2)
            }
            val layout = RestoreSecretKeyLayout(
                RestoreSecretKeyLayout.Input(
                    Size(width, height), safeArea,
                    // The IME is measured from the window's bottom, gesture area included.
                    keyboardOverlap = if (keyboard > 0) keyboard + safeArea.bottom else 0.0,
                    instruction = instruction.measurable,
                    fieldText = fieldText,
                    button = Measurable { restoreButton.layout(it).size },
                ),
            )
            scroll.setFrame(layout.scroll)
            scroll.place(
                layout.contentSize,
                mapOf(instruction to layout.instruction, fieldBackground to layout.field, field to layout.field, restoreButton to layout.button),
            )
        }

        override fun applyTheme() {
            setBackgroundColor(Theme.color(Palette.groupedBackground))
            fieldBackground.background = GradientDrawable().apply {
                cornerRadius = dp(RestoreSecretKeyLayout.Metrics.fieldCornerRadius)
                setColor(Theme.color(Palette.surfaceHigh))
            }
        }
    }
}
