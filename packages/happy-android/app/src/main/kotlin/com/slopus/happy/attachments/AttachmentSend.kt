package com.slopus.happy.attachments

import android.net.Uri
import com.slopus.happy.model.SessionMetadata
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import java.io.File
import java.io.IOException

/** What a send with attachments tells the user (sync.ts sendMessage's alerts). */
sealed interface AttachmentNotice {
    /** imageUpload.notSupported: the agent takes no images, or the rig's policy refused some. */
    data object NotSupported : AttachmentNotice

    /** imageUpload.uploadFailed with the count. */
    data class UploadFailed(val count: Int) : AttachmentNotice
}

/**
 * The attachment half of sync.ts sendMessage, before the text is queued.
 * [records] go to the sender ahead of the text; [send] false means nothing
 * is sent at all (an image-only message to an agent that takes no images).
 */
data class AttachmentSendPlan(val send: Boolean, val records: List<JsonObject>, val notices: List<AttachmentNotice>)

/** Which pictures may go, and what to tell the user; [send] false means nothing goes at all. */
data class AttachmentPick(val send: Boolean, val accepted: List<AttachmentPreview>, val notices: List<AttachmentNotice>)

/**
 * sync.ts sendMessage's attachment plan ([imageAttachmentSendPlan], the rig
 * policy filter), before anything is uploaded: what the outbox should take.
 */
fun planAttachmentSend(metadata: SessionMetadata?, text: String, attachments: List<AttachmentPreview>): AttachmentPick {
    if (attachments.isEmpty()) return AttachmentPick(send = true, accepted = emptyList(), notices = emptyList())
    val plan = imageAttachmentSendPlan(metadata, text, attachments.size)
    val policy = metadata.rigAttachmentPolicy()
    val effective = when {
        !plan.shouldUseAttachments -> emptyList()
        policy != null -> attachments.filter { isAttachmentAllowedByPolicy(it.mimeType, it.size, policy) }
        else -> attachments
    }
    val rejectedByRig = policy != null && attachments.size > effective.size
    val notices = mutableListOf<AttachmentNotice>()
    if (plan.shouldShowUnsupportedAlert || rejectedByRig) {
        notices += AttachmentNotice.NotSupported
        if (!plan.shouldSendText || (text.isBlank() && effective.isEmpty())) {
            return AttachmentPick(send = false, accepted = emptyList(), notices = notices)
        }
    }
    return AttachmentPick(send = true, accepted = effective, notices = notices)
}

/**
 * Plans the send ([planAttachmentSend]), uploads what may go and builds its
 * file event records. With no attachments it does nothing.
 */
suspend fun prepareAttachmentSend(
    metadata: SessionMetadata?,
    text: String,
    attachments: List<AttachmentPreview>,
    upload: suspend (List<AttachmentPreview>) -> AttachmentUploadResult,
): AttachmentSendPlan {
    val pick = planAttachmentSend(metadata, text, attachments)
    if (!pick.send || pick.accepted.isEmpty()) return AttachmentSendPlan(send = pick.send, records = emptyList(), notices = pick.notices)
    val result = upload(pick.accepted)
    val notices = pick.notices + listOfNotNull(if (result.failed > 0) AttachmentNotice.UploadFailed(result.failed) else null)
    return AttachmentSendPlan(send = true, records = fileEventRecords(result.uploaded), notices = notices)
}

/** readFileBytes for a picked image: the normalized JPEG in the cache. */
suspend fun readAttachmentBytes(preview: AttachmentPreview): ByteArray = withContext(Dispatchers.IO) {
    val path = Uri.parse(preview.uri).path ?: throw IOException("Not a file")
    File(path).readBytes()
}
