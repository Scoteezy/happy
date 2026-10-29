package com.slopus.happy.harness

import android.content.Context
import com.slopus.happy.AppGraph
import com.slopus.happy.auth.AuthState
import com.slopus.happy.data.MachineRecord
import com.slopus.happy.data.SessionRecord
import com.slopus.happy.model.SessionListGrouping
import com.slopus.happy.sync.SyncEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*

/** Fiction stored in the isolated relay account; no server or real credentials. */
object RelayTasksShellFixture {
    suspend fun seed(context: Context, graph: AppGraph) {
        check(context.packageName.endsWith(".shellharness"))
        val signedIn = graph.auth.state.value as AuthState.SignedIn
        check(signedIn.credentials.token == "shell-fixture-token")
        graph.switchAccount(signedIn.encryption.anonId)
        withContext(Dispatchers.IO) {
            val root = Json.parseToJsonElement(context.assets.open("harness/relay-task-home.json").bufferedReader().use { it.readText() }).jsonObject
            val now = System.currentTimeMillis()
            graph.store.upsertMachines(listOf(MachineRecord("fixture-machine", 0, now, now, true, now,
                root.getValue("machine").toString(), 2, null, 0, "")))
            graph.store.upsertSessions(root.getValue("sessions").jsonArray.map { value ->
                val item = value.jsonObject
                val created = now - (10 - item.getValue("created").jsonPrimitive.long) * 60_000
                SessionRecord(item.getValue("id").jsonPrimitive.content, 0, created, created, true, now,
                    item.getValue("metadata").toString(), 3, null, 0, "", null)
            })
            graph.store.setCursor(SyncEngine.CURSOR_SESSIONS_LISTED_AT, now)
        }
        graph.settings.change { it.copy(sessionListGrouping = SessionListGrouping.Project) }
    }
}