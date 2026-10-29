package com.slopus.happy.ui.machine

import android.content.Context
import android.view.View
import android.widget.FrameLayout
import com.slopus.happy.AppGraph
import com.slopus.happy.R
import com.slopus.happy.icons.Icon
import com.slopus.happy.layout.ColorToken
import com.slopus.happy.layout.Palette
import com.slopus.happy.layout.SecondaryPalette
import com.slopus.happy.layout.SecondaryTextStyles
import com.slopus.happy.model.details.MachineDetailsModel
import com.slopus.happy.model.details.SessionDetails
import com.slopus.happy.model.parseJsonObject
import com.slopus.happy.ui.chat.ChatScreen
import com.slopus.happy.ui.components.Alerts
import com.slopus.happy.ui.components.GroupedListView
import com.slopus.happy.ui.components.IconView
import com.slopus.happy.ui.components.ItemGroupView
import com.slopus.happy.ui.components.ItemRowView
import com.slopus.happy.ui.components.Label
import com.slopus.happy.ui.nav.BarScreen
import com.slopus.happy.ui.nav.NavigationBar
import com.slopus.happy.ui.settings.CenteredMessageView
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * app/(app)/machine/[id].tsx (happy-ios `MachineViewController`): the
 * daemon's status with Stop Daemon, the CLIs it found, its recent sessions,
 * the machine's details and Delete. The pencil in the bar renames it
 * (metadata `displayName`).
 *
 * The rows are worked out off the main thread from the stored machine and
 * sessions and built again only when what they show changed, so a
 * heartbeat touches nothing. iOS's pull to refresh is left out: the
 * machine's updates arrive over the socket.
 */
class MachineScreen(context: Context, private val graph: AppGraph, private val machineId: String) : BarScreen(context) {
    // navigation/Header.tsx's plain header: the tablet shell's back serves a tablet.
    override val dropsBackButtonOnTablet get() = true

    private val root = FrameLayout(context)
    private val list = GroupedListView(context)
    private var model: MachineDetailsModel? = null
    private var loaded = false
    private var stoppingDaemon = false
    private var renaming = false
    private var deleting = false
    private var notFound: CenteredMessageView? = null

    override fun createContent(): View {
        root.addView(list)
        return root
    }

    override fun viewCreated(view: View) {
        super.viewCreated(view)
        // Header.tsx's title, which scales with the text size (happy-ios setPlainHeaderTitle).
        bar.plainTitle = true
        scope.launch {
            graph.store.changes
                .map {
                    val machine = graph.store.machine(machineId)
                    machine?.let { MachineDetailsModel(it, graph.store.sessions()) }
                }
                .flowOn(Dispatchers.IO)
                .distinctUntilChanged()
                .collect { next ->
                    if (loaded && next == model) return@collect
                    loaded = true
                    model = next
                    rebuild()
                }
        }
    }

    override fun onInsetsChanged() {
        super.onInsetsChanged()
        list.safeArea = contentSafeArea
        notFound?.safeArea = contentSafeArea
    }

    private fun string(id: Int) = context.getString(id)

    // Building

    private fun rebuild() {
        if (!loaded) return
        val model = model
        if (model == null) {
            bar.title = ""
            bar.actions = emptyList()
            list.visibility = View.GONE
            if (notFound == null) {
                val message = CenteredMessageView(context, string(R.string.machine_not_found), SecondaryTextStyles.machineNotFound, SecondaryPalette.notFound)
                message.safeArea = contentSafeArea
                notFound = message
                root.addView(message)
            }
            return
        }
        notFound?.let { root.removeView(it) }
        notFound = null
        list.visibility = View.VISIBLE
        bar.title = model.name
        // Octicons pencil 24, dimmed while renaming.
        bar.actions = listOf(NavigationBar.Action(Icon.octicons("pencil"), string(R.string.machine_rename_title), 24.0) { rename() })

        val tints = mutableListOf<() -> Unit>()
        val sections = mutableListOf<View>(daemonGroup(model, tints))
        model.cliAvailability?.let { sections += cliGroup(it) }
        if (model.recentSessions.isNotEmpty()) sections += sessionsGroup(model.recentSessions)
        sections += machineGroup(model)
        sections += dangerZone(tints)
        list.sections = sections
        // Title colours go on last: rows re-apply their own when they change.
        tints.forEach { it() }
    }

