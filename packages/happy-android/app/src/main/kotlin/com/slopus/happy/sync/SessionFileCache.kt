package com.slopus.happy.sync

import com.slopus.happy.model.GitStatusFiles
import java.util.concurrent.ConcurrentHashMap

/**
 * storage.ts `sessionFileCache` and `pathGitStatusFiles`, in memory for the
 * account's life (happy-app doesn't persist either): the file viewer's last
 * read of each file, and each checkout's last `git status` file list, so a
 * screen shows them at once while it fetches again. A deleted session's
 * files go with it (storage.ts `deleteSession`); the file lists are keyed by
 * checkout, not session, and stay.
 */
class SessionFileCache {
    /** storage.ts `sessionFileCache[sessionId][filePath]`. */
    data class File(val content: String?, val diff: String?, val isBinary: Boolean, val cachedAt: Long)

    /** A checkout's last answer; [files] is null when `git status` failed. */
    data class StatusFiles(val files: GitStatusFiles?)

    /** A session's root as it was when one of its files was last shown; [path] null when it had none. */
    data class Root(val path: String?)

    private val files = ConcurrentHashMap<String, ConcurrentHashMap<String, File>>()
    private val roots = ConcurrentHashMap<String, Root>()
    private val statusFiles = ConcurrentHashMap<String, StatusFiles>()

    fun file(sessionId: String, filePath: String): File? = files[sessionId]?.get(filePath)

    /** The session's root when a file of it was last shown, so a revisit finds its cached copy before the store is read; null if none was. */
    fun root(sessionId: String): Root? = roots[sessionId]

    fun setRoot(sessionId: String, root: Root) {
        roots[sessionId] = root
    }

    /** storage.ts `applyFileCache`. */
    fun putFile(sessionId: String, filePath: String, content: String?, diff: String?, isBinary: Boolean, now: Long = System.currentTimeMillis()) {
        files.getOrPut(sessionId) { ConcurrentHashMap() }[filePath] = File(content, diff, isBinary, now)
    }

    /** `useSessionGitStatusFiles`: null until the checkout's first answer. */
    fun statusFiles(pathKey: String): StatusFiles? = statusFiles[pathKey]

    /** storage.ts `applyGitStatusFiles`. */
    fun putStatusFiles(pathKey: String, value: GitStatusFiles?) {
        statusFiles[pathKey] = StatusFiles(value)
    }

    fun forgetSession(sessionId: String) {
        files.remove(sessionId)
        roots.remove(sessionId)
    }
}
