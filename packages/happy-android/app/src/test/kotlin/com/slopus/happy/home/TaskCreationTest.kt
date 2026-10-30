package com.slopus.happy.home

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.slopus.happy.attachments.AttachmentPreview
import com.slopus.happy.attachments.AttachmentNotice
import com.slopus.happy.model.*
import com.slopus.happy.model.HomeModelFixtures.machine
import com.slopus.happy.model.HomeModelFixtures.machineMetadata
import com.slopus.happy.sync.*
import com.slopus.happy.ui.home.HomeDockController
import com.slopus.happy.ui.home.HomeDockFocusView
import com.slopus.happy.ui.home.HomeDockView
import com.slopus.happy.ui.composer.AgentInputCardView
import com.slopus.happy.ui.theme.Theme
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowAlertDialog
import java.io.File
import java.util.UUID

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class TaskCreationTest {
    private class Fixture(scope: CoroutineScope, tasks: JsonElement? = JsonPrimitive(true), val context: Context = ApplicationProvider.getApplicationContext<Context>()) {
        val drafts = NewSessionDraftStore(context.getSharedPreferences(UUID.randomUUID().toString(), Context.MODE_PRIVATE))
        var machines = listOf(machine("rig", machineMetadata {
            put("machineKind", "rig")
            put("rigOnly", true)
            putJsonObject("capabilities") {
                put("newSession", true); put("bots", true)
                tasks?.let { put("tasks", it) }
            }
            putJsonArray("models") { addJsonObject {
                put("code", "model"); put("value", "Model"); put("providerId", "codex"); put("id", "model")
                putJsonArray("thinkingLevels") { add("medium") }
            } }
            putJsonArray("operatingModes") { addJsonObject { put("code", "ask"); put("value", "Ask") } }
        }))
        val spawns = mutableListOf<SpawnSessionOptions>()
        val sent = mutableListOf<Pair<String, String>>()
        val sentImages = mutableListOf<List<AttachmentPreview>>()
        val killed = mutableListOf<String>()
        var spawn: suspend () -> SpawnSessionResult = { SpawnSessionResult.Success("session-${spawns.size}") }
        var send: suspend () -> FirstSend = { FirstSend(true) }
        var ready: suspend () -> Boolean = { true }
        var owner: Any = Any()
        var faces = BotFaceWearer { _, _ -> null }
        private var ids = 0
        val actions = object : MachineActions {
            override suspend fun spawnSession(options: SpawnSessionOptions): SpawnSessionResult { spawns += options; return spawn() }
            override suspend fun createWorktree(machineId: String, basePath: String): WorktreeOutcome = error("Task must not create a worktree")
            override suspend fun listWorktrees(machineId: String, basePath: String) = emptyList<GitWorktree>()
            override suspend fun stopSession(machineId: String, sessionId: String): OpResult = error("Task uses killSession")
        }
        val sessions = object : SessionActions {
            override suspend fun kill(sessionId: String): OpResult { killed += sessionId; return OpResult(true) }
            override suspend fun archiveOnServer(sessionId: String): OpResult = error("No Task HTTP fallback")
            override suspend fun delete(sessionId: String): OpResult = error("No Task delete")
        }
        val sender = object : FirstMessageSender {
            override suspend fun pinModes(sessionId: String, modes: Map<AgentModeField, String?>) = error("Rig modes travel in spawn")
            override suspend fun send(sessionId: String, text: String, attachments: List<AttachmentPreview>, isCurrent: () -> Boolean): FirstSend {
                assertTrue(isCurrent()); sent += sessionId to text; sentImages += attachments.toList(); return send()
            }
        }
        val starter = SessionStarter(
            machines = { machines }, sessions = { emptyList() }, settings = { Settings() }, drafts = drafts,
            machineActions = actions, sessionActions = sessions, sender = sender, awaitSession = { ready() },
            scope = scope, requestId = { "request-${++ids}" },
            bindOperation = {
                val started = owner
                SessionStartOperation(actions, sessions, sender, { ready() }, started, faces) { owner === started }
            },
        )
        init {
            drafts.change { NewSessionDraft(selectedMachineId = "rig", agentType = AgentType.Claude, input = "  Fix the redirect\n") }
        }
        suspend fun start(task: Boolean = true, current: () -> Boolean = { true }) =
            starter.start(createsTask = task, isCurrentTarget = current, confirmDirectory = { error("Task has no directory approval") })
    }

    @Test fun offersOnlyAdvertisedKindsInNativeOrder() {
        assertEquals(listOf(HomeDockKind.Session), HomeDockKind.offered(false, false))
        assertEquals(listOf(HomeDockKind.Session, HomeDockKind.Bot), HomeDockKind.offered(true, false))
        assertEquals(listOf(HomeDockKind.Session, HomeDockKind.Task), HomeDockKind.offered(false, true))
        assertEquals(HomeDockKind.entries, HomeDockKind.offered(true, true))
        assertEquals(HomeDockKind.Bot, HomeDockKind.current(true, true))
    }

    @Test fun botFaceFailureOpensBotAndRetryKeepsOriginalSessionAfterDraftReset() = runTest {
        val f = Fixture(this)
        val worn = mutableListOf<Pair<String, String>>()
        f.faces = BotFaceWearer { id, seed ->
            worn += id to seed
            if (worn.size == 1) BotFaceFailure(BotFaceLeg.Uploading, "fixture") else null
        }
        val result = f.starter.start(bot = BotRequest("Fixture Bot", "happy"), confirmDirectory = { false }) as StartResult.Started
        assertEquals(HappyAgentSpawnTarget.Bot("Fixture Bot"), f.spawns.single().happyAgentTarget)
        assertEquals(BotFaceLeg.Uploading, result.faceFailure?.leg)
        assertTrue(f.sent.isEmpty())
        assertEquals("  Fix the redirect\n", f.drafts.draft.value.input)
        f.drafts.change { it.copy(input = "Next task") }
        assertNull(result.faceRetry!!.attempt())
        assertEquals(listOf(result.sessionId to "happy", result.sessionId to "happy"), worn)
        assertEquals(1, f.spawns.size)
        f.owner = Any()
        try { result.faceRetry.attempt(); fail("Retry must stay on original account") } catch (_: CancellationException) {}
        assertEquals(2, worn.size)
    }

    @Test fun stoppingDuringFaceUploadDoesNotWearOrOpenBot() = runTest {
        val f = Fixture(this)
        val painting = CompletableDeferred<Unit>()
        var wore = false
        f.faces = LiveBotFaceWearer(paint = { painting.await(); byteArrayOf(1) }, upload = { _, _ -> "ref" },
            setAvatar = { _, _, _, _ -> wore = true }, isCurrent = { true })
        val started = async { f.starter.start(bot = BotRequest("Fixture Bot", "happy"), confirmDirectory = { false }) }
        runCurrent()
        assertEquals(StartPhase.Avatar, f.starter.phase.value)
        f.starter.cancel()
        painting.complete(Unit)
        assertEquals(StartResult.Abandoned, started.await())
        runCurrent()
        assertFalse(wore)
        assertEquals(listOf("session-1"), f.killed)
    }

    @Test fun tasksRequireStrictAdvertisedCapability() = runTest {
        for (capability in listOf(null, JsonPrimitive(false), JsonPrimitive("true"))) {
            val f = Fixture(this, capability)
            assertEquals(StartResult.Refused(StartRefusal.TasksUnsupported), f.start())
            assertTrue(f.spawns.isEmpty())
        }
    }

    @Test fun unnamedTaskForcesHappyAgentAndSendsFirstMessageWithoutDirectory() = runTest {
        val f = Fixture(this)
        f.drafts.change { it.copy(selectedProjectId = "stale-project", sessionType = SessionType.Worktree, worktreeKey = "missing-workspace") }
        assertEquals(StartResult.Started("session-1"), f.start())
        val spawn = f.spawns.single()
        assertEquals("rig", spawn.agent)
        assertEquals(HappyAgentSpawnTarget.Task(), spawn.happyAgentTarget)
        val payload = MachineOps.spawnRequest(spawn)
        assertEquals(buildJsonObject { put("kind", "task") }, payload["target"])
        assertEquals(setOf("type", "clientRequestId", "target", "agentConfiguration"), payload.keys)
        assertFalse(payload.getValue("agentConfiguration").jsonObject.containsKey("directory"))
        assertEquals(listOf("session-1" to "Fix the redirect"), f.sent)
        assertEquals("", f.drafts.draft.value.input)
    }

    @Test fun retryKeepsRequestDespitePromptEditAndSuccessMakesNextRequestFresh() = runTest {
        val f = Fixture(this)
        f.spawn = { SpawnSessionResult.Error("offline") }
        assertEquals(StartResult.Failed("offline"), f.start())
        f.drafts.change { it.copy(input = "Different first message") }
        f.spawn = { SpawnSessionResult.Success("made") }
        assertTrue(f.start() is StartResult.Started)
        f.drafts.change { it.copy(input = "Next task") }
        assertTrue(f.start() is StartResult.Started)
        assertEquals(listOf("request-1", "request-1", "request-2"), f.spawns.map { it.clientRequestId })
    }

    @Test fun failedFirstSendReusesTaskThenSwitchingKindAbandonsIt() = runTest {
        val f = Fixture(this)
        f.send = { throw IllegalStateException("send refused") }
        assertEquals(StartResult.Failed("send refused"), f.start())
        assertEquals(StartResult.Failed("send refused"), f.start())
        assertEquals(1, f.spawns.size)
        f.send = { FirstSend(true) }
        assertTrue(f.start(task = false) is StartResult.Started)
        assertEquals(listOf("session-1"), f.killed)
        f.drafts.change { it.copy(input = "A fresh task") }
        assertTrue(f.start() is StartResult.Started)
        assertEquals(listOf("request-1", "request-2", "request-3"), f.spawns.map { it.clientRequestId })
    }

    @Test fun stopCleansLateTaskWithoutSendingAndRetryUsesNewRequest() = runTest {
        val f = Fixture(this)
        val response = CompletableDeferred<SpawnSessionResult>()
        f.spawn = { response.await() }
        val started = async { f.start() }
        runCurrent()
        f.starter.cancel()
        runCurrent()
        assertEquals(StartResult.Abandoned, started.await())
        response.complete(SpawnSessionResult.Success("late-task"))
        runCurrent()
        assertEquals(listOf("late-task"), f.killed)
        assertTrue(f.sent.isEmpty())
        f.spawn = { SpawnSessionResult.Success("next") }
        assertTrue(f.start() is StartResult.Started)
        assertEquals(listOf("request-1", "request-2"), f.spawns.map { it.clientRequestId })
    }

    @Test fun changedKindBeforeFirstMessageAbandonsTask() = runTest {
        val f = Fixture(this)
        var current = true
        f.ready = { current = false; true }
        assertEquals(StartResult.Abandoned, f.start(current = { current }))
        runCurrent()
        assertEquals(listOf("session-1"), f.killed)
        assertTrue(f.sent.isEmpty())
        assertEquals("  Fix the redirect\n", f.drafts.draft.value.input)
    }

    @Test fun changedAccountCannotSendAndDoesNotReusePreviousRequest() = runTest {
        val f = Fixture(this)
        f.ready = { f.owner = Any(); true }
        assertEquals(StartResult.Abandoned, f.start())
        runCurrent()
        assertTrue(f.sent.isEmpty())
        f.ready = { true }
        assertTrue(f.start() is StartResult.Started)
        assertEquals(listOf("request-1", "request-2"), f.spawns.map { it.clientRequestId })
    }

    @Test fun taskProgressUsesTaskWordsAndBotKeepsPrecedence() {
        assertEquals(StartProgress.CreatingTask, resolveStartProgress(StartPhase.Spawning, "Claude", false, createsTask = true))
        assertEquals(StartProgress.Opening(task = true), resolveStartProgress(StartPhase.Opening, "Claude", false, createsTask = true))
        assertEquals(StartProgress.CreatingBot, resolveStartProgress(StartPhase.Spawning, "Claude", false, true, true))
    }

    @Test fun immediateRetryAfterStopNeverReusesTheAbandonedRequest() = runTest {
        val f = Fixture(this)
        val response = CompletableDeferred<SpawnSessionResult>()
        f.spawn = { response.await() }
        val first = async { f.start() }
        runCurrent()
        f.starter.cancel()
        f.spawn = { SpawnSessionResult.Success("next-task") }
        // The next press can arrive before the canceled coroutine has unwound.
        val next = async(start = CoroutineStart.UNDISPATCHED) { f.start() }
        runCurrent()
        assertEquals(StartResult.Abandoned, first.await())
        assertTrue(next.await() is StartResult.Started)
        response.complete(SpawnSessionResult.Success("old-task"))
        runCurrent()
        assertEquals(listOf("request-1", "request-2"), f.spawns.map { it.clientRequestId })
        assertEquals(listOf("old-task"), f.killed)
        assertEquals(listOf("next-task" to "Fix the redirect"), f.sent)
    }

    @Test fun draftKindsAreExclusiveMemoryOnlyAndReconcileOnlyKnownCatalogs() = runTest {
        val f = Fixture(this)
        val snapshots = MutableStateFlow<HomeSnapshot?>(HomeSnapshot(emptyList(), f.machines))
        val services = NewSessionServices(f.drafts, snapshots, MutableStateFlow(Settings()), f.actions,
            f.starter, NewSessionLabels(), f.context::getString, backgroundScope, StandardTestDispatcher(testScheduler))
        runCurrent()
        assertTrue(services.options.value!!.supportsTasks)
        val persisted = f.drafts.draft.value.toJson()
        services.setCreatesTask(true)
        assertTrue(services.createsTask.value)
        assertFalse(services.bot.value.creating)
        assertEquals(persisted, f.drafts.draft.value.toJson())
        services.setCreatesBot(true)
        assertFalse(services.createsTask.value)
        services.setCreatesTask(true)
        assertFalse(services.bot.value.creating)
        snapshots.value = HomeSnapshot(emptyList(), emptyList(), ready = false)
        runCurrent()
        assertTrue(services.createsTask.value)
        snapshots.value = HomeSnapshot(emptyList(), emptyList(), ready = true)
        runCurrent()
        assertFalse(services.createsTask.value)
        services.setCreatesTask(true)
        services.resetDraft()
        assertFalse(services.createsTask.value)
    }

    @Test fun realDockTaskCardOmitsSessionModesAndSuccessfulCreateResetsKind() = runTest {
        val f = Fixture(this)
        Theme.install(f.context)
        val snapshots = MutableStateFlow<HomeSnapshot?>(HomeSnapshot(emptyList(), f.machines))
        val services = NewSessionServices(f.drafts, snapshots, MutableStateFlow(Settings()), f.actions,
            f.starter, NewSessionLabels(), f.context::getString, backgroundScope, StandardTestDispatcher(testScheduler))
        runCurrent()
        val opened = mutableListOf<String>()
        val controller = HomeDockController(f.context, HomeDockView(f.context), services, backgroundScope,
            object : HomeDockController.Host {
                override val canPresent = true
                override fun present(focus: HomeDockFocusView) = Unit
                override fun dismiss(focus: HomeDockFocusView) = Unit
                override fun openSession(id: String) { opened += id }
            })
        controller.setKind(HomeDockKind.Task)
        controller.setPrompt("Ship it")
        val card = controller.cardConfiguration()
        assertEquals("What should this task do?", card.placeholder)
        assertEquals("Create task", card.primaryAccessibilityLabel)
        assertNull(card.permission)
        assertNull(card.model)
        assertNull(card.effort)
        assertTrue(controller.submit())
        assertEquals(listOf("session-1"), opened)
        assertEquals(listOf("session-1" to "Ship it"), f.sent)
        assertFalse(services.createsTask.value)
        assertEquals(HomeDockKind.Session, controller.kind)
        assertEquals("", controller.prompt)

        // A stale selected kind remains that kind until reconciliation; a
        // press during capability refresh must never create an ordinary session.
        f.machines = Fixture(this, JsonPrimitive(false)).machines
        snapshots.value = HomeSnapshot(emptyList(), f.machines)
        runCurrent()
        assertFalse(services.options.value!!.supportsTasks)
        services.setCreatesTask(true)
        assertEquals(HomeDockKind.Task, controller.kind)
        assertEquals(StartResult.Refused(StartRefusal.TasksUnsupported), f.start())
        assertEquals(1, f.spawns.size)
    }

    @Test @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun pictureOnlyTaskPassesNormalizedImageAndClearsOnlyAfterSuccess() = runTest {
        val f = Fixture(this)
        val (dock, opened) = pictureDock(f)
        assertFalse(dock.canSubmit)
        val picture = addPicture(dock)
        assertTrue(dock.canSubmit)
        dock.setKind(HomeDockKind.Task)
        assertEquals(listOf(picture), dock.cardConfiguration().attachments.map { it.file })
        assertEquals("Add image", dock.cardConfiguration().addAccessibilityLabel)
        f.send = {
            val attachment = f.sentImages.last().single()
            assertEquals("image/jpeg", attachment.mimeType)
            assertTrue(picture.isFile)
            assertArrayEquals(byteArrayOf(-1, -40), picture.readBytes().take(2).toByteArray())
            FirstSend(true)
        }
        assertTrue(dock.submit())
        assertEquals(listOf("session-1" to ""), f.sent)
        assertEquals(listOf("session-1"), opened)
        assertEquals(HappyAgentSpawnTarget.Task(), f.spawns.single().happyAgentTarget)
        assertFalse(picture.exists())
        assertTrue(dock.images.images.isEmpty())
        assertFalse(dock.canSubmit)
        assertEquals(HomeDockKind.Session, dock.kind)
    }

    @Test @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun botPreservesSessionPicturesAndPromptAcrossKindSwitches() = runTest {
        val f = Fixture(this)
        val (dock, _) = pictureDock(f)
        val picture = addPicture(dock)
        dock.setPrompt("Keep this prompt")
        dock.setKind(HomeDockKind.Bot)
        assertFalse(dock.canSubmit)
        assertFalse(dock.cardConfiguration().showsAdd)
        assertTrue(dock.cardConfiguration().attachments.isEmpty())
        dock.setBotName("Fixture Bot")
        assertTrue(dock.submit())
        assertTrue(f.sent.isEmpty())
        assertEquals("Keep this prompt", dock.prompt)
        assertTrue(picture.isFile)
        assertEquals(listOf(picture), dock.cardConfiguration().attachments.map { it.file })
        dock.setKind(HomeDockKind.Task)
        assertEquals(listOf(picture), dock.cardConfiguration().attachments.map { it.file })
        dock.images.remove(dock.images.images.single().id)
        assertFalse(picture.exists())
        dock.setPrompt("")
        assertFalse(dock.canSubmit)
    }

    @Test @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun ordinarySessionAlsoAcceptsAPictureWithoutText() = runTest {
        val f = Fixture(this)
        val (dock, opened) = pictureDock(f)
        val picture = addPicture(dock)
        assertEquals(HomeDockKind.Session, dock.kind)
        assertTrue(dock.submit())
        assertNull("A session must not become a task", f.spawns.single().happyAgentTarget)
        assertEquals(listOf("session-1" to ""), f.sent)
        assertEquals(1, f.sentImages.single().size)
        assertEquals(listOf("session-1"), opened)
        assertFalse(picture.exists())
    }

    @Test @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun startingRefusesPictureControlsAndStopKeepsTheSelection() = runTest {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        try {
            val f = Fixture(this, context = activity.get())
            val (dock, _) = pictureDock(f)
            val picture = addPicture(dock)
            val id = dock.images.images.single().id
            dock.setKind(HomeDockKind.Task)
            val focus = HomeDockFocusView(f.context, dock)
            activity.get().setContentView(focus)
            fun findCard(parent: ViewGroup): AgentInputCardView? {
                for (index in 0 until parent.childCount) {
                    val child = parent.getChildAt(index)
                    if (child is AgentInputCardView) return child
                    if (child is ViewGroup) findCard(child)?.let { return it }
                }
                return null
            }
            val card = requireNotNull(findCard(focus))
            val gate = CompletableDeferred<Unit>()
            f.ready = { gate.await(); true }
            val submitting = async { dock.submit() }
            runCurrent()
            assertTrue(dock.isStarting)
            card.onRemoveAttachment?.invoke(id)
            card.onReceiveImages?.invoke(listOf(Uri.fromFile(picture)))
            card.onAdd?.invoke()
            assertNull(ShadowAlertDialog.getLatestAlertDialog())
            assertEquals(listOf(id), dock.images.images.map { it.id })
            dock.cancelStart()
            assertFalse(submitting.await())
            runCurrent()
            assertEquals(listOf("session-1"), f.killed)
            assertTrue(picture.isFile)
            card.onRemoveAttachment?.invoke(id)
            assertTrue(dock.images.images.isEmpty())
            assertFalse(picture.exists())
        } finally { activity.pause().stop().destroy() }
    }

    @Test @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun refusedFirstPictureSendKeepsSelectionAndRetriesSameTask() = runTest {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        try {
            val f = Fixture(this, context = activity.get())
            val (dock, opened) = pictureDock(f)
            val picture = addPicture(dock)
            dock.setKind(HomeDockKind.Task)
            f.send = { FirstSend(false, listOf(AttachmentNotice.NotSupported)) }
            assertFalse(dock.submit())
            assertTrue(picture.isFile)
            assertEquals(HomeDockKind.Task, dock.kind)
            assertTrue(opened.isEmpty())
            ShadowAlertDialog.getLatestAlertDialog().dismiss()
            f.send = { FirstSend(true) }
            assertTrue(dock.submit())
            assertEquals(1, f.spawns.size)
            assertEquals(listOf("session-1", "session-1"), f.sent.map { it.first })
            assertEquals(f.sentImages.first(), f.sentImages.last())
            assertFalse(picture.exists())
        } finally { activity.pause().stop().destroy() }
    }

    @Test @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun destroyingHomeRemovesOnlyItsOwnPictureStaging() = runTest {
        val ownedJob = SupervisorJob(backgroundScope.coroutineContext[Job])
        val owned = CoroutineScope(ownedJob + StandardTestDispatcher(testScheduler))
        val (dock, _) = pictureDock(Fixture(owned), owned)
        val (other, _) = pictureDock(Fixture(this))
        val picture = addPicture(dock)
        val otherPicture = addPicture(other)
        owned.cancel()
        ownedJob.join()
        assertFalse(picture.exists())
        assertTrue(otherPicture.isFile)
        assertTrue(dock.images.images.isEmpty())
        assertEquals(1, other.images.images.size)
    }

    private fun TestScope.pictureDock(f: Fixture, ownerScope: CoroutineScope = backgroundScope): Pair<HomeDockController, MutableList<String>> {
        Theme.install(f.context)
        val services = NewSessionServices(f.drafts, MutableStateFlow<HomeSnapshot?>(HomeSnapshot(emptyList(), f.machines)),
            MutableStateFlow(Settings()), f.actions, f.starter, NewSessionLabels(), f.context::getString,
            ownerScope, StandardTestDispatcher(testScheduler))
        runCurrent()
        val opened = mutableListOf<String>()
        return HomeDockController(f.context, HomeDockView(f.context), services, ownerScope,
            object : HomeDockController.Host {
                override val canPresent = true
                override fun present(focus: HomeDockFocusView) = Unit
                override fun dismiss(focus: HomeDockFocusView) = Unit
                override fun openSession(id: String) { opened += id }
            }) to opened
    }

    private suspend fun TestScope.addPicture(dock: HomeDockController): File {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val source = File.createTempFile("dock-picture-", ".png", context.cacheDir)
        val bitmap = Bitmap.createBitmap(32, 16, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLUE) }
        source.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        bitmap.recycle()
        try {
            dock.images.receive(listOf(Uri.fromFile(source)))
            // Normalization deliberately stays on the real IO dispatcher.
            repeat(300) {
                runCurrent()
                dock.images.images.singleOrNull()?.let { return it.image.file }
                withContext(Dispatchers.Default) { delay(10) }
            }
            error("Picture normalization did not finish")
        } finally { source.delete() }
    }
}