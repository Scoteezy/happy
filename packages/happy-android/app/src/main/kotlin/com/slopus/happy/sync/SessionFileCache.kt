package com.slopus.happy.sync

import com.slopus.happy.files.FileViewer
import com.slopus.happy.model.GitStatusFiles
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.max

/**
 * storage.ts `sessionFileCache` (happy-ios `SessionFileCache`): what each
 * file looked like when last read, so a revisit draws at once and refreshes
 * behind it. One per account ([AccountSync.fileCache]), never shared
 * between accounts.
 *
 * happy-app keeps it in memory only. Given a directory, this one also keeps
 * each read in the account's own files, so a file seen once shows again
 * after a relaunch, offline. Only files the person opened are kept: nothing
 * is fetched to fill it.
 *   - In memory, the most recently used [maxCount] reads, at most [maxCost]
 *     bytes of text; [get] reads only these, with no disk, so a screen can
 *     ask for it as it opens.
 *   - On disk, the most recently used `maxFiles`, at most `maxDiskBytes`;
 *     [snapshot] reads one off the main thread, once however many ask. A
 *     read bigger than `maxEntryBytes` is shown but never written, and a
 *     file on disk bigger than that is never read. At most
 *     [maxPendingReads] files are being read at once; a read asked for past
 *     that is a miss, and the screen asks the computer.
 *   - Writes and reads run one at a time on the cache's own thread, and a
 *     read sees every write asked for before it. Writes waiting for the
 *     thread are one per file, the newest; at most `maxPendingBytes` of
 *     text wait (older waiting writes give way, and their file's older copy
 *     goes, so it never comes back stale) and at most `maxPendingChanges`
 *     files or sessions (past that, the directory is emptied before what
 *     comes next is written).
 *   - A session's root is kept for the [maxRoots] most recently used.
 *   - [close] lets everything go; once it returns nothing more is read or
 *     written, so the account's directory can be removed.
 *
 * It also holds storage.ts `pathGitStatusFiles`, each checkout's last
 * `git status` file list, in memory as happy-app does.
 */
