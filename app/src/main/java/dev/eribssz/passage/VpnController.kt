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
    fun traffic(t: Traffic) { _traffic.value = t }

    fun log(msg: String) {
        val line = "${fmt.format(Date())}  $msg"
        _log.value = (_log.value + line).takeLast(300)
    }
}
