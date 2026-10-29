package com.slopus.happy.ui.home

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import com.slopus.happy.AppGraph
import com.slopus.happy.R
import com.slopus.happy.data.SessionRecord
import com.slopus.happy.home.ArchiveResult
import com.slopus.happy.home.HomeServices
import com.slopus.happy.home.ResumeBlock
import com.slopus.happy.home.SessionActionId
import com.slopus.happy.home.resumeAvailability
import com.slopus.happy.home.sessionActionIds
import com.slopus.happy.model.Machine
import com.slopus.happy.model.Session
import com.slopus.happy.model.details.SessionInfoText
import com.slopus.happy.model.forkSource
import com.slopus.happy.model.isTask
import com.slopus.happy.model.canLeaveTask
import com.slopus.happy.model.taskCanArchive
import com.slopus.happy.model.parseJsonObject
import com.slopus.happy.resume.AccountResumeOps
import com.slopus.happy.resume.ResumeOutcome
import com.slopus.happy.resume.SessionResumer
import com.slopus.happy.resume.resumeTexts
import com.slopus.happy.sync.SpawnSessionResult
import com.slopus.happy.ui.chat.ChatScreen
import com.slopus.happy.ui.components.Alerts
import com.slopus.happy.ui.details.DuplicateSheet
import com.slopus.happy.ui.details.SessionInfoScreen
import com.slopus.happy.ui.nav.Screen
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The "Session" alert a long press on a home row opens, what each item
 * does, and the row swipe's archive (happy-ios `HomeSessionActions`,
 * hooks/useSessionQuickActions.ts; happy-app's popover is web-only, on a
 * phone it is this alert). [detached] outlives home, since an archive's
 * failure is shown after the screen may have gone.
 */
