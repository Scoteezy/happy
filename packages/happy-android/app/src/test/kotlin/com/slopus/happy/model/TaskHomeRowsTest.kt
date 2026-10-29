package com.slopus.happy.model

import com.slopus.happy.model.HomeModelFixtures.rigMetadata
import com.slopus.happy.model.HomeModelFixtures.session
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class TaskHomeRowsTest {
    @Test fun sharedDeviceFixtureContainsValidLatestProtocolMetadata() {
        val root = Json.parseToJsonElement(File("src/debug/assets/harness/relay-task-home.json").readText()).jsonObject
        val sessions = root.getValue("sessions").jsonArray.map { value ->
            val item = value.jsonObject
            val id = item.getValue("id").jsonPrimitive.content
            val metadata = SessionMetadata.parse(item.getValue("metadata").jsonObject)
            assertNotNull("Fixture $id must not fall back to an unknown machine or New chat", metadata)
            assertEquals("fixture-machine", metadata!!.machineId)
            session(id, metadata)
        }
        val items = buildSessionListViewData(SessionListInputs(sessions, emptyMap()))
        val rows = buildProjectHomeRows(items, emptyMap(), "unknown", emptySet())
        assertEquals(listOf("fixture-machine"), rows.filterIsInstance<ProjectHomeRow.MachineHeader>().map { it.machineId })
        assertEquals("Reference project", rows.filterIsInstance<ProjectHomeRow.ProjectCard>().single().project.name)
        assertEquals(setOf("fixture-task-five", "fixture-task-forty-five"), sessions.filter { it.canLeaveTask }.map { it.id }.toSet())
        assertEquals(listOf("fixture-task-forty-five"), sessions.filter { it.taskCanArchive }.map { it.id })
    }

    private fun task(id: String, key: String? = null, created: Long = 0, archived: Boolean = false, machine: String = "m") =
        session(id, rigMetadata(machineId = machine, projectId = "must-not-group") {
            putJsonObject("task") {
                put("id", id); put("name", "Task $id"); put("folderName", id)
                if (key != null) put("orderKey", key)
            }
            if (archived) put("lifecycleState", "archived")
        }, createdAt = created)

    @Test fun tasksUseMembershipStringOrderThenCreationAndIdNotActivity() {
        val sessions = listOf(task("five", "5"), task("forty-five", "45"), task("z", created = 5),
            task("a", created = 5), task("old", created = 1), task("first", "1", machine = "a"))
        val items = buildSessionListViewData(SessionListInputs(sessions, emptyMap()))
        assertEquals(listOf("first", "forty-five", "five", "a", "z", "old"), (items.single() as SessionListItem.Tasks).sessions.map { it.id })
        assertEquals(items, visibleSessionListItems(items, true))
        assertTrue(projectWorktreesBySession(items).isEmpty())
    }

    @Test fun groupedTasksSitBetweenBotsAndProjectsAndTaskOnlyMachineSurvives() {
        val taskRows = buildSessionListViewData(SessionListInputs(listOf(task("t"), task("other", machine = "other")), emptyMap()))
        val bot = SessionRowData("b", botId = "b", machineId = "m")
        val rows = buildProjectHomeRows(listOf(SessionListItem.Bots(listOf(bot))) + taskRows, emptyMap(), "unknown", emptySet())
        assertEquals(listOf(ProjectHomeSection.Bots, ProjectHomeSection.Tasks, ProjectHomeSection.Tasks), rows.filterIsInstance<ProjectHomeRow.Section>().map { it.section })
        assertEquals(listOf("t", "other"), rows.filterIsInstance<ProjectHomeRow.Task>().map { it.session.id })
        assertTrue(rows.none { it is ProjectHomeRow.ProjectCard })
        assertEquals(2, rows.filterIsInstance<ProjectHomeRow.MachineHeader>().size)
    }

    @Test fun retiredAndLocallyRetiringTasksDisappearWithoutArchiveToggle() {
        val items = buildSessionListViewData(SessionListInputs(listOf(task("archived", archived = true), task("leaving")),
            emptyMap(), archivingSessionIds = setOf("leaving")))
        assertTrue(items.isEmpty())
        assertFalse(hasArchivedSessions(items))
    }

    @Test fun taskIdentityUsesNameAndFolderButNoProjectOrWorktree() {
        val s = task("t")
        val row = buildSessionRowData(s, SessionListInputs(listOf(s), emptyMap()))
        assertTrue(row.isTask)
        assertEquals("Task t", row.name)
        assertEquals("m:task:t", row.avatarId)
        assertEquals("~/Tasks/t", toFlatSessionRow(row).projectName)
        assertNull(toFlatSessionRow(row).workspaceName)
        assertEquals("~/Tasks", toFlatSessionRow(row.copy(taskFolderName = null)).projectName)
        assertEquals(1, buildFlatSessionRows(listOf(SessionListItem.Tasks(listOf(row))), true).size)
    }

    @Test fun botWinsIfBothObjectsExistAndMalformedTaskIsNotATask() {
        val metadata = rigMetadata {
            putJsonObject("task") { put("name", "Not the name") }
            putJsonObject("bot") {
                put("id", "b"); put("name", "Bot"); put("username", "bot")
                put("workspaceId", "w"); put("orderKey", "1")
            }
        }
        val bot = session("b", metadata)
        assertFalse(bot.isTask)
        assertEquals("Bot", bot.displayName())
        assertFalse(session("bad", rigMetadata { put("task", JsonNull) }).isTask)
        // Native object-presence semantics do not demand a task ID.
        assertTrue(session("empty", rigMetadata { putJsonObject("task") {} }).isTask)
    }
}