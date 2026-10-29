package com.slopus.happy.ui.stemcells

import com.slopus.happy.direct.DirectAgentClient
import com.slopus.happy.stemcells.AgentMode
import com.slopus.happy.stemcells.StemCellAccountUsage
import com.slopus.happy.stemcells.StemCellModelCatalog
import com.slopus.happy.stemcells.StemCellsOfflineFile
import com.slopus.happy.stemcells.accountUsage
import com.slopus.happy.stemcells.modelCatalogResponse
import com.slopus.happy.stemcells.refreshAccountUsage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * One paired computer's models and its accounts' usage, read for the
 * composer's pickers, and the pick the home dock holds for new work
 * (happy-ios `StemCellModelState`). Read and changed on the main thread.
 */
class StemCellModelState(
    private val client: DirectAgentClient,
    private val offline: StemCellsOfflineFile?,
    private val scope: CoroutineScope,
) {
    var catalog: StemCellModelCatalog? = null
        private set
    var usage: Map<String, StemCellAccountUsage> = emptyMap()
        private set

    /** What new work is sent with: picked on the dock before the work exists. */
    var dockMode: AgentMode? = null
        set(value) {
            if (value == field) return
            field = value
            changed()
            val offline = offline ?: return
            scope.launch(Dispatchers.IO) { offline.update { it.copy(dockMode = value) } }
        }

    private val watchers = LinkedHashMap<Any, () -> Unit>()
    private var loading: Job? = null
    private var refreshing: Job? = null

    /** The dock's pick, or the computer's default until one is made. */
    val newWorkMode: AgentMode? get() = dockMode ?: catalog?.defaultMode()

    /** Calls [watch] on every change from now on; the result stops it. */
    fun watch(watch: () -> Unit): () -> Unit {
        val key = Any()
        watchers[key] = watch
        return { watchers.remove(key) }
    }

    private fun changed() {
        for (watcher in watchers.values.toList()) watcher()
    }

    /**
     * Reads the catalog, once at a time: what was kept first, so the
     * pickers are drawn offline too, then the computer's own.
     */
    fun load() {
        if (loading != null) return
        loading = scope.launch {
            if (catalog == null && offline != null) {
                val kept = withContext(Dispatchers.IO) { offline.read() }
                val read = kept?.config?.let { runCatching { StemCellModelCatalog.from(DirectAgentClient.parse(it.toByteArray())) }.getOrNull() }
                if (catalog == null && read != null) {
                    catalog = read
                    changed()
                }
                if (dockMode == null) kept?.dockMode?.let { dockMode = it }
            }
            val read = runCatching { client.modelCatalogResponse() }.getOrNull()
            loading = null
            read ?: return@launch
            if (read.first != catalog) {
                catalog = read.first
                changed()
            }
            val offline = offline ?: return@launch
            withContext(Dispatchers.IO) { offline.update { it.copy(config = read.second) } }
        }
    }

    /**
     * The accounts' readings now, then fresher ones for [accounts] once the
     * computer has asked their services (desktop asks as its menu opens).
     */
    fun refreshUsage(accounts: List<String>) {
        refreshing?.cancel()
        refreshing = scope.launch {
            runCatching { client.accountUsage() }.getOrNull()?.let(::apply)
            runCatching { client.refreshAccountUsage(accounts) }
            repeat(3) {
                delay(2000)
                runCatching { client.accountUsage() }.getOrNull()?.let(::apply)
            }
        }
    }

    private fun apply(usage: Map<String, StemCellAccountUsage>) {
        if (usage == this.usage) return
        this.usage = usage
        changed()
    }
}
