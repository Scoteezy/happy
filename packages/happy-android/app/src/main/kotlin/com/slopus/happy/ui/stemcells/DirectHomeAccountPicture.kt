package com.slopus.happy.ui.stemcells

import android.graphics.Bitmap
import com.slopus.happy.direct.DirectAccount
import com.slopus.happy.images.AccountPictureImages
import kotlinx.coroutines.*

/** One Direct home's image, never an account-global cache or an independent lifetime. */
internal class DirectHomeAccountPicture(
    private val owner: String,
    private val scope: CoroutineScope,
    private val decode: suspend (ByteArray?, String?) -> Bitmap? = { bytes, thumbhash ->
        withContext(Dispatchers.Default) {
            AccountPictureImages.image(bytes, thumbhash)
        }
    },
    private val apply: (Bitmap?) -> Unit,
) {
    private data class Key(val etag: String?, val size: Int?, val thumbhash: String?)
    private var key: Key? = null
    private var job: Job? = null
    private var generation = 0
    private var closed = false

    fun update(account: DirectAccount?) {
        if (closed || (account != null && account.id != owner)) return
        if (account == null) {
            generation++; job?.cancel(); job = null; key = null
            apply(null)
            return
        }
        val next = Key(account.avatarETag, account.avatar?.size, account.thumbhash)
        if (next == key) return
        key = next
        val current = ++generation
        job?.cancel()
        // Swift's Data has value semantics; don't share a mutable ByteArray
        // with an identity update while its picture is being decoded.
        val bytes = account.avatar?.copyOf()
        val thumbhash = account.thumbhash
        job = scope.launch {
            val image = decode(bytes, thumbhash)
            ensureActive()
            // Keep this owner's old picture until the replacement is ready.
            if (!closed && current == generation) apply(image)
        }
    }

    fun close() {
        if (closed) return
        closed = true; generation++; job?.cancel(); job = null; key = null
        apply(null)
    }
}