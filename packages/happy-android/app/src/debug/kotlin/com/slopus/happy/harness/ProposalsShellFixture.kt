package com.slopus.happy.harness

import kotlinx.serialization.json.*

/** A real proposal tool presentation, served only by isolated fake computers. */
object ProposalsShellFixture {
    fun apply(fixture: String): String {
        val root = Json.parseToJsonElement(fixture).jsonObject
        val agents = root.getValue("agents").jsonObject
        val agent = agents.getValue("agprio").jsonObject
        val runs = agent.getValue("runs").jsonArray.toMutableList()
        val run = runs.last().jsonObject
        val messages = run.getValue("messages").jsonArray.toMutableList()
        val message = messages.last().jsonObject
        val presentation = buildJsonObject {
            put("type", "tool_call")
            put("id", "tc-sidebar-proposal")
            put("name", "propose_sidebar")
            put("status", "completed")
            putJsonObject("presentation") {
                put("type", "artifact")
                put("artifactId", "p1")
                put("artifactType", "sidebar_proposal")
                put("title", "Proposed sidebar")
                put("version", 3)
                put("shows", "latest")
            }
        }
        messages[messages.lastIndex] = JsonObject(message + ("content" to JsonArray(message.getValue("content").jsonArray + presentation)))
        runs[runs.lastIndex] = JsonObject(run + ("messages" to JsonArray(messages)))
        val proposal = buildJsonObject {
            put("html", """<nav><happy-search/><happy-create/><happy-space id="sphappy" expanded="true"><happy-space id="agprio"/></happy-space><happy-section id="proposed" label="Proposed work" collapsed="true"/><happy-space id="spandroid"/><happy-space id="splogin"/></nav>""")
            putJsonObject("proposal") {
                put("artifactId", "p1")
                put("version", 3)
                put("baseVersion", root.getValue("sidebar").jsonObject.getValue("version"))
                put("status", "open")
                put("createdAt", root.getValue("now"))
                put("decidedAt", JsonNull)
                put("acceptedVersion", JsonNull)
            }
        }
        return JsonObject(root + mapOf(
            "proposals" to buildJsonObject { put("p1", proposal) },
            "agents" to JsonObject(agents + ("agprio" to JsonObject(agent + ("runs" to JsonArray(runs))))),
        )).toString()
    }
}