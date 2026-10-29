package com.slopus.happy.home

import com.slopus.happy.model.HomeModelFixtures
import com.slopus.happy.model.Session
import com.slopus.happy.model.SessionMetadata
import com.slopus.happy.model.SessionRowData
import com.slopus.happy.sync.OpResult
import com.slopus.happy.sync.rigMetadataJson
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.*
import org.junit.Test

/** Native task menu/swipe and local retirement rules, independent of Android views. */
class TaskSessionActionsTest {
    private fun task(leave: Boolean = true, archive: Boolean? = null, bot: Boolean = false): Session {
        val raw = JsonObject(rigMetadataJson(rpcMethods = if (leave) listOf("leaveTask") else emptyList(), bot = bot) +
            ("task" to buildJsonObject { archive?.let { put("canArchive", it) } }))
        return HomeModelFixtures.session("task", requireNotNull(SessionMetadata.parse(raw)))
    }

    private fun items(session: Session) = sessionActionIds(session, null, continuationExperiments = false, devMode = false)

    @Test fun taskMenuRequiresPublishedLeaveAndExplicitArchiveOwnership() {
        assertEquals(listOf(SessionActionId.Details), items(task(leave = false)))
        assertEquals(listOf(SessionActionId.Details, SessionActionId.LeaveTask), items(task(archive = false)))
        assertEquals(listOf(SessionActionId.Details, SessionActionId.Archive), items(task(leave = false, archive = true)))
        assertEquals(listOf(SessionActionId.Details, SessionActionId.LeaveTask, SessionActionId.Archive), items(task(archive = true)))
        assertEquals(listOf(SessionActionId.Details, SessionActionId.Archive), items(task(bot = true)))
    }

    @Test fun bothTaskLayoutsLeaveAndUnsupportedTasksNeverArchiveBySwipe() {
        val task = SessionRowData("task", isTask = true, canLeaveTask = true)
        for (grouped in listOf(false, true)) {
            assertEquals(SessionActionId.LeaveTask, sessionSwipeAction(task, archived = false, grouped))
            assertNull(sessionSwipeAction(task.copy(canLeaveTask = false), archived = false, grouped))
            assertNull(sessionSwipeAction(task, archived = true, grouped))
        }
        assertEquals(SessionActionId.Archive, sessionSwipeAction(SessionRowData("normal"), false, false))
        assertNull(sessionSwipeAction(SessionRowData("normal"), false, true))
    }

    @Test fun stringTrueIsNotTaskArchivePermission() {
        val raw = JsonObject(rigMetadataJson(rpcMethods = emptyList()) +
            ("task" to buildJsonObject { put("canArchive", "true") }))
        assertEquals(listOf(SessionActionId.Details), items(HomeModelFixtures.session("task", SessionMetadata.parse(raw))))
    }

    private open class Actions : SessionActions {
        val calls = mutableListOf<String>()
        var answer = OpResult(false, "Only the owner may archive this task")
        override suspend fun kill(sessionId: String): OpResult { calls += "kill"; return answer }
        override suspend fun archiveTask(sessionId: String): OpResult { calls += "task-archive"; return answer }
        override suspend fun leaveTask(sessionId: String): OpResult { calls += "leave"; return answer }
        override suspend fun archiveOnServer(sessionId: String): OpResult { calls += "server-archive"; return OpResult(true) }
        override suspend fun delete(sessionId: String): OpResult { calls += "delete"; return OpResult(true) }
    }

    @Test fun taskArchiveNeverCleansWorktreesOrFallsBackAndPreservesRefusal() = runBlocking {
        val actions = Actions()
        val archiver = SessionArchiver(actions)
        val result = archiver.archive(task(archive = true)) { fail("Task worktree must not be cleaned") }
        assertEquals(ArchiveResult.Failed(actions.answer.message, bot = false), result)
        assertEquals(listOf("task-archive"), actions.calls)
        assertTrue(archiver.archiving.value.isEmpty())
    }

    @Test fun ordinaryArchiveStillFallsBackToServer() = runBlocking {
        val actions = Actions()
        val archiver = SessionArchiver(actions)
        assertEquals(ArchiveResult.Archived, archiver.archive(HomeModelFixtures.session("normal")))
        assertEquals(listOf("kill", "server-archive"), actions.calls)
        assertEquals(setOf("normal"), archiver.archiving.value)
    }

    @Test fun leaveHidesImmediatelyRejectsDuplicatesAndRestoresOnFailure() = runBlocking {
        val reply = CompletableDeferred<OpResult>()
        val actions = object : Actions() {
            override suspend fun leaveTask(sessionId: String): OpResult { calls += "leave"; return reply.await() }
        }
        val archiver = SessionArchiver(actions)
        val pending = async(start = CoroutineStart.UNDISPATCHED) { archiver.leave(task()) }
        assertEquals(setOf("task"), archiver.archiving.value)
        assertEquals(ArchiveResult.InFlight, archiver.leave(task()))
        reply.complete(OpResult(false, "No longer a member"))
        assertEquals(ArchiveResult.Failed("No longer a member", false), pending.await())
        assertTrue(archiver.archiving.value.isEmpty())
        assertEquals(listOf("leave"), actions.calls)
    }

    @Test fun successfulLeaveStaysHiddenAndUnsupportedTaskSendsNothing() = runBlocking {
        val actions = Actions().apply { answer = OpResult(true) }
        val archiver = SessionArchiver(actions)
        assertTrue(archiver.leave(task(leave = false)) is ArchiveResult.Failed)
        assertTrue(actions.calls.isEmpty())
        assertEquals(ArchiveResult.Archived, archiver.leave(task()))
        assertEquals(setOf("task"), archiver.archiving.value)
        assertEquals(listOf("leave"), actions.calls)
    }

    @Test fun cancellationRestoresTheTask() = runBlocking {
        val archiver = SessionArchiver(object : Actions() {
            override suspend fun leaveTask(sessionId: String): OpResult = throw CancellationException("closed")
        })
        try {
            archiver.leave(task())
            fail("Cancellation must propagate")
        } catch (_: CancellationException) { }
        assertTrue(archiver.archiving.value.isEmpty())
    }

    @Test fun oldAccountFailureCannotRestoreANewAccountsSameId() = runBlocking {
        val reply = CompletableDeferred<OpResult>()
        var first = true
        val archiver = SessionArchiver(object : Actions() {
            override suspend fun leaveTask(sessionId: String): OpResult = if (first) {
                first = false
                reply.await()
            } else OpResult(true)
        })
        val old = async(start = CoroutineStart.UNDISPATCHED) { archiver.leave(task()) }
        archiver.reset()
        assertTrue(archiver.archiving.value.isEmpty())
        assertEquals(ArchiveResult.Archived, archiver.leave(task()))
        reply.complete(OpResult(false))
        old.await()
        assertEquals(setOf("task"), archiver.archiving.value)
    }

    @Test fun newAccountOperationResetsBeforeTheAccountCollectorAndIsNotClearedByIt() = runBlocking {
        var owner = Any()
        val actions = Actions().apply { answer = OpResult(true) }
        val archiver = SessionArchiver(actions) { owner }
        archiver.leave(task())
        owner = Any()
        assertEquals(ArchiveResult.Archived, archiver.leave(task()))
        archiver.accountChanged()
        assertEquals(setOf("task"), archiver.archiving.value)
        assertEquals(listOf("leave", "leave"), actions.calls)
    }
}