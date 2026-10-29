package com.slopus.happy.direct

import kotlinx.coroutines.flow.Flow
import java.io.ByteArrayOutputStream

/**
 * A response head, and its decoded body as the bytes arrive (happy-ios
 * `DirectHTTPStream`). The body ends when the server closes it; ending the
 * collection closes the stream.
 */
class DirectHttpStream(val status: Int, val headers: Map<String, String> = emptyMap(), val body: Flow<ByteArray>)

/** The tunnel or the computer could not be reached (happy-ios `DirectPairingError.unavailable`). */
class DirectUnavailableException(message: String = "The computer could not be reached.") : Exception(message)

/** An answer that is not HTTP (happy-ios `DirectPairingError.invalidResponse`). */
class DirectProtocolException(message: String = "The computer's answer could not be read.") : Exception(message)

/**
 * A response's status line and headers, once all of them have arrived, and
 * the body bytes that came with them (happy-ios `DirectHTTPHead`).
 */
class DirectHttpHead(val status: Int, val headers: Map<String, String>) {
    companion object {
        private val divider = "\r\n\r\n".toByteArray()

        /** The head and the rest, or null while it is still arriving. */
        fun parse(data: ByteArray): Pair<DirectHttpHead, ByteArray>? {
            val end = indexOf(data, divider, 0)
            if (end < 0) {
                if (data.size > 32768) throw DirectProtocolException()
                return null
            }
            if (end > 32768) throw DirectProtocolException()
            val head = try {
                data.decodeToString(0, end, throwOnInvalidSequence = true)
            } catch (_: CharacterCodingException) {
                throw DirectProtocolException()
            }
            val lines = head.split("\r\n")
            val statusParts = lines.first().split(' ').filter { it.isNotEmpty() }
            val status = statusParts.getOrNull(1)?.toIntOrNull()
            if (statusParts.size < 2 || statusParts[0] !in listOf("HTTP/1.1", "HTTP/1.0") || status == null || status !in 200..599) throw DirectProtocolException()
            val headers = HashMap<String, String>()
            for (line in lines.drop(1)) {
                val colon = line.indexOf(':')
                if (colon < 0) throw DirectProtocolException()
                val name = line.substring(0, colon).lowercase()
                if (name.isEmpty() || name in headers) throw DirectProtocolException()
                headers[name] = line.substring(colon + 1).trim(' ', '\t')
            }
            return DirectHttpHead(status, headers) to data.copyOfRange(end + divider.size, data.size)
        }

        internal fun indexOf(data: ByteArray, needle: ByteArray, from: Int): Int {
            var index = from
            while (index + needle.size <= data.size) {
                var matches = true
                for (offset in needle.indices) if (data[index + offset] != needle[offset]) { matches = false; break }
                if (matches) return index
                index++
            }
            return -1
        }
    }
}

/**
 * A streamed body decoded as it comes: chunked framing undone, or the bytes
 * as they are when the body is close-delimited (happy-ios `DirectBodyDecoder`).
 */
class DirectBodyDecoder(private val chunked: Boolean) {
    private var pending = ByteArray(0)
    /** Bytes left in the current chunk; null while a size line is awaited. */
    private var remaining: Int? = null
    var finished = false
        private set

    fun append(bytes: ByteArray): ByteArray {
        if (!chunked) return bytes
        pending += bytes
        val out = ByteArrayOutputStream()
        while (!finished) {
            val count = remaining
            if (count != null) {
                if (count > 0) {
                    val take = minOf(count, pending.size)
                    if (take == 0) break
                    out.write(pending, 0, take)
                    pending = pending.copyOfRange(take, pending.size)
                    remaining = count - take
                    continue
                }
                if (pending.size < 2) break
                if (pending[0] != '\r'.code.toByte() || pending[1] != '\n'.code.toByte()) throw DirectProtocolException()
                pending = pending.copyOfRange(2, pending.size)
                remaining = null
                continue
            }
            val lineEnd = DirectHttpHead.indexOf(pending, CRLF, 0)
            if (lineEnd < 0) {
                if (pending.size >= 8192) throw DirectProtocolException()
                break
            }
            val text = pending.copyOfRange(0, lineEnd).decodeToString()
            val size = text.split(';', limit = 2).first().trim(' ', '\t').toIntOrNull(16)
            if (size == null || size < 0 || size > 8 * 1024 * 1024) throw DirectProtocolException()
            pending = pending.copyOfRange(lineEnd + 2, pending.size)
            if (size == 0) finished = true else remaining = size
        }
        return out.toByteArray()
    }

    private companion object {
        val CRLF = "\r\n".toByteArray()
    }
}

/** One Server-Sent Events frame. */
data class ServerSentEvent(val id: String?, val event: String, val data: String)

/**
 * Splits a byte stream into SSE frames (WHATWG event-stream parsing): lines
 * end in LF, CR or CRLF; comments are skipped; `data` lines join with LF.
 * A port of happy-ios `ServerSentEventParser`.
 */
class ServerSentEventParser {
    private val buffer = ByteArrayOutputStream()
    private var sawCR = false
    private var id: String? = null
    private var event = ""
    private val data = ArrayList<String>()
    private var hasData = false

    fun append(bytes: ByteArray): List<ServerSentEvent> {
        val frames = ArrayList<ServerSentEvent>()
        for (byte in bytes) {
            if (sawCR) {
                sawCR = false
                if (byte == LF) continue
            }
            if (byte == LF || byte == CR) {
                sawCR = byte == CR
                line(buffer.toByteArray().decodeToString(), frames)
                buffer.reset()
            } else {
                buffer.write(byte.toInt())
            }
        }
        return frames
    }

    private fun line(line: String, frames: MutableList<ServerSentEvent>) {
        if (line.isEmpty()) {
            if (hasData) frames += ServerSentEvent(id, event.ifEmpty { "message" }, data.joinToString("\n"))
            event = ""; data.clear(); hasData = false; id = null
            return
        }
        if (line.startsWith(":")) return
        val colon = line.indexOf(':')
        val field = if (colon >= 0) line.substring(0, colon) else line
        val value = if (colon >= 0) line.substring(colon + 1).removePrefix(" ") else ""
        when (field) {
            "event" -> event = value
            "data" -> { data += value; hasData = true }
            "id" -> if ('\u0000' !in value) id = value
        }
    }

    private companion object {
        const val LF: Byte = 0x0A
        const val CR: Byte = 0x0D
    }
}
