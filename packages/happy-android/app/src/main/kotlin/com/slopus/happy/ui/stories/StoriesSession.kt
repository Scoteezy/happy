package com.slopus.happy.ui.stories

import com.slopus.happy.direct.DirectAgentClient
import com.slopus.happy.direct.DirectEvent
import com.slopus.happy.direct.DirectOfflineFile
import com.slopus.happy.stories.StoriesSnapshot
import com.slopus.happy.stories.StoriesStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import java.io.File

/**
 * One paired computer's Stories (happy-ios `StoriesSession`), shared by the
 * row, the viewer, the editor and every inline story: the store and the
 * snapshots it hands out. The store runs on a dispatcher of its own, one
 * coroutine at a time, as the Swift actor does; watchers hear on the main
 * thread.
 */
class StoriesSession(
    val accountId: String,
    val client: DirectAgentClient,
    offline: DirectOfflineFile?,
    stickerDirectory: File?,
    events: suspend () -> Flow<DirectEvent>,
) {
    @OptIn(ExperimentalCoroutinesApi::class)
    private val storeScope = CoroutineScope(SupervisorJob() + Dispatchers.Default.limitedParallelism(1))
    private val main = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val store = StoriesStore(client, events, offline, storeScope)
    val media = StoriesMedia(client, stickerDirectory)

    val snapshot: StoriesSnapshot get() = store.updates.value

    init {
        store.start()
    }

    /** Calls [watch] on the main thread with every snapshot from now on, starting with the current one. */
    fun watch(watch: (StoriesSnapshot) -> Unit): Job = main.launch {
        var last: StoriesSnapshot? = null
        store.updates.collect { snapshot ->
            if (snapshot == last) return@collect
            last = snapshot
            watch(snapshot)
        }
    }

    /** Leaving the foreground: what waits to be kept is saved. */
    fun flush() = store.flush()

    fun close() {
        store.stop()
        media.close()
        main.cancel()
    }
}
