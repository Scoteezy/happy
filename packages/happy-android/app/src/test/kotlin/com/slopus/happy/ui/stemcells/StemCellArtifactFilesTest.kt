package com.slopus.happy.ui.stemcells

import android.net.Uri
import android.webkit.WebResourceRequest
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.slopus.happy.direct.DirectAgentClient
import com.slopus.happy.harness.StemCellsHarnessTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * A page's files as the artifact viewer's web view gets them: only its own
 * version's, read through the computer, whole or by range
 * (happy-ios `StemCellArtifactFiles`).
 */
@RunWith(AndroidJUnit4::class)
class StemCellArtifactFilesTest {
    private val fixture = File("src/debug/assets/harness/stem-cells-computer.json").readText()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val client = DirectAgentClient(StemCellsHarnessTransport(fixture, scope), "t")
    private val files = StemCellArtifactFiles(client, "arshops", scope)

    @After fun tearDown() = scope.cancel()

    private fun request(url: String, headers: Map<String, String> = emptyMap()) = object : WebResourceRequest {
        override fun getUrl(): Uri = Uri.parse(url)
        override fun isForMainFrame() = true
        override fun isRedirect() = false
        override fun hasGesture() = false
        override fun getMethod() = "GET"
        override fun getRequestHeaders() = headers
    }

    private fun page(path: String): String {
        val raw = DirectAgentClient.parse(fixture.toByteArray()) as kotlinx.serialization.json.JsonObject
        val shops = (raw["artifacts"] as kotlinx.serialization.json.JsonObject)["arshops"] as kotlinx.serialization.json.JsonObject
        return ((shops["files"] as kotlinx.serialization.json.JsonObject)[path] as kotlinx.serialization.json.JsonPrimitive).content
    }

    @Test fun aFileIsItsVersionAndItsDecodedPath() {
        assertEquals(3 to "assets/a b.css", StemCellArtifactFiles.file(Uri.parse("${StemCellArtifactFiles.ORIGIN}/3/assets/a%20b.css")))
        assertNull(StemCellArtifactFiles.file(Uri.parse("${StemCellArtifactFiles.ORIGIN}/3/")))
        assertNull(StemCellArtifactFiles.file(Uri.parse("${StemCellArtifactFiles.ORIGIN}/latest/index.html")))
        assertNull(StemCellArtifactFiles.file(Uri.parse("https://example.com/1/index.html")))
        assertFalse(StemCellArtifactFiles.owns(Uri.parse("http://artifact.happy.invalid/1/index.html")))
    }

    /** The whole file in one answer, with the computer's own type. */
    @Test fun theWholeFileComesThroughTheComputer() {
        val answer = files.answer(request("${StemCellArtifactFiles.ORIGIN}/1/index.html"))
        assertEquals(200, answer.statusCode)
        assertEquals("text/html", answer.mimeType)
        val html = page("index.html")
        assertEquals(html.toByteArray().size.toString(), answer.responseHeaders["content-length"])
        assertArrayEquals(html.toByteArray(), answer.data.readBytes())
    }

    /** A player's range is asked of the computer as it is, and answered as a part. */
    @Test fun aRangeIsAnsweredAsAPart() {
        val answer = files.answer(request("${StemCellArtifactFiles.ORIGIN}/1/style.css", mapOf("Range" to "bytes=0-9")))
        assertEquals(206, answer.statusCode)
        assertEquals("text/css", answer.mimeType)
        assertArrayEquals(page("style.css").toByteArray().copyOf(10), answer.data.readBytes())
        assertEquals("bytes 0-9/${page("style.css").toByteArray().size}", answer.responseHeaders["content-range"])
    }

    /** Nothing the page asks of the network gets out, and a file the version lacks fails. */
    @Test fun anythingElseIsRefused() {
        assertEquals(403, files.answer(request("https://example.com/tracker.png")).statusCode)
        assertEquals(502, files.answer(request("${StemCellArtifactFiles.ORIGIN}/1/missing.js")).statusCode)
    }
}