    private fun info(title: String, subtitle: String? = null, detail: String? = null) =
        ItemRowView(context, title, subtitle, detail).apply { showsChevron = false }

    private fun ItemRowView.mono(style: com.slopus.happy.layout.TextStyle = SecondaryTextStyles.monoSubtitle) = apply { subtitleLabel.style = style }

    private fun trailingIcon(name: String, tint: ColorToken) = IconView(context, Icon.ionicons(name), 20.0, tint)

    /** DetailTrailingText: the system face at 14. */
    private fun trailingText(text: String, color: ColorToken) =
        Label(context, TRAILING_TEXT, color, maxLines = 1).apply { this.text = text }

    private fun daemonGroup(model: MachineDetailsModel, tints: MutableList<() -> Unit>): ItemGroupView {
        val rows = mutableListOf<ItemRowView>()
        val status = info(string(R.string.machine_status), detail = string(if (model.online) R.string.status_online else R.string.status_offline))
        status.detailLabel.color = if (model.online) SecondaryPalette.green else Palette.textSecondary
        rows += status

        val stopTint = if (model.online) SecondaryPalette.orange else Palette.textSecondary
        val stop = ItemRowView(context, string(R.string.machine_stop_daemon))
        if (model.online && !stoppingDaemon) stop.onTap = { confirmStopDaemon() }
        stop.trailingView = trailingIcon("stop-circle", stopTint)
        stop.isLoading = stoppingDaemon
        rows += stop
        tints += { stop.titleLabel.color = stopTint }

        model.daemon?.let { daemon ->
            daemon.pid?.let { rows += info(string(R.string.machine_last_known_pid), subtitle = it).mono() }
            daemon.httpPort?.let { rows += info(string(R.string.machine_last_known_http_port), subtitle = it).mono() }
            daemon.startedAt?.let { rows += info(string(R.string.machine_started_at), subtitle = SessionDetails.localeDateTime(it)) }
            daemon.cliVersion?.let { rows += info(string(R.string.machine_cli_version), subtitle = it).mono() }
        }
        rows += info(string(R.string.machine_daemon_state_version), subtitle = model.daemonStateVersion.toString())
        return ItemGroupView(context, rows = rows)
    }

    private fun cliGroup(cli: MachineDetailsModel.CliAvailability): ItemGroupView {
        fun row(name: String, installed: Boolean) = ItemRowView(context, name).apply {
            showsChevron = false
            trailingView = trailingText(
                string(if (installed) R.string.machine_cli_installed else R.string.machine_cli_not_found),
                if (installed) SecondaryPalette.green else Palette.textSecondary,
            )
        }
        val rows = mutableListOf(row("Claude", cli.claude), row("Codex", cli.codex), row("Gemini", cli.gemini), row("OpenClaw", cli.openclaw))
        cli.rig?.let { rows += row("Rig", it) }
        rows += info(string(R.string.machine_last_detected), subtitle = SessionDetails.localeDateTime(cli.detectedAt))
        return ItemGroupView(context, title = string(R.string.machine_cli_availability), rows = rows)
    }

    private fun sessionsGroup(sessions: List<MachineDetailsModel.RecentSession>): ItemGroupView =
        ItemGroupView(context, title = string(R.string.tabs_sessions), rows = sessions.map { recent ->
            ItemRowView(
                context,
                recent.title ?: string(R.string.session_new_chat),
                recent.subtitle ?: string(R.string.status_unknown),
                onTap = { push(ChatScreen(context, graph, recent.id)) },
            ).apply { trailingView = trailingIcon("chevron-forward", SecondaryPalette.chevron) }
        })

