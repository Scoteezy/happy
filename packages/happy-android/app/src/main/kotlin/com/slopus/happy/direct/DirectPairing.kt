package com.slopus.happy.direct

import com.slopus.happy.crypto.DirectPairingCrypto
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull

/**
 * Why pairing or a paired computer failed (happy-ios `DirectPairingError`).
 * The screens say each in words of their own (`strings_direct.xml`).
 */
class DirectPairingError(val kind: Kind) : Exception(kind.name) {
    enum class Kind { InvalidCode, UpdateRequired, Expired, Rejected, InvalidProof, InvalidResponse, Unavailable, Revoked }

    override fun equals(other: Any?) = other is DirectPairingError && other.kind == kind
    override fun hashCode() = kind.hashCode()
}

/** Base64url without padding, and only its one spelling of each value (happy-ios `CanonicalBase64URL`). */
object CanonicalBase64Url {
    fun encode(data: ByteArray): String = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(data)

    fun decode(text: String): ByteArray? {
        if (text.isEmpty() || !text.all { it in 'A'..'Z' || it in 'a'..'z' || it in '0'..'9' || it == '-' || it == '_' }) return null
        val bytes = runCatching { java.util.Base64.getUrlDecoder().decode(text) }.getOrNull() ?: return null
        return bytes.takeIf { encode(it) == text }
    }
}

/**
 * A `happy://direct?…` pairing code (happy-ios `DirectPairingLink`). Direct
 * codes are classified before legacy parsing, malformed ones included: a bad
 * direct code must never authorize a relay terminal.
 */
