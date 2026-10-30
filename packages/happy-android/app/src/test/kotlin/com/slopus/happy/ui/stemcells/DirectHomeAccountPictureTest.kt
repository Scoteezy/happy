package com.slopus.happy.ui.stemcells

import android.graphics.Bitmap
import com.slopus.happy.direct.DirectAccount
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class DirectHomeAccountPictureTest {
    private fun picture() = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)

    @Test fun sameOwnerKeepsItsOldPictureUntilReplacementAndNameChangesDoNotDecodeAgain() = runTest {
        val first = picture(); val second = picture()
        val gate = CompletableDeferred<Unit>()
        var decoded = 0
        var shown: Bitmap? = null
        val binding = DirectHomeAccountPicture("a", backgroundScope, decode = { _, _ ->
            decoded++
            if (decoded == 1) first else { gate.await(); second }
        }) { shown = it }
        val account = DirectAccount("a", "First", byteArrayOf(1), "v1")
        binding.update(account); runCurrent()
        assertSame(first, shown)
        binding.update(account.copy(name = "Renamed")); runCurrent()
        assertEquals(1, decoded)
        binding.update(account.copy(avatarETag = "v2")); runCurrent()
        assertSame(first, shown)
        gate.complete(Unit); runCurrent()
        assertSame(second, shown)
        binding.close()
        assertNull(shown)
        assertFalse(first.isRecycled)
    }

    @Test fun replacedAndRemovedOwnersCannotApplyAnOldDecodeEvenIfItIgnoresCancellation() = runTest {
        val old = picture(); val current = picture()
        val gate = CompletableDeferred<Unit>()
        var shown: Bitmap? = null
        val binding = DirectHomeAccountPicture("a", backgroundScope, decode = { bytes, _ ->
            if (bytes?.first() == 1.toByte()) { withContext(NonCancellable) { gate.await() }; old } else current
        }) { shown = it }
        binding.update(DirectAccount("a", "A", byteArrayOf(1), "v1")); runCurrent()
        binding.update(DirectAccount("a", "A", byteArrayOf(2), "v2")); runCurrent()
        assertSame(current, shown)
        gate.complete(Unit); runCurrent()
        assertSame(current, shown)
        binding.update(null); runCurrent()
        assertNull(shown)
        binding.close()
    }

    @Test fun foreignAccountIsIgnoredAndClosingRejectsPendingAndFuturePictures() = runTest {
        val gate = CompletableDeferred<Unit>()
        var decoded = 0
        var shown: Bitmap? = null
        val binding = DirectHomeAccountPicture("a", backgroundScope, decode = { _, _ ->
            decoded++; withContext(NonCancellable) { gate.await() }; picture()
        }) { shown = it }
        binding.update(DirectAccount("b", "Wrong owner", byteArrayOf(1), "v1")); runCurrent()
        assertEquals(0, decoded)
        binding.update(DirectAccount("a", "A", byteArrayOf(1), "v1")); runCurrent()
        assertEquals(1, decoded)
        binding.close()
        gate.complete(Unit); runCurrent()
        binding.update(DirectAccount("a", "A", byteArrayOf(2), "v2")); runCurrent()
        assertNull(shown)
        assertEquals(1, decoded)
    }

    @Test fun pendingDecodeOwnsACopyOfItsInputBytes() = runTest {
        val bytes = byteArrayOf(1, 2)
        var received: ByteArray? = null
        val binding = DirectHomeAccountPicture("a", backgroundScope, decode = { value, _ -> received = value; null }) {}
        binding.update(DirectAccount("a", "A", bytes, "v1"))
        bytes[0] = 9
        runCurrent()
        assertArrayEquals(byteArrayOf(1, 2), received)
        binding.close()
    }
}