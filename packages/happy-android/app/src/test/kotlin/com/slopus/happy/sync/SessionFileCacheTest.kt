package com.slopus.happy.sync

import com.slopus.happy.files.FileViewer
import com.slopus.happy.files.SessionFileOps
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.Base64

// Ported from happy-ios Tests/HappyCoreTests/SessionFileCacheTests.swift.

/**
 * The file viewer's cache kept in the account's own files: a file seen
 * once shows again after a relaunch, offline, and never in another account.
 */
class SessionFileCacheTest {
    private lateinit var root: File

    @Before fun setUp() {
        root = Files.createTempDirectory("file-cache").toFile()
    }

    @After fun tearDown() {
        root.deleteRecursively()
    }

    /** An account's cache directory, made ready as the account starts. */
    private fun account(id: String): File {
        val account = File(root, id)
        account.mkdirs()
        return SessionFileCache.directory(account).also(SessionFileCache::prepare)
    }

    private fun snapshot(text: String, diff: String? = null) = FileViewer.Snapshot(FileViewer.Content(text, false), diff)

    private fun files(directory: File) = directory.list()?.sorted() ?: emptyList()

    private val path = "/repo/src/a.swift"

    /** Waits until [condition] holds: a state the code under test reaches on another thread, polled rather than slept for. */
    private suspend fun until(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 10_000
        while (!condition()) {
            if (System.currentTimeMillis() > deadline) fail("never happened")
            delay(1)
        }
    }

    private fun test(block: suspend () -> Unit) = runBlocking { withContext(Dispatchers.IO) { block() } }

    @Test fun aSeenFileShowsAfterARelaunch() = test {
        val directory = account("one")
        val before = SessionFileCache(directory)
        before.set("s", path, snapshot("let a = 1\n", "+let a = 1\n"))
        before.close()

        val after = SessionFileCache(directory)
        // Opening the screen reads only memory: nothing yet.
        assertNull(after.get("s", path))
        val kept = after.snapshot("s", path)
        assertEquals(snapshot("let a = 1\n", "+let a = 1\n"), kept)
        // Kept in memory from then on.
        assertEquals(kept, after.get("s", path))
        assertNull(after.get("s", "/repo/src/b.swift"))
        assertNull(after.snapshot("s", "/repo/src/b.swift"))
        // The name says nothing of the session or the path.
        assertEquals(1, files(directory).size)
        assertFalse(files(directory)[0].contains("repo"))
    }

    @Test fun theNewestReadWins() = test {
        val directory = account("one")
        val cache = SessionFileCache(directory)
        cache.set("s", path, snapshot("v1"))
        cache.set("s", path, snapshot("v2", "+v2"))
        cache.close()
        assertEquals(snapshot("v2", "+v2"), SessionFileCache(directory).snapshot("s", path))
        assertEquals(1, files(directory).size)
    }

    @Test fun accountsNeverSeeEachOthersFiles() = test {
        val first = SessionFileCache(account("one"))
        val second = SessionFileCache(account("two"))
        first.set("s", path, snapshot("secret"))
        first.disk!!.drain()
        // The same session id and path in another account.
        assertNull(second.get("s", path))
        assertNull(second.snapshot("s", path))
        // And after a relaunch.
        assertNull(SessionFileCache(account("two")).snapshot("s", path))
    }

    private class Ops(private val online: Boolean, private val text: String = "") : SessionFileOps {
        override suspend fun bash(sessionId: String, command: String, cwd: String?, timeoutMs: Long): BashResult =
            if (online) BashResult(true, "", "", 0) else throw IllegalStateException("no answer")
        override suspend fun readFile(sessionId: String, path: String): ReadFileResult =
            if (online) ReadFileResult(true, Base64.getEncoder().encodeToString(text.toByteArray())) else throw IllegalStateException("no answer")
        override suspend fun gitState(sessionId: String): GitState = throw UnsupportedOperationException()
        override suspend fun readGitFile(sessionId: String, base: String, file: GitFile): GitFileContent = throw UnsupportedOperationException()
    }

