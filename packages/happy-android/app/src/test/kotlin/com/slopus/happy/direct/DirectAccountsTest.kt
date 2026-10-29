package com.slopus.happy.direct

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.slopus.happy.auth.ExpoSecureStore
import com.slopus.happy.testing.SoftwareKeys
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import kotlin.coroutines.EmptyCoroutineContext

/** A computer's identity routes (happy-ios `IdentityTransport`). */
private class IdentityTransport : DirectTransport {
    var name = "Verified Mac"
    val image = byteArrayOf(1, 2, 3)
    var etag = "\"v1\""
    var revoked = false
    var noAvatar = false
    val paths = ArrayList<String>()
    var closed = false
    var failuresRemaining = 0
    val patches = ArrayList<ByteArray>()

    override suspend fun request(method: String, path: String, credential: String, body: ByteArray?, headers: Map<String, String>): DirectHttpResponse {
        paths += path
        if (method == "PATCH") {
            patches += body ?: ByteArray(0)
            ((DirectAgentClient.parse(body!!) as JsonObject)["node"] as? JsonObject)?.get("name")?.let { name = (it as JsonPrimitive).content }
        }
        if (failuresRemaining > 0) { failuresRemaining -= 1; throw DirectPairingError(DirectPairingError.Kind.Unavailable) }
        if (revoked) return DirectHttpResponse(401)
        return when (path) {
            "/v0/health" -> DirectHttpResponse(200, body = """{"ready":true,"version":{"protocol":27}}""".toByteArray())
            "/v0/config" -> {
                val avatar = if (noAvatar) "null" else """{"thumbhash":"same-placeholder"}"""
                DirectHttpResponse(200, body = """{"config":{"node":{"name":${JsonPrimitive(name)},"avatar":$avatar}}}""".toByteArray())
            }
            "/v0/node/avatar" ->
                if (headers["If-None-Match"] == etag) DirectHttpResponse(304)
                else DirectHttpResponse(200, mapOf("content-type" to "image/png", "etag" to etag), image)
            else -> throw DirectPairingError(DirectPairingError.Kind.InvalidResponse)
        }
    }

    override suspend fun close() { closed = true }
}

/** Answers health only after 30 s, to prove a late identity cannot bring a removed computer back. */
private class LateIdentityTransport : DirectTransport {
    var started = false
    var closed = false

    override suspend fun request(method: String, path: String, credential: String, body: ByteArray?, headers: Map<String, String>): DirectHttpResponse {
        if (path == "/v0/health") {
            started = true
            delay(30_000)
            return DirectHttpResponse(200, body = """{"ready":true,"version":{"protocol":27}}""".toByteArray())
        }
        return DirectHttpResponse(200, body = """{"config":{"node":{"name":"Late response","avatar":null}}}""".toByteArray())
    }

    override suspend fun close() { closed = true }
}

/** happy-ios `DirectAccountsTests`, but for the relay account controller's, which the Android account switcher covers. */
@RunWith(RobolectricTestRunner::class)
class DirectAccountsTest {
    @get:Rule val folder = TemporaryFolder()

    private fun value(id: String = "device") = DirectDeviceCredentials("tcAddress", 24781, id, CanonicalBase64Url.encode(ByteArray(32) { 7 }))

    private fun TestScope.accounts(credentials: DirectCredentialStore, directory: File?, refreshIntervalMs: Long = 60_000, transport: (DirectDeviceCredentials) -> DirectTransport) =
        DirectAccounts(
            credentials, directory, backgroundScope, transport,
            makeEngine = { _, client -> DirectSyncEngine(client, null, backgroundScope, EmptyCoroutineContext) },
            refreshIntervalMs = refreshIntervalMs, io = EmptyCoroutineContext,
        )

    private suspend fun until(condition: () -> Boolean) {
        repeat(300) {
            if (condition()) return
            delay(10)
        }
    }

    @Test fun secureStoreRecoverySelectionAndRemoval() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = context.getSharedPreferences(ExpoSecureStore.PREFS_NAME, Context.MODE_PRIVATE).also { it.edit().clear().commit() }
        val store = SecureStoreDirectCredentialStore(ExpoSecureStore(prefs, SoftwareKeys()))
        val directory = folder.newFolder()
        val manager = accounts(store, directory) { IdentityTransport() }
        manager.setForeground(false)
        val first = manager.add(value())
        assertEquals("Verified Mac", first.name)
        assertEquals(value(), store.all()[first.id])
        // The list on disk names computers; their addresses and tokens stay in the secure store.
        val registry = File(directory, DirectAccountStorage.FILE_NAME).readText()
        assertFalse(registry.contains(value().deviceToken))
        assertFalse(registry.contains(value().address))
        assertFalse(prefs.all.values.any { it.toString().contains(value().deviceToken) })

