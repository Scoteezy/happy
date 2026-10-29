package com.slopus.happy.harness

import com.slopus.happy.config.ThemePreference
import com.slopus.happy.direct.DirectTransport
import com.slopus.happy.ui.nav.Screen
import com.slopus.happy.ui.stemcells.StemCellsSession
import com.slopus.happy.ui.stemcells.StemCellsSettingsHost
import com.slopus.happy.ui.theme.Theme
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Stem Cells settings over the harness's one computer: renaming goes to
 * the fake computer (`PATCH /v0/config`), as the app's accounts do; every
 * flow that leaves settings is written to [asked] instead, for the probe.
 */
class StemCellsHarnessSettingsHost(
    private val transport: DirectTransport,
    private val session: StemCellsSession,
    private val account: String,
    private var name: String,
    /** Every view takes the new theme's colours, as the app's coordinator does. */
    private val themed: () -> Unit,
) : StemCellsSettingsHost {
    private val watchers = ArrayList<() -> Unit>()
    /** The last flow asked for, e.g. `scan` or `open:About`. */
    var asked = "none"
        private set
    private var developer = false

    override val profiles: List<StemCellsSettingsHost.Profile>
        get() = listOfNotNull(
            StemCellsSettingsHost.Profile(account, name, "Connected", isDirect = true, isActive = active == account),
            StemCellsSettingsHost.Profile(HAPPY, "Ada Lovelace", "@ada", isDirect = false, isActive = active == HAPPY).takeIf { withAccount },
        )

    override fun watch(changed: () -> Unit): () -> Unit {
        watchers += changed
        return { watchers -= changed }
    }

    override fun session(id: String) = if (id == account) session else null
    override fun connections(id: String) = StemCellsSettingsHost.Connections()
    private var active = account
    /** A Happy account after the computer, to swipe to (the harness's `account` extra). */
    var withAccount = false

    override suspend fun switchTo(id: String) {
        asked = "switch:$id"
        active = id
        watchers.toList().forEach { it() }
    }

    override suspend fun rename(id: String, name: String) {
        val body = JsonObject(mapOf("node" to JsonObject(mapOf("name" to JsonPrimitive(name))))).toString().toByteArray()
        transport.request("PATCH", "/v0/config", "harness", body, mapOf("content-type" to "application/json"))
        this.name = name
        watchers.toList().forEach { it() }
    }

    override suspend fun remove(id: String) { asked = "remove:$id" }
    override fun addComputer(from: Screen) { asked = "addComputer" }
    override fun addHappyAccount(from: Screen) { asked = "addHappyAccount" }
    override fun open(page: StemCellsSettingsHost.Page, id: String, from: Screen) { asked = "open:${page.name}" }
    override fun connect(connection: StemCellsSettingsHost.Connection, connected: Boolean, id: String, from: Screen) { asked = "connect:${connection.name}" }
    override fun scan(from: Screen) { asked = "scan" }
    override fun enterLink(from: Screen) { asked = "enterLink" }
    override var directConnection = false

    override var theme = ThemePreference.Adaptive
        set(value) {
            field = value
            Theme.setPreference(value)
            themed()
        }

    override val version = "harness / development"

    private companion object {
        const val HAPPY = "happy-harness"
    }

    override fun toggleDeveloperMode(): Boolean {
        developer = !developer
        return developer
    }
}
