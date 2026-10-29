package com.slopus.happy.ui.stemcells

import com.slopus.happy.direct.DirectAgentClient
import com.slopus.happy.direct.DirectSyncEngine
import com.slopus.happy.stemcells.StemCellsSnapshot
import com.slopus.happy.stemcells.StemCellsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * One paired computer's Stem Cells, shared by every screen that shows it
 * (happy-ios `StemCellsSession`): the store, the client its conversations
 * use, the pictures it has read, and which row is open. The store's
 * snapshots are handed, on the main thread, to whoever watches.
 */
class StemCellsSession private constructor(val accountId: String, val engine: DirectSyncEngine) {
    val client: DirectAgentClient get() = engine.client
    val store: StemCellsStore get() = engine.spaces
    val images = StemCellsImages(engine.client, engine.offline)
    var snapshot = StemCellsSnapshot()
        private set
    /** The row whose conversation was opened last; it stays selected. */
    var selectedRowId: String? = null
    private val scope: CoroutineScope = MainScope()
    private val watchers = LinkedHashMap<Any, (StemCellsSnapshot) -> Unit>()
    private val following: Job

    init {
        following = scope.launch {
            store.updates.collect { snapshot ->
                this@StemCellsSession.snapshot = snapshot
                for (watcher in watchers.values.toList()) watcher(snapshot)
            }
        }
    }

    /** Calls `watch` with every snapshot from now on, starting with the current one; the result stops it. */
    fun watch(watch: (StemCellsSnapshot) -> Unit): () -> Unit {
        val key = Any()
        watchers[key] = watch
        watch(snapshot)
        return { watchers.remove(key) }
    }

    companion object {
        private val sessions = HashMap<String, StemCellsSession>()

        /** The session for a paired computer, made once and kept while the app runs. */
        fun session(accountId: String, engine: DirectSyncEngine): StemCellsSession {
            sessions[accountId]?.takeIf { it.engine === engine }?.let { return it }
            sessions.remove(accountId)?.scope?.cancel()
            return StemCellsSession(accountId, engine).also { sessions[accountId] = it }
        }

        /** Forgets a computer that was unpaired. */
        fun forget(accountId: String) {
            val session = sessions.remove(accountId) ?: return
            session.following.cancel()
            session.scope.cancel()
        }
    }
}
