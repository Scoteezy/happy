package com.slopus.happy.auth

import com.slopus.happy.config.ServerConfig
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.attribute.BasicFileAttributes
import java.util.zip.CRC32

/** A host established from RN's own file, never the native onboarding preference. */
sealed interface ImportedAccountServer {
    data class Confirmed(val url: String) : ImportedAccountServer
    data object NeedsConfirmation : ImportedAccountServer
}

/**
 * happy-ios RNServerConfigImporter, reading Android's files/mmkv directory.
 * react-native-mmkv 3.3.3's MmkvPlatformContextModule supplies that exact path;
 * NativeMmkv.ts passes it unchanged to initialize. Never opens an MMKV instance:
 * its recovery/initialization can rewrite the very evidence being imported.
 */
object RNServerConfigImporter {
    const val DEFAULT_SERVER_URL = "https://api.cluster-fluster.com"
    private const val MAX_FILE_BYTES = 8 * 1024 * 1024

    /** Blocking, read-only; the account repository calls this on its I/O dispatcher. */
    fun read(directory: File): ImportedAccountServer = try {
        val file = File(directory, "server-config")
        val metadata = File(directory, "server-config.crc")
        // An orphaned checksum is not evidence that RN used the default host.
        if (!exists(file)) {
            if (exists(metadata)) {
                ImportedAccountServer.NeedsConfirmation
            } else ImportedAccountServer.Confirmed(DEFAULT_SERVER_URL)
        } else {
            val value = MMKVStringDecoder.string("custom-server-url", readBounded(file),
                if (exists(metadata)) readBounded(metadata) else null)
            val url = if (value.isNullOrEmpty()) DEFAULT_SERVER_URL else value.trim().trimEnd('/')
            if (ServerConfig.validate(url) == ServerConfig.Validation.Valid) ImportedAccountServer.Confirmed(url)
            else ImportedAccountServer.NeedsConfirmation
        }
    } catch (_: IOException) {
        ImportedAccountServer.NeedsConfirmation
    } catch (_: IllegalArgumentException) {
        ImportedAccountServer.NeedsConfirmation
    } catch (_: SecurityException) {
        ImportedAccountServer.NeedsConfirmation
    }

    // File.exists() conflates missing with denied access; only ENOENT establishes absence.
    private fun exists(file: File): Boolean = try {
        Files.readAttributes(file.toPath(), BasicFileAttributes::class.java)
        true
    } catch (_: NoSuchFileException) {
        false
    }

    private fun readBounded(file: File): ByteArray = file.inputStream().use { input ->
        require(file.length() <= MAX_FILE_BYTES)
        // Bound the read too: another process may grow the file after length().
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (out.size() <= MAX_FILE_BYTES) {
            val count = input.read(buffer, 0, minOf(buffer.size, MAX_FILE_BYTES + 1 - out.size()))
            if (count < 0) break
            out.write(buffer, 0, count)
        }
        require(out.size() <= MAX_FILE_BYTES)
        out.toByteArray()
    }
}

/**
 * Read only the unencrypted, non-expiring map/string format. Pinned RN 3.3.3
 * uses Tencent/MMKV f77545297d3e034066607954288a3710c80e5b4f:
 * MMKVMetaInfo.hpp is 112 bytes (CRC 0, version 4, actualSize 28, flags 104).
 * MMKV_IO.cpp checks IEEE CRC32 over actual data after the four-byte header.
 * No greedy salvage: an apparently valid earlier host is unsafe after a torn write.
 */
internal object MMKVStringDecoder {
    fun string(key: String, data: ByteArray, metadata: ByteArray?): String? {
        require(data.size >= 4)
        // A partial metadata struct cannot establish which size/flags apply.
        require(metadata == null || metadata.size >= 112)
        var size = fixed32(data, 0)
        if (metadata != null) {
            val version = fixed32(metadata, 4)
            require(version < 5) // The pinned writer's versions are 0..4; 5 is reserved for future use.
            if (version >= 2) require((12 until 28).all { metadata[it] == 0.toByte() }) // No encrypted/random-IV data.
            if (version >= 4) require((104 until 112).all { metadata[it] == 0.toByte() })
            if (version >= 3) size = fixed32(metadata, 28)
        }
        require(size <= data.size - 4L)
        val end = 4 + size.toInt()
        if (metadata != null) {
            val crc = CRC32().apply { update(data, 4, size.toInt()) }.value
            require(crc == fixed32(metadata, 0))
        } else require(size == 0L)
        if (size == 0L) return null
        val input = Input(data, 4, end)
        input.varint32() // MiniPBCoder's item-size holder, not a record count.
        var last: ByteArray? = null
        while (!input.atEnd) {
            val recordKey = utf8(input.lengthPrefixed())
            require(recordKey.isNotEmpty())
            val value = input.lengthPrefixed()
            if (recordKey == key) last = value.takeIf { it.isNotEmpty() }
        }
        return last?.let {
            val value = Input(it)
            val string = utf8(value.lengthPrefixed())
            require(value.atEnd)
            string
        }
    }

    private fun fixed32(bytes: ByteArray, offset: Int): Long =
        (0..3).fold(0L) { result, index -> result or ((bytes[offset + index].toLong() and 255) shl (8 * index)) }

    private fun utf8(bytes: ByteArray): String = Charsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(bytes)).toString()

    private class Input(val bytes: ByteArray, var position: Int = 0, val end: Int = bytes.size) {
        val atEnd get() = position == end
        fun varint32(): Long {
            var value = 0L
            repeat(10) { index ->
                require(position < end)
                val byte = bytes[position++].toInt() and 255
                if (index < 5) value = value or ((byte.toLong() and 127) shl (7 * index))
                if (byte and 128 == 0) {
                    require(value <= 0xffffffffL)
                    return value
                }
            }
            throw IllegalArgumentException("Malformed MMKV varint")
        }
        fun lengthPrefixed(): ByteArray {
            val length = varint32()
            require(length <= end - position.toLong())
            val start = position
            position += length.toInt()
            return bytes.copyOfRange(start, position)
        }
    }
}