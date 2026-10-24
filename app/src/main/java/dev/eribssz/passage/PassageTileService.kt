package dev.eribssz.passage

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.net.VpnService
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/** Quick Settings toggle, like Tailscale's: tap to connect / disconnect. */
class PassageTileService : TileService() {

    companion object {
        fun refresh(ctx: Context) = runCatching {
            requestListeningState(ctx, ComponentName(ctx, PassageTileService::class.java))
        }
    }

    private var scope: CoroutineScope? = null

    override fun onStartListening() {
        super.onStartListening()
        VpnController.attach(this)
        scope?.cancel()
        scope = CoroutineScope(Dispatchers.Main + Job()).also { s ->
            s.launch { VpnController.state.collectLatest { render(it) } }
        }
    }

    override fun onStopListening() {
        scope?.cancel(); scope = null
        super.onStopListening()
    }

    private fun render(state: VpnState) {
        val tile = qsTile ?: return
        val name = Prefs(this).active().name
        tile.icon = Icon.createWithResource(this, R.drawable.ic_tile)
        tile.label = "Passage"
        val (st, sub) = when (state) {
            is VpnState.Connected -> Tile.STATE_ACTIVE to name
            is VpnState.Busy -> Tile.STATE_ACTIVE to "Connecting…"
            is VpnState.NeedsCert, is VpnState.NeedsCode -> Tile.STATE_INACTIVE to "Open to finish"
            is VpnState.Idle -> Tile.STATE_INACTIVE to if (state.isError) "Failed" else "Off"
        }
        tile.state = st
        if (Build.VERSION.SDK_INT >= 29) tile.subtitle = sub
        if (Build.VERSION.SDK_INT >= 30) tile.stateDescription = sub
        tile.updateTile()
    }

    override fun onClick() {
        super.onClick()
        when (VpnController.state.value) {
            is VpnState.Connected, is VpnState.Busy -> { PassageVpnService.disconnect(this); return }
            is VpnState.NeedsCert, is VpnState.NeedsCode -> { openApp(); return }
            is VpnState.Idle -> {}
        }
        val prefs = Prefs(this)
        val password = prefs.loadPassword(prefs.active().id)
        // First use needs Android's VPN consent dialog, and without a saved password
        // there is nowhere to type it — both live in the app.
        if (password.isNullOrEmpty() || VpnService.prepare(this) != null) { openApp(); return }
        VpnController.pendingPassword = password
        VpnController.set(VpnState.Busy("Starting"))
        try {
            PassageVpnService.connect(this)
        } catch (e: IllegalStateException) {
            VpnController.set(VpnState.Idle())
            openApp()
        }
    }

    @SuppressLint("StartActivityAndCollapseDeprecated")
    private fun openApp() {
        val intent = Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= 34) {
            startActivityAndCollapse(PendingIntent.getActivity(this, 1, intent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
        } else {
            @Suppress("DEPRECATION") startActivityAndCollapse(intent)
        }
    }
}
