package com.slopus.happy.crypto

import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * API.md's pairing transcript, which binds the address and port into the
 * proof. The derived device token never travels on the wire during
 * pairing. happy-ios `DirectPairingCrypto`.
 */
class DirectPairingCrypto(privateKey: ByteArray? = null, nonce: ByteArray? = null, random: RandomSource = SecureRandomSource) {
    private val privateKey: ByteArray = privateKey?.copyOf() ?: random.nextBytes(32)
    val publicKey: ByteArray
    val nonce: ByteArray = nonce?.copyOf() ?: random.nextBytes(32)
    val commitment: ByteArray

    init {
        require(this.privateKey.size == 32) { "pairing key" }
        require(this.nonce.size == 32) { "pairing nonce" }
        publicKey = Sodium.scalarMultBase(this.privateKey)
        commitment = sha256("$LABEL/commit".toByteArray() + publicKey + this.nonce)
    }

    class Secrets(val transcriptHash: ByteArray, val emojiKey: ByteArray, val confirmKey: ByteArray, val deviceToken: ByteArray) {
        val emoji: List<String> get() = emoji(sha256(emojiKey))

        fun mac(role: String): ByteArray = hmacSha256(confirmKey, role.toByteArray() + transcriptHash)

        fun verifyDaemon(proof: ByteArray): Boolean = MessageDigest.isEqual(proof, mac("daemon"))
    }

    /** Null for a key that agrees on nothing (low order) or is not a key at all. */
    fun derive(daemonPublicKey: ByteArray, daemonNonce: ByteArray, token: ByteArray, address: String, port: Int): Secrets? {
        if (daemonPublicKey.size != 32 || daemonNonce.size != 32 || token.size != 32) return null
        val shared = Sodium.scalarMult(privateKey, daemonPublicKey) ?: return null
        if (shared.all { it == 0.toByte() }) return null
        val fields = listOf(token, commitment, publicKey, daemonPublicKey, nonce, daemonNonce, address.toByteArray(), byteArrayOf((port shr 8).toByte(), (port and 255).toByte()))
        var transcript = LABEL.toByteArray()
        for (field in fields) {
            val count = field.size
            transcript += byteArrayOf((count ushr 24).toByte(), (count ushr 16).toByte(), (count ushr 8).toByte(), count.toByte())
            transcript += field
        }
        val hash = sha256(transcript)
        fun expand(purpose: String) = hkdfSha256(shared, hash, "$LABEL $purpose".toByteArray(), 32)
        return Secrets(hash, expand("emoji"), expand("confirm"), expand("device-token"))
    }

    companion object {
        private const val LABEL = "happy-mobile-pair-v1"

        fun emoji(hash: ByteArray): List<String> {
            if (hash.size != 32) return emptyList()
            val table = DirectPairingEmoji.table
            return (0 until 4).map { index ->
                var value = 0L
                for (i in 0 until 8) value = (value shl 8) or (hash[index * 8 + i].toLong() and 0xff)
                value = value and Long.MAX_VALUE
                table[(value % table.size).toInt()]
            }
        }

        internal fun sha256(data: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(data)

        internal fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray {
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(SecretKeySpec(key, "HmacSHA256"))
            return mac.doFinal(data)
        }

        /** RFC 5869, as CryptoKit's `hkdfDerivedSymmetricKey`. */
        internal fun hkdfSha256(secret: ByteArray, salt: ByteArray, info: ByteArray, length: Int): ByteArray {
            val prk = hmacSha256(salt, secret)
            val out = ArrayList<Byte>()
            var previous = ByteArray(0)
            var counter = 1
            while (out.size < length) {
                previous = hmacSha256(prk, previous + info + byteArrayOf(counter.toByte()))
                out += previous.toList()
                counter += 1
            }
            return out.take(length).toByteArray()
        }
    }
}
