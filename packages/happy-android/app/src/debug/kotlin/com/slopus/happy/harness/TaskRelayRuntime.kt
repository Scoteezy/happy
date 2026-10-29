package com.slopus.happy.harness

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import com.slopus.happy.AppGraph
import com.slopus.happy.MainActivity
import com.slopus.happy.home.HomeServices
import com.slopus.happy.model.SessionListGrouping
import com.slopus.happy.model.SessionMetadata
import com.slopus.happy.model.task
import com.slopus.happy.testing.SoftwareKeys
import com.slopus.happy.testing.TaskRelayScenario
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import java.io.File

/** Only the isolated APK can instantiate this; no production graph/transport hooks. */
class TaskRelayRuntime(private val context: Context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default.limitedParallelism(1))
    private val scenario: TaskRelayScenario
    private val closed = java.util.concurrent.atomic.AtomicBoolean(false)
    val graph: AppGraph

    init {
        check(context.packageName == "com.slopus.happy.dev.shellharness")
        val fixture = Json.parseToJsonElement(context.assets.open("harness/relay-task-home.json").bufferedReader().use { it.readText() }).jsonObject
        // MockWebServer's bind is explicitly 127.0.0.1:0 and completes before
        // constructing the unchanged real transport/account graph.
        scenario = runBlocking(Dispatchers.IO) { TaskRelayScenario(fixture.getValue("machine").jsonObject).start() }
        graph = try {
            AppGraph(context, "android/task-relay-fixture", scenario.server.url,
                secureStoreKeys = SoftwareKeys(), databaseName = null,
                directTransport = { error("Task relay fixture has no Direct computer") }).also { made ->
                runBlocking(Dispatchers.IO) {
                    check(made.auth.registry.value.entries.isEmpty()) { "Fixture namespace must be fresh" }
                    check(made.serverConfig.url.value == scenario.server.url)
                    made.auth.signIn(scenario.server.token, scenario.server.secret)
                }
            }
        } catch (failure: Throwable) {
            scope.cancel()
            runBlocking(Dispatchers.IO) { scenario.close() }
            throw failure
        }
        scope.launch {
            graph.account.filterNotNull().first()
            graph.settings.change { it.copy(sessionListGrouping = SessionListGrouping.Project) }
            val home = HomeServices.of(graph)
            val composer = home.newSession(context)
            val evidence = File(context.filesDir, "task-relay-evidence.json")
            while (isActive) {
                val protocol = scenario.evidence()
                val rows = scenario.created.map { id ->
                    val stored = graph.store.session(id)
                    buildJsonObject {
                        put("sessionId", id)
                        put("storedTask", stored?.let { SessionMetadata.parse(it.metadataJson)?.task != null } == true)
                        put("homeTask", home.session(id)?.metadata?.task != null)
                    }
                }
                val report = JsonObject(protocol + mapOf(
                    "rows" to JsonArray(rows),
                    "draftEmpty" to JsonPrimitive(composer.draft.value.input.isEmpty()),
                    "taskModeReset" to JsonPrimitive(!composer.createsTask.value),
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
        scope.coroutineContext[Job]?.cancelAndJoin()
        try {
            graph.setForeground(false)
            graph.logout().join()
        } finally {
            withContext(Dispatchers.IO + NonCancellable) { scenario.close() }
        }
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
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        check(packageName == "com.slopus.happy.dev.shellharness")
        val app = application as ShellHarnessApplication
        if (intent.getBooleanExtra("shutdown", false)) {
            lifecycleScope.launch { app.closeTaskRelay(); finishAndRemoveTask() }
            return
        }
        app.enableTaskRelay()
        val graph = app.graph
        lifecycleScope.launch {
            withTimeout(30_000) { graph.account.filterNotNull().first() }
            startActivity(Intent(this@TaskRelayHarnessActivity, MainActivity::class.java))
            finish()
        }
    }
}