    @Test fun aFailedReadKeepsTheKeptCopy() = test {
        val directory = account("one")
        val seeded = SessionFileCache(directory)
        seeded.set("s", path, snapshot("kept"))
        seeded.close()

        // Offline after a relaunch: the copy shows, the read fails.
        val cache = SessionFileCache(directory)
        assertEquals(snapshot("kept"), cache.snapshot("s", path))
        val target = FileViewer.Target("src/a.swift", "/repo")
        val failed = FileViewer.load(target, "/repo", Ops(online = false), "s", cache)
        assertEquals(FileViewer.LoadResult.Failed(FileViewer.Failure.Load, null), failed)
        assertEquals(snapshot("kept"), cache.get("s", path))
        cache.close()
        assertEquals(snapshot("kept"), SessionFileCache(directory).snapshot("s", path))

        // Back online, the fresh read replaces it.
        val cache3 = SessionFileCache(directory)
        val loaded = FileViewer.load(target, "/repo", Ops(online = true, text = "fresh"), "s", cache3)
        assertEquals(FileViewer.LoadResult.Loaded(snapshot("fresh")), loaded)
        cache3.close()
        assertEquals(snapshot("fresh"), SessionFileCache(directory).snapshot("s", path))
    }

    @Test fun oneDiskReadHoweverManyAsk() = test {
        val directory = account("one")
        val seeded = SessionFileCache(directory)
        seeded.set("s", path, snapshot("x"))
        seeded.close()
        val cache = SessionFileCache(directory)
        val disk = cache.disk!!
        val release = disk.hold()
        runBlocking(Dispatchers.IO) {
            val first = async { cache.snapshot("s", path) }
            val second = async { cache.snapshot("s", path) }
            // Both wait on the held disk before it goes on.
            until { cache.diskWaitCount == 2 }
            assertEquals(1, cache.pendingReadCount)
            release()
            assertEquals(snapshot("x"), first.await())
            assertEquals(snapshot("x"), second.await())
        }
        assertEquals(1, disk.reads)
        // Then from memory.
        cache.snapshot("s", path)
        assertEquals(1, disk.reads)
    }

    @Test fun aFreshReadOvertakesADiskReadUnderWay() = test {
        val directory = account("one")
        val seeded = SessionFileCache(directory)
        seeded.set("s", path, snapshot("old"))
        seeded.close()
        val cache = SessionFileCache(directory)
        val release = cache.disk!!.hold()
        runBlocking(Dispatchers.IO) {
            val reading = async { cache.snapshot("s", path) }
            until { cache.pendingReadCount == 1 }
            cache.set("s", path, snapshot("new"))
            release()
            assertEquals(snapshot("new"), reading.await())
        }
        assertEquals(snapshot("new"), cache.get("s", path))
        cache.close()
        assertEquals(snapshot("new"), SessionFileCache(directory).snapshot("s", path))
    }

    @Test fun aClosedCacheReadsAndWritesNothing() = test {
        val directory = account("one")
        val cache = SessionFileCache(directory)
        cache.set("s", path, snapshot("x"))
        cache.setRoot("s", SessionFileCache.Root("/repo"))
        cache.close()
        assertTrue(cache.isClosed)
        assertNull(cache.get("s", path))
        assertNull(cache.root("s"))
        assertNull(cache.snapshot("s", path))
        // The account directory is removed; nothing writes it back.
        directory.parentFile!!.deleteRecursively()
        cache.set("s", "/repo/b.swift", snapshot("late"))
        assertFalse(directory.parentFile!!.exists())
    }

    @Test fun preparingIsSafeEveryLaunchButNeedsTheAccount() {
        val account = File(root, "again")
        val directory = SessionFileCache.directory(account)
        assertEquals("file-cache", directory.name)
        // Without the account's directory: nothing is made.
        try {
            SessionFileCache.prepare(directory)
            fail("prepared without the account's directory")
        } catch (_: java.io.IOException) {
        }
        assertFalse(account.exists())
        account.mkdirs()
        SessionFileCache.prepare(directory)
        // The next launch.
        SessionFileCache.prepare(directory)
        assertTrue(directory.isDirectory)
    }

    @Test fun anUnpreparedDirectoryIsNeverMade() = test {
        val account = File(root, "fresh").also { it.mkdirs() }
        val directory = SessionFileCache.directory(account)
        val cache = SessionFileCache(directory)
        cache.set("s", path, snapshot("x"))
        cache.disk!!.drain()
        assertFalse(directory.exists())
        assertEquals(snapshot("x"), cache.get("s", path))
    }

