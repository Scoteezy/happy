package com.slopus.happy.direct

import com.slopus.happy.auth.ExpoSecureStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import java.io.File
import java.util.UUID

/** happy-ios `DirectConnectionStatus`. */
enum class DirectConnectionStatus { Connecting, Connected, Retrying, Paused, Revoked }

/** A computer paired straight to this phone (happy-ios `DirectAccount`). */
data class DirectAccount(
    val id: String,
    val name: String,
    val avatar: ByteArray? = null,
    val avatarETag: String? = null,
    val thumbhash: String? = null,
    val revoked: Boolean = false,
    val status: DirectConnectionStatus = DirectConnectionStatus.Paused,
    /** Unix milliseconds. */
    val addedAt: Long = System.currentTimeMillis(),
) {
    override fun equals(other: Any?) = other is DirectAccount && other.id == id && other.name == name &&
        (if (avatar == null || other.avatar == null) avatar == other.avatar else avatar.contentEquals(other.avatar)) &&
        other.avatarETag == avatarETag && other.thumbhash == thumbhash && other.revoked == revoked && other.status == status && other.addedAt == addedAt

    override fun hashCode() = id.hashCode()

    /** What is kept of it: its identity and a permanent revocation, never its transient status. */
    fun json(): JsonObject = Wire.objectOf(
        "id" to JsonPrimitive(id), "name" to JsonPrimitive(name),
        "avatar" to avatar?.let { JsonPrimitive(java.util.Base64.getEncoder().encodeToString(it)) },
        "avatarETag" to avatarETag?.let(::JsonPrimitive), "thumbhash" to thumbhash?.let(::JsonPrimitive),
        "revoked" to JsonPrimitive(revoked), "addedAt" to JsonPrimitive(addedAt),
    )

    companion object {
        fun from(json: JsonElement): DirectAccount? = runCatching {
            val o = Wire.obj(json)
            DirectAccount(
                Wire.string(o, "id"), Wire.string(o, "name"),
                Wire.stringOrNull(o, "avatar")?.let { java.util.Base64.getDecoder().decode(it) },
                Wire.stringOrNull(o, "avatarETag"), Wire.stringOrNull(o, "thumbhash"),
                Wire.boolOrNull(o, "revoked") ?: false, addedAt = Wire.doubleOrNull(o, "addedAt")?.toLong() ?: 0,
            )
        }.getOrNull()
    }
}

/** Where paired devices' credentials live: never on disk in the clear (happy-ios `DirectCredentialStore`). */
interface DirectCredentialStore {
    fun all(): Map<String, DirectDeviceCredentials>
    fun save(credentials: DirectDeviceCredentials, id: String)
    fun remove(id: String)
}

class InMemoryDirectCredentialStore : DirectCredentialStore {
    private val values = LinkedHashMap<String, DirectDeviceCredentials>()
    @Synchronized override fun all(): Map<String, DirectDeviceCredentials> = values.toMap()
    @Synchronized override fun save(credentials: DirectDeviceCredentials, id: String) { values[id] = credentials }
    @Synchronized override fun remove(id: String) { values.remove(id) }
}

/**
 * The Android Keystore-backed store (happy-ios `KeychainDirectCredentialStore`):
 * each device's credentials in an item of their own, and an index item
 * naming them, since the store cannot list its items.
 */
class SecureStoreDirectCredentialStore(private val store: ExpoSecureStore) : DirectCredentialStore {
    @Synchronized
    override fun all(): Map<String, DirectDeviceCredentials> {
        val result = LinkedHashMap<String, DirectDeviceCredentials>()
        for (id in index()) {
            val text = store.getItem(key(id)) ?: continue
            val value = runCatching { DirectDeviceCredentials.from(DirectAgentClient.parse(text.toByteArray())) }.getOrNull() ?: continue
            result[id] = value
        }
        return result
    }

    @Synchronized
    override fun save(credentials: DirectDeviceCredentials, id: String) {
        store.setItem(key(id), credentials.json().toString())
        val ids = index()
        if (id !in ids) store.setItem(INDEX, JsonArray((ids + id).map(::JsonPrimitive)).toString())
    }

