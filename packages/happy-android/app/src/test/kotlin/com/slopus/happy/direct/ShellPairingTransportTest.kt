package com.slopus.happy.direct

import com.slopus.happy.harness.ShellPairingTransport
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class ShellPairingTransportTest {
    @Test fun fakeDaemonProvesTheRealPhonesRandomHandshake() = runTest {
        val link = DirectPairingLink.parse(DirectPairingLink.PREFIX + CanonicalBase64Url.encode(buildJsonObject {
            put("v", 1); put("a", "shell-fixture-paired"); put("p", 1234)
            put("t", CanonicalBase64Url.encode(ByteArray(32) { 7 })); put("n", "Fixture computer")
            put("e", System.currentTimeMillis() + 120_000)
        }.toString().toByteArray()))
        val computer = object : DirectTransport {
            override suspend fun request(method: String, path: String, credential: String, body: ByteArray?, headers: Map<String, String>) = DirectHttpResponse(503)
        }
        val service = DirectPairingService(link, ShellPairingTransport(computer, link.address, link.port))
        assertEquals(4, service.start("Android fixture").size)
        val credentials = service.confirm()
        assertEquals("paired-fixture", credentials.deviceId)
        assertEquals(32, CanonicalBase64Url.decode(credentials.deviceToken)!!.size)
    }
}