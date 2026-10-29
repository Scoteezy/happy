package com.slopus.happy.home

import android.content.Context
import com.slopus.happy.R
import com.slopus.happy.chat.ChatActions
import com.slopus.happy.chat.PermissionAnswer
import com.slopus.happy.sync.SendException
import com.slopus.happy.data.HappyStore
import com.slopus.happy.chat.AgentQuestionAnswer
import com.slopus.happy.model.PendingChat
import com.slopus.happy.model.SessionStartRequest
import com.slopus.happy.sync.AccountSync
import com.slopus.happy.sync.SendOptions
import com.slopus.happy.sync.SendResult
import com.slopus.happy.ui.components.Alerts
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import java.lang.ref.WeakReference

/**
 * The worktree strip's starts for one account (happy-ios
 * `ChatPendingStarts`, WorktreeTabStrip.tsx `newTab`): `+` opens a
 * stand-in at once, its start runs underneath on a starter of its own (the
 * dock's can be busy), one after another, and the chat takes its real
 * session when the machine answers. Kept per account, so a start outlives
 * the screen that asked for it; a new account starts over.
 */
class ChatPendingStarts(
    private val account: AccountSync,
    private val store: HappyStore,
    starter: SessionStarter,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val records = PendingChatStore()
    /** The screen that pressed `+` last: a failure's alert goes up on it. */
    private var presenter = WeakReference<Context>(null)

    val chats: StateFlow<Map<String, PendingChat>> get() = records.chats

    private val handover = PendingChatHandover(records, object : PendingChatSessions {
        override suspend fun send(sessionId: String, text: String): Boolean =
            account.sender.send(sessionId, text, SendOptions(source = "new_session")) is SendResult.Accepted

        override fun draft(sessionId: String): String? = account.composer.mirrors.value[sessionId]?.draft

        override suspend fun writeDraft(sessionId: String, text: String) = account.composer.setText(sessionId, text)

        override fun exists(sessionId: String): Boolean = store.session(sessionId) != null

        override fun alert(message: String) = showError(message)
    })

    private val launcher = PendingChatStarter(
        records, handover, scope,
        // The directory is the open chat's own, so there is nothing to create.
        start = { request -> starter.start(request.draft, target = request.target, confirmDirectory = { false }) },
        failed = ::showError,
    )

    /** `openPendingChat` beside [anchorSessionId], whose checkout holds [knownSessionIds] right now. */
    fun open(context: Context, anchorSessionId: String, knownSessionIds: List<String>, request: SessionStartRequest): PendingChat {
        presenter = WeakReference(context)
        return launcher.open(anchorSessionId, knownSessionIds, request)
    }

    operator fun get(id: String): PendingChat? = records[id]

    /** The screen standing on a settled or failed record leaves: the record goes with it. */
    fun dismiss(id: String) = records.dismiss(id)

    /** What a stand-in's composer does: Send queues the text on the record and keystrokes are its draft. */
    fun actions(id: String): ChatActions = PendingChatActions(records, id)

    fun close() = scope.cancel()

    private fun showError(message: String) {
        val context = presenter.get() ?: return
        scope.launch(Dispatchers.Main) { Alerts.show(context, context.getString(R.string.common_error), message) }
    }
}

/**
 * happy-ios `PendingChatSender` and `PendingChatComposerStorage`: the
 * composer of a chat that doesn't exist yet. A message waits on the record
 * for the handover; one the record refuses (the chat has settled, or a
 * picture rides along) throws, so the composer keeps the text.
 */
private class PendingChatActions(private val records: PendingChatStore, private val id: String) : ChatActions {
    override suspend fun send(
        sessionId: String,
        text: String,
        meta: JsonObject?,
        attachments: List<JsonObject>,
        awaitDelivery: Boolean,
        files: List<com.slopus.happy.attachments.AttachmentPreview>,
    ) {
        val before = records[id]
        if (attachments.isNotEmpty() || files.isNotEmpty() || before == null || before.sessionId != null || text.isBlank()) {
            throw SendException(REFUSED)
        }
        records.submit(id, text)
    }

    override suspend fun setDraft(sessionId: String, text: String) = records.setDraft(id, text)

    override suspend fun abort(sessionId: String) = Unit

    override suspend fun answerPermission(sessionId: String, answer: PermissionAnswer) = Unit

    override suspend fun answerQuestion(sessionId: String, communicationId: String, answers: Map<String, AgentQuestionAnswer>, kind: String) = Unit

    override suspend fun cancelQuestion(sessionId: String, communicationId: String, kind: String) = Unit

    private companion object {
        const val REFUSED = "This chat is no longer waiting to start"
    }
}
