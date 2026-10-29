package com.slopus.happy.auth

import android.util.Log
import com.slopus.happy.crypto.AccountEncryption
import com.slopus.happy.crypto.AuthChallenge
import com.slopus.happy.crypto.Base64Variant
import com.slopus.happy.crypto.RandomSource
import com.slopus.happy.crypto.SecretKeyBackup
import com.slopus.happy.crypto.SecureRandomSource
import com.slopus.happy.crypto.decodeBase64
import com.slopus.happy.crypto.encodeBase64
import com.slopus.happy.net.HappyJson
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import java.io.IOException

sealed interface AuthState {
    data object SignedOut : AuthState

    data class SignedIn(val credentials: AuthCredentials, val encryption: AccountEncryption) : AuthState {
        /** The account id, from the token (parseToken.ts); happy-app's `sync.serverID`. */
        val publicId: String? get() = tokenSubject(credentials.token)

        /** The secret in the backup format shown in Settings > Account. */
        val formattedSecretKey: String get() = SecretKeyBackup.format(credentials.secret)
    }
}

/** The secret key the user typed is not one. */
class InvalidSecretKeyException(cause: Throwable? = null) : IllegalArgumentException("Invalid secret key", cause)

/**
 * Account sign-in, as in happy-app: the account *is* a random 32-byte secret.
 * Creating an account and restoring one are the same exchange
 * (POST /v1/auth with a signed challenge); the server creates the account
 * the first time it sees the public key.
 *
 * Several accounts can be signed in at once (happy-ios `AccountController`):
 * [registry] lists them in the order they were added and [state] is the
 * active one. A sign-in adds its account, or signs it in again, and makes
 * it active; signing one out removes it, and when it was active the next by
 * added order takes over. The registry holds no secrets: the first account
 * keeps happy-app's own entry in [credentialStore], which the React Native
 * build reads too, and each later one an entry of its own.
 */
