package com.slopus.happy

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.slopus.happy.direct.*
import com.slopus.happy.config.ThemePreference
import com.slopus.happy.sync.eventually
import com.slopus.happy.testing.SoftwareKeys
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DirectShellTest {
    @Test fun relaySignOutDoesNotClearTheActiveComputersPreferences() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val graph = AppGraph(context, "test", "http://127.0.0.1:1", SoftwareKeys(), databaseName = null,
            directTransport = { object : DirectTransport {
                override suspend fun request(method: String, path: String, credential: String, body: ByteArray?, headers: Map<String, String>) = DirectHttpResponse(503)
            } })
        eventually { graph.shellAccount.value != null }
        graph.auth.signIn("fixture-token", ByteArray(32) { 4 })
        val relayId = graph.auth.registry.value.activeId!!
        val computer = graph.addDirect(DirectDeviceCredentials("fixture", 1234, "device", CanonicalBase64Url.encode(ByteArray(32))))
        graph.selectDirect(computer.id)
        graph.localSettings.setThemePreference(ThemePreference.Dark)
        graph.signOut(relayId).join()
        eventually { graph.auth.registry.value.entries.isEmpty() }
        assertEquals(computer.id, graph.direct.state.value.activeId)
        assertEquals(ThemePreference.Dark, graph.localSettings.themePreference.value)
        graph.removeDirect(computer.id)
    }

    @Test fun computerSelectionPreferencesRemovalAndStartupRestore() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val keys = SoftwareKeys()
        val transport = object : DirectTransport {
            override suspend fun request(method: String, path: String, credential: String, body: ByteArray?, headers: Map<String, String>) =
                DirectHttpResponse(200, emptyMap(), (if (path == "/v0/health") """{"version":{"protocol":27},"ready":true}""" else """{"config":{"node":{"name":"Fixture computer"}}}""").toByteArray())
            override suspend fun stream(path: String, credential: String, headers: Map<String, String>): DirectHttpStream = error("No screen asked for a sync engine")
        }
        fun graph() = AppGraph(context, "test", "https://example.invalid", keys, databaseName = null, directTransport = { transport })
        val graph = graph()
        graph.stemCells.directConnection = true
        eventually { graph.shellAccount.value != null }
        val first = graph.direct.add(DirectDeviceCredentials("fixture-one", 1234, "one", CanonicalBase64Url.encode(ByteArray(32))))
        graph.selectDirect(first.id)
        graph.localSettings.setThemePreference(ThemePreference.Dark)
        val firstDirectory = java.io.File(context.filesDir, "direct/${first.id}").apply { mkdirs() }
        java.io.File(firstDirectory, "fixture-cache").writeText("first computer only")
        assertNull(graph.account.value)
        val second = graph.direct.add(DirectDeviceCredentials("fixture-two", 1234, "two", CanonicalBase64Url.encode(ByteArray(32))))
        graph.selectDirect(second.id)
        assertEquals(ThemePreference.Adaptive, graph.localSettings.themePreference.value)
        graph.selectDirect(first.id)
        assertEquals(ThemePreference.Dark, graph.localSettings.themePreference.value)
        graph.direct.setForeground(false)
        val restored = graph()
        eventually { restored.shellAccount.value?.directId == first.id }
        assertEquals(ThemePreference.Dark, restored.localSettings.themePreference.value)
        restored.removeDirect(first.id)
        assertFalse(firstDirectory.exists())
        eventually { restored.shellAccount.value?.directId == second.id }
        assertEquals(listOf(second.id), restored.direct.state.value.accounts.map { it.id })
        restored.removeDirect(second.id)
        eventually { restored.shellAccount.value?.directId == null }
        assertTrue(restored.direct.state.value.accounts.isEmpty())
    }
}