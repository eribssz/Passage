package dev.eribssz.passage

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import java.util.concurrent.ConcurrentHashMap

/** Tracks the phone's real (non-VPN) internet networks: Wi-Fi, mobile data… */
class NetworkMonitor(ctx: Context, private val onLost: () -> Unit) {
    private val cm = ctx.getSystemService(ConnectivityManager::class.java)
    private val nets = ConcurrentHashMap.newKeySet<Network>()
    private val lock = Object()
    @Volatile private var started = false

    private val cb = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(n: Network) {
            nets.add(n)
            synchronized(lock) { lock.notifyAll() }
        }
        override fun onLost(n: Network) {
            nets.remove(n)
            onLost()
        }
    }

    fun start() {
        if (started) return
        started = true
        runCatching {
            cm.registerNetworkCallback(NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
                .build(), cb)
        }.onFailure { started = false }
    }

    fun stop() {
        if (!started) return
        started = false
        runCatching { cm.unregisterNetworkCallback(cb) }
        nets.clear()
    }

    val hasNetwork get() = !started || nets.isNotEmpty()

    /** Waits up to [ms] for any network; true if one is there. */
    fun await(ms: Long): Boolean {
        if (hasNetwork) return true
        if (ms > 0) synchronized(lock) { if (!hasNetwork) lock.wait(ms) }
        return hasNetwork
    }
}