class AuthRepository(
    private val credentialStore: CredentialStore,
    private val api: AuthApi,
    private val random: RandomSource = SecureRandomSource,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val registryStore: AccountRegistryStore = AccountRegistryStore.InMemory(),
    /** The server a sign-in is for: the one onboarding chose. */
    private val serverUrl: () -> String = { "" },
    /** A successful sign-in, not startup restore or sign-out handover. */
    private val onSignedIn: suspend () -> Unit = {},
) {
    private val lock = Any()
    private val registryState = MutableStateFlow(bootstrap())
    private val mutableState = MutableStateFlow(activeState(registryState.value))
    private val adding = MutableStateFlow(false)

    /** The active account, or signed out when there is none. */
    val state: StateFlow<AuthState> = mutableState.asStateFlow()

    /** Every account signed in on this device. */
    val registry: StateFlow<AccountRegistry> = registryState.asStateFlow()

    /** The sign-in screens are up over a signed-in account (Settings › Account › Add Account). */
    val isAddingAccount: StateFlow<Boolean> = adding.asStateFlow()

    fun beginAddingAccount() {
        adding.value = true
    }

    fun cancelAddingAccount() {
        adding.value = false
    }

    /** index.tsx createAccount */
    suspend fun createAccount() = login(random.nextBytes(32))

    /** restore/manual.tsx: accepts the dashed backup format or the raw base64url secret. */
    suspend fun restore(secretKey: String) {
        val secret = try {
            decodeBase64(SecretKeyBackup.normalize(secretKey), Base64Variant.Url)
        } catch (e: IllegalArgumentException) {
            throw InvalidSecretKeyException(e)
        }
        if (secret.size != 32) throw InvalidSecretKeyException()
        login(secret)
    }

    /**
     * restore/index.tsx after the other phone approved the link
     * ([AccountLinkRequest]): the server already issued the token, so it is
     * stored as is, as `auth.login(token, secret)` does. The account joins
     * the signed-in ones, or is signed in again, and becomes active.
     */
    suspend fun signIn(token: String, secret: ByteArray) {
        require(secret.size == 32) { "Invalid secret" }
        val credentials = AuthCredentials(token = token, secret = encodeBase64(secret, Base64Variant.Url))
        val signedIn = signedIn(credentials) as AuthState.SignedIn
        val id = signedIn.encryption.anonId
        val server = serverUrl()
        withContext(io) {
            synchronized(lock) {
                val registry = registryState.value
                val existing = registry.entry(id)
                val location = existing?.location
                    ?: if (registry.entries.none { it.location == CredentialLocation.Shared } && credentialStore.read() == null) {
                        CredentialLocation.Shared
                    } else {
                        CredentialLocation.Own
                    }
                if (location == CredentialLocation.Shared) credentialStore.write(credentials) else credentialStore.write(id, credentials)
                val entry = AccountRegistryEntry(id, server, location, existing?.addedOrder ?: registry.nextOrder, existing?.display)
                save(AccountRegistry(registry.entries.filter { it.id != id } + entry, activeId = id))
            }
        }
        adding.value = false
        mutableState.value = signedIn
        onSignedIn()
    }

    private suspend fun login(secret: ByteArray) {
        signIn(api.getToken(AuthChallenge.create(secret, random)), secret)
    }

    /** Makes [accountId] the active account. Throws when it isn't signed in here or its credentials can't be read. */
    suspend fun switchTo(accountId: String) {
        if ((state.value as? AuthState.SignedIn)?.encryption?.anonId == accountId) return
        val next = withContext(io) {
            synchronized(lock) {
                val registry = registryState.value
                val entry = registry.entry(accountId) ?: throw IllegalArgumentException("Unknown account")
                val next = activeState(entry) as? AuthState.SignedIn ?: throw IllegalStateException("The account's credentials can't be read")
                save(registry.copy(activeId = accountId))
                next
            }
        }
        mutableState.value = next
    }

    /** [accountId]'s stored credentials, for what must still reach the server as it signs out. */
    suspend fun credentials(accountId: String): AuthCredentials? = withContext(io) {
        registryState.value.entry(accountId)?.let(::credentials)
    }

    /**
     * Forgets [accountId]'s credentials. Signing out the active account
     * makes the next by added order active, or signs out when it was the
     * last. Clearing its local data is the caller's job (AppGraph.logout).
     */
    suspend fun signOut(accountId: String) {
        val next = withContext(io) {
            synchronized(lock) {
                val registry = registryState.value
                val entry = registry.entry(accountId) ?: return@synchronized null
                if (entry.location == CredentialLocation.Shared) credentialStore.clear() else credentialStore.clear(accountId)
                val rest = registry.entries.filter { it.id != accountId }
                if (registry.activeId != accountId) {
                    save(registry.copy(entries = rest))
                    return@synchronized null
                }
                val following = AccountRegistry(rest).ordered.firstOrNull()
                save(AccountRegistry(rest, activeId = following?.id))
                following?.let(::activeState) ?: AuthState.SignedOut
            }
        }
        if (next != null) mutableState.value = next
    }

    /** Signs out the active account. */
    suspend fun logout() {
        val active = state.value as? AuthState.SignedIn ?: return
        signOut(active.encryption.anonId)
    }

    /** Keeps what the switcher shows of [accountId], from its synced profile. Blocks; call off the main thread. */
    fun updateDisplay(accountId: String, display: AccountDisplay) {
        synchronized(lock) {
            val registry = registryState.value
            val entry = registry.entry(accountId) ?: return
            if (entry.display == display) return
            try {
                save(registry.copy(entries = registry.entries.map { if (it.id == accountId) it.copy(display = display) else it }))
            } catch (e: IOException) {
                Log.w(TAG, "Could not keep the account's profile", e)
            }
        }
    }

    private fun save(registry: AccountRegistry) {
        registryStore.save(registry)
        registryState.value = registry
    }

    private fun credentials(entry: AccountRegistryEntry): AuthCredentials? =
        if (entry.location == CredentialLocation.Shared) credentialStore.read() else credentialStore.read(entry.id)

    private fun activeState(registry: AccountRegistry): AuthState = registry.active?.let(::activeState) ?: AuthState.SignedOut

    private fun activeState(entry: AccountRegistryEntry): AuthState = signedIn(credentials(entry))

    /**
     * The registry as last saved, squared with happy-app's entry: an account
     * there that the registry doesn't have in it (the first launch of this
     * build, or the React Native build signed in again) joins as the shared
     * one. A locked or unreadable store never removes an account.
     */
    private fun bootstrap(): AccountRegistry {
        val stored = registryStore.load()
        var registry = stored ?: AccountRegistry()
        val sharedEntry = registry.entries.firstOrNull { it.location == CredentialLocation.Shared }
        val shared = signedIn(credentialStore.read()) as? AuthState.SignedIn
        if (shared != null && sharedEntry?.id != shared.encryption.anonId) {
            val id = shared.encryption.anonId
            val others = registry.entries.filter { it.location != CredentialLocation.Shared && it.id != id }
            val entry = AccountRegistryEntry(
                id,
                sharedEntry?.serverUrl ?: serverUrl(),
                CredentialLocation.Shared,
                sharedEntry?.addedOrder ?: AccountRegistry(others).nextOrder,
            )
            registry = AccountRegistry(others + entry, activeId = registry.activeId?.takeIf { active -> others.any { it.id == active } } ?: id)
        }
        if (registry.active == null) registry = registry.copy(activeId = registry.ordered.firstOrNull()?.id)
        if (registry != stored && (stored != null || registry.entries.isNotEmpty())) {
            try {
                registryStore.save(registry)
            } catch (e: IOException) {
                Log.w(TAG, "Could not save the account registry", e)
            }
        }
        return registry
    }

    private fun signedIn(credentials: AuthCredentials?): AuthState {
        if (credentials == null) return AuthState.SignedOut
        val secret = try {
            credentials.secretBytes()
        } catch (_: IllegalArgumentException) {
            return AuthState.SignedOut
        }
        if (secret.size != 32) return AuthState.SignedOut
        return AuthState.SignedIn(credentials, AccountEncryption.create(secret, random))
    }

    private companion object {
        const val TAG = "AuthRepository"
    }
}

/** parseToken.ts: the `sub` claim of the server's JWT, or null if it has none. */
fun tokenSubject(token: String): String? {
    val parts = token.split('.')
    if (parts.size != 3 || parts.any { it.isEmpty() }) return null
    return try {
        val payload = HappyJson.parseToJsonElement(decodeBase64(parts[1], Base64Variant.Url).decodeToString())
        (payload.jsonObject["sub"] as? JsonPrimitive)?.takeIf { it.isString }?.content
    } catch (_: IllegalArgumentException) {
        // Also covers SerializationException.
        null
    }
}