    private fun machineGroup(model: MachineDetailsModel): ItemGroupView {
        val rows = mutableListOf(
            info(string(R.string.machine_host), subtitle = model.host),
            info(string(R.string.machine_machine_id), subtitle = model.machineId).mono(SecondaryTextStyles.monoSubtitleSmall),
        )
        model.username?.let { rows += info(string(R.string.machine_username), subtitle = it) }
        model.homeDirectory?.let { rows += info(string(R.string.machine_home_directory), subtitle = it).mono() }
        model.platform?.let { rows += info(string(R.string.machine_platform), subtitle = it) }
        model.architecture?.let { rows += info(string(R.string.machine_architecture), subtitle = it) }
        rows += info(string(R.string.machine_last_seen), subtitle = model.lastSeen?.let { SessionDetails.localeDateTime(it) } ?: string(R.string.machine_never))
        rows += info(string(R.string.machine_metadata_version), subtitle = model.metadataVersion.toString())
        return ItemGroupView(context, title = string(R.string.machine_machine_group), rows = rows)
    }

    private fun dangerZone(tints: MutableList<() -> Unit>): ItemGroupView {
        val row = ItemRowView(context, string(R.string.machine_delete))
        row.showsChevron = false
        if (!deleting) row.onTap = { confirmDelete() }
        row.trailingView = trailingIcon("trash-outline", SecondaryPalette.red)
        row.isLoading = deleting
        tints += { row.titleLabel.color = SecondaryPalette.red }
        return ItemGroupView(context, title = string(R.string.machine_danger_zone), footer = string(R.string.machine_delete_footer), rows = listOf(row))
    }

    // Actions

    private fun confirmStopDaemon() {
        scope.launch {
            val confirmed = Alerts.confirm(
                context,
                string(R.string.machine_stop_daemon_confirm_title),
                string(R.string.machine_stop_daemon_confirm_message),
                confirmTitle = string(R.string.machine_stop_daemon_confirm),
                cancelTitle = string(R.string.machine_stop_daemon_cancel),
                destructive = true,
            )
            if (confirmed) stopDaemon()
        }
    }

    private suspend fun stopDaemon() {
        val ops = graph.account.value?.machineOps ?: return
        stoppingDaemon = true
        rebuild()
        try {
            val message = ops.stopDaemon(machineId)
            Alerts.show(context, string(R.string.machine_daemon_stopped), message)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            Alerts.show(context, string(R.string.common_error), string(R.string.machine_stop_daemon_failed))
        }
        stoppingDaemon = false
        rebuild()
    }

    private fun rename() {
        val model = model ?: return
        if (renaming) return
        val account = graph.account.value ?: return
        scope.launch {
            val name = Alerts.prompt(
                context,
                string(R.string.machine_rename_title),
                string(R.string.machine_rename_message),
                placeholder = model.hostPlaceholder ?: string(R.string.machine_rename_placeholder),
                confirmTitle = string(R.string.common_rename),
                initial = model.displayName,
            ) ?: return@launch
            renaming = true
            rebuild()
            try {
                val record = kotlinx.coroutines.withContext(Dispatchers.IO) { graph.store.machine(machineId) }
                    ?: throw IllegalStateException("machine gone")
                account.machineOps.updateMetadata(
                    machineId,
                    MachineDetailsModel.renamedMetadata(parseJsonObject(record.metadataJson), name),
                    record.metadataVersion,
                )
                Alerts.show(context, string(R.string.common_success), string(R.string.machine_renamed))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                account.syncNow()
                Alerts.show(context, string(R.string.machine_rename_error_title), string(R.string.machine_rename_failed))
            }
            renaming = false
            rebuild()
        }
    }

    private fun confirmDelete() {
        val ops = graph.account.value?.machineOps ?: return
        scope.launch {
            val confirmed = Alerts.confirm(
                context,
                string(R.string.machine_delete_confirm_title),
                string(R.string.machine_delete_confirm_message),
                confirmTitle = string(R.string.common_delete),
                destructive = true,
            )
            if (!confirmed) return@launch
            deleting = true
            rebuild()
            val result = ops.delete(machineId)
            deleting = false
            rebuild()
            if (result.success) pop()
            else Alerts.show(context, string(R.string.common_error), result.message?.ifEmpty { null } ?: string(R.string.machine_delete_failed))
        }
    }

    private companion object {
        val TRAILING_TEXT = com.slopus.happy.layout.TextStyle(com.slopus.happy.layout.FontFace.System, 14)
    }
}
