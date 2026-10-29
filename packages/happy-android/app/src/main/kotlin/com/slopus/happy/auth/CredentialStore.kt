package com.slopus.happy.auth

import android.util.Log
import com.slopus.happy.crypto.Base64Variant
import com.slopus.happy.crypto.decodeBase64
import com.slopus.happy.net.HappyJson
import kotlinx.serialization.Serializable
import java.security.GeneralSecurityException

/**
 * What happy-app keeps under `auth_credentials`: the server token and the
 * 32-byte account secret as base64url. Encoded exactly as tokenStorage.ts
 * writes it, `JSON.stringify({ token, secret })`.
 */
@Serializable
data class AuthCredentials(val token: String, val secret: String) {
    fun secretBytes(): ByteArray = decodeBase64(secret, Base64Variant.Url)
}

/**
 * Where signed-in accounts are kept: happy-app's one entry ([read], [write],
 * [clear]), and an entry of its own for each account added after the first
 * (happy-ios `AccountCredentialStore`).
 */
interface CredentialStore {
    fun read(): AuthCredentials?
    fun write(credentials: AuthCredentials)
    fun clear()

    fun read(accountId: String): AuthCredentials?
    fun write(accountId: String, credentials: AuthCredentials)
    fun clear(accountId: String)
}

/**
 * tokenStorage.ts on Android: the credentials live in expo-secure-store, in
 * the same entry the React Native build uses. A value that cannot be read
 * or parsed reads as signed out and is left alone, as tokenStorage.ts does.
 */
class SecureStoreCredentialStore(private val secureStore: ExpoSecureStore) : CredentialStore {
    override fun read(): AuthCredentials? = readItem(KEY)

    override fun write(credentials: AuthCredentials) = writeItem(KEY, credentials)

    override fun clear() = secureStore.deleteItem(KEY)

    override fun read(accountId: String): AuthCredentials? = readItem(ownKey(accountId))

    override fun write(accountId: String, credentials: AuthCredentials) = writeItem(ownKey(accountId), credentials)

    override fun clear(accountId: String) = secureStore.deleteItem(ownKey(accountId))

    private fun ownKey(accountId: String) = "${KEY}_$accountId"

    private fun readItem(key: String): AuthCredentials? = try {
        secureStore.getItem(key)?.let { HappyJson.decodeFromString(AuthCredentials.serializer(), it) }
    } catch (e: GeneralSecurityException) {
        Log.w(TAG, "Could not read credentials", e)
        null
    } catch (e: IllegalArgumentException) {
        Log.w(TAG, "Could not parse credentials", e)
        null
    }

    private fun writeItem(key: String, credentials: AuthCredentials) {
        secureStore.setItem(key, HappyJson.encodeToString(AuthCredentials.serializer(), credentials))
    }

    companion object {
        /** tokenStorage.ts AUTH_KEY */
        const val KEY = "auth_credentials"
        private const val TAG = "CredentialStore"
    }
}
