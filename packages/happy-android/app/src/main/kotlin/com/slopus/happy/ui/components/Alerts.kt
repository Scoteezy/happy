package com.slopus.happy.ui.components

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.text.InputType
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.ContextThemeWrapper
import android.widget.EditText
import android.widget.FrameLayout
import com.slopus.happy.R
import com.slopus.happy.layout.Palette
import com.slopus.happy.ui.theme.Theme
import com.slopus.happy.ui.view.dp
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * happy-ios `Alerts` (UIAlertController) as the platform's dialogs: the
 * device's alert dialog in the app's light or dark appearance.
 */
object Alerts {
    private fun themed(context: Context): Context = ContextThemeWrapper(
        context,
        if (Theme.isDark || context is DarkAlerts) android.R.style.Theme_DeviceDefault_Dialog_Alert else android.R.style.Theme_DeviceDefault_Light_Dialog_Alert,
    )

    /**
     * [context] for alerts that are dark whatever the app's appearance:
     * over the story viewer and editor, which iOS keeps dark
     * (`overrideUserInterfaceStyle = .dark`).
     */
    fun dark(context: Context): Context = context as? DarkAlerts ?: DarkAlerts(context)

    private class DarkAlerts(base: Context) : android.content.ContextWrapper(base)

    /** An alert with OK; [then] runs once it is gone. */
    fun show(context: Context, title: String, message: String? = null, then: (() -> Unit)? = null) {
        AlertDialog.Builder(themed(context))
            .setTitle(title)
            .apply { message?.let(::setMessage) }
            .setPositiveButton(context.getString(R.string.common_ok), null)
            .apply { then?.let { action -> setOnDismissListener { action() } } }
            .show()
    }

    suspend fun confirm(
        context: Context,
        title: String,
        message: String? = null,
        confirmTitle: String,
        cancelTitle: String = context.getString(R.string.common_cancel),
        destructive: Boolean = false,
    ): Boolean = suspendCancellableCoroutine { cont ->
        var answered = false
        val dialog = AlertDialog.Builder(themed(context))
            .setTitle(title)
            .apply { message?.let(::setMessage) }
            .setNegativeButton(cancelTitle) { _, _ -> answered = true; if (cont.isActive) cont.resume(false) }
            .setPositiveButton(confirmTitle) { _, _ -> answered = true; if (cont.isActive) cont.resume(true) }
            .setOnDismissListener { if (!answered && cont.isActive) cont.resume(false) }
            .create()
        dialog.show()
        if (destructive) dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.setTextColor(Theme.color(com.slopus.happy.layout.Palette.textDestructive))
        cont.invokeOnCancellation { dialog.dismiss() }
    }

    /** A text field in an alert; null when cancelled. */
    suspend fun prompt(
        context: Context,
        title: String,
        message: String? = null,
        placeholder: String? = null,
        confirmTitle: String,
        initial: String = "",
    ): String? = suspendCancellableCoroutine { cont ->
        val field = EditText(themed(context)).apply {
            hint = placeholder
            setText(initial)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            isSingleLine = true
        }
        val holder = FrameLayout(context).apply {
            setPadding(dp(20), dp(8), dp(20), 0)
            addView(field)
        }
        var answered = false
        val dialog = AlertDialog.Builder(themed(context))
            .setTitle(title)
            .apply { message?.let(::setMessage) }
            .setView(holder)
            .setNegativeButton(context.getString(R.string.common_cancel)) { _, _ -> answered = true; if (cont.isActive) cont.resume(null) }
            .setPositiveButton(confirmTitle) { _, _ -> answered = true; if (cont.isActive) cont.resume(field.text.toString()) }
            .setOnDismissListener { if (!answered && cont.isActive) cont.resume(null) }
            .create()
        dialog.show()
        field.requestFocus()
        cont.invokeOnCancellation { dialog.dismiss() }
    }

    /**
     * One choice from a list (UIAlertController's action sheet, or an alert
     * of buttons); null when cancelled. [destructive] options are red, as
     * UIAlertAction's `.destructive` style.
     */
    suspend fun choose(
        context: Context,
        title: String?,
        message: String? = null,
        options: List<String>,
        destructive: Set<Int> = emptySet(),
        cancelTitle: String? = null,
    ): Int? = suspendCancellableCoroutine { cont ->
        var answered = false
        val builder = AlertDialog.Builder(themed(context)).setTitle(title)
        if (message != null && title != null) builder.setTitle("$title")
        val red = Theme.color(Palette.statusError)
        val items = options.mapIndexed { index, option ->
            if (index !in destructive) option
            else SpannableString(option).apply { setSpan(ForegroundColorSpan(red), 0, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE) }
        }
        builder.setItems(items.toTypedArray<CharSequence>()) { _, which -> answered = true; if (cont.isActive) cont.resume(which) }
            .setNegativeButton(cancelTitle ?: context.getString(R.string.common_cancel)) { _, _ -> answered = true; if (cont.isActive) cont.resume(null) }
            .setOnDismissListener { if (!answered && cont.isActive) cont.resume(null) }
        val dialog = builder.create()
        dialog.show()
        cont.invokeOnCancellation { dialog.dismiss() }
    }

    /** Get help: where to ask, in happy-app's order. */
    fun help(context: Context) {
        val links = listOf(
            R.string.onboarding_help_discord to "https://discord.gg/fX9WBAhyfD",
            R.string.onboarding_help_bra1n_dump to "https://x.com/bra1n_dump",
            R.string.onboarding_help_ex3ndr to "https://x.com/Ex3NDR",
            R.string.onboarding_help_issues to "https://github.com/slopus/happy/issues",
        )
        AlertDialog.Builder(themed(context))
            .setTitle(context.getString(R.string.onboarding_get_help) + "\n" + context.getString(R.string.onboarding_help_message))
            .setItems(links.map { context.getString(it.first) }.toTypedArray()) { _, which -> open(context, links[which].second) }
            .setNegativeButton(context.getString(R.string.common_cancel), null)
            .show()
    }

    fun open(context: Context, url: String) {
        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }
}