    @Test fun aGoneAccountDirectoryStaysGone() = test {
        val directory = account("one")
        directory.parentFile!!.deleteRecursively()
        val cache = SessionFileCache(directory)
        cache.set("s", path, snapshot("x"))
        cache.disk!!.drain()
        assertFalse(directory.parentFile!!.exists())
        // Still shown from memory.
        assertEquals(snapshot("x"), cache.get("s", path))
    }

    @Test fun boundedInMemoryAndOnDisk() = test {
        val directory = account("one")
        val cache = SessionFileCache(directory, maxCount = 2, maxCost = 1_000, maxFiles = 2, maxDiskBytes = 100_000, maxEntryBytes = 600)
        for ((index, name) in listOf("a", "b", "c").withIndex()) {
            cache.set("s", "/repo/$name", snapshot(name))
            cache.disk!!.drain()
            // Used in this order, whatever the clock's resolution.
            File(directory, FileCacheDisk.name(SessionFileCache.Key("s", "/repo/$name"))).setLastModified((index + 1) * 1000L)
        }
        assertEquals(2, cache.keptCount)
        assertNull(cache.get("s", "/repo/a"))
        assertEquals(2, files(directory).size)
        cache.close()
        val reopened = SessionFileCache(directory)
        assertNull(reopened.snapshot("s", "/repo/a"))
        assertEquals(snapshot("c"), reopened.snapshot("s", "/repo/c"))

        // Bigger than one entry may be: shown and kept in memory, never written, and the older copy on disk goes.
        val big = "x".repeat(700)
        val cache2 = SessionFileCache(directory, maxEntryBytes = 600)
        cache2.set("s", "/repo/c", snapshot(big))
        cache2.close()
        assertNull(SessionFileCache(directory).snapshot("s", "/repo/c"))
        // Bigger than the whole memory budget: not kept at all.
        val small = SessionFileCache(null, maxCost = 10)
        small.set("s", path, snapshot(big))
        assertNull(small.get("s", path))
    }

    @Test fun aDeletedSessionsFilesGo() = test {
        val directory = account("one")
        val cache = SessionFileCache(directory)
        cache.set("s", "/repo/a", snapshot("a"))
        cache.set("s", "/repo/b", snapshot("b"))
        cache.set("t", "/repo/a", snapshot("t"))
        cache.removeSession("s")
        assertNull(cache.get("s", "/repo/a"))
        assertEquals(snapshot("t"), cache.get("t", "/repo/a"))
        cache.close()
        assertEquals(1, files(directory).size)
        val reopened = SessionFileCache(directory)
        assertNull(reopened.snapshot("s", "/repo/b"))
        assertEquals(snapshot("t"), reopened.snapshot("t", "/repo/a"))
    }

    // What waits for the disk

    private fun cost(snapshot: FileViewer.Snapshot) = FileCacheDisk.cost(snapshot)

    /** Writes a copy of each path, as an earlier launch did. */
    private suspend fun seed(directory: File, keys: List<Pair<String, String>>, text: String = "old") {
        val seeded = SessionFileCache(directory)
        for ((sessionId, path) in keys) seeded.set(sessionId, path, snapshot(text))
        seeded.close()
    }

    @Test fun aHeldWriterKeepsOneWriteAFileTheNewest() = test {
        val directory = account("one")
        val cache = SessionFileCache(directory)
        val disk = cache.disk!!
        val release = disk.hold()
        for (version in 1..50) cache.set("s", path, snapshot("v$version"))
        assertEquals(1 to cost(snapshot("v50")), disk.waiting)
        release()
        disk.drain()
        assertEquals(0 to 0, disk.waiting)
        assertEquals(1, disk.writes)
        cache.close()
        assertEquals(snapshot("v50"), SessionFileCache(directory).snapshot("s", path))
    }

