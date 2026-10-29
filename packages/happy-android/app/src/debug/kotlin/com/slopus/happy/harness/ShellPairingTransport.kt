package com.slopus.happy.harness

import com.slopus.happy.crypto.DirectPairingCrypto
import com.slopus.happy.crypto.Sodium
import com.slopus.happy.direct.*
import kotlinx.serialization.json.*
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** Fake daemon side of pairing, only injected by the isolated shell APK. Never used by real connections. */
class ShellPairingTransport(private val computer: DirectTransport, private val address: String, private val port: Int) : DirectTransport by computer {
    private val token = ByteArray(32) { 7 }
    private val privateKey = ByteArray(32) { 8 }
    private val publicKey = Sodium.scalarMultBase(privateKey)
    private val nonce = ByteArray(32) { 9 }
    private val handshakeId = CanonicalBase64Url.encode(ByteArray(32) { 10 })
    private var commitment: ByteArray? = null
    private var secrets: DirectPairingCrypto.Secrets? = null
    override suspend fun request(method: String, path: String, credential: String, body: ByteArray?, headers: Map<String, String>): DirectHttpResponse {
        if (!path.startsWith("/v0/mobile-connector/handshake")) return computer.request(method, path, credential, body, headers)
        val input = body?.let { DirectAgentClient.parse(it) as JsonObject }
        fun binary(key: String) = CanonicalBase64Url.decode((input?.get(key) as JsonPrimitive).content)!!
        fun answer(vararg values: Pair<String, JsonElement>) = DirectHttpResponse(200, body = JsonObject(mapOf(*values)).toString().toByteArray())
        if (path.endsWith("/handshake") && method == "POST") {
            if (credential != CanonicalBase64Url.encode(token)) return DirectHttpResponse(401)
            commitment = binary("commitment")
            return answer("handshakeId" to JsonPrimitive(handshakeId), "publicKey" to JsonPrimitive(CanonicalBase64Url.encode(publicKey)),
                "nonce" to JsonPrimitive(CanonicalBase64Url.encode(nonce)), "expiresAt" to JsonPrimitive(System.currentTimeMillis() + 120_000))
        }
        if (credential != handshakeId) return DirectHttpResponse(401)
        if (path.endsWith("/reject")) { secrets = null; return answer("status" to JsonPrimitive("rejected")) }
        if (path.endsWith("/reveal")) {
            val mobileKey = binary("publicKey")
            val mobileNonce = binary("nonce")
            val committed = commitment ?: return DirectHttpResponse(400)
            if (!MessageDigest.isEqual(committed, sha("$LABEL/commit".toByteArray() + mobileKey + mobileNonce))) return DirectHttpResponse(400)
            val shared = Sodium.scalarMult(privateKey, mobileKey) ?: return DirectHttpResponse(400)
            val fields = listOf(token, committed, mobileKey, publicKey, mobileNonce, nonce, address.toByteArray(), byteArrayOf((port shr 8).toByte(), port.toByte()))
            val transcript = java.io.ByteArrayOutputStream().apply {
                write(LABEL.toByteArray())
                java.io.DataOutputStream(this).also { output -> fields.forEach { output.writeInt(it.size); output.write(it) } }
            }.toByteArray()
            val hash = sha(transcript)
            fun expand(purpose: String) = mac(mac(hash, shared), "$LABEL $purpose".toByteArray() + byteArrayOf(1))
            secrets = DirectPairingCrypto.Secrets(hash, expand("emoji"), expand("confirm"), expand("device-token"))
            return answer("status" to JsonPrimitive("verifying"))
        }
        if (path.endsWith("/confirm")) {
            val proof = secrets ?: return DirectHttpResponse(400)
            if (!MessageDigest.isEqual(proof.mac("mobile"), binary("mac"))) return DirectHttpResponse(403)
            return answer("status" to JsonPrimitive("completed"), "deviceId" to JsonPrimitive("paired-fixture"),
                "mac" to JsonPrimitive(CanonicalBase64Url.encode(proof.mac("daemon"))))
        }
        return DirectHttpResponse(400)
    }
    override suspend fun close() {} // A handle goes away, not the fake daemon.
    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
    private fun mac(key: ByteArray, value: ByteArray) = Mac.getInstance("HmacSHA256").run { init(SecretKeySpec(key, "HmacSHA256")); doFinal(value) }
    private companion object { const val LABEL = "happy-mobile-pair-v1" }
}