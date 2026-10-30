package com.slopus.happy.harness

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import com.slopus.happy.AppGraph
import com.slopus.happy.MainActivity
import com.slopus.happy.auth.AuthCredentials
import com.slopus.happy.auth.AuthState
import com.slopus.happy.auth.ExpoSecureStore
import com.slopus.happy.auth.SecureStoreCredentialStore
import com.slopus.happy.crypto.Base64Variant
import com.slopus.happy.crypto.encodeBase64
import com.slopus.happy.home.HomeServices
import com.slopus.happy.home.BotFacePainter
import com.slopus.happy.model.StartPhase
import com.slopus.happy.model.SessionListGrouping
import com.slopus.happy.model.SessionMetadata
import com.slopus.happy.model.task
import com.slopus.happy.testing.SoftwareKeys
import com.slopus.happy.testing.TaskRelayScenario
import com.slopus.happy.testing.FakeHappyServer
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import java.io.File

/** Only the isolated APK can instantiate this; no production graph/transport hooks. */
class TaskRelayRuntime(private val context: Context, botFaces: Boolean = false, failFirstAvatar: Boolean = false, dockPictures: Boolean = false, importServer: Boolean = false, messageReading: Boolean = false) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default.limitedParallelism(1))
    private val scenario: TaskRelayScenario
    private val unrelatedServer: FakeHappyServer?
    private val closed = java.util.concurrent.atomic.AtomicBoolean(false)
    val graph: AppGraph
    @Volatile private var expectedFacePng: ByteArray? = null

    init {
        check(context.packageName == "com.slopus.happy.dev.shellharness")
        val fixture = Json.parseToJsonElement(context.assets.open("harness/relay-task-home.json").bufferedReader().use { it.readText() }).jsonObject
        // MockWebServer's bind is explicitly 127.0.0.1:0 and completes before
        // constructing the unchanged real transport/account graph.
        scenario = runBlocking(Dispatchers.IO) { TaskRelayScenario(fixture.getValue("machine").jsonObject, botFaces, failFirstAvatar, pictures = dockPictures).start() }
        if (messageReading) runBlocking(Dispatchers.IO) { MessageReadingCapture.seed(scenario.server) }
        unrelatedServer = if (importServer) runBlocking(Dispatchers.IO) {
            FakeHappyServer(secret = ByteArray(32) { 31 }, accountId = "unrelated-import-account").start()
        } else null
        val keys = SoftwareKeys()
        val importedFile = File(context.filesDir, "mmkv/server-config")
        val importedBytes = "synthetic-torn-import".toByteArray()
        graph = try {
            if (importServer) runBlocking(Dispatchers.IO) {
                // Only this process's fresh isolated namespace and software keys.
                SecureStoreCredentialStore(ExpoSecureStore(context.getSharedPreferences(ExpoSecureStore.PREFS_NAME, Context.MODE_PRIVATE), keys))
                    .write(AuthCredentials(scenario.server.token, encodeBase64(scenario.server.secret, Base64Variant.Url)))
                importedFile.parentFile!!.mkdirs()
                importedFile.writeBytes(importedBytes)
            }
            AppGraph(context, "android/task-relay-fixture", unrelatedServer?.url ?: scenario.server.url,
                secureStoreKeys = keys, databaseName = null,
                directTransport = { error("Task relay fixture has no Direct computer") }).also { made ->
                runBlocking(Dispatchers.IO) {
                    made.auth.restoreStored()
                    if (importServer) {
                        check(made.auth.state.value is AuthState.PendingServer)
                        check(made.serverConfig.url.value == unrelatedServer!!.url)
                    } else {
                        check(made.auth.registry.value.entries.isEmpty()) { "Fixture namespace must be fresh" }
                        check(made.serverConfig.url.value == scenario.server.url)
                        made.auth.signIn(scenario.server.token, scenario.server.secret)
                    }
                }
            }
        } catch (failure: Throwable) {
            scope.cancel()
            runBlocking(Dispatchers.IO) { scenario.close() }
            unrelatedServer?.close()
            throw failure
        }
        if (importServer) scope.launch(Dispatchers.IO) {
            val evidence = File(context.filesDir, "import-server-evidence.json")
            while (isActive) {
                val report = buildJsonObject {
                    put("loopbackOnly", true)
                    put("confirmationServer", scenario.server.url)
                    put("pending", graph.auth.state.value is AuthState.PendingServer)
                    put("signedIn", graph.auth.state.value is AuthState.SignedIn)
                    put("serverConfirmed", graph.auth.registry.value.active?.serverConfirmed == true)
                    put("selectedHostPersisted", graph.auth.registry.value.active?.let { it.serverConfirmed && it.serverUrl == scenario.server.url } == true)
                    put("selectedRequests", scenario.server.requests.size)
                    put("selectedAuthenticatedRequests", scenario.server.requests.count { it.headers["Authorization"] != null })
                    put("selectedSocketAuthentications", scenario.server.socketAuths.size)
                    put("selectedAuthProbes", scenario.server.requestsTo("/v1/auth").size)
                    put("unrelatedRequests", unrelatedServer!!.requests.size)
                    put("unrelatedSocketAuthentications", unrelatedServer.socketAuths.size)
                    put("sourceUnchanged", importedFile.readBytes().contentEquals(importedBytes))
                }
                val temporary = File(context.filesDir, "import-server-evidence.tmp")
                temporary.writeText(report.toString())
                check(temporary.renameTo(evidence))
                delay(100)
            }
        }
        scope.launch {
            while (isActive) {
                delay(scenario.server.pingIntervalMs)
                scenario.server.heartbeat()
            }
        }
        scope.launch {
            graph.account.filterNotNull().first()
            graph.settings.change { it.copy(sessionListGrouping = SessionListGrouping.Project) }
            val home = HomeServices.of(graph)
            val composer = home.newSession(context)
            if (botFaces) launch {
                composer.starter.phase.collect { phase ->
                    if (phase == StartPhase.Avatar) {
                        val selected = composer.bot.value.faceSeed
                        expectedFacePng = BotFacePainter.of(context).png(selected)
                    }
                }
            }
            val evidence = File(context.filesDir, "task-relay-evidence.json")
            while (isActive) {
                val protocol = scenario.evidence()
                val rows = scenario.created.map { id ->
                    val stored = graph.store.session(id)
                    buildJsonObject {
                        put("sessionId", id)
                        put("storedTask", stored?.let { SessionMetadata.parse(it.metadataJson)?.task != null } == true)
                        put("homeTask", home.session(id)?.metadata?.task != null)
                        put("storedBot", stored?.let { SessionMetadata.parse(it.metadataJson)?.bot != null } == true)
                        put("homeBot", home.session(id)?.metadata?.bot != null)
                        put("homeAvatarLoaded", home.session(id)?.avatar != null)
                    }
                }
                val report = JsonObject(protocol + mapOf(
                    "rows" to JsonArray(rows),
                    "draftEmpty" to JsonPrimitive(composer.draft.value.input.isEmpty()),
                    "taskModeReset" to JsonPrimitive(!composer.createsTask.value),
                    "botModeReset" to JsonPrimitive(!composer.bot.value.creating),
                    "botNameReset" to JsonPrimitive(composer.bot.value.name.isEmpty()),
                    "selectedFaceUploaded" to JsonPrimitive(expectedFacePng?.let { expected -> scenario.avatarPngs.lastOrNull()?.contentEquals(expected) } == true),
                    "startPhase" to JsonPrimitive(composer.starter.phase.value?.name),
                    "socketConnections" to JsonPrimitive(scenario.server.socketAuths.size),
                    "rpcAckSent" to JsonArray(scenario.server.rpcAckSent.map(::JsonPrimitive)),
                    "socketFailures" to JsonArray(scenario.server.socketFailures.map(::JsonPrimitive)),
                    "accountBoundToCurrentLoopback" to JsonPrimitive(graph.auth.registry.value.active?.serverUrl == scenario.server.url),
                ))
                val temporary = File(context.filesDir, "task-relay-evidence.tmp")
                temporary.writeText(report.toString())
                check(temporary.renameTo(evidence))
                delay(250)
            }
        }
    }

    suspend fun close() {
        if (!closed.compareAndSet(false, true)) return
        suspend fun stage(name: String) = withContext(Dispatchers.IO + NonCancellable) {
            android.util.Log.i("TaskRelayFixture", "close:$name")
            File(context.filesDir, "task-relay-closing.json").writeText(buildJsonObject { put("stage", name) }.toString())
        }
        stage("stopping-fixture-scope")
        scope.coroutineContext[Job]?.cancelAndJoin()
        try {
            stage("logging-out-fixture")
            graph.setForeground(false)
            graph.logout().join()
        } finally {
            stage("closing-loopback-server")
            withContext(Dispatchers.IO + NonCancellable) { scenario.close() }
            withContext(Dispatchers.IO + NonCancellable) { unrelatedServer?.close() }
        }
        stage("closed")
        withContext(Dispatchers.IO) {
            File(context.filesDir, "task-relay-closed.json").writeText(buildJsonObject {
                put("relayClosed", true)
                put("fixtureScopeStopped", !scope.isActive)
                put("accountStopped", graph.account.value == null)
            }.toString())
        }
    }
}