    @Test fun aHeldWriterHoldsNoMoreTextThanItsBudgetAndNothingComesBackStale() = test {
        val directory = account("one")
        val names = listOf("a", "b", "c", "d")
        seed(directory, names.map { "s" to "/repo/$it" })
        val cache = SessionFileCache(directory, maxPendingBytes = 2_500)
        val disk = cache.disk!!
        val release = disk.hold()
        for (name in names) {
            cache.set("s", "/repo/$name", snapshot(name.repeat(1_000)))
            assertTrue(disk.waiting.second <= 2_500)
        }
        // Two writes wait; the two older gave way to removals.
        assertEquals(4 to 2_000, disk.waiting)
        // Every read still shows from memory.
        for (name in names) assertEquals(snapshot(name.repeat(1_000)), cache.get("s", "/repo/$name"))
        release()
        disk.drain()
        assertEquals(2, disk.writes)
        cache.close()
        val reopened = SessionFileCache(directory)
        // The old copy went with the write that gave way.
        for (name in listOf("a", "b")) assertNull(reopened.snapshot("s", "/repo/$name"))
        for (name in listOf("c", "d")) assertEquals(snapshot(name.repeat(1_000)), reopened.snapshot("s", "/repo/$name"))
    }

    @Test fun tooManyWaitingChangesEmptyTheDirectoryFirst() = test {
        val directory = account("one")
        seed(directory, listOf("z" to "/repo/z"))
        val cache = SessionFileCache(directory, maxPendingChanges = 3)
        val disk = cache.disk!!
        val release = disk.hold()
        for (index in 1..5) {
            cache.set("s", "/repo/$index", snapshot("$index"))
            assertTrue(disk.waiting.first <= 3)
        }
        cache.removeSession("z")
        assertEquals(2, disk.waiting.first)
        release()
        disk.drain()
        cache.close()
        assertEquals(2, files(directory).size)
        val reopened = SessionFileCache(directory)
        for (index in 1..3) assertNull(reopened.snapshot("s", "/repo/$index"))
        for (index in 4..5) assertEquals(snapshot("$index"), reopened.snapshot("s", "/repo/$index"))
        assertNull(reopened.snapshot("z", "/repo/z"))
    }

    @Test fun aSessionDeletedWhileTheWriterIsHeldDropsItsWaitingWrites() = test {
        val directory = account("one")
        seed(directory, listOf("s" to "/repo/old"))
        val cache = SessionFileCache(directory)
        val disk = cache.disk!!
        val release = disk.hold()
        cache.set("s", "/repo/a", snapshot("sa"))
        cache.set("t", "/repo/a", snapshot("ta"))
        cache.set("s", "/repo/b", snapshot("sb"))
        cache.removeSession("s")
        // t's write and s's removal; s's writes are let go at once.
        assertEquals(2 to cost(snapshot("ta")), disk.waiting)
        // Read again after the deletion: kept.
        cache.set("s", "/repo/c", snapshot("sc"))
        release()
        disk.drain()
        cache.close()
        val reopened = SessionFileCache(directory)
        for (path in listOf("/repo/old", "/repo/a", "/repo/b")) assertNull(reopened.snapshot("s", path))
        assertEquals(snapshot("sc"), reopened.snapshot("s", "/repo/c"))
        assertEquals(snapshot("ta"), reopened.snapshot("t", "/repo/a"))
    }

    @Test fun closingWritesWhatWaitsThenNothing() = test {
        val directory = account("one")
        val cache = SessionFileCache(directory)
        val disk = cache.disk!!
        val release = disk.hold()
        cache.set("s", "/repo/a", snapshot("a"))
        runBlocking(Dispatchers.IO) {
            val closing = async { cache.close() }
            until { !disk.isAccepting }
            // Asked for after closing began.
            cache.set("s", "/repo/b", snapshot("b"))
            disk.write(SessionFileCache.Key("s", "/repo/c"), snapshot("c"))
            assertEquals(1, disk.waiting.first)
            release()
            closing.await()
        }
        assertEquals(0, disk.waiting.first)
        assertEquals(1, files(directory).size)
        assertEquals(snapshot("a"), SessionFileCache(directory).snapshot("s", "/repo/a"))
    }

