package dev.eribssz.passage.core

import java.io.ByteArrayOutputStream
import kotlin.random.Random

data class IpcpResult(
    val localIp: String,
    val peerIp: String?,
    val dns: List<String>,
    val mtu: Int,
)

/**
 * Minimal PPP (RFC 1661) with LCP and IPCP, as used inside the Fortinet tunnel.
 * Frames passed in/out are bare PPP: protocol field + information (no HDLC, no FF03).
 */
class PppEngine(
    private val send: (ByteArray) -> Unit,
    private val log: (String) -> Unit = {},
    ourMru: Int = 1354,
    private val now: () -> Long = System::currentTimeMillis,
) {
    companion object {
        const val PROTO_IP = 0x0021
        const val PROTO_IPV6 = 0x0057
        const val PROTO_LCP = 0xC021
        const val PROTO_IPCP = 0x8021

        const val CONF_REQ = 1; const val CONF_ACK = 2; const val CONF_NAK = 3; const val CONF_REJ = 4
        const val TERM_REQ = 5; const val TERM_ACK = 6; const val CODE_REJ = 7; const val PROTO_REJ = 8
        const val ECHO_REQ = 9; const val ECHO_REP = 10; const val DISCARD_REQ = 11

        private const val RETRY_MS = 3_000L
        private const val MAX_TRIES = 10
        private const val ECHO_EVERY_MS = 10_000L
        private const val DEAD_AFTER_MS = 35_000L

        fun ip(b: ByteArray, o: Int = 0) = "${b[o].toInt() and 255}.${b[o + 1].toInt() and 255}.${b[o + 2].toInt() and 255}.${b[o + 3].toInt() and 255}"
        fun ipBytes(s: String) = s.split('.').map { it.toInt().toByte() }.toByteArray()
        private fun u16(v: Int) = byteArrayOf((v shr 8).toByte(), v.toByte())
        private fun u32(v: Int) = byteArrayOf((v shr 24).toByte(), (v shr 16).toByte(), (v shr 8).toByte(), v.toByte())
    }

    enum class Phase { DEAD, LCP, IPCP, UP, CLOSED }

    @Volatile var phase = Phase.DEAD
        private set

    var onUp: ((IpcpResult) -> Unit)? = null
    var onIp: ((ByteArray, Int, Int) -> Unit)? = null
    var onClosed: ((String) -> Unit)? = null

    private val magic = Random.nextInt().let { if (it == 0) 0x1badcafe else it }
    private var nextId = 1

    // LCP
    private val lcpOpts = linkedMapOf(1 to u16(ourMru), 5 to u32(magic))
    private var lcpReqId = -1
    private var lcpOurAcked = false
    private var lcpPeerAcked = false
    private var peerMru: Int? = null

    // IPCP
    private val ipcpOpts = linkedMapOf(3 to ByteArray(4), 129 to ByteArray(4), 131 to ByteArray(4))
    private var ipcpReqId = -1
    private var ipcpOurAcked = false
    private var ipcpPeerAcked = false
    private var peerIp: String? = null

    private var lastSent = 0L
    private var tries = 0
    private var lastRx = 0L
    private var lastEcho = 0L

    @Synchronized
    fun start() {
        phase = Phase.LCP
        lastRx = now()
        sendLcpReq()
    }

    // ---------- wire helpers ----------

    private fun control(proto: Int, code: Int, id: Int, data: ByteArray): ByteArray {
        val len = 4 + data.size
        return u16(proto) + byteArrayOf(code.toByte(), id.toByte()) + u16(len) + data
    }

    private fun encodeOpts(opts: Map<Int, ByteArray>): ByteArray {
        val o = ByteArrayOutputStream()
        for ((t, v) in opts) { o.write(t); o.write(v.size + 2); o.write(v) }
        return o.toByteArray()
    }

    private fun parseOpts(b: ByteArray, off: Int, end: Int): List<Pair<Int, ByteArray>>? {
        val res = mutableListOf<Pair<Int, ByteArray>>()
        var p = off
        while (p < end) {
            if (p + 2 > end) return null
            val t = b[p].toInt() and 255
            val l = b[p + 1].toInt() and 255
            if (l < 2 || p + l > end) return null
            res += t to b.copyOfRange(p + 2, p + l)
            p += l
        }
        return res
    }

    private fun optBytes(opts: List<Pair<Int, ByteArray>>): ByteArray {
        val o = ByteArrayOutputStream()
        for ((t, v) in opts) { o.write(t); o.write(v.size + 2); o.write(v) }
        return o.toByteArray()
    }

    private fun sendLcpReq() {
        lcpReqId = nextId++ and 255
        lastSent = now(); tries++
        send(control(PROTO_LCP, CONF_REQ, lcpReqId, encodeOpts(lcpOpts)))
    }

    private fun sendIpcpReq() {
        ipcpReqId = nextId++ and 255
        lastSent = now(); tries++
        send(control(PROTO_IPCP, CONF_REQ, ipcpReqId, encodeOpts(ipcpOpts)))
    }

    // ---------- input ----------

    @Synchronized
    fun onFrame(b: ByteArray, off: Int, len: Int) {
        if (phase == Phase.CLOSED || len < 2) return
        lastRx = now()
        var p = off
        val end = off + len
        if ((b[p].toInt() and 255) == 0xFF && (b[p + 1].toInt() and 255) == 0x03) p += 2
        if (p >= end) return
        val proto: Int
        if ((b[p].toInt() and 1) == 1) { proto = b[p].toInt() and 255; p += 1 }
        else { if (p + 2 > end) return; proto = ((b[p].toInt() and 255) shl 8) or (b[p + 1].toInt() and 255); p += 2 }

        when (proto) {
            PROTO_IP -> if (phase == Phase.UP) onIp?.invoke(b, p, end - p)
            PROTO_LCP -> lcp(b, p, end)
            PROTO_IPCP -> ipcp(b, p, end)
            PROTO_IPV6 -> {}
            else -> if (phase != Phase.LCP && phase != Phase.DEAD) {
                // Unknown protocol (CCP, IPv6CP...): tell the peer we don't speak it.
                val info = b.copyOfRange(p, minOf(end, p + 1400))
                send(control(PROTO_LCP, PROTO_REJ, nextId++ and 255, u16(proto) + info))
            }
        }
    }

    private class Ctl(val code: Int, val id: Int, val data: ByteArray)

    private fun parseCtl(b: ByteArray, p: Int, end: Int): Ctl? {
        if (p + 4 > end) return null
        val code = b[p].toInt() and 255
        val id = b[p + 1].toInt() and 255
        val l = ((b[p + 2].toInt() and 255) shl 8) or (b[p + 3].toInt() and 255)
        val realEnd = if (l in 4..(end - p)) p + l else end
        return Ctl(code, id, b.copyOfRange(p + 4, realEnd))
    }

    private fun lcp(b: ByteArray, p: Int, end: Int) {
        val c = parseCtl(b, p, end) ?: return
        val d = c.data
        when (c.code) {
            CONF_REQ -> {
                val opts = parseOpts(d, 0, d.size) ?: return
                val rej = opts.filter { it.first !in setOf(1, 2, 5, 7, 8) }
                if (rej.isNotEmpty()) {
                    log("LCP: rejecting options ${rej.map { it.first }}")
                    send(control(PROTO_LCP, CONF_REJ, c.id, optBytes(rej)))
                    return
                }
                val m = opts.firstOrNull { it.first == 5 }?.second
                if (m != null && m.size == 4 && m.contentEquals(u32(magic))) {
                    send(control(PROTO_LCP, CONF_NAK, c.id, optBytes(listOf(5 to u32(Random.nextInt())))))
                    return
                }
                opts.firstOrNull { it.first == 1 && it.second.size == 2 }?.let {
                    peerMru = ((it.second[0].toInt() and 255) shl 8) or (it.second[1].toInt() and 255)
                }
                send(control(PROTO_LCP, CONF_ACK, c.id, d))
                lcpPeerAcked = true
                checkLcp()
            }
            CONF_ACK -> if (c.id == lcpReqId) { lcpOurAcked = true; checkLcp() }
            CONF_NAK -> if (c.id == lcpReqId && phase == Phase.LCP) {
                parseOpts(d, 0, d.size)?.forEach { (t, v) -> if (lcpOpts.containsKey(t)) lcpOpts[t] = v }
                tries = 0; sendLcpReq()
            }
            CONF_REJ -> if (c.id == lcpReqId && phase == Phase.LCP) {
                parseOpts(d, 0, d.size)?.forEach { (t, _) -> lcpOpts.remove(t) }
                tries = 0; sendLcpReq()
            }
            TERM_REQ -> { send(control(PROTO_LCP, TERM_ACK, c.id, ByteArray(0))); close("Gateway ended the session") }
            TERM_ACK -> close("Link terminated")
            ECHO_REQ -> if (phase != Phase.LCP) {
                val rest = if (d.size > 4) d.copyOfRange(4, d.size) else ByteArray(0)
                send(control(PROTO_LCP, ECHO_REP, c.id, u32(magic) + rest))
            }
            ECHO_REP, DISCARD_REQ, CODE_REJ -> {}
            PROTO_REJ -> {
                if (d.size >= 2) log("Gateway rejected protocol 0x%04x".format(((d[0].toInt() and 255) shl 8) or (d[1].toInt() and 255)))
            }
            else -> send(control(PROTO_LCP, CODE_REJ, nextId++ and 255, b.copyOfRange(p, minOf(end, p + 1400))))
        }
    }

    private fun checkLcp() {
        if (phase == Phase.LCP && lcpOurAcked && lcpPeerAcked) {
            log("LCP up (gateway MRU ${peerMru ?: "default"})")
            phase = Phase.IPCP
            tries = 0
            sendIpcpReq()
        }
    }

    private fun ipcp(b: ByteArray, p: Int, end: Int) {
        if (phase == Phase.LCP || phase == Phase.DEAD) return
        val c = parseCtl(b, p, end) ?: return
        val d = c.data
        when (c.code) {
            CONF_REQ -> {
                val opts = parseOpts(d, 0, d.size) ?: return
                val rej = opts.filter { it.first != 3 }
                if (rej.isNotEmpty()) {
                    send(control(PROTO_IPCP, CONF_REJ, c.id, optBytes(rej)))
                    return
                }
                opts.firstOrNull { it.first == 3 && it.second.size == 4 }?.let { peerIp = ip(it.second) }
                send(control(PROTO_IPCP, CONF_ACK, c.id, d))
                ipcpPeerAcked = true
                checkIpcp()
            }
            CONF_ACK -> if (c.id == ipcpReqId) { ipcpOurAcked = true; checkIpcp() }
            CONF_NAK -> if (c.id == ipcpReqId && phase == Phase.IPCP) {
                parseOpts(d, 0, d.size)?.forEach { (t, v) -> if (ipcpOpts.containsKey(t) && v.size == 4) ipcpOpts[t] = v }
                tries = 0; sendIpcpReq()
            }
            CONF_REJ -> if (c.id == ipcpReqId && phase == Phase.IPCP) {
                parseOpts(d, 0, d.size)?.forEach { (t, _) -> ipcpOpts.remove(t) }
                tries = 0; sendIpcpReq()
            }
            TERM_REQ -> { send(control(PROTO_IPCP, TERM_ACK, c.id, ByteArray(0))); close("Gateway closed IP link") }
            else -> {}
        }
    }

    private fun checkIpcp() {
        if (phase == Phase.IPCP && ipcpOurAcked && ipcpPeerAcked) {
            val local = ipcpOpts[3]?.let { ip(it) } ?: "0.0.0.0"
            val dns = listOfNotNull(ipcpOpts[129], ipcpOpts[131]).map { ip(it) }.filter { it != "0.0.0.0" }
            val mtu = (peerMru ?: 1354).coerceIn(576, 1500)
            phase = Phase.UP
            lastEcho = now()
            log("IPCP up: $local (gateway ${peerIp ?: "?"})")
            onUp?.invoke(IpcpResult(local, peerIp, dns, mtu))
        }
    }

    // ---------- output / timers ----------

    fun sendIp(b: ByteArray, off: Int, len: Int) {
        if (phase != Phase.UP) return
        val f = ByteArray(len + 2)
        f[0] = 0x00; f[1] = 0x21
        System.arraycopy(b, off, f, 2, len)
        send(f)
    }

    /** Call about once a second. */
    @Synchronized
    fun tick() {
        val t = now()
        when (phase) {
            Phase.LCP, Phase.IPCP -> {
                val waiting = if (phase == Phase.LCP) !lcpOurAcked else !ipcpOurAcked
                if (waiting && t - lastSent >= RETRY_MS) {
                    if (tries >= MAX_TRIES) { close("No answer from gateway during ${phase.name} setup"); return }
                    if (phase == Phase.LCP) sendLcpReq() else sendIpcpReq()
                }
            }
            Phase.UP -> {
                if (t - lastRx > DEAD_AFTER_MS) { close("Gateway stopped responding"); return }
                if (t - lastEcho >= ECHO_EVERY_MS) {
                    lastEcho = t
                    send(control(PROTO_LCP, ECHO_REQ, nextId++ and 255, u32(magic)))
                }
            }
            else -> {}
        }
    }

    /** Sends an LCP echo now (used to check the link after a network change). */
    @Synchronized
    fun sendEcho() {
        if (phase != Phase.UP) return
        lastEcho = now()
        send(control(PROTO_LCP, ECHO_REQ, nextId++ and 255, u32(magic)))
    }

    @Synchronized
    fun terminate() {
        if (phase == Phase.UP || phase == Phase.IPCP || phase == Phase.LCP) {
            runCatching { send(control(PROTO_LCP, TERM_REQ, nextId++ and 255, ByteArray(0))) }
        }
        close("Disconnected")
    }

    private fun close(reason: String) {
        if (phase == Phase.CLOSED) return
        phase = Phase.CLOSED
        log(reason)
        onClosed?.invoke(reason)
    }
}
