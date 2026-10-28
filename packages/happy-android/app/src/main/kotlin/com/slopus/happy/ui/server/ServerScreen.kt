package com.slopus.happy.ui.server

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.text.Layout
import android.view.View
import com.slopus.happy.AppGraph
import com.slopus.happy.R
import com.slopus.happy.auth.AuthApi
import com.slopus.happy.config.ServerConfig
import com.slopus.happy.layout.Measurable
import com.slopus.happy.layout.Palette
import com.slopus.happy.layout.RoundButtonDisplay
import com.slopus.happy.layout.RoundButtonSize
import com.slopus.happy.layout.ServerFormLayout
import com.slopus.happy.layout.TextStyles
import com.slopus.happy.ui.components.Alerts
import com.slopus.happy.ui.components.GroupedListView
import com.slopus.happy.ui.components.ItemGroupView
import com.slopus.happy.ui.components.Label
import com.slopus.happy.ui.components.RoundButton
import com.slopus.happy.ui.components.TextField
import com.slopus.happy.ui.nav.BarScreen
import com.slopus.happy.ui.text.TextLayouts
import com.slopus.happy.ui.theme.Theme
import com.slopus.happy.ui.theme.ThemeAware
import com.slopus.happy.ui.view.ManualLayout
import com.slopus.happy.ui.view.dp
import com.slopus.happy.ui.view.setFrame
import kotlinx.coroutines.launch

/** The custom server form from app/(app)/server.tsx (happy-ios `ServerFormView`), laid out by [ServerFormLayout]. */
class ServerFormView(context: Context) : ManualLayout(context), ThemeAware {
    val field = TextField(context, TextStyles.serverInput).apply {
        urlKeyboard()
        hint = context.getString(R.string.common_url_placeholder)
    }
    val save = RoundButton(context, context.getString(R.string.common_save), RoundButtonSize.Normal)
    val reset = RoundButton(context, context.getString(R.string.server_reset_to_default), RoundButtonSize.Normal, RoundButtonDisplay.Inverted)

    var error: String? = null
        set(value) { field = value; apply() }

    var isValidating = false
        set(value) { field = value; apply() }

    var isCustomServer = false
        set(value) { field = value; apply() }

    private val label = Label(context, TextStyles.fieldLabel, Palette.textSecondary, maxLines = 1).apply { text = context.getString(R.string.server_server_url_label) }
    private val fieldBackground = View(context)
    private val errorLabel = Label(context, TextStyles.fieldError, Palette.textDestructive)
    private val validatingLabel = Label(context, TextStyles.fieldError, Palette.statusConnecting).apply { text = context.getString(R.string.server_validating_server) }
    private val statusLabel = Label(context, TextStyles.fieldStatus, Palette.textSecondary, alignment = Layout.Alignment.ALIGN_CENTER).apply {
        text = context.getString(R.string.server_currently_using_custom_server)
    }

    init {
        listOf(label, fieldBackground, field, errorLabel, validatingLabel, save, reset, statusLabel).forEach(::addView)
        apply()
        applyTheme()
    }

    fun layout(width: Double) = ServerFormLayout(
        ServerFormLayout.Input(
            width = width,
            label = label.measurable,
            fieldLineHeight = TextLayouts.lineHeight(TextStyles.serverInput, TextLayouts.Env(Theme.density, Theme.textScale)),
            error = if (error == null) null else errorLabel.measurable,
            validating = if (isValidating) validatingLabel.measurable else null,
            save = Measurable { save.layout(it).size },
            reset = if (isCustomServer) Measurable { reset.layout(it).size } else null,
            status = if (isCustomServer) statusLabel.measurable else null,
        ),
    )

    override fun sizeThatFits(width: Double, height: Double) = layout(width).size

