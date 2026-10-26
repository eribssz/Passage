package dev.eribssz.passage

import dev.eribssz.passage.core.CertInfo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.LinkedBlockingQueue

sealed interface VpnState {
    data class Idle(val message: String? = null, val isError: Boolean = false) : VpnState
    data class Busy(val step: String, val endpoint: String? = null) : VpnState
    data class NeedsCert(val info: CertInfo) : VpnState
    data class NeedsCode(val prompt: String) : VpnState
    data class Connected(
        val ip: String,
        val gateway: String,
        val profileName: String,
        val addressIndex: Int,
        val addressCount: Int,
        val fixed: Boolean,
        val since: Long,
        val fortiOs: String?,
        val routes: List<String>,
        val dns: List<String>,
        val mtu: Int,
    ) : VpnState
}

data class Traffic(val down: Long = 0, val up: Long = 0)

/** Bytes per second over one sample interval. */
data class Rate(val down: Long = 0, val up: Long = 0)

/** The last minute of throughput samples (oldest first) and the session's peak download rate. */
data class Throughput(val samples: List<Rate> = emptyList(), val peakDown: Long = 0) {
    val current: Rate get() = samples.lastOrNull() ?: Rate()
    companion object { const val WINDOW = 60 }
}

/** Shared state between the UI and the VPN service (same process). */
object VpnController {
    private val _state = MutableStateFlow<VpnState>(VpnState.Idle())
    val state: StateFlow<VpnState> = _state
    private val _traffic = MutableStateFlow(Traffic())
    val traffic: StateFlow<Traffic> = _traffic
    private val _log = MutableStateFlow<List<String>>(emptyList())
    val log: StateFlow<List<String>> = _log

    /** Password for the attempt in progress; kept only in memory. */
    @Volatile var pendingPassword: String? = null

    /** The service waits here for a 2FA code; "" means cancelled. */
    val codeQueue = LinkedBlockingQueue<String>()

    private val fmt = SimpleDateFormat("HH:mm:ss", Locale.US)

    @Volatile private var appContext: android.content.Context? = null
    fun attach(ctx: android.content.Context) { appContext = ctx.applicationContext }

    fun set(s: VpnState) {
        val changedKind = _state.value::class != s::class
        _state.value = s
        // Wake the Quick Settings tile so it reflects the new state even when not visible.
        if (changedKind) appContext?.let { PassageTileService.refresh(it) }
    }
    private val _throughput = MutableStateFlow(Throughput())
    /** Kept here (not in the UI) so the graph survives rotation and leaving the app. */
    val throughput: StateFlow<Throughput> = _throughput
    private var lastTraffic: Traffic? = null
    private var lastAt = 0L

    fun traffic(t: Traffic) {
        val now = System.currentTimeMillis()
        val prev = lastTraffic
        if (t.down == 0L && t.up == 0L) {
            // A new session (or none): start the graph over.
            lastTraffic = null
            _throughput.value = Throughput()
        } else {
            if (prev != null && now > lastAt && t.down >= prev.down && t.up >= prev.up) {
                val secs = (now - lastAt) / 1000.0
                val r = Rate(((t.down - prev.down) / secs).toLong(), ((t.up - prev.up) / secs).toLong())
                val cur = _throughput.value
                _throughput.value = Throughput((cur.samples + r).takeLast(Throughput.WINDOW), maxOf(cur.peakDown, r.down))
            }
            lastTraffic = t; lastAt = now
        }
        _traffic.value = t
    }

    /** For screenshots and previews. */
    fun setThroughput(t: Throughput) { _throughput.value = t }

    fun log(msg: String) {
        val line = "${fmt.format(Date())}  $msg"
        _log.value = (_log.value + line).takeLast(300)
    }
}