    @Synchronized
    override fun remove(id: String) {
        store.deleteItem(key(id))
        store.setItem(INDEX, JsonArray((index() - id).map(::JsonPrimitive)).toString())
    }

    private fun index(): List<String> = store.getItem(INDEX)?.let { text ->
        runCatching { (DirectAgentClient.parse(text.toByteArray()) as JsonArray).map { (it as JsonPrimitive).content } }.getOrNull()
    } ?: emptyList()

    private fun key(id: String) = "direct-device-$id"

    companion object {
        private const val INDEX = "direct-devices"
    }
}

/**
 * The paired computers' list and which is active, beside their credentials
 * (happy-ios `DirectAccountStorage`). No direct secret is written to disk;
 * reading every credential must succeed before missing accounts are
 * reconciled. Calls are blocking; callers run them off the main thread.
 */
class DirectAccountStorage(private val credentials: DirectCredentialStore, directory: File?) {
    private var file: File? = directory?.let { File(it, FILE_NAME) }
    private var accounts = ArrayList<DirectAccount>()
    private var activeId: String? = null

    class Loaded(val accounts: List<DirectAccount>, val activeId: String?, val credentials: Map<String, DirectDeviceCredentials>)

    @Synchronized fun configureDirectory(directory: File?) { file = directory?.let { File(it, FILE_NAME) } }

    @Synchronized
    fun load(): Loaded {
        val values = credentials.all()
        file?.takeIf { it.isFile }?.let { saved ->
            runCatching {
                val o = Wire.obj(DirectAgentClient.parse(saved.readBytes()))
                accounts = ArrayList(Wire.array(o["accounts"]).mapNotNull(DirectAccount::from))
                activeId = Wire.stringOrNull(o, "activeID")
            }
        }
        accounts.removeAll { values[it.id] == null }
        for (id in values.keys.sorted()) if (accounts.none { it.id == id }) accounts += DirectAccount(id, "Happy Agent")
        if (accounts.none { it.id == activeId }) activeId = null
        accounts = ArrayList(accounts.map { it.copy(status = if (it.revoked) DirectConnectionStatus.Revoked else DirectConnectionStatus.Paused) })
        persist()
        return Loaded(accounts.toList(), activeId, values)
    }

    @Synchronized
    fun add(account: DirectAccount, value: DirectDeviceCredentials) {
        credentials.save(value, account.id)
        accounts.removeAll { it.id == account.id }
        accounts += account
        activeId = account.id
        persist()
    }

    @Synchronized
    fun update(account: DirectAccount) {
        val index = accounts.indexOfFirst { it.id == account.id }
        if (index < 0) return
        accounts[index] = account
        persist()
    }

    @Synchronized
    fun select(id: String?) {
        activeId = id
        persist()
    }

    @Synchronized
    fun remove(id: String) {
        credentials.remove(id)
        accounts.removeAll { it.id == id }
        if (activeId == id) activeId = null
        // The credential's removal is authoritative; a later metadata write
        // failure cannot make it reappear at recovery.
        runCatching { persist() }
    }

    private fun persist() {
        val file = file ?: return
        file.parentFile?.mkdirs()
        val json = Wire.objectOf("accounts" to JsonArray(accounts.map { it.json() }), "activeID" to activeId?.let(::JsonPrimitive))
        val temporary = File(file.parentFile, "${file.name}.tmp")
        temporary.writeBytes(json.toString().toByteArray())
        if (!temporary.renameTo(file)) {
            file.delete()
            temporary.renameTo(file)
        }
    }

    companion object {
        const val FILE_NAME = "direct-accounts.json"
    }
}

/**
 * A computer's name and picture, read as one serialized config and image
 * read; the ETag, not the ThumbHash, identifies the bytes (happy-ios
 * `DirectIdentity`).
 */
