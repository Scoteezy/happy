package com.slopus.happy.ui.server

import android.content.Context
import android.view.View
import com.slopus.happy.AppGraph
import com.slopus.happy.R
import com.slopus.happy.config.ServerConfig
import com.slopus.happy.ui.components.Alerts
import com.slopus.happy.ui.components.GroupedListView
import com.slopus.happy.ui.components.ItemGroupView
import com.slopus.happy.ui.nav.BarScreen
import kotlinx.coroutines.CancellationException

/** happy-ios AccountServerConfirmationViewController: no host guessed, no network probe. */
class AccountServerConfirmationScreen(
    context: Context,
    private val graph: AppGraph,
    private val accountId: String,
) : BarScreen(context) {
    private val list = GroupedListView(context)
    private val form = ServerFormView(context)

    override fun createContent(): View {
        bar.title = context.getString(R.string.server_title)
        list.sections = listOf(ItemGroupView(context,
            footer = context.getString(R.string.server_continue_with_server), rows = listOf(form)))
        form.field.onChange = { form.error = null }
        form.save.action = { save() }
        return list
    }

    override fun onInsetsChanged() {
        super.onInsetsChanged()
        list.safeArea = contentSafeArea.copy(bottom = safeArea.bottom + keyboard)
    }

    private suspend fun save() {
        val input = form.field.string
        if (input.isBlank()) {
            form.error = context.getString(R.string.server_enter_server_url)
            return
        }
        if (ServerConfig.validate(input) != ServerConfig.Validation.Valid) {
            form.error = context.getString(R.string.errors_invalid_format)
            return
        }
        if (!Alerts.confirm(context, context.getString(R.string.server_change_server),
                context.getString(R.string.server_continue_with_server),
                confirmTitle = context.getString(R.string.common_continue))) return
        try {
            graph.auth.confirmServer(accountId, input)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            form.error = context.getString(R.string.server_failed_to_connect_to_server)
        }
    }
}