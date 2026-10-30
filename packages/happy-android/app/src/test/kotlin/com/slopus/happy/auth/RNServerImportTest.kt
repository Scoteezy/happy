package com.slopus.happy.auth

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.CRC32

/** Independent MiniPBCoder/CodedOutputData fixtures, matching frozen native tests. */
internal object MMKVFixtures {
    fun varint(value: Int): ByteArray {
        var remaining = value
        val bytes = ArrayList<Byte>()
        do {
            val byte = remaining and 127
            remaining = remaining ushr 7
            bytes += (byte or if (remaining == 0) 0 else 128).toByte()
        } while (remaining != 0)
        return bytes.toByteArray()
    }
    fun field(value: ByteArray) = varint(value.size) + value
    fun region(vararg records: Pair<String, String?>): ByteArray = records.fold(varint(0x200000)) { bytes, (key, value) ->
        bytes + field(key.toByteArray()) + field(value?.let { field(it.toByteArray()) } ?: byteArrayOf())
    }
    fun put(bytes: ByteArray, offset: Int, value: Long) {
        repeat(4) { bytes[offset + it] = (value ushr (8 * it)).toByte() }
    }
    fun file(region: ByteArray, version: Int = 3, headerSize: Int = region.size): Pair<ByteArray, ByteArray> {
        val data = ByteArray(4).also { put(it, 0, headerSize.toLong()) } + region + ByteArray(64)
        val meta = ByteArray(112)
        put(meta, 0, CRC32().apply { update(region) }.value)
        put(meta, 4, version.toLong())
        put(meta, 28, region.size.toLong())
        return data to meta
    }
    fun write(directory: File, fixture: Pair<ByteArray, ByteArray>) {
        directory.mkdirs()
        File(directory, "server-config").writeBytes(fixture.first)
        File(directory, "server-config.crc").writeBytes(fixture.second)
    }
}

class RNServerImportTest {
    @get:Rule val temporary = TemporaryFolder()
    private val key = "custom-server-url"
    private val default = ImportedAccountServer.Confirmed(RNServerConfigImporter.DEFAULT_SERVER_URL)
    private fun decode(fixture: Pair<ByteArray, ByteArray>) = MMKVStringDecoder.string(key, fixture.first, fixture.second)
    private fun rejected(fixture: Pair<ByteArray, ByteArray>) {
        assertTrue(runCatching { decode(fixture) }.isFailure)
    }

    @Test fun crcMatchesZlibVector() {
        assertEquals(0xcbf43926L, CRC32().apply { update("123456789".toByteArray()) }.value)
    }

    @Test fun lastRecordWinsIncludingLongNestedString() {
        val host = "https://self.test/" + "a".repeat(150)
        assertEquals(host, decode(MMKVFixtures.file(MMKVFixtures.region(key to "https://old.test", "other" to "ignored", key to host))))
    }

    @Test fun deletedEmptyAndMissingAreDistinctInDecoderAndAllImportDefault() {
        val directory = temporary.newFolder()
        val regions = listOf(
            MMKVFixtures.region(key to "https://old.test", key to null) to null,
            MMKVFixtures.region(key to "") to "",
            MMKVFixtures.region("other" to "value") to null,
        )
        for ((region, expected) in regions) {
            val fixture = MMKVFixtures.file(region)
            assertEquals(expected, decode(fixture))
            MMKVFixtures.write(directory, fixture)
            assertEquals(default, RNServerConfigImporter.read(directory))
        }
    }

    @Test fun metadataSizeOverridesStaleHeaderAndUncommittedTail() {
        val committed = MMKVFixtures.region(key to "https://committed.test")
        val tail = MMKVFixtures.region(key to "https://uncommitted.test").drop(4).toByteArray()
        val fixture = MMKVFixtures.file(committed, headerSize = committed.size + tail.size)
        tail.copyInto(fixture.first, 4 + committed.size)
        assertEquals("https://committed.test", decode(fixture))
    }

    @Test fun oldFullMetadataUsesVerifiedHeaderSize() {
        assertEquals("https://old.test", decode(MMKVFixtures.file(MMKVFixtures.region(key to "https://old.test"), version = 2)))
    }

