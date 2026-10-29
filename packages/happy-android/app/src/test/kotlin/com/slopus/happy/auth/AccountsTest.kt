package com.slopus.happy.auth

import android.content.Context
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.slopus.happy.crypto.Base64Variant
import com.slopus.happy.crypto.SecretKeyBackup
import com.slopus.happy.crypto.encodeBase64
import com.slopus.happy.net.HappyHttp
import com.slopus.happy.testing.FakeHappyServer
import com.slopus.happy.testing.SoftwareKeys
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Several accounts signed in at once (happy-ios `AccountController`'s sign-in, switch and sign-out). */
@RunWith(AndroidJUnit4::class)
class AccountsTest {
    private lateinit var server: FakeHappyServer
    private lateinit var prefs: SharedPreferences
    private val keys = SoftwareKeys()
    private val registry = AccountRegistryStore.InMemory()
    private var serverUrl = "https://api.example.com"

    @Before
    fun setUp() {
        server = FakeHappyServer().start()
        prefs = ApplicationProvider.getApplicationContext<Context>()
            .getSharedPreferences(ExpoSecureStore.PREFS_NAME, Context.MODE_PRIVATE)
            .also { it.edit().clear().commit() }
    }

    @After
    fun tearDown() = server.close()

    private fun repository() = AuthRepository(
        SecureStoreCredentialStore(ExpoSecureStore(prefs, keys)),
        AuthApi(HappyHttp(OkHttpClient(), serverUrl = { server.url }, clientId = "android/test")),
        io = Dispatchers.Unconfined,
        registryStore = registry,
        serverUrl = { serverUrl },
    )

    private val AuthRepository.activeId get() = (state.value as? AuthState.SignedIn)?.encryption?.anonId

    @Test
    fun aSecondSignInAddsAnAccountAndMakesItActive() = runTest {
        val auth = repository()
        auth.restore(SecretKeyBackup.format(encodeBase64(server.secret, Base64Variant.Url)))
        val first = auth.activeId!!
        serverUrl = "https://self.example.com"
        auth.createAccount()
        val second = auth.activeId!!

        assertEquals(listOf(first, second), auth.registry.value.ordered.map { it.id })
        assertEquals(listOf(CredentialLocation.Shared, CredentialLocation.Own), auth.registry.value.ordered.map { it.location })
        assertEquals(listOf("https://api.example.com", "https://self.example.com"), auth.registry.value.ordered.map { it.serverUrl })
        assertEquals(second, auth.registry.value.activeId)

        // Both survive a restart, the active one stays active.
        val again = repository()
        assertEquals(second, again.activeId)
        assertEquals(2, again.registry.value.entries.size)

        again.switchTo(first)
        assertEquals(first, again.activeId)
        assertEquals(first, repository().activeId)
    }

    @Test
    fun signingInAgainKeepsTheAccountsPlace() = runTest {
        val auth = repository()
        val raw = SecretKeyBackup.format(encodeBase64(server.secret, Base64Variant.Url))
        auth.restore(raw)
        auth.createAccount()
        auth.restore(raw)
        assertEquals(2, auth.registry.value.entries.size)
        assertEquals(auth.activeId, auth.registry.value.ordered.first().id)
        assertEquals(0, auth.registry.value.ordered.first().addedOrder)
    }

    @Test
    fun signingOutTheActiveAccountHandsOverToTheNext() = runTest {
        val auth = repository()
        auth.createAccount()
        val first = auth.activeId!!
        auth.createAccount()
        val second = auth.activeId!!

        auth.signOut(second)
        assertEquals(first, auth.activeId)
        assertEquals(listOf(first), auth.registry.value.entries.map { it.id })
        assertNull(SecureStoreCredentialStore(ExpoSecureStore(prefs, keys)).read(second))

        auth.logout()
        assertEquals(AuthState.SignedOut, auth.state.value)
        assertTrue(auth.registry.value.entries.isEmpty())
        assertTrue(prefs.all.isEmpty())
    }

    @Test
    fun signingOutAnotherAccountLeavesTheActiveOne() = runTest {
        val auth = repository()
        auth.createAccount()
        val first = auth.activeId!!
        auth.createAccount()
        val second = auth.activeId!!

        auth.signOut(first)
        assertEquals(second, auth.activeId)
        assertEquals(listOf(second), auth.registry.value.entries.map { it.id })
        // The shared entry is free again; the remaining account keeps its own.
        assertNull(SecureStoreCredentialStore(ExpoSecureStore(prefs, keys)).read())
    }

    @Test
    fun switchingToAnUnknownAccountFails() = runTest {
        val auth = repository()
        auth.createAccount()
        val result = runCatching { auth.switchTo("0123456789abcdef") }
        assertTrue(result.isFailure)
    }

    @Test
    fun theReactNativeBuildsAccountJoinsTheRegistry() = runTest {
        val secret = encodeBase64(server.secret, Base64Variant.Url)
        ExpoSecureStore(prefs, keys).setItem("auth_credentials", """{"token":"${server.token}","secret":"$secret"}""")

        val auth = repository()
        val entry = auth.registry.value.entries.single()
        assertEquals(server.encryption.anonId, entry.id)
        assertEquals(CredentialLocation.Shared, entry.location)
        assertEquals(entry.id, auth.activeId)
    }

    @Test
    fun theRegistryFileRoundTripsAndRejectsWhatItCantTrust() {
        val file = File(ApplicationProvider.getApplicationContext<Context>().filesDir, "registry-test/accounts.json")
        file.parentFile!!.deleteRecursively()
        val store = AccountRegistryFile(file)
        assertNull(store.load())
        val value = AccountRegistry(
            listOf(AccountRegistryEntry("0123456789abcdef", "https://api.example.com", CredentialLocation.Shared, 0, AccountDisplay(name = "Ada"))),
            activeId = "0123456789abcdef",
        )
        store.save(value)
        assertEquals(value, store.load())

        file.writeText("""{"entries":[{"id":"nothex","serverUrl":"https://api.example.com","location":"own","addedOrder":0}]}""")
        assertNull(store.load())
        file.writeText("not json")
        assertNull(store.load())
        assertFalse(file.readText().isEmpty())
    }
}