        val restored = accounts(store, directory) { IdentityTransport() }
        restored.setForeground(false)
        restored.restore()
        assertEquals(first.id, restored.state.value.activeId)
        assertEquals("Verified Mac", restored.state.value.accounts.first().name)
        assertArrayEquals(byteArrayOf(1, 2, 3), restored.state.value.accounts.first().avatar)
        restored.remove(first.id)
        assertTrue(store.all().isEmpty())
        assertNull(restored.state.value.activeId)
        assertTrue(restored.state.value.accounts.isEmpty())
    }

    @Test fun identityETagRefreshRenameAndAvatarRemoval() = runTest {
        val transport = IdentityTransport()
        val first = DirectIdentity.refresh(DirectAccount("direct-one", "QR name"), transport, "token")
        assertEquals("Verified Mac", first.name)
        assertArrayEquals(byteArrayOf(1, 2, 3), first.avatar)
        transport.name = "Renamed Mac"
        val second = DirectIdentity.refresh(first, transport, "token")
        assertEquals("Renamed Mac", second.name)
        assertArrayEquals(first.avatar, second.avatar)
        transport.etag = "\"v2\""
        val third = DirectIdentity.refresh(second, transport, "token")
        assertEquals("\"v2\"", third.avatarETag)
        transport.noAvatar = true
        val last = DirectIdentity.refresh(third, transport, "token")
        assertNull(last.avatar)
        assertNull(last.avatarETag)
    }

    @Test fun renameSendsTheNodeNameAndShowsItAtOnce() = runTest {
        val transport = IdentityTransport()
        val manager = accounts(InMemoryDirectCredentialStore(), folder.newFolder()) { transport }
        manager.setForeground(false)
        val account = manager.add(value())
        manager.rename(account.id, "Studio Mac")
        assertEquals("Studio Mac", manager.state.value.accounts.first().name)
        assertEquals(1, transport.patches.size)
        assertEquals("""{"node":{"name":"Studio Mac"}}""", transport.patches[0].decodeToString())
    }

    @Test fun revocationStopsRetryAndBackgroundPauses() = runTest {
        val transport = IdentityTransport()
        val manager = accounts(InMemoryDirectCredentialStore(), null, refreshIntervalMs = 5) { transport }
        manager.setForeground(false)
        val account = manager.add(value())
        transport.revoked = true
        manager.setForeground(true)
        until { manager.state.value.accounts.first().status == DirectConnectionStatus.Revoked }
        assertEquals(DirectConnectionStatus.Revoked, manager.state.value.accounts.first().status)
        val count = transport.paths.size
        delay(30)
        assertEquals(count, transport.paths.size)
        manager.setForeground(false)
        manager.setForeground(true)
        assertEquals(count, transport.paths.size)
        manager.remove(account.id)
        assertTrue(manager.state.value.accounts.isEmpty())
        assertEquals(1, DirectAccounts.retryDelay(0))
        assertEquals(30, DirectAccounts.retryDelay(100))
    }

    @Test fun removalAwaitsCancellationAndDiscardsLateIdentity() = runTest {
        val store = InMemoryDirectCredentialStore()
        store.save(value(), "direct-cancel")
        val transport = LateIdentityTransport()
        val manager = accounts(store, null) { transport }
        manager.restore()
        until { transport.started }
        manager.remove("direct-cancel")
        assertTrue(manager.state.value.accounts.isEmpty())
        assertTrue(store.all().isEmpty())
        assertTrue(transport.closed)
        manager.setForeground(false)
        manager.setForeground(true)
        delay(60_000)
        assertTrue(manager.state.value.accounts.isEmpty())
    }

    @Test fun failedInstanceRetriesWithoutAffectingAnother() = runTest {
        val failing = IdentityTransport()
        val healthy = IdentityTransport()
        val manager = accounts(InMemoryDirectCredentialStore(), null) { if (it.deviceId == "failing") failing else healthy }
        manager.setForeground(false)
        failing.failuresRemaining = 2
        val first = manager.add(value("failing"))
        val second = manager.add(value("healthy"))
        manager.setForeground(true)
        until { manager.state.value.accounts.all { it.status == DirectConnectionStatus.Connected } }
        assertEquals(DirectConnectionStatus.Connected, manager.state.value.accounts.first { it.id == first.id }.status)
        assertEquals(DirectConnectionStatus.Connected, manager.state.value.accounts.first { it.id == second.id }.status)
        assertTrue(failing.paths.count { it == "/v0/health" } >= 3)
        manager.setForeground(false)
        assertTrue(manager.state.value.accounts.all { it.status == DirectConnectionStatus.Paused })
    }

    @Test fun syncEnginesFollowTheActiveComputer() = runTest {
        val manager = accounts(InMemoryDirectCredentialStore(), null) { IdentityTransport() }
        manager.setForeground(false)
        val first = manager.add(value("one"))
        val second = manager.add(value("two"))
        assertEquals(second.id, manager.state.value.activeId)
        val engine = manager.syncEngine(first.id)
        assertTrue(engine === manager.syncEngine(first.id))
        assertNull(manager.syncEngine("unknown"))
        manager.select(first.id)
        assertEquals(first.id, manager.state.value.active?.id)
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test fun selectionCanCreateTheIncomingHomesEngineWhileOldEnginesPause() = runTest {
        val manager = accounts(InMemoryDirectCredentialStore(), null) { IdentityTransport() }
        manager.setForeground(false)
        val first = manager.add(value("one"))
        val second = manager.add(value("two"))
        val incoming = manager.add(value("incoming"))
        manager.syncEngine(first.id)
        manager.syncEngine(second.id)
        manager.select(first.id)
        // The real coordinator and Settings observe selection and lazily create
        // its engine while select() is suspended in an old engine's pause().
        backgroundScope.launch {
            manager.state.collect { state ->
                if (state.activeId == incoming.id) manager.syncEngine(incoming.id)
            }
        }
        runCurrent()
        manager.select(incoming.id)
        assertEquals(incoming.id, manager.state.value.activeId)
        assertTrue(manager.syncEngine(incoming.id) === manager.syncEngine(incoming.id))
    }
}