    @Test fun checksumMissingTruncatedAndTornAreRejected() {
        val fixture = MMKVFixtures.file(MMKVFixtures.region(key to "https://safe.test"))
        assertTrue(runCatching { MMKVStringDecoder.string(key, fixture.first, null) }.isFailure)
        for (length in listOf(0, 4, 8, 28, 104, 111)) rejected(fixture.first to fixture.second.copyOf(length))
        fixture.first[8] = (fixture.first[8].toInt() xor 1).toByte()
        rejected(fixture)
    }

    @Test fun futureVersionAndExpiryFlagsAreRejected() {
        rejected(MMKVFixtures.file(MMKVFixtures.region(key to "https://safe.test"), version = 5))
        rejected(MMKVFixtures.file(MMKVFixtures.region(key to "https://safe.test"), version = 6))
        val flags = MMKVFixtures.file(MMKVFixtures.region(key to "https://safe.test"), version = 4)
        flags.second[104] = 1
        rejected(flags)
        flags.second[104] = 0
        flags.second[111] = 1
        rejected(flags)
        val encrypted = MMKVFixtures.file(MMKVFixtures.region(key to "https://safe.test"))
        encrypted.second[12] = 1
        rejected(encrypted)
    }

    @Test fun invalidSizesAndMalformedRecordsNeverSalvageEarlierHost() {
        rejected(MMKVFixtures.file(byteArrayOf(), version = 2, headerSize = 1000))
        rejected(MMKVFixtures.file(byteArrayOf(0, 127, 1)))
        rejected(MMKVFixtures.file(MMKVFixtures.region(key to "https://safe.test") + byteArrayOf(127)))
        rejected(MMKVFixtures.file(byteArrayOf(0) + MMKVFixtures.field(key.toByteArray()) + MMKVFixtures.field(byteArrayOf(1, -1))))
        rejected(MMKVFixtures.file(ByteArray(10) { -128 }))
        rejected(MMKVFixtures.file(byteArrayOf(0) + MMKVFixtures.field(byteArrayOf(-1)) + byteArrayOf(0)))
    }

    @Test fun noStoreMeansRnDefaultAndDoesNotCreateDirectory() {
        val directory = File(temporary.root, "missing-mmkv")
        assertEquals(default, RNServerConfigImporter.read(directory))
        assertFalse(directory.exists())
    }

    @Test fun orphanedChecksumAndUnreadableFormatNeedConfirmation() {
        val directory = temporary.newFolder()
        File(directory, "server-config.crc").writeBytes(ByteArray(112))
        assertEquals(ImportedAccountServer.NeedsConfirmation, RNServerConfigImporter.read(directory))
        File(directory, "server-config").mkdir()
        assertEquals(ImportedAccountServer.NeedsConfirmation, RNServerConfigImporter.read(directory))
    }

    @Test fun customUrlIsValidatedNormalizedAndSourceFilesStayByteIdentical() {
        val directory = temporary.newFolder()
        for (host in listOf(" https://self.test/path/// ", "file:///tmp/server", "not a host", " ")) {
            val fixture = MMKVFixtures.file(MMKVFixtures.region(key to host))
            MMKVFixtures.write(directory, fixture)
            val expected = if (host.startsWith(" https:")) ImportedAccountServer.Confirmed("https://self.test/path")
                else ImportedAccountServer.NeedsConfirmation
            assertEquals(expected, RNServerConfigImporter.read(directory))
            assertArrayEquals(fixture.first, File(directory, "server-config").readBytes())
            assertArrayEquals(fixture.second, File(directory, "server-config.crc").readBytes())
        }
    }

    @Test fun oversizedFilesAreRefusedWithoutRewriting() {
        val directory = temporary.newFolder()
        val file = File(directory, "server-config")
        java.io.RandomAccessFile(file, "rw").use { it.setLength(8L * 1024 * 1024 + 1) }
        assertEquals(ImportedAccountServer.NeedsConfirmation, RNServerConfigImporter.read(directory))
        assertEquals(8L * 1024 * 1024 + 1, file.length())
    }
}