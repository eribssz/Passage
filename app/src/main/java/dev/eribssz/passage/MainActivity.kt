package dev.eribssz.passage

import android.app.StatusBarManager
import android.content.ComponentName
import android.graphics.drawable.Icon
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import dev.eribssz.passage.ui.PassageApp

class MainActivity : ComponentActivity() {

    private val vpnPermission = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        if (res.resultCode == RESULT_OK) PassageVpnService.connect(this)
        else VpnController.set(VpnState.Idle("VPN permission is needed to connect", true))
    }

    private var configRev by mutableIntStateOf(0)

    private val exportDoc = registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri == null) return@registerForActivityResult
        runCatching {
            contentResolver.openOutputStream(uri)!!.use { it.write(Prefs(this).exportJson().toByteArray()) }
        }.onSuccess { toast("Config exported") }.onFailure { toast("Export failed: ${it.message}") }
    }

    private val importDoc = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@registerForActivityResult
        runCatching {
            val text = contentResolver.openInputStream(uri)!!.use { it.readBytes().decodeToString() }
            Prefs(this).importJson(text)
        }.onSuccess { n ->
            configRev++
            toast(if (n == 0) "No gateways in that file" else "Imported $n gateway${if (n > 1) "s" else ""}")
        }.onFailure { toast("Not a valid Passage config") }
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        VpnController.attach(this)
        setContent {
            PassageApp(
                prefs = Prefs(this),
                onConnect = ::startConnect,
                onDisconnect = { PassageVpnService.disconnect(this) },
                onAddTile = if (Build.VERSION.SDK_INT >= 33) ::addTile else null,
                onExport = { exportDoc.launch("passage-gateways.json") },
                onImport = { importDoc.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) },
                configRev = configRev,
            )
        }
    }

    private fun startConnect(password: String) {
        VpnController.pendingPassword = password
        VpnController.set(VpnState.Busy("Starting"))
        val intent = VpnService.prepare(this)
        if (intent != null) vpnPermission.launch(intent) else PassageVpnService.connect(this)
    }

    private fun addTile() {
        if (Build.VERSION.SDK_INT < 33) return
        getSystemService(StatusBarManager::class.java).requestAddTileService(
            ComponentName(this, PassageTileService::class.java), "Passage",
            Icon.createWithResource(this, R.drawable.ic_tile), mainExecutor) { }
    }
}