class DirectPairingLink private constructor(
    val address: String,
    val port: Int,
    val token: ByteArray,
    val name: String,
    /** Unix milliseconds. */
    val expiresAt: Long,
) {
    companion object {
        const val PREFIX = "happy://direct?"

        fun isDirect(text: String): Boolean {
            val trimmed = text.trim()
            val uri = runCatching { java.net.URI(trimmed) }.getOrNull()
            if (uri != null && uri.scheme != null) return uri.scheme.equals("happy", true) && (uri.host ?: uri.rawAuthority)?.equals("direct", true) == true
            return trimmed.lowercase().startsWith("happy://direct")
        }

        fun parse(text: String, now: Long = System.currentTimeMillis()): DirectPairingLink {
            val trimmed = text.trim()
            val invalid = DirectPairingError(DirectPairingError.Kind.InvalidCode)
            if (!trimmed.startsWith(PREFIX) || trimmed.toByteArray().size > 16384) throw invalid
            val data = CanonicalBase64Url.decode(trimmed.removePrefix(PREFIX)) ?: throw invalid
            val bundle = runCatching { DirectAgentClient.parse(data) as JsonObject }.getOrNull() ?: throw invalid
            fun number(key: String) = (bundle[key] as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull
            fun string(key: String) = (bundle[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
            val v = number("v") ?: throw invalid
            val a = string("a") ?: throw invalid
            val p = number("p") ?: throw invalid
            val t = string("t") ?: throw invalid
            val n = string("n") ?: throw invalid
            val e = number("e") ?: throw invalid
            if (v != 1.0) throw DirectPairingError(DirectPairingError.Kind.UpdateRequired)
            if (a.isEmpty() || a.toByteArray().size > 8192 || a.any(Char::isISOControl)) throw invalid
            if (p != Math.floor(p) || p <= 0 || p > 65535) throw invalid
            val token = CanonicalBase64Url.decode(t)?.takeIf { it.size == 32 } ?: throw invalid
            if (n.isEmpty() || n.codePointCount(0, n.length) > 128 || n.any(Char::isISOControl)) throw invalid
            if (!e.isFinite() || e <= 0) throw invalid
            val expiry = e.toLong()
            if (expiry <= now) throw DirectPairingError(DirectPairingError.Kind.Expired)
            return DirectPairingLink(a, p.toInt(), token, n, expiry)
        }
    }
}

/** What pairing leaves: where the computer is, and this device's ID and token there (happy-ios `DirectDeviceCredentials`). */
data class DirectDeviceCredentials(val address: String, val port: Int, val deviceId: String, val deviceToken: String) {
    fun json(): JsonObject = JsonObject(mapOf(
        "address" to JsonPrimitive(address), "port" to JsonPrimitive(port),
        "deviceID" to JsonPrimitive(deviceId), "deviceToken" to JsonPrimitive(deviceToken),
    ))

    companion object {
        /** Null for anything but whole, plausible credentials. */
        fun from(json: kotlinx.serialization.json.JsonElement): DirectDeviceCredentials? = runCatching {
            val o = Wire.obj(json)
            DirectDeviceCredentials(Wire.string(o, "address"), Wire.int(o, "port"), Wire.string(o, "deviceID"), Wire.string(o, "deviceToken"))
        }.getOrNull()?.takeIf { it.port in 1..65535 && it.address.isNotEmpty() && it.deviceId.isNotEmpty() && CanonicalBase64Url.decode(it.deviceToken)?.size == 32 }
    }
}

/**
 * The phone's side of API.md's mobile-connector handshake (happy-ios
 * `DirectPairingService`). An unfinished handshake owns its tunnel and
 * secrets, and stores nothing; only a valid daemon MAC produces
 * credentials.
 */
class DirectPairingService(
    private val link: DirectPairingLink,
    private val transport: DirectTransport,
    private val crypto: DirectPairingCrypto? = null,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val lock = Mutex()
    private var handshakeId: String? = null
    private var secrets: DirectPairingCrypto.Secrets? = null
    private var expiresAt = link.expiresAt
    @Volatile private var cancelled = false

    /** Starts the handshake and returns the four emoji both screens show. */
    suspend fun start(deviceName: String): List<String> = lock.withLock {
        checkActive()
        val crypto = crypto ?: DirectPairingCrypto()
        val safeName = deviceName.filterNot(Char::isISOControl).let { name -> name.take(name.offsetByCodePoints(0, minOf(128, name.codePointCount(0, name.length)))) }
        val body = JsonObject(mapOf(
            "commitment" to JsonPrimitive(CanonicalBase64Url.encode(crypto.commitment)),
            "device" to JsonObject(mapOf("name" to JsonPrimitive(safeName.ifEmpty { "Android" }), "platform" to JsonPrimitive("android"))),
        ))
        val started = decode(transport.request("POST", ROUTE, CanonicalBase64Url.encode(link.token), body.toString().toByteArray(), JSON))
        val id = started.str("handshakeId")
        val key = started.str("publicKey")?.let(CanonicalBase64Url::decode)
        val nonce = started.str("nonce")?.let(CanonicalBase64Url::decode)
        val expiry = started.num("expiresAt")
        if (id == null || CanonicalBase64Url.decode(id)?.size != 32 || key?.size != 32 || nonce?.size != 32 || expiry == null || !expiry.isFinite()) {
            throw DirectPairingError(DirectPairingError.Kind.InvalidResponse)
        }
        // Recorded even if cancellation came during start, so cleanup can
        // reject a handshake whose one-time token was spent.
        handshakeId = id
        expiresAt = minOf(link.expiresAt, expiry.toLong())
        checkActive()
        val derived = crypto.derive(key, nonce, link.token, link.address, link.port) ?: throw DirectPairingError(DirectPairingError.Kind.InvalidResponse)
        val reveal = JsonObject(mapOf("publicKey" to JsonPrimitive(CanonicalBase64Url.encode(crypto.publicKey)), "nonce" to JsonPrimitive(CanonicalBase64Url.encode(crypto.nonce))))
        val status = decode(transport.request("POST", "$ROUTE/reveal", id, reveal.toString().toByteArray(), JSON))
        checkActive()
        check(status)
        if (status.str("status") != "verifying") throw DirectPairingError(DirectPairingError.Kind.InvalidResponse)
        secrets = derived
        derived.emoji
    }

    /** The person saw the same emoji on both: proves this side, waits for the computer's, and returns the credentials. */
    suspend fun confirm(): DirectDeviceCredentials = lock.withLock { confirmLocked() }

    private suspend fun confirmLocked(): DirectDeviceCredentials {
        checkActive()
        val id = handshakeId ?: throw DirectPairingError(DirectPairingError.Kind.InvalidResponse)
        val secrets = secrets ?: throw DirectPairingError(DirectPairingError.Kind.InvalidResponse)
        val body = JsonObject(mapOf("mac" to JsonPrimitive(CanonicalBase64Url.encode(secrets.mac("mobile")))))
        var status = decode(transport.request("POST", "$ROUTE/confirm", id, body.toString().toByteArray(), JSON))
        while (true) {
            checkActive()
            check(status)
            if (status.str("status") == "completed") {
                val deviceId = status.str("deviceId")
                val mac = status.str("mac")?.let(CanonicalBase64Url::decode)
                if (deviceId.isNullOrEmpty() || deviceId.length > 128 || mac == null || !secrets.verifyDaemon(mac)) {
                    throw DirectPairingError(DirectPairingError.Kind.InvalidProof)
                }
                this.secrets = null
                transport.close()
                return DirectDeviceCredentials(link.address, link.port, deviceId, CanonicalBase64Url.encode(secrets.deviceToken))
            }
            status = decode(transport.request("GET", "$ROUTE?wait=25", id, null, emptyMap()))
        }
    }

    /** Rejects the handshake, best effort and never for more than two seconds, and lets the tunnel go. */
    suspend fun cancel() {
        if (cancelled) return
        cancelled = true
        secrets = null
        val id = handshakeId
        coroutineScope {
            val reject = async { if (id != null) runCatching { transport.request("POST", "$ROUTE/reject", id, null, emptyMap()) } }
            val timer = async { delay(2_000) }
            select<Unit> {
                reject.onAwait {}
                timer.onAwait {}
            }
            reject.cancel(); timer.cancel()
        }
        transport.close()
    }

    private suspend fun checkActive() {
        currentCoroutineContext().ensureActive()
        if (cancelled) throw CancellationException("Pairing was cancelled.")
        if (expiresAt <= now()) throw DirectPairingError(DirectPairingError.Kind.Expired)
    }

    private fun decode(response: DirectHttpResponse): JsonObject {
        if (response.status == 401) throw DirectPairingError(DirectPairingError.Kind.Expired)
        if (response.status != 200) throw DirectPairingError(DirectPairingError.Kind.InvalidResponse)
        return runCatching { DirectAgentClient.parse(response.body) as JsonObject }.getOrNull() ?: throw DirectPairingError(DirectPairingError.Kind.InvalidResponse)
    }

    private fun check(status: JsonObject) {
        when (status.str("status")) {
            "verifying", "completed" -> Unit
            "expired" -> throw DirectPairingError(DirectPairingError.Kind.Expired)
            "rejected" -> throw DirectPairingError(DirectPairingError.Kind.Rejected)
            else -> throw DirectPairingError(DirectPairingError.Kind.InvalidResponse)
        }
    }

    companion object {
        private const val ROUTE = "/v0/mobile-connector/handshake"
        private val JSON = mapOf("content-type" to "application/json")

        private fun JsonObject.str(key: String) = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
        private fun JsonObject.num(key: String) = (this[key] as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull
    }
}
