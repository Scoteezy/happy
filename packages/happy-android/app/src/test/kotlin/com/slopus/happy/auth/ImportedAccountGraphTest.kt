package com.slopus.happy.auth

import android.app.AlertDialog
import android.content.Context
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.test.core.app.ApplicationProvider
import com.slopus.happy.AppGraph
import com.slopus.happy.R
import com.slopus.happy.attachments.AttachmentServices
import com.slopus.happy.config.ServerConfig
import com.slopus.happy.crypto.Base64Variant
import com.slopus.happy.crypto.encodeBase64
import com.slopus.happy.data.LocalCaches
import com.slopus.happy.push.*
import com.slopus.happy.testing.FakeHappyServer
import com.slopus.happy.testing.SoftwareKeys
import com.slopus.happy.ui.AppCoordinator
import com.slopus.happy.ui.home.HomeScreen
import com.slopus.happy.ui.server.AccountServerConfirmationScreen
import com.slopus.happy.ui.server.ServerFormView
import com.slopus.happy.voice.MicrophoneAccess
import com.slopus.happy.voice.MicrophonePermission
import com.slopus.happy.voice.VoiceStart
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.*
import okhttp3.OkHttpClient
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.RecordedRequest
import kotlinx.serialization.json.jsonPrimitive
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowAlertDialog
import java.io.File

