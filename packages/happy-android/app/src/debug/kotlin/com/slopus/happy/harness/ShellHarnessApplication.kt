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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Only the dedicated .shellharness APK gets fixtures; normal debug/preview/release never do. */
class ShellHarnessApplication : HappyApplication() {
    private var relayNamespace: String? = null
    private var relay: TaskRelayRuntime? = null
    private var graphCreated = false
    private val relayCloseLock = Mutex()

    fun enableTaskRelay(botFaces: Boolean = false, failFirstAvatar: Boolean = false) {
        check(packageName == "com.slopus.happy.dev.shellharness")
        check(!graphCreated) { "Force-stop the isolated harness before changing fixture modes" }
        super.getSharedPreferences("task-relay-mode", MODE_PRIVATE).edit().putBoolean("enabled", true)
            .putBoolean("botFaces", botFaces).putBoolean("failFirstAvatar", failFirstAvatar).commit()
    }

    fun useOfflineShellFixture() {
        check(packageName == "com.slopus.happy.dev.shellharness")
        if (!super.getSharedPreferences("task-relay-mode", MODE_PRIVATE).getBoolean("enabled", false)) return
        check(!graphCreated) { "Force-stop the isolated harness before changing fixture modes" }
        super.getSharedPreferences("task-relay-mode", MODE_PRIVATE).edit().putBoolean("enabled", false).commit()
    }

    override fun getSharedPreferences(name: String, mode: Int): android.content.SharedPreferences =
        super.getSharedPreferences(relayNamespace?.let { "$it-$name" } ?: name, mode)

    override fun getFilesDir(): java.io.File = relayNamespace?.let {
        java.io.File(super.getFilesDir(), "task-relay/$it").apply { mkdirs() }
    } ?: super.getFilesDir()

    override fun getCacheDir(): java.io.File = relayNamespace?.let {
        java.io.File(super.getCacheDir(), "task-relay/$it").apply { mkdirs() }
    } ?: super.getCacheDir()

    suspend fun closeTaskRelay() = withContext(NonCancellable) {
        android.util.Log.i("TaskRelayFixture", "close requested relayPresent=${relay != null}")
        relayCloseLock.withLock { relay?.close(); relay = null }
    }

    override fun onTerminate() {
        runBlocking { closeTaskRelay() }
        super.onTerminate()
    }

    override fun createGraph(): AppGraph {
        if (!packageName.endsWith(".shellharness")) return super.createGraph()
        graphCreated = true
        if (super.getSharedPreferences("task-relay-mode", MODE_PRIVATE).getBoolean("enabled", false)) {
            // Never read an old registry, credential, custom URL, draft or direct
            // account. This process owns a new disposable namespace and keys.
            relayNamespace = "task-relay-${java.util.UUID.randomUUID()}"
            val mode = super.getSharedPreferences("task-relay-mode", MODE_PRIVATE)
            return TaskRelayRuntime(this, mode.getBoolean("botFaces", false), mode.getBoolean("failFirstAvatar", false)).also { relay = it }.graph
        }
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
                    val computerFixture = when {
                        stories != null -> StoriesShellFixture.apply(namedFixture)
                        credentials.address.startsWith("shell-fixture-proposals-") -> ProposalsShellFixture.apply(namedFixture)
                        else -> namedFixture
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
        (application as ShellHarnessApplication).useOfflineShellFixture()
        if (intent.getBooleanExtra("troubleshootProbe", false)) TroubleshootProbe.install(application)
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
            if (intent.getBooleanExtra("relayTasks", false)) RelayTasksShellFixture.seed(this@ShellHarnessActivity, graph,
                offline = intent.getBooleanExtra("relayTroubleshoot", false), withoutSessions = intent.getBooleanExtra("relayWithoutSessions", false))
            startActivity(Intent(this@ShellHarnessActivity, MainActivity::class.java))
            finish()
        }
    }
}