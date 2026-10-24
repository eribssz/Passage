package dev.eribssz.passage

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import dev.eribssz.passage.core.AuthException
import dev.eribssz.passage.core.CancelledException
import dev.eribssz.passage.core.ConnectResult
import dev.eribssz.passage.core.FortiConnector
import dev.eribssz.passage.core.FortiLink
import dev.eribssz.passage.core.FortiSession
import dev.eribssz.passage.core.Route
import dev.eribssz.passage.core.SocketOpener
import dev.eribssz.passage.core.UntrustedCertException
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.TimeUnit

class PassageVpnService : VpnService() {

    companion object {
        const val ACTION_CONNECT = "dev.eribssz.passage.CONNECT"
        const val ACTION_DISCONNECT = "dev.eribssz.passage.DISCONNECT"

        fun connect(ctx: Context) = ctx.startService(Intent(ctx, PassageVpnService::class.java).setAction(ACTION_CONNECT))
        fun disconnect(ctx: Context) = ctx.startService(Intent(ctx, PassageVpnService::class.java).setAction(ACTION_DISCONNECT))
    }

    @Volatile private var worker: Thread? = null
    @Volatile private var session: FortiSession? = null
    @Volatile private var link: FortiLink? = null
    @Volatile private var tun: ParcelFileDescriptor? = null
    @Volatile private var userStop = false
    /** Kept in memory only while a session is wanted, so a dropped link can reconnect by itself. */
    @Volatile private var password: String? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        VpnController.attach(this)
        when (intent?.action) {
            ACTION_CONNECT -> if (worker?.isAlive != true) {
                userStop = false
                password = VpnController.pendingPassword
                VpnController.pendingPassword = null
                val pw = password
                if (pw.isNullOrEmpty()) { finish("Enter your password", true) }
                else worker = Thread({ run(pw, reconnect = false) }, "passage-connect").apply { start() }
            }
            ACTION_DISCONNECT -> stopVpn("Disconnected")
        }
        return START_NOT_STICKY
    }

    override fun onRevoke() {
        stopVpn("VPN turned off by the system")
        super.onRevoke()
    }

    override fun onDestroy() {
        stopVpn(null)
        super.onDestroy()
    }

    private val opener = SocketOpener { host, port ->
        Socket().also { s ->
            protect(s)
            s.tcpNoDelay = true
            s.connect(InetSocketAddress(host, port), 7_000)
        }
    }

    private val net by lazy {
        NetworkMonitor(this) {
            // A network went away (e.g. Wi-Fi off). If our tunnel rode on it, it's dead:
            // check quickly instead of waiting for keepalives to time out.
            val l = link
            if (l != null && l.isRunning && VpnController.state.value is VpnState.Connected) {
                Thread({ if (!l.probe(5_000)) l.drop("Network changed") }, "passage-probe").start()
            }
        }
    }

    /**
     * Connects to the active gateway. With [reconnect] (the tunnel dropped by itself) it
     * waits for a network, retries the same address a few times — mobile blips are usually
     * local — and keeps trying with backoff for up to 10 minutes.
     */
    private fun run(pw: String, reconnect: Boolean) {
        val prefs = Prefs(this)
        val log: (String) -> Unit = VpnController::log
        net.start()
        val deadline = System.currentTimeMillis() + 10 * 60_000
        var round = 0
        while (true) {
            if (userStop) return
            val p = prefs.active()
            if (p.servers.isEmpty()) { finish("Add a gateway address first", true); return }

            if (!net.await(if (reconnect) 0 else 3_000)) {
                if (!reconnect) { finish("No network connection", true); return }
                VpnController.set(VpnState.Busy("Waiting for network"))
                VpnController.log("Waiting for network")
                while (!userStop && !net.await(15_000) && System.currentTimeMillis() < deadline) {}
                if (userStop) return
                if (!net.hasNetwork) { finish("No network for 10 minutes", true); return }
                continue
            }

            val fixed = p.fixed
            val endpoints = if (fixed != null) listOf(fixed) else p.servers
            val start = if (fixed != null) 0 else p.lastGood
            try {
                if (round == 0) VpnController.log(
                    "${if (reconnect) "Reconnecting" else "Connecting"} to ${p.name} " +
                        if (fixed != null) "(fixed ${fixed.key})" else "(${p.servers.size} address${if (p.servers.size > 1) "es" else ""}, auto)")
                val r: ConnectResult = FortiConnector(opener, log).connect(
                    endpoints = endpoints, startIndex = start,
                    username = p.username, password = pw, realm = p.realm, pins = p.pins,
                    onStep = { step, ep ->
                        VpnController.set(VpnState.Busy(if (reconnect && step == "Signing in") "Reconnecting" else step, ep.key))
                    },
                    askCode = { prompt ->
                        VpnController.codeQueue.clear()
                        VpnController.set(VpnState.NeedsCode(prompt))
                        VpnController.codeQueue.poll(5, TimeUnit.MINUTES)
                    },
                    cancelled = { userStop },
                    onLinkCreated = { link = it },
                    firstAttempts = if (reconnect) 3 else 1,
                )
                session = r.session
                link = r.link
                if (userStop) { r.link.close(); return }
                val index = p.servers.indexOf(r.endpoint)
                if (fixed == null && index >= 0) prefs.setLastGood(p.id, index)
                establish(p, r, index, fixed != null)
                return
            } catch (e: UntrustedCertException) {
                VpnController.log(if (e.info.changed) "Certificate CHANGED at ${e.info.endpoint}" else "New certificate at ${e.info.endpoint}")
                cleanup()
                password = null
                VpnController.set(VpnState.NeedsCert(e.info))
                stopSelf()
                return
            } catch (e: AuthException) {
                password = null
                finish(e.message ?: "Sign-in failed", true)
                return
            } catch (e: CancelledException) {
                if (!userStop) finish("Sign-in cancelled", false)
                return
            } catch (e: Exception) {
                if (userStop) return
                VpnController.log("Error: ${e.javaClass.simpleName}: ${e.message}")
                if (!reconnect || System.currentTimeMillis() >= deadline) {
                    password = null
                    finish(if (reconnect) "Couldn't reconnect: ${friendly(e)}" else friendly(e), true)
                    return
                }
                round++
                val wait = minOf(60, 5 * round)
                VpnController.log("Next try in ${wait}s")
                for (sec in wait downTo 1) {
                    if (userStop) return
                    VpnController.set(VpnState.Busy("Retrying in ${sec}s"))
                    try { Thread.sleep(1_000) } catch (_: InterruptedException) { if (userStop) return }
                }
            }
        }
    }

    private fun establish(p: Profile, r: ConnectResult, index: Int, fixed: Boolean) {
        val cfg = r.config
        val l = r.link
        val ip = r.ipcp.localIp.takeIf { it != "0.0.0.0" } ?: cfg.address ?: throw IllegalStateException("No IP assigned")
        val dns = (r.ipcp.dns + cfg.dns).distinct()
        val builder = Builder()
            .setSession("Passage · ${p.name}")
            .setMtu(r.ipcp.mtu)
            .addAddress(ip, 32)
            .setBlocking(true)
            .setConfigureIntent(PendingIntent.getActivity(this, 0,
                Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE))
        dns.forEach { builder.addDnsServer(it) }
        cfg.searchDomains.forEach { builder.addSearchDomain(it) }
        val routes = if (cfg.routes.isEmpty()) listOf(Route("0.0.0.0", 0)) else cfg.routes + dns.map { Route(it, 32) }
        routes.distinct().forEach { rt ->
            runCatching { builder.addRoute(network(rt.address, rt.prefix), rt.prefix) }
                .onFailure { VpnController.log("Skipped route $rt: ${it.message}") }
        }
        if (Build.VERSION.SDK_INT >= 29) builder.setMetered(false)

        // On reconnect this replaces the previous interface in place.
        val fd = builder.establish() ?: throw IllegalStateException("VPN permission was revoked")
        val old = tun
        tun = fd
        runCatching { old?.close() }
        val tunOut = FileOutputStream(fd.fileDescriptor)
        l.onIpPacket = { b, o, n -> runCatching { tunOut.write(b, o, n) } }
        l.onClosed = { reason -> if (!userStop) onLinkLost(reason) }

        Thread({
            val input = FileInputStream(fd.fileDescriptor)
            val buf = ByteArray(32767)
            try {
                while (l.isRunning) {
                    val n = input.read(buf)
                    if (n < 0) break
                    if (n > 0 && (buf[0].toInt() shr 4) == 4) l.sendIp(buf, 0, n)
                }
            } catch (_: Exception) {}
        }, "passage-tun").apply { isDaemon = true }.start()

        Thread({
            while (l.isRunning) {
                VpnController.traffic(Traffic(l.bytesIn.get(), l.bytesOut.get()))
                try { Thread.sleep(1000) } catch (_: InterruptedException) { break }
            }
        }, "passage-stats").apply { isDaemon = true }.start()

        VpnController.set(VpnState.Connected(
            ip = ip, gateway = r.endpoint.key, profileName = p.name,
            addressIndex = index, addressCount = p.servers.size, fixed = fixed,
            since = System.currentTimeMillis(), fortiOs = cfg.fortiOs,
            routes = if (cfg.routes.isEmpty()) emptyList() else cfg.routes.map { it.toString() },
            dns = dns, mtu = r.ipcp.mtu,
        ))
        VpnController.log("Connected as $ip via ${r.endpoint.key}")
    }

    /**
     * The tunnel died without the user asking: reconnect, same address first.
     * The old interface stays up meanwhile, so traffic waits instead of leaking.
     */
    private fun onLinkLost(reason: String) {
        val pw = password
        VpnController.log("Link lost: $reason")
        if (pw == null || worker?.isAlive == true && worker != Thread.currentThread()) { stopVpn(reason); return }
        link = null
        session?.let { s -> Thread({ s.logout() }, "passage-logout").start() }
        session = null
        worker = Thread({ run(pw, reconnect = true) }, "passage-reconnect").apply { start() }
    }

    private fun friendly(e: Exception): String {
        val c = e.cause as? Exception ?: e
        return when {
            c is java.net.SocketTimeoutException -> "Gateway didn't answer (timeout)"
            c is java.net.ConnectException -> "Can't reach the gateway"
            c is java.net.UnknownHostException -> "Unknown server address"
            c is java.net.NoRouteToHostException -> "No network"
            else -> e.message ?: e.javaClass.simpleName
        }
    }

    private fun network(ip: String, prefix: Int): String {
        val b = ip.split('.').map { it.toInt() }
        val v = (b[0] shl 24) or (b[1] shl 16) or (b[2] shl 8) or b[3]
        val m = if (prefix == 0) 0 else (-1 shl (32 - prefix))
        val n = v and m
        return "${(n ushr 24) and 255}.${(n ushr 16) and 255}.${(n ushr 8) and 255}.${n and 255}"
    }

    private fun stopNet() = runCatching { net.stop() }

    private fun cleanup() {
        link?.close(); link = null
        runCatching { tun?.close() }; tun = null
        val s = session; session = null
        if (s != null) Thread({ s.logout() }, "passage-logout").start()
    }

    private fun finish(message: String, isError: Boolean) {
        cleanup()
        stopNet()
        VpnController.set(VpnState.Idle(message, isError))
        VpnController.traffic(Traffic())
        stopSelf()
    }

    @Synchronized
    private fun stopVpn(reason: String?) {
        val wasActive = link != null || tun != null || worker?.isAlive == true
        userStop = reason == "Disconnected" || reason == null
        password = null
        if (VpnController.state.value is VpnState.NeedsCode) VpnController.codeQueue.offer("")
        cleanup()
        stopNet()
        worker?.interrupt()
        if (reason != null && (wasActive || VpnController.state.value !is VpnState.Idle)) {
            val isError = reason != "Disconnected"
            VpnController.log(reason)
            VpnController.set(VpnState.Idle(if (isError) reason else null, isError))
        }
        VpnController.traffic(Traffic())
        stopSelf()
    }
}