object DirectIdentity {
    suspend fun refresh(account: DirectAccount, transport: DirectTransport, token: String): DirectAccount {
        fun check(response: DirectHttpResponse) {
            if (response.status == 401 || response.status == 403) throw DirectPairingError(DirectPairingError.Kind.Revoked)
            if (response.status != 200) throw DirectPairingError(DirectPairingError.Kind.Unavailable)
        }
        val health = transport.request("GET", "/v0/health", token, null, emptyMap())
        check(health)
        val state = runCatching { DirectAgentClient.parse(health.body) as JsonObject }.getOrNull() ?: throw DirectPairingError(DirectPairingError.Kind.InvalidResponse)
        val protocol = ((state["version"] as? JsonObject)?.get("protocol") as? JsonPrimitive)?.doubleOrNull ?: throw DirectPairingError(DirectPairingError.Kind.InvalidResponse)
        if (protocol < 27) throw DirectPairingError(DirectPairingError.Kind.UpdateRequired)
        if (state["ready"] != JsonPrimitive(true)) throw DirectPairingError(DirectPairingError.Kind.Unavailable)
        val response = transport.request("GET", "/v0/config", token, null, emptyMap())
        check(response)
        val config = runCatching { (DirectAgentClient.parse(response.body) as JsonObject)["config"] as JsonObject }.getOrNull() ?: throw DirectPairingError(DirectPairingError.Kind.InvalidResponse)
        var updated = account
        val node = config["node"]?.takeIf { it != JsonNull } as? JsonObject
        if (node != null) {
            val name = (node["name"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: throw DirectPairingError(DirectPairingError.Kind.InvalidResponse)
            if (name.isEmpty() || name.codePointCount(0, name.length) > 128 || name.any(Char::isISOControl)) throw DirectPairingError(DirectPairingError.Kind.InvalidResponse)
            val avatar = node["avatar"]?.takeIf { it != JsonNull } as? JsonObject
            updated = updated.copy(name = name, thumbhash = (avatar?.get("thumbhash") as? JsonPrimitive)?.content)
            if (avatar == null) {
                updated = updated.copy(avatar = null, avatarETag = null)
            } else {
                val image = transport.request("GET", "/v0/node/avatar", token, null, account.avatarETag?.let { mapOf("If-None-Match" to it) } ?: emptyMap())
                if (image.status == 401 || image.status == 403) throw DirectPairingError(DirectPairingError.Kind.Revoked)
                when (image.status) {
                    200 -> {
                        if (image.body.size > 8 * 1024 * 1024 || image.headers["content-type"]?.lowercase()?.startsWith("image/") != true) {
                            throw DirectPairingError(DirectPairingError.Kind.InvalidResponse)
                        }
                        updated = updated.copy(avatar = image.body, avatarETag = image.headers["etag"])
                    }
                    304 -> Unit
                    404 -> updated = updated.copy(avatar = null, avatarETag = null)
                    else -> throw DirectPairingError(DirectPairingError.Kind.Unavailable)
                }
            }
        }
        return updated.copy(status = DirectConnectionStatus.Connected)
    }
}

/**
 * Owns the independent direct connections (happy-ios `DirectAccounts`):
 * the paired computers, which is active, each one's identity loop and its
 * sync engine. The relay account's store, API and sync are never made for
 * a direct device.
 *
 * `scope` runs one coroutine at a time; it plays the main actor here.
 * `engines` makes an account's sync engine on first ask.
 */
class DirectAccounts(
    credentials: DirectCredentialStore,
    directory: File?,
    private val scope: CoroutineScope,
    private val makeTransport: (DirectDeviceCredentials) -> DirectTransport,
    private val makeEngine: (id: String, client: DirectAgentClient) -> DirectSyncEngine,
    private val refreshIntervalMs: Long = 60_000,
    private val io: kotlin.coroutines.CoroutineContext = kotlinx.coroutines.Dispatchers.IO,
    /** Where each account keeps what Stem Cells shows offline; it goes with the account. */
    private val offlineDirectory: (String) -> File? = { null },
) {
    class State(val accounts: List<DirectAccount> = emptyList(), val activeId: String? = null) {
        val active: DirectAccount? get() = accounts.firstOrNull { it.id == activeId }
    }

    private val storage = DirectAccountStorage(credentials, directory)
    private val stateFlow = MutableStateFlow(State())
    val state: StateFlow<State> = stateFlow.asStateFlow()

    private val confined = scope.coroutineContext.minusKey(Job)
    private var accounts = ArrayList<DirectAccount>()
    private var activeId: String? = null
    private var credentials = HashMap<String, DirectDeviceCredentials>()
    private val jobs = HashMap<String, Job>()
    private var generation = 0L
    private var foreground = true
    private var loaded = false
    private val engines = HashMap<String, DirectSyncEngine>()

    class UnknownAccount : Exception("No such paired computer.")

    /** One engine per account, shared by home, settings and conversations; made without I/O. */
    suspend fun syncEngine(id: String): DirectSyncEngine? = withContext(confined) {
        engines[id]?.let { return@withContext it }
        val client = credentials[id]?.let { DirectAgentClient(makeTransport(it), it.deviceToken) } ?: return@withContext null
        val engine = makeEngine(id, client)
        engines[id] = engine
        if (foreground && activeId == id) engine.start()
        engine
    }

    fun credential(id: String): DirectDeviceCredentials? = credentials[id]

    suspend fun restore() = withContext(confined) {
        if (loaded) return@withContext
        val saved = withContext(io) { storage.load() }
        accounts = ArrayList(saved.accounts); activeId = saved.activeId; credentials = HashMap(saved.credentials); loaded = true
        publish()
        startConnections()
    }

    suspend fun add(value: DirectDeviceCredentials): DirectAccount = withContext(confined) {
        if (!loaded) restore()
        // Pairing the same device again updates its entry; a newly paired
        // device gets its own ID even on the same installation.
        val id = credentials.entries.firstOrNull { it.value.address == value.address && it.value.deviceId == value.deviceId }?.key ?: "direct-${UUID.randomUUID()}"
        stop(id)
        engines.remove(id)?.stop()
        var account = DirectAccount(id, "Happy Agent")
        val transport = makeTransport(value)
        try {
            account = DirectIdentity.refresh(account, transport, value.deviceToken)
        } catch (e: DirectPairingError) {
            if (e.kind == DirectPairingError.Kind.Revoked) {
                transport.close()
                throw e
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
        }
        transport.close()
        withContext(io) { storage.add(account, value) }
        credentials[id] = value
        accounts.removeAll { it.id == id }
        accounts += account
        activeId = id
        for ((other, engine) in engines.toList()) if (other != activeId && engines[other] === engine) engine.pause()
        publish()
        startConnections()
        account
    }

    suspend fun select(id: String?) = withContext(confined) {
        if (!loaded) restore()
        if (id != null && accounts.none { it.id == id }) throw UnknownAccount()
        withContext(io) { storage.select(id) }
        activeId = id
        publish()
        // Publishing lets the coordinator create the incoming home's engine.
        // pause() suspends, so never retain a live map iterator across it.
        for ((accountId, engine) in engines.toList()) {
            if (engines[accountId] !== engine) continue
            if (foreground && accountId == activeId) engine.start() else engine.pause()
        }
    }

    /**
     * Renames the computer (`PATCH /v0/config` `node.name`). The name shows
     * at once; the identity loop starts again from it, so a retry never
     * puts the old one back.
     */
    suspend fun rename(id: String, name: String) = withContext(confined) {
        val credential = credentials[id] ?: throw UnknownAccount()
        val body = JsonObject(mapOf("node" to JsonObject(mapOf("name" to JsonPrimitive(name))))).toString().toByteArray()
        val transport = makeTransport(credential)
        try {
            val response = transport.request("PATCH", "/v0/config", credential.deviceToken, body, mapOf("content-type" to "application/json"))
            if (response.status == 401) throw DirectPairingError(DirectPairingError.Kind.Revoked)
            if (response.status != 200) {
                // The daemon's own words (an owner-only rename, a name it refuses).
                val failure = runCatching { DirectAgentClient.parse(response.body) as JsonObject }.getOrNull()
                throw DirectAgentError.Status(response.status, failure?.let { Wire.stringOrNull(it, "code") }, failure?.let { Wire.stringOrNull(it, "error") })
            }
        } finally {
            scope.launch { transport.close() }
        }
        stop(id)
        // Looked up after the wait: the list may have changed meanwhile.
        val index = accounts.indexOfFirst { it.id == id }
        if (index < 0) return@withContext
        accounts[index] = accounts[index].copy(name = name)
        publish()
        val renamed = accounts[index]
        runCatching { withContext(io) { storage.update(renamed) } }
        startConnections()
    }

    suspend fun remove(id: String) = withContext(confined) {
        // Teardown first: the loop can no longer publish or save an old identity.
        stop(id)
        engines.remove(id)?.stop()
        try {
            withContext(io) { storage.remove(id) }
        } catch (e: Exception) {
            startConnections()
            throw e
        }
        credentials.remove(id)
        accounts.removeAll { it.id == id }
        // What Stem Cells kept to open on it offline goes with it.
        offlineDirectory(id)?.let { withContext(io) { DirectOfflineFile.remove(it) } }
        if (activeId == id) activeId = null
        publish()
    }

    suspend fun setForeground(active: Boolean) = withContext(confined) {
        foreground = active
        generation += 1
        val pending = jobs.values.toList()
        jobs.clear()
        pending.forEach { it.cancel() }
        pending.forEach { it.join() }
        if (foreground != active) return@withContext
        for ((id, engine) in engines.toList()) {
            if (foreground != active) return@withContext
            if (active && id == activeId) engine.start() else engine.pause()
        }
        if (foreground != active) return@withContext
        if (active) {
            startConnections()
        } else {
            accounts = ArrayList(accounts.map { if (it.revoked) it else it.copy(status = DirectConnectionStatus.Paused) })
            publish()
        }
    }

    private suspend fun stop(id: String) {
        val job = jobs.remove(id) ?: return
        job.cancel()
        job.join()
    }

    private fun startConnections() {
        if (!foreground) return
        for (account in accounts) {
            if (jobs[account.id] != null || account.revoked) continue
            val credential = credentials[account.id] ?: continue
            val generation = generation
            jobs[account.id] = scope.launch {
                var current = account
                var attempt = 0
                while (isActive) {
                    val transport = makeTransport(credential)
                    try {
                        current = current.copy(status = if (attempt == 0) DirectConnectionStatus.Connecting else DirectConnectionStatus.Retrying)
                        apply(current, generation)
                        while (isActive) {
                            current = DirectIdentity.refresh(current, transport, credential.deviceToken)
                            apply(current, generation)
                            attempt = 0
                            delay(refreshIntervalMs)
                        }
                    } catch (e: DirectPairingError) {
                        if (e.kind == DirectPairingError.Kind.Revoked) {
                            current = current.copy(revoked = true, status = DirectConnectionStatus.Revoked)
                            apply(current, generation)
                            transport.close()
                            return@launch
                        }
                    } catch (e: CancellationException) {
                        withContext(kotlinx.coroutines.NonCancellable) { transport.close() }
                        throw e
                    } catch (_: Exception) {
                    }
                    transport.close()
                    if (!isActive) return@launch
                    current = current.copy(status = DirectConnectionStatus.Retrying)
                    apply(current, generation)
                    delay(retryDelay(attempt) * 1000L)
                    attempt = minOf(attempt + 1, 5)
                }
            }
        }
    }

    private suspend fun apply(account: DirectAccount, generation: Long) {
        if (this.generation != generation) return
        val index = accounts.indexOfFirst { it.id == account.id }
        if (index < 0) return
        val previous = accounts[index]
        if (previous == account) return
        accounts[index] = account
        publish()
        // Status is transient: identity and a permanent revocation are kept.
        if (previous.name != account.name || !(previous.avatar contentEqualsNullable account.avatar) || previous.avatarETag != account.avatarETag ||
            previous.thumbhash != account.thumbhash || previous.revoked != account.revoked
        ) {
            runCatching { withContext(io) { storage.update(account) } }
        }
    }

    private fun publish() {
        stateFlow.value = State(accounts.toList(), activeId)
    }

    companion object {
        fun retryDelay(attempt: Int): Int = minOf(30, 1 shl maxOf(0, minOf(5, attempt)))

        private infix fun ByteArray?.contentEqualsNullable(other: ByteArray?): Boolean =
            if (this == null || other == null) this == other else contentEquals(other)
    }
}
