package com.slopus.happy.direct

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import mobile.Mobile
import org.json.JSONArray
import org.json.JSONObject
import java.net.NetworkInterface
import java.util.Collections

/**
 * expo-tailcat's Go library, linked (AGENTS.md "Tailcat"). Go can't list
 * Android's network interfaces itself, so it is handed a snapshot before
 * each open and as the network changes; a change closes every tunnel at
 * once, as expo-tailcat's module does, and each transport opens a new one
 * on its next request.
 */
object Tailcat : TailcatTunnels {
    override val isLinked = true
    private val manager by lazy { Mobile.newManager() }
    private val lock = Any()
    private var lastSnapshot: String? = null
    private var lastNetwork: Network? = null
    private var installed = false

    /** Follows the default network; once, from the application. */
    fun install(context: Context) = synchronized(lock) {
        if (installed) return
        installed = true
        val connectivity = context.applicationContext.getSystemService(ConnectivityManager::class.java) ?: return
        lastNetwork = connectivity.activeNetwork
        connectivity.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = changed(network)
            override fun onLost(network: Network) = changed(null)
            override fun onLinkPropertiesChanged(network: Network, properties: LinkProperties) = changed(network)
        })
    }

    override fun open(address: String, port: Int, timeoutMs: Int): TailcatTunnels.Tunnel {
        refreshInterfaces()
        val options = JSONObject()
            .put("address", address)
            .put("port", port)
            .put("hostname", "server.tailcat")
            .put("connectTimeoutMs", timeoutMs)
        val answer = try {
            JSONObject(manager.openTunnel(options.toString()))
        } catch (e: Exception) {
            // The library's words never hold the address or the endpoint's key.
            throw DirectUnavailableException(e.message ?: "The computer could not be reached.")
        }
        return TailcatTunnels.Tunnel(answer.getString("id"), answer.getString("httpUrl"))
    }

    override fun close(id: String) = manager.closeTunnel(id)

    private fun changed(network: Network?) {
        val changed = synchronized(lock) {
            val interfaces = refreshInterfaces()
            (interfaces || lastNetwork != network).also { lastNetwork = network }
        }
        // Tailcat would otherwise notice only at Go's ten-minute poll.
        if (changed) manager.closeAll()
    }

    /** Whether the interfaces differ from the last snapshot. */
    private fun refreshInterfaces(): Boolean = synchronized(lock) {
        val snapshot = JSONArray()
        val interfaces = runCatching { NetworkInterface.getNetworkInterfaces() }.getOrNull()
        if (interfaces != null) {
            for (iface in Collections.list(interfaces).sortedBy { it.index }) {
                val addresses = JSONArray()
                for (address in iface.interfaceAddresses) {
                    val host = address.address?.hostAddress?.substringBefore('%') ?: continue
                    addresses.put("$host/${address.networkPrefixLength}")
                }
                snapshot.put(
                    JSONObject()
                        .put("name", iface.name)
                        .put("index", iface.index)
                        .put("mtu", runCatching { iface.mtu }.getOrDefault(0))
                        .put("up", runCatching { iface.isUp }.getOrDefault(false))
                        .put("loopback", runCatching { iface.isLoopback }.getOrDefault(false))
                        .put("multicast", runCatching { iface.supportsMulticast() }.getOrDefault(false))
                        .put("addresses", addresses),
                )
            }
        }
        val json = snapshot.toString()
        val changed = lastSnapshot != null && lastSnapshot != json
        Mobile.setInterfaces(json)
        lastSnapshot = json
        changed
    }
}
