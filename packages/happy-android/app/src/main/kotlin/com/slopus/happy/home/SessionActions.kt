package com.slopus.happy.home

import com.slopus.happy.model.Session
import com.slopus.happy.model.isTask
import com.slopus.happy.model.canLeaveTask
import com.slopus.happy.sync.OpResult
import com.slopus.happy.sync.SessionOps
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The session operations the home list and session info run (sync/ops.ts),
 * as [SessionOps] answers them; tests use a fake.
 */
interface SessionActions {
    /** Bind a multi-step operation to its current account, never a later one. */
    fun forOperation(): SessionActions = this

    /** sessionKill: the `killSession` RPC to the session's agent. */
    suspend fun kill(sessionId: String): OpResult

    /** sessionArchive: the server retires a session whose agent is already gone. */
    suspend fun archiveOnServer(sessionId: String): OpResult

    /** sessionDelete: the session and everything in it, permanently. */
    suspend fun delete(sessionId: String): OpResult

    /** A task's machine may refuse archive; never fall back to server retirement. */
    suspend fun archiveTask(sessionId: String): OpResult = kill(sessionId)

    suspend fun leaveTask(sessionId: String): OpResult = OpResult(false)
}

/** [SessionActions] over the signed-in account's [SessionOps]; signed out, nothing can be reached. */
class LiveSessionActions(private val ops: () -> SessionOps?) : SessionActions {
    override fun forOperation(): SessionActions {
        val owner = ops()
        return LiveSessionActions { owner?.takeIf { ops() === owner } }
    }

    override suspend fun kill(sessionId: String): OpResult = ops()?.kill(sessionId) ?: SIGNED_OUT

    override suspend fun archiveOnServer(sessionId: String): OpResult = ops()?.archive(sessionId) ?: SIGNED_OUT

    override suspend fun delete(sessionId: String): OpResult = ops()?.delete(sessionId) ?: SIGNED_OUT

    override suspend fun archiveTask(sessionId: String): OpResult = ops()?.archiveTask(sessionId) ?: SIGNED_OUT

    override suspend fun leaveTask(sessionId: String): OpResult = ops()?.leaveTask(sessionId) ?: SIGNED_OUT

    private companion object {
        val SIGNED_OUT = OpResult(false)
    }
}

/** Why an archive failed, for the alert: the bot case has its own words in happy-app. */
sealed interface ArchiveResult {
    data object Archived : ArchiveResult
    data object InFlight : ArchiveResult
    data class Failed(val message: String?, val bot: Boolean) : ArchiveResult
}

/**
 * useSessionQuickActions' archive. The row leaves the list on the press
 * ([archiving]) and only comes back if the archive fails. A bot or task is only
 * killed; anything else first gets [archive]'s `cleanup` (maybeCleanupWorktree's
 * prompt to delete its worktree), then is killed, and retired on the server
 * when its agent can't be reached.
 */
class SessionArchiver(private val actions: SessionActions, private val ownerKey: () -> Any? = { Unit }) {
    private val state = MutableStateFlow<Set<String>>(emptySet())
    private val lock = Any()
    private var generation = 0L
    private var currentOwner = ownerKey()

    /** storage.ts archivingSessionIds: archived here, not yet confirmed by the server. */
    val archiving: StateFlow<Set<String>> = state.asStateFlow()

    /** Account replacement clears local retirement, including completed old operations. */
    fun reset() = synchronized(lock) {
        generation++
        currentOwner = ownerKey()
        state.value = emptySet()
    }

    /** Also checked synchronously by begin, before an account-change collector can run. */
    fun accountChanged() = synchronized(lock) {
        val next = ownerKey()
        if (next !== currentOwner) {
            currentOwner = next
            generation++
            state.value = emptySet()
        }
    }

    private fun begin(id: String): Long? = synchronized(lock) {
        accountChanged()
        if (id in state.value) return@synchronized null
        state.value = state.value + id
        generation
    }

    private fun restore(id: String, owner: Long) = synchronized(lock) {
        if (generation == owner) state.value = state.value - id
    }

    suspend fun archive(session: Session, cleanup: suspend () -> Unit = {}): ArchiveResult {
        val operation = actions.forOperation()
        val owner = begin(session.id) ?: return ArchiveResult.InFlight
        val bot = session.metadata?.bot != null
        val result = try {
            if (session.isTask) {
                operation.archiveTask(session.id)
            } else if (bot) {
                operation.kill(session.id)
            } else {
                cleanup()
                operation.kill(session.id).takeIf { it.success } ?: operation.archiveOnServer(session.id)
            }
        } catch (e: Throwable) {
            restore(session.id, owner)
            throw e
        }
        if (result.success) return ArchiveResult.Archived
        restore(session.id, owner)
        return ArchiveResult.Failed(result.message, bot)
    }

    /** Leave hides a task immediately and restores it on refusal, disconnect or cancellation. */
    suspend fun leave(session: Session): ArchiveResult {
        if (!session.canLeaveTask) return ArchiveResult.Failed(null, bot = false)
        val operation = actions.forOperation()
        val owner = begin(session.id) ?: return ArchiveResult.InFlight
        val result = try {
            operation.leaveTask(session.id)
        } catch (e: Throwable) {
            restore(session.id, owner)
            throw e
        }
        if (result.success) return ArchiveResult.Archived
        restore(session.id, owner)
        return ArchiveResult.Failed(result.message, bot = false)
    }
}
