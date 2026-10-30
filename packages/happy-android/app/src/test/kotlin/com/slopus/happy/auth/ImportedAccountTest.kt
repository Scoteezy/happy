package com.slopus.happy.auth

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.slopus.happy.crypto.AccountEncryption
import com.slopus.happy.crypto.Base64Variant
import com.slopus.happy.crypto.encodeBase64
import com.slopus.happy.net.HappyHttp
import com.slopus.happy.testing.FakeHappyServer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.async
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class ImportedAccountTest {
    private val server = FakeHappyServer().start()
    private val credentials = AuthCredentials(server.token, encodeBase64(server.secret, Base64Variant.Url))
    private val id = server.encryption.anonId
    private val store = MemoryCredentials(credentials)
    private val registry = RecordingRegistry()
    private var imports = 0
    private val activated = mutableListOf<String>()
    private val observations = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    @After fun close() { observations.cancel(); server.close() }

    private fun repository(import: () -> ImportedAccountServer = { ImportedAccountServer.NeedsConfirmation }) = AuthRepository(
        store, AuthApi(HappyHttp(OkHttpClient(), { server.url }, "android/import-test")),
        io = Dispatchers.Unconfined, registryStore = registry, serverUrl = { "https://unrelated-native.test" },
        rnServerImport = { imports++; import() },
    ).also { auth ->
        observations.launch {
            auth.state.collect { state ->
                if (state is AuthState.SignedIn) {
                    assertTrue(registry.value!!.active!!.serverConfirmed)
                    assertEquals(state.serverUrl, registry.value!!.active!!.serverUrl)
                    activated += state.serverUrl
                }
            }
        }
    }

    @Test fun constructorDoesNoCredentialRegistryOrImportWork() {
        val auth = repository()
        assertEquals(AuthState.Restoring, auth.state.value)
        assertEquals(0, store.reads)
        assertEquals(0, registry.loads)
        assertEquals(0, imports)
        assertTrue(activated.isEmpty())
    }

    @Test fun ambiguousImportHasNoCredentialsOrActivationAndSurvivesRestart() = runTest {
        val auth = repository()
        auth.restoreStored()
        assertEquals(AuthState.PendingServer(id), auth.state.value)
        assertNull(auth.credentials(id))
        assertFalse(auth.registry.value.active!!.serverConfirmed)
        assertTrue(activated.isEmpty())
        val again = repository { error("Persisted pending imports are not reinterpreted") }
        again.restoreStored()
        assertEquals(AuthState.PendingServer(id), again.state.value)
        assertEquals(1, imports)
        assertEquals(0, store.writes)
        assertEquals(credentials, store.shared)
        assertTrue(server.authenticatedKeys.isEmpty())
    }

    @Test fun importedHostNeverComesFromNativePreference() = runTest {
        val auth = repository { ImportedAccountServer.Confirmed("https://rn-host.test") }
        auth.restoreStored()
        assertEquals(listOf("https://rn-host.test"), activated)
        assertEquals(credentials, (auth.state.value as AuthState.SignedIn).credentials)
        assertEquals("https://rn-host.test", registry.value!!.active!!.serverUrl)
        assertEquals(0, store.writes)
    }

    @Test fun replacingSharedAccountDoesNotInheritTheOldSlotsHost() = runTest {
        registry.value = AccountRegistry(listOf(AccountRegistryEntry("0123456789abcdef", "https://old-account.test", CredentialLocation.Shared, 0)), "0123456789abcdef")
        val auth = repository()
        auth.restoreStored()
        assertEquals(AuthState.PendingServer(id), auth.state.value)
        assertEquals(listOf(id), auth.registry.value.entries.map { it.id })
        assertNotEquals("https://old-account.test", auth.registry.value.active!!.serverUrl)
        assertTrue(activated.isEmpty())
    }

    @Test fun knownConfirmedBindingIsNotOverwrittenByRnNoise() = runTest {
        registry.value = AccountRegistry(listOf(AccountRegistryEntry(id, "https://known.test", CredentialLocation.Shared, 0)), id)
        val auth = repository { error("Must not reread MMKV for a known account") }
        auth.restoreStored()
        assertEquals(listOf("https://known.test"), activated)
        assertEquals(0, imports)
        assertTrue(auth.state.value is AuthState.SignedIn)
    }

    @Test fun invalidChoiceAndFailedPersistenceStayPendingWithoutRewritingCredentials() = runTest {
        val auth = repository().also { it.restoreStored() }
        for (url in listOf("", "bad host", "ftp://self.test")) assertTrue(runCatching { auth.confirmServer(id, url) }.isFailure)
        registry.failSave = true
        assertTrue(runCatching { auth.confirmServer(id, "https://confirmed.test") }.exceptionOrNull() is IOException)
        assertEquals(AuthState.PendingServer(id), auth.state.value)
        assertFalse(auth.registry.value.active!!.serverConfirmed)
        assertFalse(registry.value!!.active!!.serverConfirmed)
        assertNull(auth.credentials(id))
        assertTrue(activated.isEmpty())
        assertEquals(0, store.writes)
        assertEquals(credentials, store.shared)
    }

    @Test fun confirmationPersistsThenBindsHostBeforeCredentialsArePublished() = runTest {
        val auth = repository().also { it.restoreStored() }
        auth.confirmServer(id, " https://confirmed.test/path/// ")
        assertEquals(listOf("https://confirmed.test/path"), activated)
        assertEquals(credentials, (auth.state.value as AuthState.SignedIn).credentials)
        assertEquals(credentials, auth.credentials(id))
        val again = repository { error("Already confirmed") }
        again.restoreStored()
        assertEquals(credentials, (again.state.value as AuthState.SignedIn).credentials)
        assertEquals(listOf("https://confirmed.test/path", "https://confirmed.test/path"), activated)
        assertEquals(0, store.writes)
        assertTrue(server.authenticatedKeys.isEmpty()) // Confirmation itself does not probe or authenticate.
    }

    @Test fun staleConfirmationCannotSwitchAnotherSelectedAccount() = runTest {
        val auth = repository().also { it.restoreStored() }
        val secret = ByteArray(32) { 42 }
        auth.signIn("synthetic-second-token", secret)
        val second = AccountEncryption.create(secret).anonId
        assertTrue(runCatching { auth.confirmServer(id, "https://wrong.test") }.isFailure)
        assertEquals(second, (auth.state.value as AuthState.SignedIn).encryption.anonId)
        auth.switchTo(id)
        assertEquals(AuthState.PendingServer(id), auth.state.value)
        assertNull(auth.credentials(id))
    }

    @Test fun changedCredentialIdentityCannotBeConfirmedFromAnOldScreen() = runTest {
        val auth = repository().also { it.restoreStored() }
        store.shared = AuthCredentials("replacement", encodeBase64(ByteArray(32) { 8 }, Base64Variant.Url))
        assertTrue(runCatching { auth.confirmServer(id, "https://confirmed.test") }.isFailure)
        assertEquals(AuthState.PendingServer(id), auth.state.value)
        assertFalse(registry.value!!.active!!.serverConfirmed)
        assertTrue(activated.isEmpty())
    }

    @Test fun signingInTheSamePendingAccountCannotBypassHostConfirmation() = runTest {
        val auth = repository().also { it.restoreStored() }
        assertTrue(runCatching { auth.signIn(credentials.token, credentials.secretBytes()) }.isFailure)
        assertTrue(runCatching { auth.restore(credentials.secret) }.isFailure)
        assertEquals(AuthState.PendingServer(id), auth.state.value)
        assertTrue(server.requests.isEmpty())
        assertEquals(0, store.writes)
        assertTrue(activated.isEmpty())
    }

    @Test fun bootstrapSaveFailureDoesNotPublishCredentialsAndCanRetry() = runTest {
        registry.failSave = true
        val auth = repository { ImportedAccountServer.Confirmed("https://rn-host.test") }
        assertTrue(runCatching { auth.restoreStored() }.exceptionOrNull() is IOException)
        assertEquals(AuthState.SignedOut, auth.state.value)
        assertTrue(auth.registry.value.entries.isEmpty())
        assertTrue(activated.isEmpty())
        registry.failSave = false
        auth.restoreStored()
        assertTrue(auth.state.value is AuthState.SignedIn)
        assertEquals(listOf("https://rn-host.test"), activated)
    }

    @Test fun restoreRunsOffCallerThreadAndConcurrentSignInWaitsForImport() = runBlocking {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val caller = Thread.currentThread()
        Executors.newSingleThreadExecutor { Thread(it, "synthetic-import-io") }.asCoroutineDispatcher().use { io ->
            val auth = AuthRepository(store, AuthApi(HappyHttp(OkHttpClient(), { server.url }, "android/import-test")),
                io = io, registryStore = registry, serverUrl = { server.url }, rnServerImport = {
                    assertNotSame(caller, Thread.currentThread())
                    entered.countDown()
                    check(release.await(5, TimeUnit.SECONDS))
                    ImportedAccountServer.NeedsConfirmation
                })
            val restore = async(Dispatchers.Default) { auth.restoreStored() }
            try {
                assertTrue(entered.await(5, TimeUnit.SECONDS))
                assertEquals(AuthState.Restoring, auth.state.value)
                val login = async(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) { auth.createAccount() }
                assertFalse(login.isCompleted)
                assertTrue(server.authenticatedKeys.isEmpty())
                assertEquals(0, store.writes)
                release.countDown()
                restore.await()
                login.await()
                assertTrue(auth.state.value is AuthState.SignedIn)
            } finally { release.countDown() }
        }
    }

    private class MemoryCredentials(var shared: AuthCredentials?) : CredentialStore {
        val own = mutableMapOf<String, AuthCredentials>()
        var reads = 0
        var writes = 0
        override fun read() = shared.also { reads++ }
        override fun read(accountId: String) = own[accountId].also { reads++ }
        override fun write(credentials: AuthCredentials) { writes++; shared = credentials }
        override fun write(accountId: String, credentials: AuthCredentials) { writes++; own[accountId] = credentials }
        override fun clear() { writes++; shared = null }
        override fun clear(accountId: String) { writes++; own.remove(accountId) }
    }

    private class RecordingRegistry : AccountRegistryStore {
        var value: AccountRegistry? = null
        var failSave = false
        var loads = 0
        override fun load() = value.also { loads++ }
        override fun save(registry: AccountRegistry) {
            if (failSave) throw IOException("Synthetic registry write failure")
            value = registry
        }
    }
}