class HomeSessionActions(
    private val host: Screen,
    private val graph: AppGraph,
    private val scope: CoroutineScope,
) {
    private val detached = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val context: Context get() = host.context

    /** useHappyAction: one run at a time per action. */
    private val running = HashSet<String>()

    /** The session whose alert is being read. */
    private var opening: String? = null

    /** The record, its computer and the settings are read off the main thread; the alert opens once they are in, if the account is still the one. */
    fun present(sessionId: String) {
        if (opening != null) return
        val account = graph.account.value ?: return
        opening = sessionId
        scope.launch {
            val read = withContext(Dispatchers.IO) { graph.store.session(sessionId) }
            opening = null
            if (read == null || graph.account.value !== account || host.stack == null) return@launch
            val session = Session.from(read)
            // The computer as home shows it, its presence applied.
            val machine = session.metadata?.machineId?.let { id -> HomeServices.of(graph).snapshots.value?.machines?.firstOrNull { it.id == id } }
            val items = sessionActionIds(
                session,
                machine,
                continuationExperiments = graph.settings.settings.value.expResumeSession,
                devMode = graph.localSettings.values.value.devModeEnabled,
            )
            val choice = Alerts.choose(
                context,
                context.getString(R.string.session_actions_title),
                options = items.map { if (it == SessionActionId.Archive && session.isTask) context.getString(R.string.tasks_archive) else title(it) },
                destructive = items.indices.filter { items[it] == SessionActionId.Archive || items[it] == SessionActionId.LeaveTask }.toSet(),
            ) ?: return@launch
            if (graph.account.value !== account || host.stack == null) return@launch
            run(items[choice], read, session, machine)
        }
    }

    private fun title(item: SessionActionId): String = when (item) {
        SessionActionId.Details -> context.getString(R.string.profile_details)
        SessionActionId.Resume -> context.getString(R.string.session_info_resume_session)
        SessionActionId.Fork -> context.getString(R.string.session_fork_action)
        SessionActionId.Duplicate -> context.getString(R.string.session_duplicate_action)
        SessionActionId.CopyMetadata -> context.getString(R.string.session_info_copy_metadata)
        SessionActionId.CopyMetadataAndLogs -> context.getString(R.string.session_info_copy_metadata) + context.getString(R.string.home_client_logs_suffix)
        SessionActionId.LeaveTask -> context.getString(R.string.tasks_leave)
        SessionActionId.Archive -> context.getString(R.string.session_archive_action)
    }

    private fun run(item: SessionActionId, record: SessionRecord, session: Session, machine: Machine?) {
        when (item) {
            SessionActionId.Details -> host.push(SessionInfoScreen(context, graph, session.id))
            SessionActionId.Resume -> perform("resume:${session.id}") { resume(session, machine) }
            SessionActionId.Fork -> perform("fork:${session.id}") { fork(session) }
            SessionActionId.Duplicate -> DuplicateSheet.present(host, graph, session.id)
            SessionActionId.CopyMetadata -> copyMetadata(record, withLogs = false)
            SessionActionId.CopyMetadataAndLogs -> copyMetadata(record, withLogs = true)
            SessionActionId.LeaveTask -> leave(session.id)
            SessionActionId.Archive -> archive(session.id)
        }
    }

    /** happy-app's HappyError: a failure with the words to show. */
    private class Failure(message: String) : Exception(message)

    /** useHappyAction: a [Failure] shows its message; anything else is "Unknown error". */
    private fun perform(key: String, work: suspend () -> Unit) {
        if (!running.add(key)) return
        scope.launch {
            try {
                work()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val message = (e as? Failure)?.message ?: context.getString(R.string.session_info_unknown_error)
                Alerts.show(context, context.getString(R.string.session_info_action_error), message)
            } finally {
                running.remove(key)
            }
        }
    }

    private suspend fun resume(session: Session, machine: Machine?) {
        val availability = resumeAvailability(session, machine)
        val machineId = session.metadata?.machineId
        if (!availability.canResume || machineId.isNullOrEmpty()) throw Failure(resumeMessage(availability.block ?: ResumeBlock.MissingMachine))
        val ops = graph.account.value?.let { AccountResumeOps(it) { graph.settings.settings.value } }
        when (val outcome = SessionResumer.of(graph).resume(ops, session.id, machineId, session.metadata, resumeTexts(context.resources))) {
            is ResumeOutcome.Resumed -> open(outcome.sessionId)
            is ResumeOutcome.Failed -> throw Failure(outcome.message)
            ResumeOutcome.InFlight -> Unit
        }
    }

    private fun resumeMessage(block: ResumeBlock): String = context.getString(
        when (block) {
            ResumeBlock.MissingMachine -> R.string.session_info_resume_session_missing_machine
            ResumeBlock.MissingBackendId -> R.string.session_info_resume_session_missing_backend_id
            ResumeBlock.SameMachineOnly -> R.string.session_info_resume_session_same_machine_only
            ResumeBlock.MachineOffline -> R.string.session_info_resume_session_machine_offline
        },
    )

    private suspend fun fork(session: Session) {
        val source = session.forkSource() ?: throw Failure(context.getString(R.string.session_fork_error_missing_metadata))
        val machineOps = graph.account.value?.machineOps ?: throw Failure(context.getString(R.string.session_fork_error_generic))
        when (val result = machineOps.forkAndSpawn(source)) {
            is SpawnSessionResult.Success -> open(result.sessionId)
            is SpawnSessionResult.Error -> throw Failure(result.errorMessage)
            else -> throw Failure(context.getString(R.string.session_fork_error_generic))
        }
    }

    private fun copyMetadata(record: SessionRecord, withLogs: Boolean) {
        scope.launch {
            val text = withContext(Dispatchers.Default) {
                parseJsonObject(record.metadataJson)?.let { SessionInfoText.metadataClipboard(it, withLogs) }
            }
            if (text == null) {
                Alerts.show(context, context.getString(R.string.common_error), context.getString(R.string.session_info_failed_to_copy_metadata))
                return@launch
            }
            (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText(null, text))
            Alerts.show(context, context.getString(R.string.common_success), context.getString(R.string.session_info_metadata_copied))
        }
    }

    /** Before an archive starts: the chat screen moves off the tab that is going. */
    var onBeforeArchive: ((String) -> Unit)? = null

    /**
     * The row leaves at once ([com.slopus.happy.home.SessionArchiver.archiving])
     * and comes back only if the machine and the server both refuse; that
     * failure is shown on whatever this account has on screen by then, and
     * nothing is shown once another account is open.
     */
    fun archive(sessionId: String) = retire(sessionId, leave = false)

    fun leave(sessionId: String) = retire(sessionId, leave = true)

    private fun retire(sessionId: String, leave: Boolean) {
        val account = graph.account.value ?: return
        val appContext = context
        detached.launch {
            val record = withContext(Dispatchers.IO) { graph.store.session(sessionId) } ?: return@launch
            if (graph.account.value !== account) return@launch
            val session = Session.from(record)
            if (leave && !session.canLeaveTask || !leave && session.isTask && !session.taskCanArchive) return@launch
            val archiver = HomeServices.of(graph).archiver
            onBeforeArchive?.invoke(sessionId)
            if (graph.account.value !== account) return@launch
            val result = try {
                if (leave) archiver.leave(session) else archiver.archive(session)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                ArchiveResult.Failed(null, bot = false)
            }
            if (result !is ArchiveResult.Failed || graph.account.value !== account) return@launch
            val message = when {
                result.bot -> BOT_ARCHIVE_MESSAGE
                leave -> result.message?.takeIf { it.isNotEmpty() } ?: "Failed to leave task"
                session.isTask -> result.message?.takeIf { it.isNotEmpty() } ?: TASK_ARCHIVE_MESSAGE
                else -> result.message?.takeIf { it.isNotEmpty() } ?: appContext.getString(R.string.session_info_failed_to_archive_session)
            }
            Alerts.show(appContext, appContext.getString(R.string.common_error), message)
        }
    }

    private fun open(sessionId: String) {
        if (host.stack != null) host.push(ChatScreen(context, graph, sessionId))
    }

    private companion object {
        /** Hard-coded in useSessionQuickActions.ts, not in en.ts (happy-ios `Strings.SessionInfo.botNeedsMachine`, `Strings.Tasks.needsMachine`). */
        const val BOT_ARCHIVE_MESSAGE = "Connect to the bot’s machine to archive it."
        const val TASK_ARCHIVE_MESSAGE = "Connect to the task’s machine to archive it."
    }
}
