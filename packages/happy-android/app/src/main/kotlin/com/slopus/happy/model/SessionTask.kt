package com.slopus.happy.model

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

/** Native SessionState: a task is metadata's object, unless the session is a bot. */
val SessionMetadata.task: JsonObject?
    get() = if (bot == null) raw["task"] as? JsonObject else null

internal fun JsonObject.taskString(key: String): String? =
    (get(key) as? JsonPrimitive)?.takeIf { it.isString }?.content

val Session.isTask: Boolean get() = metadata?.task != null

/** Native SessionState: absent ownership/capability is not permission. */
val Session.taskCanArchive: Boolean get() = (metadata?.task?.get("canArchive") as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull == true
val Session.canLeaveTask: Boolean get() = isTask && metadata?.capabilities?.rpcMethods?.contains("leaveTask") == true

/** ProjectHomeList.taskPrecedes: membership keys are lexical, never numeric. */
internal val taskOrder = Comparator<Session> { a, b ->
    val machineOrder = java.text.Collator.getInstance().compare(a.metadata?.machineId.orEmpty(), b.metadata?.machineId.orEmpty())
    if (machineOrder != 0) return@Comparator machineOrder
    val keyA = a.metadata?.task?.taskString("orderKey")
    val keyB = b.metadata?.task?.taskString("orderKey")
    when {
        keyA != null && keyB != null -> keyA.compareTo(keyB).takeIf { it != 0 } ?: a.id.compareTo(b.id)
        keyA != null -> -1
        keyB != null -> 1
        else -> b.createdAt.compareTo(a.createdAt).takeIf { it != 0 } ?: a.id.compareTo(b.id)
    }
}