/** Launch this after force-stop. MainActivity thereafter restores only a NEW fixture. */
class TaskRelayHarnessActivity : ComponentActivity() {
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        android.util.Log.i("TaskRelayFixture", "newIntent shutdown=${intent.getBooleanExtra("shutdown", false)}")
        // adb may deliver to an existing top instance instead of recreating it.
        if (intent.getBooleanExtra("shutdown", false)) {
            lifecycleScope.launch { (application as ShellHarnessApplication).closeTaskRelay(); finishAndRemoveTask() }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        android.util.Log.i("TaskRelayFixture", "create shutdown=${intent.getBooleanExtra("shutdown", false)}")
        check(packageName == "com.slopus.happy.dev.shellharness")
        val app = application as ShellHarnessApplication
        if (intent.getBooleanExtra("shutdown", false)) {
            lifecycleScope.launch { app.closeTaskRelay(); finishAndRemoveTask() }
            return
        }
        val importServer = intent.getBooleanExtra("importServer", false)
        app.enableTaskRelay(intent.getBooleanExtra("botFaces", false), intent.getBooleanExtra("failFirstAvatar", false),
            intent.getBooleanExtra("dockPictures", false), importServer, intent.getBooleanExtra("messageReading", false))
        val graph = app.graph
        lifecycleScope.launch {
            withTimeout(30_000) {
                if (importServer) graph.shellAccount.first { it?.relay is AuthState.PendingServer }
                else graph.account.filterNotNull().first()
            }
            startActivity(Intent(this@TaskRelayHarnessActivity, MainActivity::class.java))
            finish()
        }
    }
}