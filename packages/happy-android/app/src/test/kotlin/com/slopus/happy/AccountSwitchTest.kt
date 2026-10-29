package com.slopus.happy

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.slopus.happy.auth.AuthState
import com.slopus.happy.crypto.Base64Variant
import com.slopus.happy.crypto.SecretKeyBackup
import com.slopus.happy.crypto.encodeBase64
import com.slopus.happy.data.OutboxKind
import com.slopus.happy.data.OutboxRecord
import com.slopus.happy.data.OutboxState
import com.slopus.happy.sync.eventually
import com.slopus.happy.testing.FakeHappyServer
import com.slopus.happy.testing.SoftwareKeys
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Each signed-in account has its own database and local settings
 * (happy-ios's per-account store): a switch puts the other account's in
 * place, and signing one out empties only its own.
 */
@RunWith(AndroidJUnit4::class)
class AccountSwitchTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val server = FakeHappyServer().start()

    @After
    fun tearDown() = server.close()

    private val AppGraph.activeId get() = (auth.state.value as? AuthState.SignedIn)?.encryption?.anonId

    @Test
    fun eachAccountKeepsItsOwnDataAcrossASwitch() {
        server.addSession("s1", updatedAt = 100)
        val graph = AppGraph(context, "android/test", server.url, SoftwareKeys(), databaseName = null)
        runBlocking { graph.auth.restore(SecretKeyBackup.format(encodeBase64(server.secret, Base64Variant.Url))) }
        val first = graph.activeId!!
        eventually(message = "first account") { graph.account.value != null }
        val firstSync = graph.account.value!!
        runBlocking { firstSync.syncNow() }
        graph.store.enqueueOutbox(listOf(failed("first")))
        graph.localSettings.change { it.copy(devModeEnabled = true) }

        runBlocking { graph.auth.createAccount() }
        val second = graph.activeId!!
        eventually(message = "second account") { graph.account.value.let { it != null && it !== firstSync } }
        runBlocking { graph.account.value!!.syncNow() }
        eventually(message = "second store") { graph.store.outbox().isEmpty() }
        assertFalse(graph.localSettings.values.value.devModeEnabled)
        assertEquals(second, graph.activeId)

        runBlocking { graph.switchAccount(first) }
        eventually(message = "back to the first store") { graph.store.outbox().map { it.localId } == listOf("first") }
        eventually(message = "the first account's settings") { graph.localSettings.values.value.devModeEnabled }

        // Signing the other one out leaves this one as it is.
        runBlocking { graph.signOut(second).join() }
        assertEquals(first, graph.activeId)
        assertEquals(listOf(first), graph.auth.registry.value.entries.map { it.id })
        assertEquals(listOf("first"), graph.store.outbox().map { it.localId })

        // The last one out: signed out, with nothing kept.
        runBlocking { graph.logout().join() }
        assertEquals(AuthState.SignedOut, graph.auth.state.value)
        eventually(message = "empty store") { graph.store.outbox().isEmpty() }
    }

    private fun failed(localId: String) =
        OutboxRecord(localId, "s1", OutboxKind.USER, "{}", "c", 1, OutboxState.FAILED, 0, "x", null, null)
}