class SessionFileCache(
    directory: File? = null,
    maxCount: Int = DEFAULT_MAX_COUNT,
    maxCost: Int = DEFAULT_MAX_COST,
    maxFiles: Int = DEFAULT_MAX_FILES,
    maxDiskBytes: Long = DEFAULT_MAX_DISK_BYTES,
    maxEntryBytes: Int = DEFAULT_MAX_ENTRY_BYTES,
    maxPendingBytes: Int = DEFAULT_MAX_PENDING_BYTES,
    maxPendingChanges: Int = DEFAULT_MAX_PENDING_CHANGES,
    maxRoots: Int = DEFAULT_MAX_ROOTS,
    maxPendingReads: Int = DEFAULT_MAX_PENDING_READS,
) {
    /** A session's root as it was when one of its files was last shown. */
    data class Root(val path: String?)

    data class Key(val sessionId: String, val path: String)

    /** A checkout's last answer; [files] is null when `git status` failed. */
    data class StatusFiles(val files: GitStatusFiles?)

    private val lock = Any()
    private val memory = KeptFiles(maxCount, maxCost)
    private val roots = KeptRoots(maxRoots)
    private val reads = HashMap<Key, Deferred<FileViewer.Snapshot?>>()
    /** Keys set or removed while their disk read was under way: that read is older than what is kept now. */
    private val overtaken = HashSet<Key>()
    private var closed = false
    private val maxPendingReads = max(1, maxPendingReads)
    private val readScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    /** Callers that have waited on a disk read so far, joined or started; for tests that hold the disk. */
    private var diskWaits = 0
    internal val disk: FileCacheDisk? = directory?.let {
        FileCacheDisk(it, maxFiles, maxDiskBytes, maxEntryBytes, maxPendingBytes, maxPendingChanges)
    }

    private val statusFiles = ConcurrentHashMap<String, StatusFiles>()

    val isClosed: Boolean get() = synchronized(lock) { closed }

    // Roots

    /** The session's root when a file of it was last shown, so a revisit can find its cached copy before the store is read; null if none was. */
    fun root(sessionId: String): Root? = synchronized(lock) { if (closed) null else roots.get(sessionId) }

    fun setRoot(sessionId: String, root: Root) = synchronized(lock) {
        if (!closed) roots.set(sessionId, root)
    }

    // Reads

    /** The read kept in memory, which counts as a use. Never touches the disk, so the main thread may ask. */
    fun get(sessionId: String, path: String): FileViewer.Snapshot? =
        synchronized(lock) { if (closed) null else memory.get(Key(sessionId, path)) }

    /**
     * The read kept in memory, else on disk (read off the main thread, and
     * then kept in memory). Asks no computer for anything. With
     * [maxPendingReads] other files being read already, only memory.
     */
    suspend fun snapshot(sessionId: String, path: String): FileViewer.Snapshot? {
        val key = Key(sessionId, path)
        val pending = synchronized(lock) {
            if (closed || memory.contains(key)) return@synchronized null
            reads[key]?.let {
                diskWaits++
                return@synchronized it
            }
            val disk = disk
            if (disk == null || reads.size >= maxPendingReads) return@synchronized null
            diskWaits++
            readScope.async { finishRead(key, disk.read(key)) }.also { reads[key] = it }
        }
        return pending?.await() ?: get(sessionId, path)
    }

    private fun finishRead(key: Key, value: FileViewer.Snapshot?): FileViewer.Snapshot? = synchronized(lock) {
        reads.remove(key)
        if (closed) return null
        if (overtaken.remove(key)) return memory.get(key)
        value ?: return null
        memory.set(key, value)
        value
    }

    // Writes

    /** Keeps a fresh read, in memory now and on disk behind it. */
    fun set(sessionId: String, path: String, snapshot: FileViewer.Snapshot) {
        val key = Key(sessionId, path)
        val disk = synchronized(lock) {
            if (closed) return
            if (key in reads) overtaken += key
            memory.set(key, snapshot)
            disk
        }
        disk?.write(key, snapshot)
    }

    /** Forgets every read of a deleted session (storage.ts `deleteSession`); the checkouts' file lists stay. */
    fun removeSession(sessionId: String) {
        val disk = synchronized(lock) {
            if (closed) return
            reads.keys.filter { it.sessionId == sessionId }.forEach { overtaken += it }
            memory.removeAll { it.sessionId == sessionId }
            roots.remove(sessionId)
            disk
        }
        disk?.remove(sessionId)
    }

    // Letting go

    /** The account is going: nothing is kept or shown from now on, and once this returns nothing more is read or written. */
    suspend fun close() {
        val disk = synchronized(lock) {
            closed = true
            memory.removeAll { true }
            roots.removeAll()
            overtaken.clear()
            disk
        }
        statusFiles.clear()
        disk?.close()
    }

    // The checkouts' file lists

    /** `useSessionGitStatusFiles`: null until the checkout's first answer. */
    fun statusFiles(pathKey: String): StatusFiles? = statusFiles[pathKey]

    /** storage.ts `applyGitStatusFiles`. */
    fun putStatusFiles(pathKey: String, value: GitStatusFiles?) {
        statusFiles[pathKey] = StatusFiles(value)
    }

    // For tests

    internal val keptCount: Int get() = synchronized(lock) { memory.count }
    internal val rootCount: Int get() = synchronized(lock) { roots.count }
    internal val pendingReadCount: Int get() = synchronized(lock) { reads.size }
    internal val diskWaitCount: Int get() = synchronized(lock) { diskWaits }

    companion object {
        const val DEFAULT_MAX_COUNT = 32
        const val DEFAULT_MAX_COST = 8 * 1024 * 1024
        const val DEFAULT_MAX_FILES = 256
        const val DEFAULT_MAX_DISK_BYTES = 64L * 1024 * 1024
        const val DEFAULT_MAX_ENTRY_BYTES = 4 * 1024 * 1024
        const val DEFAULT_MAX_PENDING_BYTES = 8 * 1024 * 1024
        const val DEFAULT_MAX_PENDING_CHANGES = 64
        const val DEFAULT_MAX_ROOTS = 256
        const val DEFAULT_MAX_PENDING_READS = 8

        /** The cache's directory inside an account's. */
        fun directory(inAccount: File) = File(inAccount, "file-cache")

        /**
         * Makes the directory, off the main thread, while the account is
         * made ready, after the account's own directory; nothing if it is
         * already there. The cache never makes it itself: without it nothing
         * is written, so a signed-out account's directory is never made again.
         */
        fun prepare(directory: File) {
            if (directory.isDirectory) return
            if (!directory.mkdir()) throw java.io.IOException("can't make $directory")
        }
    }
}