    @Test fun waitingWorkIsDoneInTheOrderAsked() {
        val a = SessionFileCache.Key("s", "/a")
        val b = SessionFileCache.Key("s", "/b")
        val t = SessionFileCache.Key("t", "/a")
        val work = PendingDiskWork(maxBytes = 100, maxChanges = 8)
        work.set(a, PendingDiskWork.Change.Write(snapshot("1"), 1))
        work.set(t, PendingDiskWork.Change.Write(snapshot("2"), 1))
        work.removeSession("s")
        work.set(b, PendingDiskWork.Change.Write(snapshot("3"), 1))
        work.set(t, PendingDiskWork.Change.Write(snapshot("4"), 1))
        assertEquals(2, work.bytes)
        val done = generateSequence { work.next() }.toList()
        assertEquals(
            listOf(
                PendingDiskWork.Work.RemoveSession("s"),
                PendingDiskWork.Work.Changed(b, PendingDiskWork.Change.Write(snapshot("3"), 1)),
                PendingDiskWork.Work.Changed(t, PendingDiskWork.Change.Write(snapshot("4"), 1)),
            ),
            done,
        )
        assertTrue(work.isEmpty)
        assertEquals(0, work.bytes)
    }

    @Test fun rootsKeepTheMostRecentlyUsed() {
        val cache = SessionFileCache(null, maxRoots = 2)
        cache.setRoot("one", SessionFileCache.Root("/1"))
        cache.setRoot("two", SessionFileCache.Root("/2"))
        // Asking counts as a use.
        assertEquals(SessionFileCache.Root("/1"), cache.root("one"))
        cache.setRoot("three", SessionFileCache.Root("/3"))
        assertEquals(2, cache.rootCount)
        assertNull(cache.root("two"))
        assertEquals(SessionFileCache.Root("/1"), cache.root("one"))
        assertEquals(SessionFileCache.Root("/3"), cache.root("three"))
        cache.removeSession("three")
        assertNull(cache.root("three"))
        assertEquals(1, cache.rootCount)
    }

    @Test fun heldReadsAreAdmittedUpToTheirLimit() = test {
        val directory = account("one")
        seed(directory, listOf("a", "b", "c").map { "s" to "/repo/$it" })
        val cache = SessionFileCache(directory, maxPendingReads = 2)
        val disk = cache.disk!!
        val release = disk.hold()
        runBlocking(Dispatchers.IO) {
            val first = async { cache.snapshot("s", "/repo/a") }
            val second = async { cache.snapshot("s", "/repo/b") }
            until { cache.diskWaitCount == 2 }
            assertEquals(2, cache.pendingReadCount)
            // The same file again joins its read.
            val again = async { cache.snapshot("s", "/repo/a") }
            until { cache.diskWaitCount == 3 }
            assertEquals(2, cache.pendingReadCount)
            // A third file is a miss at once, with the disk still held.
            assertNull(cache.snapshot("s", "/repo/c"))
            assertEquals(2, cache.pendingReadCount)
            release()
            assertEquals(snapshot("old"), first.await())
            assertEquals(snapshot("old"), second.await())
            assertEquals(snapshot("old"), again.await())
        }
        assertEquals(0, cache.pendingReadCount)
        assertEquals(2, disk.reads)
        // Admitted once the others are done.
        assertEquals(snapshot("old"), cache.snapshot("s", "/repo/c"))
        assertEquals(3, disk.reads)
    }

    @Test fun aFileOnDiskBiggerThanAnEntryIsNeverRead() = test {
        val directory = account("one")
        seed(directory, listOf("s" to path))
        val file = File(directory, files(directory).first())
        // As if written by something else: valid, but past the entry cap.
        val big = SessionFileCache(directory, maxEntryBytes = 10_000)
        big.set("s", path, snapshot("x".repeat(2_000)))
        big.close()
        assertTrue(file.length() > 1_000)
        val cache = SessionFileCache(directory, maxEntryBytes = 1_000)
        assertNull(cache.snapshot("s", path))
        assertTrue(files(directory).isEmpty())
    }

    @Test fun anUnreadableFileIsAMiss() = test {
        val directory = account("one")
        val cache = SessionFileCache(directory)
        cache.set("s", path, snapshot("x"))
        cache.close()
        File(directory, files(directory).first()).writeText("not json")
        assertNull(SessionFileCache(directory).snapshot("s", path))
        assertTrue(files(directory).isEmpty())
    }
}
