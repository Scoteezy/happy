package com.slopus.happy.harness

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import com.slopus.happy.AppGraph
import com.slopus.happy.HappyApplication
import com.slopus.happy.MainActivity
import com.slopus.happy.auth.AccountDisplay
import com.slopus.happy.auth.AuthState
import com.slopus.happy.direct.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first

/** Only the dedicated .shellharness APK gets fixtures; normal debug/preview/release never do. */
class ShellHarnessApplication : HappyApplication() {
    override fun createGraph(): AppGraph {
        if (!packageName.endsWith(".shellharness")) return super.createGraph()
        val fixtureScope = CoroutineScope(SupervisorJob() + Dispatchers.Default.limitedParallelism(1))
        val fixture = assets.open("harness/stem-cells-computer.json").bufferedReader().use { it.readText() }
        val computers = HashMap<String, DirectTransport>()
        getSharedPreferences("stem-cells", MODE_PRIVATE).edit().putBoolean("stemCells.direct", true).apply()
        return AppGraph(this, "android/shell-harness", "http://127.0.0.1:1", directTransport = { credentials ->
            check(credentials.address.startsWith("shell-fixture-")) { "The shell harness refuses real computers" }
            val computer = synchronized(computers) {
                computers.getOrPut(credentials.address) {
                    val namePreferences = getSharedPreferences("shell-computer-names", MODE_PRIVATE)
                    val name = namePreferences.getString(credentials.address, "Fixture computer ${credentials.address.removePrefix("shell-fixture-")}")!!
                    val namedFixture = fixture.replace("\"name\": \"Studio Mac\"", "\"name\": ${kotlinx.serialization.json.JsonPrimitive(name)}")
                    val stories = if (credentials.address.startsWith("shell-fixture-stories-")) {
                        StoriesHarnessTransport(System.currentTimeMillis()).also {
                            it.drawFrames(StoriesHarnessActivity.frames())
                            it.drawStickers(StoriesHarnessActivity.stickers())
                        }
                    } else null
                    val computerFixture = if (stories == null) namedFixture else {
                        val root = kotlinx.serialization.json.Json.parseToJsonElement(namedFixture) as kotlinx.serialization.json.JsonObject
                        val settings = root["settings"] as kotlinx.serialization.json.JsonObject
                        kotlinx.serialization.json.JsonObject(root + ("settings" to kotlinx.serialization.json.JsonObject(settings +
                            (com.slopus.happy.stories.STORIES_SETTING_KEY to kotlinx.serialization.json.JsonPrimitive(true))))).toString()
                    }
                    val computer = StemCellsHarnessTransport(computerFixture, fixtureScope,
                        fallback = stories,
                        drawMedia = { color, label, width, height ->
                            val bitmap = android.graphics.Bitmap.createBitmap(width.coerceAtLeast(1), height.coerceAtLeast(1), android.graphics.Bitmap.Config.ARGB_8888)
                            android.graphics.Canvas(bitmap).apply {
                                drawColor(color)
                                drawText(label, width / 2f, height / 2f, android.graphics.Paint(3).apply {
                                    this.color = android.graphics.Color.WHITE; textSize = minOf(width, height) / 8f; textAlign = android.graphics.Paint.Align.CENTER
                                })
                            }
                            java.io.ByteArrayOutputStream().use { out ->
                                bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out); bitmap.recycle(); out.toByteArray()
                            }
                        },
                        asset = { path -> runCatching { assets.open(path).use { it.readBytes() } }.getOrNull() })
                    // Identity refreshes open and close handles independently of the shared fake computer.
                    object : DirectTransport by computer {
                        override suspend fun close() {}
                        private fun offline() = getSharedPreferences("shell-transport-state", MODE_PRIVATE).getBoolean(credentials.address, false)
                        override suspend fun stream(path: String, credential: String, headers: Map<String, String>): DirectHttpStream {
                            if (offline()) return DirectHttpStream(503, body = kotlinx.coroutines.flow.emptyFlow())
                            return computer.stream(path, credential, headers)
                        }
                        override suspend fun request(method: String, path: String, credential: String, body: ByteArray?, headers: Map<String, String>): DirectHttpResponse {
                            if (offline()) return DirectHttpResponse(503)
                            val response = computer.request(method, path, credential, body, headers)
                            if (method == "PATCH" && path == "/v0/config" && response.status == 200 && body != null) {
                                val patch = DirectAgentClient.parse(body) as kotlinx.serialization.json.JsonObject
                                val node = patch["node"] as? kotlinx.serialization.json.JsonObject
                                (node?.get("name") as? kotlinx.serialization.json.JsonPrimitive)?.content?.let { namePreferences.edit().putString(credentials.address, it).apply() }
                            }
                            return response
                        }
                    }
                }
            }
            ShellPairingTransport(computer, credentials.address, credentials.port)
        })
    }
}

/** Seeds only this isolated APK, then opens the actual MainActivity/Coordinator/Settings host. */
class ShellHarnessActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!packageName.endsWith(".shellharness")) { finish(); return }
        val graph = (application as HappyApplication).graph
        lifecycleScope.launch {
            graph.shellAccount.filterNotNull().first()
            // Fault injection only for this APK's synthetic computers. Restart the
            // fixture app afterward to test cold offline restoration, not a live link.
            intent.getStringExtra("offlineComputer")?.let { address ->
                check(address.startsWith("shell-fixture-"))
                getSharedPreferences("shell-transport-state", MODE_PRIVATE).edit()
                    .putBoolean(address, intent.getBooleanExtra("offline", true)).commit()
                startActivity(Intent(this@ShellHarnessActivity, MainActivity::class.java))
                finish()
                return@launch
            }
            val preferences = getSharedPreferences("shell-harness", MODE_PRIVATE)
            if (!preferences.getBoolean("seeded", false)) {
                graph.auth.signIn("shell-fixture-token", ByteArray(32) { 42 })
                val id = (graph.auth.state.value as AuthState.SignedIn).encryption.anonId
                withContext(Dispatchers.IO) { graph.auth.updateDisplay(id, AccountDisplay("Ada Fixture", "ada")) }
                for (number in 1..2) {
                    val computer = graph.direct.add(DirectDeviceCredentials("shell-fixture-$number", 1234, "fixture-$number", CanonicalBase64Url.encode(ByteArray(32) { number.toByte() })))
                    graph.direct.rename(computer.id, "Fixture computer $number")
                }
                preferences.edit().putBoolean("seeded", true).apply()
            }
            startActivity(Intent(this@ShellHarnessActivity, MainActivity::class.java))
            finish()
        }
    }
}