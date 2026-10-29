package com.slopus.happy.harness

import com.slopus.happy.stories.STORIES_SETTING_KEY
import kotlinx.serialization.json.*

/** Stories in the real shell, including a real tool presentation in Prioritizing. Debug only. */
object StoriesShellFixture {
    fun apply(fixture: String): String {
        val root = Json.parseToJsonElement(fixture).jsonObject
        val settings = JsonObject(root.getValue("settings").jsonObject + (STORIES_SETTING_KEY to JsonPrimitive(true)))
        val agents = root.getValue("agents").jsonObject
        val agent = agents.getValue("agprio").jsonObject
        val runs = agent.getValue("runs").jsonArray.toMutableList()
        val run = runs.last().jsonObject
        val messages = run.getValue("messages").jsonArray.toMutableList()
        val message = messages.last().jsonObject
        val presentation = buildJsonObject {
            put("type", "tool_call")
            put("id", "tc-inline-story")
            put("name", "draft_story")
            put("status", "completed")
            putJsonObject("presentation") {
                put("type", "artifact")
                put("artifactId", "d1")
                put("artifactType", "story")
                put("title", "Story draft")
                put("version", 1)
                put("shows", "latest")
            }
        }
        messages[messages.lastIndex] = JsonObject(message + ("content" to JsonArray(message.getValue("content").jsonArray + presentation)))
        runs[runs.lastIndex] = JsonObject(run + ("messages" to JsonArray(messages)))
        return JsonObject(root + mapOf(
            "settings" to settings,
            "agents" to JsonObject(agents + ("agprio" to JsonObject(agent + ("runs" to JsonArray(runs))))),
        )).toString()
    }
}