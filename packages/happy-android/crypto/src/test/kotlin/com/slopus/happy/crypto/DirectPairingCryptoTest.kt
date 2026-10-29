package com.slopus.happy.crypto

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.MessageDigest

/** API.md's independent pairing vectors (happy-ios `DirectPairingCryptoTests`). */
class DirectPairingCryptoTest {
    private fun bytes(value: Int, count: Int) = ByteArray(count) { value.toByte() }

    @Test fun independentAPIVectors() {
        val phone = DirectPairingCrypto(privateKey = bytes(1, 32), nonce = bytes(4, 32))
        assertArrayEquals(decodeHex("a4e09292b651c278b9772c569f5fa9bb13d906b46ab68c9df9dc2b4409f8a209"), phone.publicKey)
        assertArrayEquals(decodeHex("ae75325169fe3b8d8571df1c5fd015ef9989051a0363e780322a09ebf2430caf"), phone.commitment)
        val secrets = phone.derive(decodeHex("ce8d3ad1ccb633ec7b70c17814a5c76ecd029685050d344745ba05870e587d59"), bytes(5, 32), bytes(3, 32), "tcFixedVectorAddress", 24781)!!
        assertArrayEquals(decodeHex("919140a78d542ec0999e8b045e9b368c94a5cc2d90d6c92e7f14e8d6d302eb08"), secrets.transcriptHash)
        assertArrayEquals(decodeHex("fa42488ee1ceadb6998e8d35792cdda1a1210a63153069fe67f1f1ce57404369"), secrets.emojiKey)
        assertArrayEquals(decodeHex("152eb695974a86f350d777dd1a7e63abb327eb6af37ee465addbb46ea53cde3f"), secrets.confirmKey)
        assertArrayEquals(decodeHex("05bf38156a0b81046b4e15f584760e0e8996efe81239e5efe4560fef29c20aeb"), secrets.deviceToken)
        assertArrayEquals(decodeHex("56562cd319819213d10c33fd9e508fffb19343d84b14882ecfc550c058181d85"), secrets.mac("mobile"))
        assertTrue(secrets.verifyDaemon(decodeHex("81fa0f2f506f539f4ca3f22eeeac675850f3cd902719f14799fc6519266893c9")))
        assertFalse(secrets.verifyDaemon(secrets.mac("mobile")))
        assertFalse(secrets.verifyDaemon(ByteArray(32)))
        assertEquals(listOf("🌀", "👗", "⏳", "👟"), secrets.emoji)
    }

    @Test fun emojiTableAndExamples() {
        assertEquals(333, DirectPairingEmoji.table.size)
        val hash = MessageDigest.getInstance("SHA-256").digest(DirectPairingEmoji.table.joinToString("\n").toByteArray())
        assertArrayEquals(decodeHex("c22c3a00d5a68df075fecc8cb8b730a45817a246a5ca70fa3b75d248ac5b54e9"), hash)
        assertEquals(listOf("😉", "😉", "😉", "😉"), DirectPairingCrypto.emoji(ByteArray(32)))
        val counting = ByteArray(32) { it.toByte() }
        assertEquals(listOf("💍", "☔️", "📅", "🍞"), DirectPairingCrypto.emoji(counting))
        assertEquals(listOf("🙉", "🇷🇺", "☔️", "✈️"), DirectPairingCrypto.emoji(MessageDigest.getInstance("SHA-256").digest(counting)))
    }

    @Test fun lowOrderAndMalformedKeysAreRejected() {
        val phone = DirectPairingCrypto()
        for (key in listOf(ByteArray(32), byteArrayOf(1) + ByteArray(31), byteArrayOf(1))) {
            assertNull(phone.derive(key, bytes(5, 32), bytes(3, 32), "tcAddress", 24781))
        }
    }
}
