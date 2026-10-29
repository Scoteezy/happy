package com.slopus.happy.direct

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import java.net.URLEncoder
import java.security.SecureRandom

/** happy-ios `DirectHTTPResponse`. Header names are lowercase. */
class DirectHttpResponse(val status: Int, val headers: Map<String, String> = emptyMap(), val body: ByteArray = ByteArray(0))

/**
 * How requests reach a paired computer (happy-ios `DirectTransport`): the
 * private tunnel in a real pairing, a fixture of the app's own in the debug
 * harnesses.
 */
interface DirectTransport {
    suspend fun request(method: String, path: String, credential: String, body: ByteArray?, headers: Map<String, String>): DirectHttpResponse

    /** Lets go of the connection so the next request makes a new one. */
    suspend fun reset() {}
    suspend fun close() {}
}

/** A refused or failed Happy Agent request (happy-ios `DirectAgentError`). */
sealed class DirectAgentError : Exception() {
    /** The daemon answered with an error status; `code` is its error code. */
    data class Status(val status: Int, val code: String?, val error: String?) : DirectAgentError()

    /** The device was unpaired. */
    data object Revoked : DirectAgentError()

    /** An answer that could not be read. */
    data object InvalidResponse : DirectAgentError()
}

/** happy-ios `AgentWire.Health`: whether the daemon is ready, and what it serves. */
data class AgentHealth(val ready: Boolean, val stories: Boolean?, val stickers: Boolean?) {
    companion object {
        fun from(json: JsonElement): AgentHealth {
            val o = Wire.obj(json)
            val capabilities = o["capabilities"] as? JsonObject
            return AgentHealth(
                ready = Wire.bool(o, "ready"),
                stories = capabilities?.let { Wire.boolOrNull(it, "stories") },
                stickers = capabilities?.let { Wire.boolOrNull(it, "stickers") },
            )
        }
    }
}

/**
 * The Happy Agent API over one paired device's private tunnel (happy-ios
 * `DirectAgentClient`). Every route is one a device token may use
 * (happy-agent `API.md`, "Mobile connector"). Stories' routes are in
 * `stories/StoriesClient.kt`.
 */
class DirectAgentClient(internal val transport: DirectTransport, private val token: String) {
    suspend fun close() = transport.close()

    /** `raw` is sent as is, in place of `json` (an upload's bytes). */
    internal suspend fun send(
        method: String,
        path: String,
        json: JsonElement? = null,
        raw: ByteArray? = null,
        headers: Map<String, String> = emptyMap(),
    ): DirectHttpResponse {
        val body = raw ?: json?.let { it.toString().toByteArray() }
        val response = transport.request(method, path, token, body, headers)
        if (response.status == 401) throw DirectAgentError.Revoked
        if (response.status !in 200 until 300 && response.status != 304 && response.status != 206) {
            val failure = runCatching { Wire.obj(parse(response.body)) }.getOrNull()
            throw DirectAgentError.Status(
                response.status,
                failure?.let { runCatching { Wire.stringOrNull(it, "code") }.getOrNull() },
                failure?.let { runCatching { Wire.stringOrNull(it, "error") }.getOrNull() },
            )
        }
        return response
    }

    internal suspend fun <T> decode(method: String, path: String, json: JsonElement? = null, read: (JsonElement) -> T): T {
        val response = send(method, path, json)
        return try {
            read(parse(response.body))
        } catch (_: Exception) {
            throw DirectAgentError.InvalidResponse
        }
    }

    // Health and settings

    suspend fun health(): AgentHealth = decode("GET", "/v0/health", read = AgentHealth::from)

    /** A user setting's value; null when it was never set. */
    suspend fun userSetting(key: String): JsonElement? = try {
        decode("GET", "/v0/user-settings/${escape(key)}") { Wire.obj(Wire.obj(it)["setting"])["value"] }
    } catch (e: DirectAgentError.Status) {
        if (e.status == 404) null else throw e
    }

    suspend fun setUserSetting(key: String, value: JsonElement) {
        send("PUT", "/v0/user-settings/${escape(key)}", json = JsonObject(mapOf("value" to value)))
    }

    companion object {
        internal val json = Json { ignoreUnknownKeys = true }

        internal fun parse(body: ByteArray): JsonElement = json.parseToJsonElement(body.decodeToString())

        /** A path or query component with only `A-Za-z0-9-._~` left as is. */
        fun escape(component: String): String =
            URLEncoder.encode(component, Charsets.UTF_8).replace("+", "%20").replace("*", "%2A").replace("%7E", "~")
    }
}

/** A CUID2-shaped ID the daemon accepts from a client (`^[a-z][a-z0-9]+$`), happy-ios `ClientID`. */
object ClientId {
    private const val ALPHABET = "abcdefghijklmnopqrstuvwxyz0123456789"
    private val random = SecureRandom()

    fun make(): String = buildString {
        append(ALPHABET[random.nextInt(26)])
        repeat(23) { append(ALPHABET[random.nextInt(ALPHABET.length)]) }
    }
}