/** Sessions' roots, least recently used let go first. */
private class KeptRoots(maxCount: Int) {
    private val maxCount = max(0, maxCount)
    private val entries = LinkedHashMap<String, SessionFileCache.Root>(16, 0.75f, true)

    val count get() = entries.size

    fun get(sessionId: String) = entries[sessionId]

    fun set(sessionId: String, root: SessionFileCache.Root) {
        entries.remove(sessionId)
        entries[sessionId] = root
        while (entries.size > maxCount) entries.remove(entries.keys.first())
    }

    fun remove(sessionId: String) = entries.remove(sessionId)

    fun removeAll() = entries.clear()
}

/** Reads by key, least recently used let go first. */
private class KeptFiles(private val maxCount: Int, private val maxCost: Int) {
    private class Entry(val snapshot: FileViewer.Snapshot, val cost: Int)

    private val entries = LinkedHashMap<SessionFileCache.Key, Entry>(16, 0.75f, true)
    private var cost = 0

    val count get() = entries.size

    fun contains(key: SessionFileCache.Key) = entries.containsKey(key)

    fun get(key: SessionFileCache.Key) = entries[key]?.snapshot

    fun set(key: SessionFileCache.Key, snapshot: FileViewer.Snapshot) {
        entries.remove(key)?.let { cost -= it.cost }
        val size = FileCacheDisk.cost(snapshot)
        // One read past the whole budget is shown, not kept.
        if (size > maxCost) return
        entries[key] = Entry(snapshot, size)
        cost += size
        while (entries.size > maxCount || cost > maxCost) {
            val oldest = entries.keys.firstOrNull() ?: break
            cost -= entries.remove(oldest)!!.cost
        }
    }

    fun removeAll(match: (SessionFileCache.Key) -> Boolean) {
        val iterator = entries.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (match(entry.key)) {
                cost -= entry.value.cost
                iterator.remove()
            }
        }
    }
}

/**
 * What the disk thread has yet to do, coalesced and bounded: one change
 * per file, the newest; a deleted session's files; or, past [maxChanges],
 * emptying the directory. Done in that order (empty, sessions, files),
 * which keeps the order they were asked in: a change asked for before a
 * session went or the directory was emptied was dropped then.
 */
internal class PendingDiskWork(maxBytes: Int, maxChanges: Int) {
    sealed interface Change {
        data class Write(val snapshot: FileViewer.Snapshot, val cost: Int) : Change
        /** The file goes: its newest read can't be written, and an older copy would come back stale. */
        data object Remove : Change
    }

    sealed interface Work {
        data object Clear : Work
        data class RemoveSession(val sessionId: String) : Work
        data class Changed(val key: SessionFileCache.Key, val change: Change) : Work
    }

    private val maxBytes = max(0, maxBytes)
    private val maxChanges = max(1, maxChanges)
    private var clearsAll = false
    private val removedSessions = LinkedHashSet<String>()
    private val changes = HashMap<SessionFileCache.Key, Pair<Change, Long>>()
    private var order = 0L
    /** The text the waiting writes hold. */
    var bytes = 0
        private set

    val count get() = changes.size + removedSessions.size
    val isEmpty get() = !clearsAll && count == 0

    fun set(key: SessionFileCache.Key, change: Change) {
        drop(key)
        if (count >= maxChanges) clearAll()
        changes[key] = change to ++order
        if (change is Change.Write) bytes += change.cost
        // The oldest waiting writes give way, the newest last.
        while (bytes > maxBytes) {
            val oldest = changes.entries.filter { it.value.first is Change.Write }.minByOrNull { it.value.second } ?: break
            val at = oldest.value.second
            drop(oldest.key)
            changes[oldest.key] = Change.Remove to at
        }
    }

