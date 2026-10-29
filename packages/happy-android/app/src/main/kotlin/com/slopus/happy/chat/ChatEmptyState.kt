package com.slopus.happy.chat

import com.slopus.happy.model.SessionMetadata
import com.slopus.happy.model.details.DuplicateSheetModel
import com.slopus.happy.model.formatPathRelativeToHome

/**
 * EmptyMessages.tsx (happy-ios `ChatEmptyState`): an empty chat names the
 * machine and folder it runs in and when it was created.
 */
data class ChatEmptyState(
    /** An Ionicons name for the machine's OS. */
    val osIcon: String,
    val host: String?,
    /** The folder, with the home directory as `~`. */
    val path: String?,
    val created: DuplicateSheetModel.Age,
) {
    companion object {
        fun of(metadata: SessionMetadata?, createdAt: Long, now: Long) = ChatEmptyState(
            osIcon = osIcon(metadata?.os),
            host = metadata?.host?.takeIf { it.isNotEmpty() },
            path = metadata?.path?.takeIf { it.isNotEmpty() }?.let { formatPathRelativeToHome(it, metadata?.homeDir) },
            created = DuplicateSheetModel.age(createdAt.toDouble(), now.toDouble()),
        )

        /** `getOSIcon`. */
        fun osIcon(os: String?): String {
            if (os.isNullOrEmpty()) return "hardware-chip-outline"
            val lower = os.lowercase()
            return when {
                "darwin" in lower || "mac" in lower -> "laptop-outline"
                "win" in lower -> "desktop-outline"
                "linux" in lower -> "terminal-outline"
                else -> "hardware-chip-outline"
            }
        }
    }
}

/**
 * PendingChatPlaceholder.tsx (happy-ios `PendingChatPlaceholderModel`): under
 * "Starting a new chat…", the messages sent meanwhile, or else the folder
 * the chat will run in.
 */
sealed interface PendingChatDetail {
    /** The queued messages, a blank line between them. */
    data class Queued(val text: String) : PendingChatDetail
    /** The anchor's folder, with the home directory as `~`. */
    data class Path(val path: String) : PendingChatDetail

    companion object {
        fun of(queued: List<String>, anchor: SessionMetadata?): PendingChatDetail? = when {
            queued.isNotEmpty() -> Queued(queued.joinToString("\n\n"))
            anchor != null && anchor.path.isNotEmpty() -> Path(formatPathRelativeToHome(anchor.path, anchor.homeDir))
            else -> null
        }
    }
}