    override fun layoutSubviews(width: Double, height: Double) {
        val layout = layout(width)
        label.setFrame(layout.label)
        fieldBackground.setFrame(layout.field)
        field.setFrame(layout.field.insetBy(ServerFormLayout.Metrics.fieldPaddingHorizontal, 0.0))
        layout.error?.let { errorLabel.setFrame(it) }
        layout.validating?.let { validatingLabel.setFrame(it) }
        save.setFrame(layout.save)
        layout.reset?.let { reset.setFrame(it) }
        layout.status?.let { statusLabel.setFrame(it) }
    }

    private fun apply() {
        errorLabel.text = error.orEmpty()
        errorLabel.visibility = if (error == null) GONE else VISIBLE
        validatingLabel.visibility = if (isValidating) VISIBLE else GONE
        field.isEnabled = !isValidating
        field.alpha = if (isValidating) 0.6f else 1f
        save.title = context.getString(if (isValidating) R.string.server_validating else R.string.common_save)
        save.isEnabled = !isValidating
        reset.visibility = if (isCustomServer) VISIBLE else GONE
        statusLabel.visibility = if (isCustomServer) VISIBLE else GONE
        requestLayout()
    }

    override fun applyTheme() {
        fieldBackground.background = GradientDrawable().apply {
            cornerRadius = dp(ServerFormLayout.Metrics.fieldCornerRadius)
            setColor(Theme.color(Palette.surfaceHigh))
        }
    }
}

/** app/(app)/server.tsx (happy-ios `ServerConfigViewController`). */
class ServerScreen(context: Context, private val graph: AppGraph) : BarScreen(context) {
    private val list = GroupedListView(context)
    private val form = ServerFormView(context)

    override fun createContent(): View {
        bar.title = context.getString(R.string.server_title)
        list.sections = listOf(ItemGroupView(context, footer = context.getString(R.string.server_self_host_footer), rows = listOf(form)))
        val config = graph.serverConfig
        form.field.setText(if (config.isCustom) config.url.value else "")
        form.isCustomServer = config.isCustom
        form.field.onChange = { form.error = null }
        form.save.action = { save() }
        form.reset.onTap = { reset() }
        return list
    }

    override fun onInsetsChanged() {
        super.onInsetsChanged()
        list.safeArea = contentSafeArea.copy(bottom = safeArea.bottom + keyboard)
    }

    private suspend fun save() {
        val input = form.field.string
        if (input.isBlank()) {
            Alerts.show(context, context.getString(R.string.common_error), context.getString(R.string.server_enter_server_url))
            return
        }
        if (ServerConfig.validate(input) != ServerConfig.Validation.Valid) {
            form.error = context.getString(R.string.errors_invalid_format)
            return
        }
        form.error = null
        form.isValidating = true
        val result = graph.authApi.probeServer(input)
        form.isValidating = false
        when (result) {
            AuthApi.ServerProbe.HappyServer -> {}
            AuthApi.ServerProbe.ServerError -> { form.error = context.getString(R.string.server_server_returned_error); return }
            AuthApi.ServerProbe.NotHappyServer -> { form.error = context.getString(R.string.server_not_valid_happy_server); return }
            AuthApi.ServerProbe.Unreachable -> { form.error = context.getString(R.string.server_failed_to_connect_to_server); return }
        }
        val confirmed = Alerts.confirm(
            context,
            context.getString(R.string.server_change_server),
            context.getString(R.string.server_continue_with_server),
            confirmTitle = context.getString(R.string.common_continue),
            destructive = true,
        )
        if (!confirmed) return
        graph.serverConfig.set(input)
        form.isCustomServer = graph.serverConfig.isCustom
    }

    private fun reset() {
        scope.launch {
            val confirmed = Alerts.confirm(
                context,
                context.getString(R.string.server_reset_to_default),
                context.getString(R.string.server_reset_server_default),
                confirmTitle = context.getString(R.string.common_reset),
                destructive = true,
            )
            if (!confirmed) return@launch
            graph.serverConfig.set(null)
            form.field.setText("")
            form.error = null
            form.isCustomServer = false
        }
    }
}