    fun removeSession(sessionId: String) {
        changes.keys.filter { it.sessionId == sessionId }.forEach(::drop)
        // Emptying the directory takes the session's files with it.
        if (clearsAll) return
        if (count >= maxChanges) {
            clearAll()
            return
        }
        removedSessions += sessionId
    }

    fun next(): Work? {
        if (clearsAll) {
            clearsAll = false
            return Work.Clear
        }
        removedSessions.firstOrNull()?.let {
            removedSessions.remove(it)
            return Work.RemoveSession(it)
        }
        val first = changes.entries.minByOrNull { it.value.second } ?: return null
        val key = first.key
        val change = first.value.first
        drop(key)
        return Work.Changed(key, change)
    }

    /** Everything waiting goes, and the directory is emptied first. */
    private fun clearAll() {
        clearsAll = true
        changes.clear()
        removedSessions.clear()
        bytes = 0
    }

    private fun drop(key: SessionFileCache.Key) {
        val previous = changes.remove(key) ?: return
        (previous.first as? Change.Write)?.let { bytes -= it.cost }
    }
}

/**
 * The account's file cache directory, touched only on its own thread, off
 * the main thread. What waits for the thread is [PendingDiskWork], bounded;
 * the thread takes it one change at a time.
 */
internal class FileCacheDisk(
    val directory: File,
    private val maxFiles: Int,
    private val maxBytes: Long,
    private val maxEntryBytes: Int,
    maxPendingBytes: Int,
    maxPendingChanges: Int,
) {
    @Serializable
    private data class Entry(
        val version: Int,
        val sessionId: String,
        val path: String,
        val content: String,
        val isBinary: Boolean,
        val diff: String? = null,
    )

    private val thread: ExecutorService = Executors.newSingleThreadExecutor { Thread(it, "SessionFileCache").apply { isDaemon = true } }
    private val lock = Any()
    private val pending = PendingDiskWork(maxPendingBytes, maxPendingChanges)
    private var flushQueued = false
    private var accepting = true
    /** Confined to [thread]. */
    private var closed = false
    /** Reads and writes done, for tests; confined to [thread]. */
    private var readCount = 0
    private var writeCount = 0

    fun read(key: SessionFileCache.Key): FileViewer.Snapshot? = onThread { readNow(key) }

    fun write(key: SessionFileCache.Key, snapshot: FileViewer.Snapshot) {
        val cost = cost(snapshot)
        // Too big to keep: written as a removal, so an older copy goes.
        val change = if (cost <= maxEntryBytes) PendingDiskWork.Change.Write(snapshot, cost) else PendingDiskWork.Change.Remove
        enqueue { it.set(key, change) }
    }

    fun remove(sessionId: String) = enqueue { it.removeSession(sessionId) }

    private fun enqueue(change: (PendingDiskWork) -> Unit) = synchronized(lock) {
        if (!accepting) return
        change(pending)
        if (flushQueued) return
        flushQueued = true
        thread.execute(::flush)
    }

    /** Writes everything asked for before, then nothing more. */
    suspend fun close() {
        synchronized(lock) { accepting = false }
        val done = CompletableDeferred<Unit>()
        thread.execute {
            flush()
            closed = true
            done.complete(Unit)
        }
        done.await()
        thread.shutdown()
    }

    /** Everything asked for so far has been done. */
    fun drain() = onThread { }

    /** Stops the thread until the returned function is called. */
    fun hold(): () -> Unit {
        val gate = CountDownLatch(1)
        thread.execute { gate.await() }
        return { gate.countDown() }
    }

    val reads: Int get() = onThread { readCount }
    val writes: Int get() = onThread { writeCount }

    /** Whether it still takes writes; false once [close] has begun. */
    val isAccepting: Boolean get() = synchronized(lock) { accepting }

    /** What waits for the thread now: files and sessions, and the text held. */
    val waiting: Pair<Int, Int> get() = synchronized(lock) { pending.count to pending.bytes }

    private fun <T> onThread(block: () -> T): T = thread.submit(block).get()

    // On the thread

    /** Does what waits, one change at a time, so whatever is asked for meanwhile still coalesces; then prunes once. */
    private fun flush() {
        var wrote = false
        while (true) {
            val work = synchronized(lock) {
                pending.next() ?: run {
                    flushQueued = false
                    null
                }
            } ?: break
            if (closed) continue
            when (work) {
                PendingDiskWork.Work.Clear -> directory.listFiles()?.forEach { it.delete() }
                is PendingDiskWork.Work.RemoveSession -> {
                    val prefix = prefix(work.sessionId)
                    directory.listFiles()?.filter { it.name.startsWith(prefix) }?.forEach { it.delete() }
                }
                is PendingDiskWork.Work.Changed -> when (val change = work.change) {
                    PendingDiskWork.Change.Remove -> File(directory, name(work.key)).delete()
                    is PendingDiskWork.Change.Write -> if (writeNow(work.key, change.snapshot)) wrote = true
                }
            }
        }
        if (wrote) prune()
    }

    private fun readNow(key: SessionFileCache.Key): FileViewer.Snapshot? {
        if (closed) return null
        readCount++
        val file = File(directory, name(key))
        if (!file.isFile) return null
        if (file.length() > maxEntryBytes) {
            // Never written this big: not this cache's.
            file.delete()
            return null
        }
        val entry = try {
            json.decodeFromString(Entry.serializer(), file.readText())
        } catch (_: Exception) {
            null
        }
        if (entry == null || entry.version != VERSION || entry.sessionId != key.sessionId || entry.path != key.path) {
            // Unreadable, from another version, or not this file.
            file.delete()
            return null
        }
        // Recently used, for pruning.
        file.setLastModified(System.currentTimeMillis())
        return FileViewer.Snapshot(FileViewer.Content(entry.content, entry.isBinary), entry.diff)
    }

    private fun writeNow(key: SessionFileCache.Key, snapshot: FileViewer.Snapshot): Boolean {
        val file = File(directory, name(key))
        val entry = Entry(VERSION, key.sessionId, key.path, snapshot.content.content, snapshot.content.isBinary, snapshot.diff)
        val data = json.encodeToString(Entry.serializer(), entry).toByteArray()
        if (data.size > maxEntryBytes) {
            // Too big to keep: an older copy would come back stale.
            file.delete()
            return false
        }
        // Never made here: a directory that is gone (the account signed out) stays gone, and nothing is written.
        if (!directory.isDirectory) return false
        val temporary = File(directory, ".${file.name}.tmp")
        try {
            temporary.writeBytes(data)
            if (!temporary.renameTo(file)) {
                temporary.delete()
                return false
            }
        } catch (_: Exception) {
            temporary.delete()
            return false
        }
        writeCount++
        return true
    }

    /** Keeps the most recently used, within [maxFiles] and [maxBytes]. */
    private fun prune() {
        val files = directory.listFiles()?.filter { it.isFile && !it.name.startsWith(".") } ?: return
        var total = files.sumOf { it.length() }
        var count = files.size
        if (count <= maxFiles && total <= maxBytes) return
        for (file in files.sortedBy { it.lastModified() }) {
            if (count <= maxFiles && total <= maxBytes) break
            total -= file.length()
            file.delete()
            count--
        }
    }

    companion object {
        private const val VERSION = 1
        private val json = Json { ignoreUnknownKeys = false }

        /** A file's name: the session's and the path's SHA-256, so the name says nothing of either, and a session's files share a prefix. */
        fun name(key: SessionFileCache.Key) = prefix(key.sessionId) + hex(sha256(key.path)) + ".json"

        private fun prefix(sessionId: String) = hex(sha256(sessionId)) + "-"

        private fun sha256(text: String) = MessageDigest.getInstance("SHA-256").digest(text.toByteArray())

        private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }

        /** The text a read holds, as the memory budget counts it. */
        fun cost(snapshot: FileViewer.Snapshot) = snapshot.content.content.toByteArray().size + (snapshot.diff?.toByteArray()?.size ?: 0)
    }
}
