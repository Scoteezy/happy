package com.slopus.happy.home

import com.slopus.happy.model.HomeModelFixtures
import com.slopus.happy.model.Settings
import com.slopus.happy.model.settings.LocalSettingsValues
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The home list is kept warm between visits, but not across accounts: a
 * null snapshot (the account changed) clears it, so the next account's
 * home starts loading instead of showing the previous account's sessions.
 */
class HomeListModelTest {
    @Test
    fun aNullSnapshotClearsTheListUntilTheNextAccountsIsIn(): Unit = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val snapshots = MutableStateFlow<HomeSnapshot?>(null)
            val model = HomeListModel(
                snapshots = snapshots,
                settings = MutableStateFlow(Settings()),
                localSettings = MutableStateFlow(LocalSettingsValues()),
                unreadSessionIds = MutableStateFlow(emptySet()),
                archivingSessionIds = MutableStateFlow(emptySet()),
                scope = scope,
            )
            val machine = HomeModelFixtures.machine("m1")
            snapshots.value = HomeSnapshot(listOf(HomeModelFixtures.session("first")), listOf(machine))
            withTimeout(5_000) { model.state.first { it != null } }

            snapshots.value = null
            withTimeout(5_000) { model.state.first { it == null } }

            // The next account's first snapshot, not yet ready: still loading.
            snapshots.value = HomeSnapshot(emptyList(), emptyList(), ready = false)
            Thread.sleep(100)
            assertEquals(null, model.state.value)

            snapshots.value = HomeSnapshot(emptyList(), listOf(machine))
            withTimeout(5_000) { model.state.first { it != null } }
        } finally {
            scope.cancel()
        }
    }
}