/** Real graph, coordinator, form and transports. Every possible endpoint is a recording loopback. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class ImportedAccountGraphTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()

    private class Fixture(val context: Context) {
        val selected = FakeHappyServer().start()
        val unrelated = FakeHappyServer(secret = ByteArray(32) { 31 }, accountId = "unrelated-import-account").start()
        val keys = SoftwareKeys()
        val id = selected.encryption.anonId
        val mmkv = File(context.filesDir, "mmkv")
        val bytes = MMKVFixtures.file(MMKVFixtures.region("custom-server-url" to selected.url)).also {
            it.second[0] = (it.second[0].toInt() xor 1).toByte() // Valid-looking URL, torn checksum.
        }
        val graphs = mutableListOf<AppGraph>()
        init {
            selected.addSession("imported-session", updatedAt = 100)
            val secure = ExpoSecureStore(context.getSharedPreferences(ExpoSecureStore.PREFS_NAME, Context.MODE_PRIVATE), keys)
            SecureStoreCredentialStore(secure).write(AuthCredentials(selected.token, encodeBase64(selected.secret, Base64Variant.Url)))
            MMKVFixtures.write(mmkv, bytes)
            ServerConfig(context.getSharedPreferences(ServerConfig.PREFS_NAME, Context.MODE_PRIVATE), unrelated.url).set(unrelated.url)
        }
        fun graph() = AppGraph(context, "android/import-graph-fixture", unrelated.url, keys, databaseName = null,
            directTransport = { error("This fixture never opens a computer") }).also { graphs += it }
        fun startWithOtherKnownAccount() {
            val credentials = SecureStoreCredentialStore(ExpoSecureStore(context.getSharedPreferences(ExpoSecureStore.PREFS_NAME, Context.MODE_PRIVATE), keys))
            credentials.write(unrelated.encryption.anonId, AuthCredentials(unrelated.token, encodeBase64(unrelated.secret, Base64Variant.Url)))
            AccountRegistryFile(LocalCaches.registryFile(context)).save(AccountRegistry(listOf(
                AccountRegistryEntry(unrelated.encryption.anonId, unrelated.url, CredentialLocation.Own, 0),
                AccountRegistryEntry(id, RNServerConfigImporter.DEFAULT_SERVER_URL, CredentialLocation.Shared, 1, serverConfirmed = false),
            ), unrelated.encryption.anonId))
            assertTrue("Fixture identities must be distinct", selected.token != unrelated.token)
        }
        fun assertOrigins() {
            for (server in listOf(selected, unrelated)) {
                assertTrue("A credential-bearing request crossed account hosts",
                    server.requests.all { it.headers["Authorization"] == null || it.headers["Authorization"] == "Bearer ${server.token}" })
                assertTrue("Socket authentication crossed account hosts", server.socketAuths.all { it["token"]?.jsonPrimitive?.content == server.token })
            }
        }
        fun unchanged() {
            assertArrayEquals(bytes.first, File(mmkv, "server-config").readBytes())
            assertArrayEquals(bytes.second, File(mmkv, "server-config.crc").readBytes())
        }
        fun noRequests() {
            // Print counts only, never recorded headers, auth payloads or synthetic tokens.
            assertEquals("selected request count", 0, selected.requests.size)
            assertEquals("unrelated request count", 0, unrelated.requests.size)
            assertEquals(0, selected.socketAuths.size)
            assertEquals(0, unrelated.socketAuths.size)
        }
        suspend fun close() {
            graphs.forEach { stop(it) }
            selected.close()
            unrelated.close()
        }
        companion object {
            suspend fun stop(graph: AppGraph) {
                val owner = AppGraph::class.java.getDeclaredField("scope").apply { isAccessible = true }.get(graph) as CoroutineScope
                if (!owner.isActive) return
                graph.account.value?.stop()
                for (name in listOf("scope", "directScope")) {
                    val field = AppGraph::class.java.getDeclaredField(name).apply { isAccessible = true }
                    (field.get(graph) as CoroutineScope).coroutineContext[Job]?.cancelAndJoin()
                }
                val databases = AppGraph::class.java.getDeclaredField("databases").apply { isAccessible = true }.get(graph) as Map<*, *>
                databases.values.forEach { (it as android.database.sqlite.SQLiteOpenHelper).close() }
            }
        }
    }

    private suspend fun shell(graph: AppGraph, predicate: (AppGraph.ShellAccount) -> Boolean): AppGraph.ShellAccount =
        withContext(Dispatchers.IO) { withTimeout(10_000) { graph.shellAccount.first { it != null && predicate(it) }!! } }

    private suspend fun <T> awaitReal(value: Deferred<T>): T = withContext(Dispatchers.IO) { withTimeout(10_000) { value.await() } }

    private fun json(body: String, code: Int = 200) = MockResponse.Builder().code(code)
        .addHeader("Content-Type", "application/json").body(body).build()

    private fun children(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) repeat(view.childCount) { yieldAll(children(view.getChildAt(it))) }
    }

    private fun answer(button: Int) {
        val dialog = ShadowAlertDialog.getLatestAlertDialog()
        assertTrue(dialog.isShowing)
        dialog.getButton(button).performClick()
        shadowOf(Looper.getMainLooper()).idle() // Android AlertDialog posts its listener/dismissal to a Handler.
        assertFalse(dialog.isShowing)
    }

    @Test fun realPendingFormAndIndirectConsumersSendNothingUntilDurableConfirmation() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val fixture = Fixture(context)
        val activityController = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        val activity = activityController.get()
        try {
            val graph = fixture.graph()
            shell(graph) { it.relay == AuthState.PendingServer(fixture.id) }
            val coordinator = AppCoordinator(activity, graph)
            activity.setContentView(coordinator.root)
            coordinator.start()
            runCurrent()
            assertTrue(coordinator.root.stack.top is AccountServerConfirmationScreen)
            val form = children(coordinator.root.stack.top!!.view).filterIsInstance<ServerFormView>().single()
            assertEquals("", form.field.string)
            assertFalse(form.isCustomServer)
            assertNull(graph.account.value)
            assertNull(graph.store.profile())
            assertNull(AttachmentServices.of(graph).transfers)
            assertNull(graph.accountHoldingSession("imported-session"))
            assertTrue(runCatching { graph.connect.gitHubOAuthUrl() }.isFailure)
            assertNull(graph.voice.usage())
            assertEquals(VoiceStart.NotSignedIn, graph.voice.start("imported-session", object : MicrophonePermission {
                override suspend fun request() = MicrophoneAccess(true, false)
                override fun openSettings() = error("No device settings in this fixture")
            }))
            graph.purchases.refresh()
            assertFalse(graph.purchases.hasPro())
            assertFalse(graph.purchases.presentPaywall().success)

            var deviceTokenReads = 0
            val pushStore = RegisteredPushTokenStore(context.getSharedPreferences("import-push-fixture", Context.MODE_PRIVATE))
            val registrar = PushRegistrar(
                deviceTokens = { deviceTokenReads++; "synthetic-fcm" },
                expo = ExpoPushTokenClient(OkHttpClient(), url = fixture.unrelated.url + "/synthetic-expo"),
                store = pushStore,
                installationId = { "synthetic-installation" }, applicationId = "synthetic-import-test",
            )
            val push = AppPushRegistration(PushPromptTest.FakePermission(PushPromptTest.GRANTED),
                supported = { true }, registrar = registrar, store = pushStore,
                account = graph::currentRequests)
            assertFalse(push.syncCurrentToken().registered)
            assertTrue(runCatching { push.removeToken("synthetic-push") }.isFailure)
            assertEquals(0, deviceTokenReads)
            graph.setForeground(true)
            graph.requestSync()
            runCurrent()
            fixture.noRequests()

            form.save.performClick(); runCurrent()
            assertEquals(context.getString(R.string.server_enter_server_url), form.error)
            form.field.setText("not a host")
            form.save.performClick(); runCurrent()
            assertEquals(context.getString(R.string.errors_invalid_format), form.error)
            form.field.setText(" ${fixture.selected.url}/// ")
            form.save.performClick(); runCurrent()
            answer(AlertDialog.BUTTON_NEGATIVE); runCurrent()
            assertEquals(AuthState.PendingServer(fixture.id), graph.auth.state.value)
            fixture.noRequests()

            // Fail the real AtomicFile writer, not a mocked repository method.
            val blocked = File(LocalCaches.registryFile(context).path + ".new")
            assertTrue(blocked.mkdir())
            form.save.performClick(); runCurrent()
            answer(AlertDialog.BUTTON_POSITIVE); runCurrent()
            withContext(Dispatchers.IO) {
                withTimeout(5000) { while (form.error == null) delay(10) }
            }
            assertEquals(context.getString(R.string.server_failed_to_connect_to_server), form.error)
            assertEquals(AuthState.PendingServer(fixture.id), graph.auth.state.value)
            assertFalse(AccountRegistryFile(LocalCaches.registryFile(context)).load()!!.active!!.serverConfirmed)
            fixture.noRequests()
            fixture.unchanged()
            // AtomicFile.readFully may already discard its stale .new path.
            if (blocked.exists()) assertTrue(blocked.delete())
            assertFalse(blocked.exists())

            form.save.performClick(); runCurrent()
            answer(AlertDialog.BUTTON_POSITIVE); runCurrent()
            shell(graph) { it.relay is AuthState.SignedIn }
            runCurrent()
            assertTrue(coordinator.root.stack.all.first() is HomeScreen)
            assertEquals(fixture.selected.url, AccountRegistryFile(LocalCaches.registryFile(context)).load()!!.active!!.serverUrl)
            graph.account.value!!.syncNow()
            push.removeToken("synthetic-push")
            assertNotNull(graph.store.profile())
            assertTrue(fixture.selected.requestsTo("/v1/account/profile").isNotEmpty())
            assertEquals(1, fixture.selected.requestsTo("/v1/push-tokens/synthetic-push").size)
            assertTrue(fixture.selected.requests.filter { it.headers["Authorization"] != null }
                .all { it.headers["Authorization"] == "Bearer ${fixture.selected.token}" })
            assertEquals(0, fixture.unrelated.requests.size)
            assertEquals(0, fixture.selected.requestsTo("/v1/auth").size)
            fixture.unchanged()
            println("import graph: before-confirmation requests=0; selected-profile=${fixture.selected.requestsTo("/v1/account/profile").size}; selected-push-delete=1; unrelated=0; auth-probe=0")
        } finally {
            activityController.pause().stop().destroy()
            fixture.close()
            Dispatchers.resetMain()
        }
    }

    @Test fun malformedRegistryReimportsOfflineAndPendingRestartDoesNotTrustChangedRnFile() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val fixture = Fixture(context)
        try {
            val registryFile = LocalCaches.registryFile(context)
            registryFile.parentFile!!.mkdirs()
            registryFile.writeText("""{"entries":[{"id":"${fixture.id}","serverUrl":"${fixture.unrelated.url}","location":"shared","addedOrder":0,"serverConfirmed":null}],"activeId":"${fixture.id}"}""")
            assertNull(AccountRegistryFile(registryFile).load())
            val first = fixture.graph()
            shell(first) { it.relay == AuthState.PendingServer(fixture.id) }
            fixture.noRequests()
            Fixture.stop(first)
            // A new valid-looking RN choice cannot override a persisted pending decision.
            MMKVFixtures.write(fixture.mmkv, MMKVFixtures.file(MMKVFixtures.region("custom-server-url" to fixture.unrelated.url)))
            val second = fixture.graph()
            shell(second) { it.relay == AuthState.PendingServer(fixture.id) }
            fixture.noRequests()
            second.auth.confirmServer(fixture.id, fixture.selected.url)
            shell(second) { it.relay is AuthState.SignedIn }
            second.account.value!!.syncNow()
            Fixture.stop(second)
            val third = fixture.graph()
            shell(third) { it.relay is AuthState.SignedIn }
            third.account.value!!.syncNow()
            assertEquals(fixture.selected.url, third.auth.registry.value.active!!.serverUrl)
            assertEquals(0, fixture.unrelated.requests.size)
            assertTrue(fixture.selected.requestsTo("/v1/account/profile").isNotEmpty())
            assertEquals(0, fixture.selected.requestsTo("/v1/auth").size)
        } finally {
            fixture.close()
            Dispatchers.resetMain()
        }
    }

    @Test fun delayedOldSyncKeepsItsHostBeforeRequestCreationAndInsideAwaitedWork() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val fixture = Fixture(context)
        val responseRelease = CountDownLatch(1)
        val beforeUrl = CompletableDeferred<Unit>()
        try {
            fixture.startWithOtherKnownAccount()
            val graph = fixture.graph()
            shell(graph) { (it.relay as? AuthState.SignedIn)?.serverUrl == fixture.unrelated.url }
            val oldSync = graph.account.value!!
            oldSync.syncNow()
            val held = AtomicBoolean(true)
            val arrived = CompletableDeferred<Unit>()
            val original = fixture.unrelated.server.dispatcher
            fixture.unrelated.server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    if (request.url.encodedPath == "/v1/account/profile" && held.compareAndSet(true, false)) {
                        arrived.complete(Unit)
                        check(responseRelease.await(10, TimeUnit.SECONDS))
                    }
                    return original.dispatch(request)
                }
            }
            // These use the REAL old AccountSync.api, outside its cancelled owner job.
            // Cancellation must not conceal a client whose URL is still mutable.
            val insideRequest = async(Dispatchers.IO) { oldSync.api.profile() }
            awaitReal(arrived)
            val beforeRequest = async(Dispatchers.IO) { beforeUrl.await(); oldSync.api.profile() }
            graph.switchAccount(fixture.id)
            assertEquals(AuthState.PendingServer(fixture.id), graph.shellAccount.value!!.relay)
            assertEquals(0, fixture.selected.requests.size)
            graph.auth.confirmServer(fixture.id, fixture.selected.url)
            shell(graph) { (it.relay as? AuthState.SignedIn)?.serverUrl == fixture.selected.url }
            responseRelease.countDown()
            beforeUrl.complete(Unit)
            assertEquals("unrelated-import-account", awaitReal(insideRequest)["id"]!!.jsonPrimitive.content)
            assertEquals("unrelated-import-account", awaitReal(beforeRequest)["id"]!!.jsonPrimitive.content)
            graph.account.value!!.syncNow()
            // Both bindings are now known/confirmed: switching either way must
            // create a client from that account, not the previous global host.
            graph.switchAccount(fixture.unrelated.encryption.anonId)
            assertEquals("unrelated-import-account", graph.account.value!!.api.profile()["id"]!!.jsonPrimitive.content)
            graph.switchAccount(fixture.id)
            assertEquals("account-1", graph.account.value!!.api.profile()["id"]!!.jsonPrimitive.content)
            fixture.assertOrigins()
            println("account routing: old sync before-URL=1 + in-request=1 completed on origin; known switches A/B=pass; crossed-credentials=0")
        } finally {
            responseRelease.countDown(); beforeUrl.complete(Unit)
            fixture.close()
            Dispatchers.resetMain()
        }
    }

    @Test fun delayedPushRegistrationRetryAndInactiveLogoutKeepTheCapturedAccountHost() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val fixture = Fixture(context)
        val deviceEntered = CompletableDeferred<Unit>()
        val deviceRelease = CompletableDeferred<Unit>()
        val expoEntered = CompletableDeferred<Unit>()
        val expoRelease = CountDownLatch(1)
        val retryEntered = CompletableDeferred<Unit>()
        val retryRelease = CompletableDeferred<Unit>()
        try {
            fixture.startWithOtherKnownAccount()
            val graph = fixture.graph()
            shell(graph) { (it.relay as? AuthState.SignedIn)?.serverUrl == fixture.unrelated.url }
            val old = graph.auth.state.value as AuthState.SignedIn
            val requests = graph.accountRequests(old)
            val original = fixture.unrelated.server.dispatcher
            val firstDelete = AtomicBoolean(true)
            fixture.unrelated.server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val path = request.url.encodedPath
                    if (path == "/synthetic-expo") {
                        fixture.unrelated.requests += request
                        expoEntered.complete(Unit)
                        check(expoRelease.await(10, TimeUnit.SECONDS))
                        return json("""{"data":{"expoPushToken":"ExponentPushToken[isolated]"}}""")
                    }
                    if (path == "/v1/push-tokens" && request.method == "POST") {
                        fixture.unrelated.requests += request
                        return json("""{"success":true}""")
                    }
                    if (path == "/v1/push-tokens/old-registration" && firstDelete.compareAndSet(true, false)) {
                        fixture.unrelated.requests += request
                        return json("{}", 503)
                    }
                    return original.dispatch(request)
                }
            }
            val store = RegisteredPushTokenStore(context.getSharedPreferences("delayed-import-push", Context.MODE_PRIVATE))
            val registrar = PushRegistrar(
                deviceTokens = { deviceEntered.complete(Unit); deviceRelease.await(); "synthetic-fcm" },
                expo = ExpoPushTokenClient(OkHttpClient(), url = fixture.unrelated.url + "/synthetic-expo"),
                store = store, installationId = { "synthetic-installation" }, applicationId = "synthetic-import-test",
                retryDelay = { retryEntered.complete(Unit); retryRelease.await() },
            )
            val push = AppPushRegistration(PushPromptTest.FakePermission(PushPromptTest.GRANTED),
                supported = { true }, registrar = registrar, store = store, account = graph::currentRequests)
            val registering = async(Dispatchers.IO) { push.syncCurrentToken() }
            val unregistering = async(Dispatchers.IO) { registrar.remove(requests, "old-registration") }
            awaitReal(deviceEntered); awaitReal(retryEntered)
            graph.switchAccount(fixture.id)
            assertEquals(0, fixture.selected.requests.size)
            graph.auth.confirmServer(fixture.id, fixture.selected.url)
            shell(graph) { (it.relay as? AuthState.SignedIn)?.serverUrl == fixture.selected.url }
            deviceRelease.complete(Unit)
            awaitReal(expoEntered) // Already switched, still awaiting work before authenticated URL construction.
            retryRelease.complete(Unit)
            expoRelease.countDown()
            assertTrue(awaitReal(registering).registered)
            awaitReal(unregistering)
            assertEquals(1, fixture.unrelated.requestsTo("/v1/push-tokens").count { it.method == "POST" })
            assertEquals(2, fixture.unrelated.requestsTo("/v1/push-tokens/old-registration").size)
            assertEquals(0, fixture.selected.requestsTo("/v1/push-tokens").size)
            graph.beforeLogout { account -> registrar.unregisterOnLogout(account) }
            graph.signOut(old.encryption.anonId).join() // Inactive account: active host must not be used.
            assertEquals(1, fixture.unrelated.requestsTo("/v1/push-tokens/ExponentPushToken%5Bisolated%5D").size)
            assertEquals(0, fixture.selected.requestsTo("/v1/push-tokens/ExponentPushToken%5Bisolated%5D").size)
            Fixture.stop(graph)
            val restored = fixture.graph()
            shell(restored) { (it.relay as? AuthState.SignedIn)?.serverUrl == fixture.selected.url }
            restored.account.value!!.syncNow()
            fixture.assertOrigins()
            println("account routing: old push POST=1, old DELETE attempts=2, inactive-logout DELETE=1; crossed-credentials=0; confirmed restart=pass")
        } finally {
            deviceRelease.complete(Unit); retryRelease.complete(Unit); expoRelease.countDown()
            fixture.close()
            Dispatchers.resetMain()
        }
    }
}