package com.slopus.happy.direct

import com.slopus.happy.crypto.DirectPairingCrypto
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** happy-ios `DirectPairingLinkTests` and `DirectPairingServiceTests`, on API.md's vectors. */
class DirectPairingTest {
    private fun hex(text: String) = ByteArray(text.length / 2) { text.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    private fun bytes(value: Int) = ByteArray(32) { value.toByte() }

    private fun code(changes: Map<String, JsonPrimitive> = emptyMap()): String {
        val bundle = linkedMapOf(
            "v" to JsonPrimitive(1), "a" to JsonPrimitive("tcAddress"), "p" to JsonPrimitive(24781),
            "t" to JsonPrimitive(CanonicalBase64Url.encode(bytes(3))), "n" to JsonPrimitive("Mac"), "e" to JsonPrimitive(2_000_000_000_000.0),
        )
        bundle.putAll(changes)
        return DirectPairingLink.PREFIX + CanonicalBase64Url.encode(JsonObject(bundle).toString().toByteArray())
    }

    private fun kind(body: () -> Unit): DirectPairingError.Kind? = try {
        body(); null
    } catch (e: DirectPairingError) {
        e.kind
    }

    @Test fun newCodesAreDirect() {
        val direct = code(mapOf("unknown" to JsonPrimitive(true)))
        assertTrue(DirectPairingLink.isDirect(direct))
        assertEquals(24781, DirectPairingLink.parse(direct, now = 0).port)
        assertFalse(DirectPairingLink.isDirect("happy://terminal?abc"))
    }

    @Test fun malformedDirectCodesNeverFallBack() {
        val codes = listOf(
            "happy://direct?bad", "happy://direct", "happy://DIRECT?x", "happy://direct?e30=",
            code(mapOf("p" to JsonPrimitive(65536))), code(mapOf("p" to JsonPrimitive(0))),
            code(mapOf("t" to JsonPrimitive("bad"))), code(mapOf("n" to JsonPrimitive("bad\nname"))),
        )
        for (code in codes) {
            assertTrue(code, DirectPairingLink.isDirect(code))
            assertEquals(code, DirectPairingError.Kind.InvalidCode, kind { DirectPairingLink.parse(code, now = 0) })
        }
    }

    @Test fun expiryAndVersionHaveActionableErrors() {
        assertEquals(DirectPairingError.Kind.Expired, kind { DirectPairingLink.parse(code(mapOf("e" to JsonPrimitive(1)))) })
        assertEquals(DirectPairingError.Kind.UpdateRequired, kind { DirectPairingLink.parse(code(mapOf("v" to JsonPrimitive(2)))) })
        assertEquals(DirectPairingError.Kind.InvalidCode, kind { DirectPairingLink.parse(code(mapOf("v" to JsonPrimitive("1")))) })
    }

    @Test fun canonicalBinary() {
        assertNull(CanonicalBase64Url.decode("AB"))
        assertNull(CanonicalBase64Url.decode("AA="))
        assertArrayEquals(byteArrayOf(0), CanonicalBase64Url.decode("AA"))
    }

    private inner class VectorTransport(
        val ending: String = "completed",
        val proof: ByteArray = hex("81fa0f2f506f539f4ca3f22eeeac675850f3cd902719f14799fc6519266893c9"),
    ) : DirectTransport {
        val requests = ArrayList<Triple<String, String, ByteArray?>>()
        var closed = false

        override suspend fun request(method: String, path: String, credential: String, body: ByteArray?, headers: Map<String, String>): DirectHttpResponse {
            requests += Triple(path, credential, body)
            val answer: Map<String, JsonPrimitive> = when {
                path.endsWith("/handshake") && method == "POST" -> mapOf(
                    "handshakeId" to JsonPrimitive(CanonicalBase64Url.encode(bytes(6))),
                    "publicKey" to JsonPrimitive(CanonicalBase64Url.encode(hex("ce8d3ad1ccb633ec7b70c17814a5c76ecd029685050d344745ba05870e587d59"))),
                    "nonce" to JsonPrimitive(CanonicalBase64Url.encode(bytes(5))),
                    "expiresAt" to JsonPrimitive((System.currentTimeMillis() + 60_000).toDouble()),
                )
                path.endsWith("/reveal") -> mapOf("status" to JsonPrimitive("verifying"))
                path.endsWith("/reject") -> mapOf("status" to JsonPrimitive("rejected"))
                path.endsWith("/confirm") -> mapOf("status" to JsonPrimitive("verifying"))
                else -> mapOf("status" to JsonPrimitive(ending), "deviceId" to JsonPrimitive("dvVectorDevice"), "mac" to JsonPrimitive(CanonicalBase64Url.encode(proof)))
            }
            return DirectHttpResponse(200, body = JsonObject(answer).toString().toByteArray())
        }

        override suspend fun close() { closed = true }
    }

    private fun service(transport: VectorTransport): DirectPairingService {
        val link = DirectPairingLink.parse(code(mapOf(
            "a" to JsonPrimitive("tcFixedVectorAddress"), "n" to JsonPrimitive("Unverified Mac"),
            "e" to JsonPrimitive((System.currentTimeMillis() + 60_000).toDouble()),
        )))
        return DirectPairingService(link, transport, DirectPairingCrypto(bytes(1), bytes(4)))
    }

    @Test fun commitRevealEmojiConfirmWaitAndProof() = runTest {
        val transport = VectorTransport()
        val service = service(transport)
        assertEquals(listOf("🌀", "👗", "⏳", "👟"), service.start("Phone"))
        val credentials = service.confirm()
        assertEquals(CanonicalBase64Url.encode(hex("05bf38156a0b81046b4e15f584760e0e8996efe81239e5efe4560fef29c20aeb")), credentials.deviceToken)
        assertEquals("dvVectorDevice", credentials.deviceId)
        assertEquals(
            listOf("/v0/mobile-connector/handshake", "/v0/mobile-connector/handshake/reveal", "/v0/mobile-connector/handshake/confirm", "/v0/mobile-connector/handshake?wait=25"),
            transport.requests.map { it.first },
        )
        assertEquals(CanonicalBase64Url.encode(bytes(3)), transport.requests[0].second)
        assertTrue(transport.requests.drop(1).all { it.second == CanonicalBase64Url.encode(bytes(6)) })
        val confirmation = DirectAgentClient.parse(transport.requests[2].third!!) as JsonObject
        assertEquals(JsonPrimitive(CanonicalBase64Url.encode(hex("56562cd319819213d10c33fd9e508fffb19343d84b14882ecfc550c058181d85"))), confirmation["mac"])
        val started = DirectAgentClient.parse(transport.requests[0].third!!) as JsonObject
        assertEquals(JsonPrimitive("android"), (started["device"] as JsonObject)["platform"])
        assertTrue(transport.closed)
    }

    @Test fun invalidProofNeverProducesCredentials() = runTest {
        val transport = VectorTransport(proof = ByteArray(32))
        val service = service(transport)
        service.start("Phone")
        try {
            service.confirm()
            fail("Bad proof accepted")
        } catch (e: DirectPairingError) {
            assertEquals(DirectPairingError.Kind.InvalidProof, e.kind)
        }
        service.cancel()
    }

    @Test fun rejectedExpiredAndCancellationAreFinal() = runTest {
        for (ending in listOf("rejected", "expired")) {
            val service = service(VectorTransport(ending))
            service.start("Phone")
            try {
                service.confirm()
                fail("Terminal status accepted")
            } catch (e: DirectPairingError) {
                assertEquals(if (ending == "rejected") DirectPairingError.Kind.Rejected else DirectPairingError.Kind.Expired, e.kind)
            }
            service.cancel()
        }
        val transport = VectorTransport()
        val service = service(transport)
        service.start("Phone")
        service.cancel()
        try {
            service.confirm()
            fail("Cancelled handshake accepted")
        } catch (_: CancellationException) {
        }
        assertEquals("/v0/mobile-connector/handshake/reject", transport.requests.last().first)
        assertTrue(transport.closed)
    }
}
