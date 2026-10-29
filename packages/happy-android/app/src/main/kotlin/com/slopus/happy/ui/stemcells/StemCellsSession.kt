package com.slopus.happy.ui.stemcells

import com.slopus.happy.direct.DirectAgentClient
import com.slopus.happy.direct.DirectSyncEngine
import com.slopus.happy.stemcells.StemCellDrafts
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
    /** The drafts kept on this phone, by agent, beside the computer's offline copy. */
    val drafts = StemCellDrafts(engine.offline?.file)
    var snapshot = StemCellsSnapshot()
        private set
    /** The row whose conversation was opened last; it stays selected. */
    var selectedRowId: String? = null
    private val scope: CoroutineScope = MainScope()
    /** The models the composer's pickers offer, and the dock's pick. */
    val models = StemCellModelState(engine.client, engine.offline, scope)
    private val watchers = LinkedHashMap<Any, (StemCellsSnapshot) -> Unit>()
    /** The computer's Stories, made when first asked for, on the store's one event stream (happy-ios `StoriesSession.session(for:)`). */
    val stories: com.slopus.happy.ui.stories.StoriesSession by lazy {
        storiesMade = true
        com.slopus.happy.ui.stories.StoriesSession(accountId, engine.client, engine.offline?.file, null) { store.events(listOf("story.")) }
    }
    private var storiesMade = false
    private val following: Job
    private var carryWork: Job? = null

    /** The drop must reach the store before buffered document events are released. */
    private fun carryInOrder(work: suspend () -> Unit) {
        val previous = carryWork
        carryWork = scope.launch { previous?.join(); work() }
    }

    fun setDragging(active: Boolean) = carryInOrder {
        if (active) store.attentionInput().join()
        store.setDragging(active).join()
    }

    fun drop(drag: com.slopus.happy.stemcells.StemCellsDrag, to: Int) = carryInOrder {
        drag.after(to)?.let { store.move(drag.moving, it.after).join() }
    }

    init {
        models.load()
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
            if (session.storiesMade) session.stories.close()
        }
    }
}
