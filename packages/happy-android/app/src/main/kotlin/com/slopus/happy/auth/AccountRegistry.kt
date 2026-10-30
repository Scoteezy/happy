package com.slopus.happy.auth

import android.util.AtomicFile
import android.util.Log
import com.slopus.happy.config.ServerConfig
import com.slopus.happy.net.HappyJson
import kotlinx.serialization.SerialName
import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException

/**
 * What the account switcher shows of an account without opening its store:
 * its profile's name, GitHub login and picture as of its last sync
 * (happy-ios `AccountDisplay`).
 */
@Serializable
data class AccountDisplay(
    val name: String? = null,
    val username: String? = null,
    val avatarUrl: String? = null,
    val avatarThumbhash: String? = null,
)

/**
 * Where an account's credentials are kept (happy-ios `CredentialLocation`):
 * happy-app's own entry, which the React Native build reads too, or one of
 * the account's own. The first account on a device takes the shared one.
 */
@Serializable
enum class CredentialLocation {
    @SerialName("shared") Shared,
    @SerialName("own") Own,
}

/** One signed-in account; no token or secret in it. [id] is the account's anonymous id. */
@Serializable
data class AccountRegistryEntry(
    val id: String,
    val serverUrl: String,
    val location: CredentialLocation,
    val addedOrder: Int,
    val display: AccountDisplay? = null,
    /** False until an ambiguous RN import's host is explicitly confirmed. Old native entries remain confirmed. */
    val serverConfirmed: Boolean = true,
) {
    internal val isValid: Boolean
        get() = id.length == 16 && id.all { it in "0123456789abcdef" } && addedOrder >= 0 &&
            ServerConfig.validate(serverUrl) == ServerConfig.Validation.Valid
}

/** happy-ios `AccountRegistry`: the accounts signed in on this device, in the order they were added, and the active one. */
@Serializable
data class AccountRegistry(
    val entries: List<AccountRegistryEntry> = emptyList(),
    val activeId: String? = null,
) {
    val ordered: List<AccountRegistryEntry> get() = entries.sortedBy { it.addedOrder }
    val nextOrder: Int get() = (entries.maxOfOrNull { it.addedOrder } ?: -1) + 1
    val active: AccountRegistryEntry? get() = entries.firstOrNull { it.id == activeId }

    fun entry(id: String): AccountRegistryEntry? = entries.firstOrNull { it.id == id }
}

/** Where the registry is kept between launches. */
interface AccountRegistryStore {
    /** Null when there is none yet, or it can't be trusted (then it is rebuilt from the credentials). */
    fun load(): AccountRegistry?
    fun save(registry: AccountRegistry)

    class InMemory(private var value: AccountRegistry? = null) : AccountRegistryStore {
        override fun load() = value
        override fun save(registry: AccountRegistry) {
            value = registry
        }
    }
}

/**
 * `accounts.json` beside the accounts' directories (happy-ios
 * `AccountRegistry.load` / `save`): written atomically; bad JSON or invalid
 * entries read as none, so the credentials rebuild it. An I/O failure on
 * save throws, so a passing problem can't replace a good registry.
 */
class AccountRegistryFile(file: File) : AccountRegistryStore {
    private val atomic = AtomicFile(file)
    // In particular, a corrupt/null confirmation bit must not coerce to the true legacy default.
    private val json = Json(HappyJson) { coerceInputValues = false }

    override fun load(): AccountRegistry? {
        val text = try {
            atomic.readFully().decodeToString()
        } catch (_: java.io.FileNotFoundException) {
            return null
        } catch (e: IOException) {
            Log.w(TAG, "Could not read the account registry", e)
            return null
        }
        val registry = try {
            json.decodeFromString(AccountRegistry.serializer(), text)
        } catch (_: SerializationException) {
            return null
        } catch (_: IllegalArgumentException) {
            return null
        }
        val ids = registry.entries.map { it.id }
        if (ids.toSet().size != ids.size || !registry.entries.all { it.isValid }) return null
        return registry
    }

    override fun save(registry: AccountRegistry) {
        atomic.baseFile.parentFile?.mkdirs()
        val out = atomic.startWrite()
        try {
            out.write(HappyJson.encodeToString(AccountRegistry.serializer(), registry).encodeToByteArray())
            atomic.finishWrite(out)
        } catch (e: IOException) {
            atomic.failWrite(out)
            throw e
        }
    }

    private companion object {
        const val TAG = "AccountRegistry"
    }
}
