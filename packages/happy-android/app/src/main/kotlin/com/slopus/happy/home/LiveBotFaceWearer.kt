package com.slopus.happy.home

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Three independently reported steps, pinned to the account that created the bot. */
class LiveBotFaceWearer(
    private val paint: suspend (seed: String) -> ByteArray,
    private val upload: suspend (sessionId: String, bytes: ByteArray) -> String,
    private val setAvatar: suspend (sessionId: String, ref: String, size: Long, mimeType: String) -> Unit,
    private val isCurrent: () -> Boolean,
) : BotFaceWearer {
    override suspend fun wear(sessionId: String, seed: String): BotFaceFailure? {
        suspend fun guard() {
            currentCoroutineContext().ensureActive()
            if (!isCurrent()) throw CancellationException("Bot face account changed")
        }
        var leg = BotFaceLeg.Painting
        try {
            guard()
            val bytes = paint(seed)
            guard()
            leg = BotFaceLeg.Uploading
            val ref = upload(sessionId, bytes)
            guard()
            leg = BotFaceLeg.Wearing
            setAvatar(sessionId, ref, bytes.size.toLong(), "image/png")
            guard()
            return null
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            guard()
            val label = when (leg) {
                BotFaceLeg.Painting -> "painting the face"
                BotFaceLeg.Uploading -> "uploading the face"
                BotFaceLeg.Wearing -> "asking the bot to wear it"
            }
            return BotFaceFailure(leg, "$label: ${error.message ?: "Unknown error"}")
        }
    }
}

/** Retains only the original session/seed/account operation; retry never spawns another bot. */
class BotFaceRetry(
    private val sessionId: String,
    private val seed: String,
    private val wearer: BotFaceWearer,
    val isCurrent: () -> Boolean,
) {
    suspend fun attempt(): BotFaceFailure? {
        currentCoroutineContext().ensureActive()
        if (!isCurrent()) throw CancellationException("Bot face account changed")
        val failure = wearer.wear(sessionId, seed)
        currentCoroutineContext().ensureActive()
        if (!isCurrent()) throw CancellationException("Bot face account changed")
        return failure
    }
}