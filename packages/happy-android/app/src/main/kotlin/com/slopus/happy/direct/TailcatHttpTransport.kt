package com.slopus.happy.direct

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * The private tunnel's native side: expo-tailcat's Go library
 * (`mobile.Manager`), which opens a WireGuard tunnel to one port on the
 * computer and serves it on a loopback URL with a random capability path.
 * [Tailcat] is it in a build linked against the library, and its absence
 * otherwise (AGENTS.md "Tailcat").
 */
interface TailcatTunnels {
    /** Whether this build carries the library. */
    val isLinked: Boolean

    /**
     * Opens a tunnel to [port] on the computer at [address] and returns once
     * that port answers through it. Blocks; call it off the main thread.
     * Throws [DirectUnavailableException] when it can't.
     */
    fun open(address: String, port: Int, timeoutMs: Int): Tunnel

    fun close(id: String)

    /** [httpUrl] ends in "/"; a request's path goes after it, without its own leading slash. */
    data class Tunnel(val id: String, val httpUrl: String)
}

/** A build without the library, or one whose tunnel could not be opened. */
class TailcatUnlinkedException : DirectUnavailableException("Tailcat isn't available in this build.")

/**
 * A paired computer reached through its private tunnel (happy-ios
 * `TailcatHTTPTransport`): one tunnel per transport, opened on the first
 * request, every request a plain HTTP exchange with its loopback end. The
 * Go side writes `Host: server.tailcat:<port>`, as iOS's does. A request
 * the tunnel carried nothing back for lets the tunnel go, so the next one
 * opens a new one: a computer whose daemon restarted has forgotten the old
 * tunnel's session.
 */
