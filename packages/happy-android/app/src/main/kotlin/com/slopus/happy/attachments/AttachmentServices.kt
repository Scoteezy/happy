package com.slopus.happy.attachments

import com.slopus.happy.AppGraph
import com.slopus.happy.auth.AuthState
import okhttp3.OkHttpClient
import java.util.WeakHashMap
import java.util.concurrent.TimeUnit

/**
 * The graph's attachment transfers, built on first use. They follow the
 * signed-in account: the token and the account's blob keys are read per
 * call, and the decrypted-blob cache is dropped when the account changes.
 */
class AttachmentServices private constructor(private val graph: AppGraph) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()
    val api = AttachmentApi(client, serverUrl = { graph.serverConfig.url.value })

    private class Account(val state: AuthState.SignedIn, val transfers: AttachmentTransfers, val pictures: AttachmentPictures)

    private var account: Account? = null

    /** The signed-in account's transfers, or null when signed out. */
    val transfers: AttachmentTransfers? get() = current()?.transfers

    /** The signed-in account's decoded pictures, or null when signed out. */
    val pictures: AttachmentPictures? get() = current()?.pictures

    @Synchronized
    private fun current(): Account? {
        val signedIn = graph.auth.state.value as? AuthState.SignedIn ?: return null.also { account = null }
        account?.let { if (it.state == signedIn) return it }
        val blobKeys = SessionBlobKeys(graph.store, signedIn.encryption)
        val transfers = AttachmentTransfers(api, blobKeys::blobKey, token = { signedIn.credentials.token })
        return Account(signedIn, transfers, AttachmentPictures()).also { account = it }
    }

    /** Lets the account's decoded pictures go (memory pressure). */
    fun trimMemory() {
        synchronized(this) { account }?.pictures?.removeAll()
    }

    companion object {
        private val instances = WeakHashMap<AppGraph, AttachmentServices>()

        fun of(graph: AppGraph): AttachmentServices = synchronized(instances) {
            instances.getOrPut(graph) { AttachmentServices(graph) }
        }
    }
}
