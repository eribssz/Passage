package dev.eribssz.passage.core

import java.io.DataInputStream
import java.io.EOFException
import java.io.IOException
import java.io.OutputStream
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * Fortinet tunnel framing: every PPP packet is wrapped in a 6-byte header
 * [total length u16][0x5050][payload length u16].
 */
object FortiFrame {
    const val MAGIC = 0x5050

    fun encode(ppp: ByteArray): ByteArray {
        val n = ppp.size
        val f = ByteArray(n + 6)
        f[0] = ((n + 6) shr 8).toByte(); f[1] = (n + 6).toByte()
        f[2] = 0x50; f[3] = 0x50
        f[4] = (n shr 8).toByte(); f[5] = n.toByte()
        System.arraycopy(ppp, 0, f, 6, n)
        return f
    }

    /** Reads one frame's payload into [buf] and returns its length. */
    fun read(input: DataInputStream, buf: ByteArray): Int {
        val h = ByteArray(6)
        input.readFully(h)
        if (h[0] == 'H'.code.toByte() && h[1] == 'T'.code.toByte() && h[2] == 'T'.code.toByte() && h[3] == 'P'.code.toByte()) {
            val line = String(h, Charsets.ISO_8859_1) + (Http.readLine(input) ?: "")
            throw IOException("Gateway refused the tunnel: ${line.trim().take(80)}")
        }
        val total = ((h[0].toInt() and 255) shl 8) or (h[1].toInt() and 255)
        val magic = ((h[2].toInt() and 255) shl 8) or (h[3].toInt() and 255)
        val len = ((h[4].toInt() and 255) shl 8) or (h[5].toInt() and 255)
        if (magic != MAGIC || total != len + 6) throw IOException("Bad tunnel frame (0x%04x, %d/%d)".format(magic, total, len))
        if (len > buf.size) throw IOException("Tunnel frame too large ($len)")
        input.readFully(buf, 0, len)
        return len
    }
}

/**
 * Runs PPP over an opened tunnel socket: a reader thread feeds the engine,
 * a ticker drives retransmits/keepalives. IP packets come out of [onIpPacket].
 */
class FortiLink(
    private val socket: Socket,
    private val log: (String) -> Unit = {},
) {
    val bytesIn = AtomicLong()
    val bytesOut = AtomicLong()
    @Volatile var lastRxAt = 0L
        private set

    @Volatile var onIpPacket: ((ByteArray, Int, Int) -> Unit)? = null
    @Volatile var onClosed: ((String) -> Unit)? = null

    private val out: OutputStream = socket.getOutputStream()
    private val writeLock = Any()
    private val engine = PppEngine(::writePpp, log)
    private val upLatch = CountDownLatch(1)
    @Volatile private var upResult: IpcpResult? = null
    @Volatile private var closeReason: String? = null
    @Volatile private var running = true

    private fun writePpp(ppp: ByteArray) {
        val f = FortiFrame.encode(ppp)
        synchronized(writeLock) {
            out.write(f)
            out.flush()
        }
    }

    /** Starts PPP and blocks until the IP link is up. */
    fun negotiate(timeoutMs: Long = 30_000): IpcpResult {
        engine.onUp = { r -> upResult = r; upLatch.countDown() }
        engine.onIp = { b, o, l ->
            bytesIn.addAndGet(l.toLong())
            onIpPacket?.invoke(b, o, l)
        }
        engine.onClosed = { reason -> shutdown(reason) }

        Thread({
            val input = DataInputStream(socket.getInputStream().buffered(65536))
            val buf = ByteArray(65536)
            try {
                while (running) {
                    val n = FortiFrame.read(input, buf)
                    lastRxAt = System.currentTimeMillis()
                    engine.onFrame(buf, 0, n)
                }
            } catch (e: EOFException) {
                shutdown("Gateway closed the connection")
            } catch (e: Exception) {
                shutdown(e.message ?: e.javaClass.simpleName)
            }
        }, "forti-reader").apply { isDaemon = true }.start()

        Thread({
            while (running) {
                try { Thread.sleep(1000) } catch (_: InterruptedException) { break }
                try { engine.tick() } catch (e: Exception) { shutdown(e.message ?: "Write failed") }
            }
        }, "forti-ticker").apply { isDaemon = true }.start()

        try { engine.start() } catch (e: IOException) { shutdown(e.message ?: "Write failed") }

        if (!upLatch.await(timeoutMs, TimeUnit.MILLISECONDS)) {
            shutdown("Timed out setting up the link")
        }
        return upResult ?: throw IOException(closeReason ?: "Link failed")
    }

    fun sendIp(b: ByteArray, off: Int, len: Int) {
        if (!running) return
        try {
            engine.sendIp(b, off, len)
            bytesOut.addAndGet(len.toLong())
        } catch (e: IOException) {
            shutdown(e.message ?: "Write failed")
        }
    }

    /** True if the gateway answers within [timeoutMs]; used after the phone changes network. */
    fun probe(timeoutMs: Long = 5_000): Boolean {
        if (!running) return false
        val t = System.currentTimeMillis()
        try { engine.sendEcho() } catch (e: IOException) { shutdown(e.message ?: "Write failed"); return false }
        while (running && System.currentTimeMillis() - t < timeoutMs) {
            if (lastRxAt >= t) return true
            Thread.sleep(100)
        }
        return running && lastRxAt >= t
    }

    /** Drops the link without the polite LCP goodbye (the network is already gone). */
    fun drop(reason: String) = shutdown(reason)

    fun close() {
        if (!running) return
        runCatching { engine.terminate() }
        shutdown("Disconnected")
    }

    private fun shutdown(reason: String) {
        synchronized(this) {
            if (!running) return
            running = false
            closeReason = reason
        }
        runCatching { socket.close() }
        upLatch.countDown()
        onClosed?.invoke(reason)
    }

    val isRunning get() = running
}
