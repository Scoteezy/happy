package com.slopus.happy.direct

import android.content.Context

/**
 * A build without expo-tailcat's library (AGENTS.md "Tailcat"): every
 * tunnel refuses, so pairing and a paired computer say Tailcat isn't
 * available in this build. Release and preview builds never get here.
 */
object Tailcat : TailcatTunnels {
    override val isLinked = false

    @Suppress("UNUSED_PARAMETER")
    fun install(context: Context) {}

    override fun open(address: String, port: Int, timeoutMs: Int): TailcatTunnels.Tunnel = throw TailcatUnlinkedException()

    override fun close(id: String) {}
}
