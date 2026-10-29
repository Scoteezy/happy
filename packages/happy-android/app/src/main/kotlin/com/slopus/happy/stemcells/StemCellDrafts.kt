package com.slopus.happy.stemcells

import com.slopus.happy.direct.DirectAgentClient
import com.slopus.happy.direct.DirectOfflineFile
import com.slopus.happy.direct.Wire
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.util.concurrent.Executors

/**
 * One conversation's draft as this phone last had it: the text, when it was
 * touched (the computer's clock of record, desktop's `updatedAt`), and
 * whether the computer has it yet. Empty text is a cleared draft, kept for
 * its stamp. happy-ios `StemCellDraft`.
 */
data class StemCellDraft(val text: String, val updatedAt: Double, val synced: Boolean)

/**
 * The drafts kept on this phone for one computer, by agent, beside what
 * else it keeps of that computer (`drafts.json` in its folder), so a draft
 * opens at once, offline too, and one the computer never got is sent when it
 * can be. happy-ios `StemCellDrafts`.
 */
class StemCellDrafts(private val offline: DirectOfflineFile?) {
    private var drafts: MutableMap<String, StemCellDraft>? = null

    @Synchronized
    fun draft(agentId: String): StemCellDraft? = loaded()[agentId]

    /** Keeps `draft` for the agent, or forgets it with null, and writes the file after any write before it. */
    @Synchronized
    fun keep(agentId: String, draft: StemCellDraft?) {
        val all = loaded()
        if (all[agentId] == draft) return
        if (draft == null) all.remove(agentId) else all[agentId] = draft
        val offline = offline ?: return
        val json = JsonObject(all.mapValues { (_, it) ->
            JsonObject(mapOf("text" to JsonPrimitive(it.text), "updatedAt" to Wire.number(it.updatedAt), "synced" to JsonPrimitive(it.synced)))
        })
        writer.execute { offline.writeExtra(FILE_NAME, json.toString().toByteArray()) }
    }

    /** Waits for the writes already asked for. */
    fun flush() {
        writer.submit {}.get()
    }

    private fun loaded(): MutableMap<String, StemCellDraft> {
        drafts?.let { return it }
        val read = HashMap<String, StemCellDraft>()
        offline?.readExtra(FILE_NAME)?.let { data ->
            runCatching {
                for ((id, value) in Wire.obj(DirectAgentClient.parse(data))) {
                    val o = Wire.obj(value)
                    read[id] = StemCellDraft(Wire.string(o, "text"), Wire.double(o, "updatedAt"), Wire.bool(o, "synced"))
                }
            }
        }
        drafts = read
        return read
    }

    companion object {
        const val FILE_NAME = "drafts.json"
        private val writer = Executors.newSingleThreadExecutor { Thread(it, "stem-cells-drafts").apply { isDaemon = true } }
    }
}

/** A message kept on this phone until the computer takes it (happy-ios `DirectQueuedMessage`). */
data class DirectQueuedMessage(
    val id: String,
    val agentId: String,
    val text: String,
    val pictures: List<OutgoingImage>,
    val mode: AgentMode?,
    val failure: String? = null,
)

/** What the outbox heard of a send (happy-ios `DirectDeliveryEvent`). */
sealed class DirectDeliveryEvent {
    data class Accepted(val id: String) : DirectDeliveryEvent()
    data class Failed(val id: String, val reason: String) : DirectDeliveryEvent()
}

/**
 * Where an account-backed conversation keeps itself and its outbox (happy-ios
 * `DirectSyncEngine`'s side of `StemCellConversation`). A conversation
 * without one sends at once and keeps nothing.
 */
interface StemCellPersistence {
    suspend fun deliveryAllowed(): Boolean
    fun deliveryEvents(agentId: String): Flow<DirectDeliveryEvent>
    suspend fun queuedMessages(agentId: String): List<DirectQueuedMessage>
    suspend fun enqueueMessage(message: DirectQueuedMessage)
    suspend fun retryMessage(id: String)
    suspend fun cachedResource(key: String): JsonElement?
    suspend fun keepResource(key: String, value: JsonElement)
}
