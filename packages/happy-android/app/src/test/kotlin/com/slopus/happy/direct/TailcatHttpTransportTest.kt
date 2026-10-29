package com.slopus.happy.direct

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.net.ServerSocket

/**
 * The tunnel's loopback end played by a local server, as expo-tailcat's
 * proxy serves it: a capability path in front of the daemon's routes.
 */
class TailcatHttpTransportTest {
    private val server = MockWebServer()

    private inner class Tunnels(var linked: Boolean = true, var base: () -> String = { server.url("/").toString() }) : TailcatTunnels {
        val opened = ArrayList<String>()
        val closed = ArrayList<String>()
        override val isLinked get() = linked

        override fun open(address: String, port: Int, timeoutMs: Int): TailcatTunnels.Tunnel {
            assertEquals("tcADDRESS", address)
            assertEquals(8080, port)
            val id = "t${opened.size + 1}"
            opened += id
            return TailcatTunnels.Tunnel(id, base() + "cap-$id/")
        }

        override fun close(id: String) {
            closed += id
        }
    }

    @Before fun start() = server.start()

    @After fun stop() = server.close()

    private fun transport(tunnels: Tunnels) = TailcatHttpTransport("tcADDRESS", 8080, tunnels)

    @Test fun aRequestGoesThroughTheTunnelsCapabilityPath() = runBlocking {
        val tunnels = Tunnels()
        server.enqueue(MockResponse.Builder().code(201).addHeader("ETag", "\"v1\"").body("""{"ok":true}""").build())
        server.enqueue(MockResponse.Builder().code(200).body("{}").build())
        val transport = transport(tunnels)
        val response = transport.request("POST", "/v0/agents/a1/messages?x=1", "TOKEN", """{"text":"hi"}""".toByteArray(), mapOf("Idempotency-Key" to "k1"))
        assertEquals(201, response.status)
        assertEquals("\"v1\"", response.headers["etag"])
        assertArrayEquals("""{"ok":true}""".toByteArray(), response.body)
        val recorded = server.takeRequest()
        assertEquals("POST", recorded.method)
        assertEquals("/cap-t1/v0/agents/a1/messages?x=1", recorded.target)
        assertEquals("Bearer TOKEN", recorded.headers["Authorization"])
        assertEquals("application/json", recorded.headers["Accept"])
        assertEquals("k1", recorded.headers["Idempotency-Key"])
        assertTrue(recorded.headers["Content-Type"]!!.startsWith("application/json"))
        assertEquals("""{"text":"hi"}""", recorded.body!!.utf8())
        transport.request("GET", "/v0/health", "TOKEN", null, emptyMap())
        // One tunnel for both.
        assertEquals(listOf("t1"), tunnels.opened)
    }

    @Test fun theProxysOwnBadGatewayLetsTheTunnelGo() = runBlocking {
        val tunnels = Tunnels()
        server.enqueue(MockResponse.Builder().code(502).addHeader("Content-Type", "text/plain; charset=utf-8").body("tailcat upstream unavailable\n").build())
        server.enqueue(MockResponse.Builder().code(200).body("{}").build())
        val transport = transport(tunnels)
        try {
            transport.request("GET", "/v0/health", "TOKEN", null, emptyMap())
            fail()
        } catch (_: DirectUnavailableException) {
        }
        assertEquals(listOf("t1"), tunnels.closed)
        assertEquals(200, transport.request("GET", "/v0/health", "TOKEN", null, emptyMap()).status)
        assertEquals(listOf("t1", "t2"), tunnels.opened)
        assertEquals("/cap-t2/v0/health", server.takeRequest().let { server.takeRequest() }.target)
    }

    @Test fun theDaemonsOwnErrorsAreAnswers() = runBlocking {
        val tunnels = Tunnels()
        server.enqueue(MockResponse.Builder().code(502).addHeader("Content-Type", "application/json").body("""{"code":"upstream"}""").build())
        val response = transport(tunnels).request("GET", "/v0/health", "TOKEN", null, emptyMap())
        assertEquals(502, response.status)
        assertTrue(tunnels.closed.isEmpty())
    }