class TailcatHttpTransport(
    private val address: String,
    private val port: Int,
    private val tunnels: TailcatTunnels = Tailcat,
    client: OkHttpClient = shared,
    /** Where the blocking open and close run; tests pass their own. */
    private val io: CoroutineContext = Dispatchers.IO,
) : DirectTransport {
    // iOS's 35 s for an exchange; an event stream has no deadline of its own
    // (DirectAgentClient gives up after 40 s without a byte).
    private val exchanges = client.newBuilder().callTimeout(35, TimeUnit.SECONDS).build()
    private val streams = client.newBuilder().readTimeout(0, TimeUnit.MILLISECONDS).callTimeout(0, TimeUnit.MILLISECONDS).build()
    private val lock = Mutex()
    private var tunnel: TailcatTunnels.Tunnel? = null
    private var closed = false

    private suspend fun connection(): TailcatTunnels.Tunnel = lock.withLock {
        if (closed) throw kotlinx.coroutines.CancellationException("The transport is closed.")
        tunnel ?: withContext(io) {
            if (!tunnels.isLinked) throw TailcatUnlinkedException()
            tunnels.open(address, port, CONNECT_TIMEOUT_MS)
        }.also { tunnel = it }
    }

    /** Lets go of [used] if it is still the tunnel, after a failure on it. */
    private suspend fun reset(used: TailcatTunnels.Tunnel) {
        val gone = lock.withLock { if (tunnel === used) used.also { tunnel = null } else null } ?: return
        withContext(io + NonCancellable) { tunnels.close(gone.id) }
    }

    override suspend fun reset() {
        val gone = lock.withLock { tunnel.also { tunnel = null } } ?: return
        withContext(io + NonCancellable) { tunnels.close(gone.id) }
    }

    override suspend fun close() {
        lock.withLock { closed = true }
        reset()
    }

    override suspend fun request(method: String, path: String, credential: String, body: ByteArray?, headers: Map<String, String>): DirectHttpResponse {
        if (method !in METHODS || !safe(path, credential, headers)) throw DirectProtocolException()
        val tunnel = connection()
        val request = build(tunnel, method, path, credential, body, headers, accept = "application/json")
        val response = try {
            exchanges.newCall(request).await()
        } catch (e: IOException) {
            reset(tunnel)
            throw DirectUnavailableException()
        }
        return response.use {
            if (carriedNothing(it)) {
                reset(tunnel)
                throw DirectUnavailableException()
            }
            val bytes = try {
                withContext(io) { it.body.bytes() }
            } catch (e: IOException) {
                reset(tunnel)
                throw DirectUnavailableException()
            }
            if (bytes.size > LIMIT) throw DirectProtocolException()
            DirectHttpResponse(it.code, headers(it), bytes)
        }
    }

    override suspend fun stream(path: String, credential: String, headers: Map<String, String>): DirectHttpStream {
        if (!safe(path, credential, headers)) throw DirectProtocolException()
        val tunnel = connection()
        val request = build(tunnel, "GET", path, credential, null, headers, accept = "text/event-stream")
        // The head within iOS's 35 s; the body then has no deadline.
        val response = try {
            kotlinx.coroutines.withTimeoutOrNull(35_000) { streams.newCall(request).await() } ?: throw IOException("No answer")
        } catch (e: IOException) {
            reset(tunnel)
            throw DirectUnavailableException()
        }
        if (carriedNothing(response)) {
            response.close()
            reset(tunnel)
            throw DirectUnavailableException()
        }
        val body = flow {
            response.use {
                val source = it.body.source()
                val buffer = ByteArray(16 * 1024)
                while (true) {
                    val read = try {
                        source.inputStream().read(buffer)
                    } catch (e: IOException) {
                        throw DirectUnavailableException()
                    }
                    if (read < 0) break
                    if (read > 0) emit(buffer.copyOf(read))
                }
            }
        }.flowOn(io)
        return DirectHttpStream(response.code, headers(response), body)
    }

    private fun build(tunnel: TailcatTunnels.Tunnel, method: String, path: String, credential: String, body: ByteArray?, headers: Map<String, String>, accept: String): Request {
        val url = (tunnel.httpUrl + path.removePrefix("/")).toHttpUrlOrNull() ?: throw DirectProtocolException()
        val builder = Request.Builder().url(url)
            .header("Authorization", "Bearer $credential")
            .header("Accept", accept)
        for ((name, value) in headers) builder.header(name, value)
        val type = headers.entries.firstOrNull { it.key.equals("content-type", ignoreCase = true) }?.value ?: "application/json"
        val payload = body?.toRequestBody(type.toMediaType()) ?: if (method in listOf("POST", "PUT", "PATCH")) ByteArray(0).toRequestBody(null) else null
        return builder.method(method, payload).build()
    }

    companion object {
        /** The tunnel's connect timeout: iOS's 20 s. */
        const val CONNECT_TIMEOUT_MS = 20_000
        private const val LIMIT = 8 * 1024 * 1024
        private val METHODS = setOf("GET", "POST", "PATCH", "PUT", "DELETE")

        /** One client for every computer: its pool and threads are shared, its timeouts set per kind of request. */
        private val shared by lazy { OkHttpClient.Builder().retryOnConnectionFailure(false).followRedirects(false).build() }

        /**
         * The loopback end's own answer that the computer couldn't be
         * reached (expo-tailcat's proxy: a plain-text 502), not the daemon's.
         */
        internal fun carriedNothing(response: Response) =
            response.code == 502 && response.header("Content-Type").orEmpty().startsWith("text/plain")

        /** A path, a token and headers that can't split the request (happy-ios `TailcatHTTPTransport.safe`). */
        internal fun safe(path: String, credential: String, headers: Map<String, String>): Boolean =
            path.startsWith("/") && path.none { it == '\r' || it == '\n' || it == ' ' } &&
                credential.none { it == '\r' || it == '\n' } &&
                headers.all { (name, value) -> name.none { it == '\r' || it == '\n' || it == ':' } && value.none { it == '\r' || it == '\n' } }

        private fun headers(response: Response): Map<String, String> =
            response.headers.names().associate { it.lowercase() to response.headers.values(it).joinToString(", ") }
    }
}

private suspend fun Call.await(): Response = suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation { cancel() }
    enqueue(object : Callback {
        override fun onResponse(call: Call, response: Response) = continuation.resume(response) { _, _, _ -> response.close() }
        override fun onFailure(call: Call, e: IOException) = continuation.resumeWithException(e)
    })
}