    @Test fun aClosedLoopbackEndIsUnavailableAndOpensAgain() = runBlocking {
        val gone = ServerSocket(0).use { it.localPort }
        val tunnels = Tunnels(base = { "http://127.0.0.1:$gone/" })
        val transport = transport(tunnels)
        try {
            transport.request("GET", "/v0/health", "TOKEN", null, emptyMap())
            fail()
        } catch (_: DirectUnavailableException) {
        }
        assertEquals(listOf("t1"), tunnels.closed)
        tunnels.base = { server.url("/").toString() }
        server.enqueue(MockResponse.Builder().code(200).body("{}").build())
        assertEquals(200, transport.request("GET", "/v0/health", "TOKEN", null, emptyMap()).status)
        assertEquals(listOf("t1", "t2"), tunnels.opened)
    }

    @Test fun aBuildWithoutTheLibrarySaysSo() = runBlocking {
        val tunnels = Tunnels(linked = false)
        try {
            transport(tunnels).request("GET", "/v0/health", "TOKEN", null, emptyMap())
            fail()
        } catch (_: TailcatUnlinkedException) {
        }
        assertTrue(tunnels.opened.isEmpty())
    }

    @Test fun aRequestThatCouldSplitIsRefusedBeforeTheTunnel() = runBlocking {
        val tunnels = Tunnels()
        val transport = transport(tunnels)
        for ((path, headers) in listOf(
            "v0/health" to emptyMap(),
            "/v0/health HTTP/1.1" to emptyMap(),
            "/v0/health" to mapOf("X-A" to "1\r\nHost: evil"),
            "/v0/health" to mapOf("X:A" to "1"),
        )) {
            try {
                transport.request("GET", path, "TOKEN", null, headers)
                fail(path)
            } catch (_: DirectProtocolException) {
            }
        }
        try {
            transport.request("TRACE", "/v0/health", "TOKEN", null, emptyMap())
            fail()
        } catch (_: DirectProtocolException) {
        }
        assertTrue(tunnels.opened.isEmpty())
    }

    @Test fun anEventStreamArrivesAsItIsWritten() = runBlocking {
        val tunnels = Tunnels()
        server.enqueue(
            MockResponse.Builder().code(200).addHeader("Content-Type", "text/event-stream")
                .body("event: hello\ndata: {}\n\nevent: x\ndata: 1\n\n").build(),
        )
        val stream = transport(tunnels).stream("/v0/events/stream", "TOKEN", mapOf("Last-Event-ID" to "c9"))
        assertEquals(200, stream.status)
        assertEquals("text/event-stream", stream.headers["content-type"])
        val text = stream.body.toList().fold(ByteArray(0)) { all, piece -> all + piece }.decodeToString()
        assertEquals("event: hello\ndata: {}\n\nevent: x\ndata: 1\n\n", text)
        val recorded = server.takeRequest()
        assertEquals("/cap-t1/v0/events/stream", recorded.target)
        assertEquals("text/event-stream", recorded.headers["Accept"])
        assertEquals("c9", recorded.headers["Last-Event-ID"])
    }

    @Test fun closingLetsTheTunnelGoForGood() = runBlocking {
        val tunnels = Tunnels()
        server.enqueue(MockResponse.Builder().code(200).body("{}").build())
        val transport = transport(tunnels)
        transport.request("GET", "/v0/health", "TOKEN", null, emptyMap())
        transport.close()
        assertEquals(listOf("t1"), tunnels.closed)
        try {
            transport.request("GET", "/v0/health", "TOKEN", null, emptyMap())
            fail()
        } catch (_: CancellationException) {
        }
        assertEquals(listOf("t1"), tunnels.opened)
    }
}
