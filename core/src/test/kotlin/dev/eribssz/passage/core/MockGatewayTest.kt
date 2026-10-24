package dev.eribssz.passage.core

import java.io.DataInputStream
import java.io.File
import java.net.Socket
import java.security.KeyStore
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLServerSocket
import kotlin.concurrent.thread
import kotlin.test.*

/** A fake FortiGate: logincheck, config XML and a PPP peer behind sslvpn-tunnel. */
class MockGateway : AutoCloseable {
    val server: SSLServerSocket
    val port get() = server.localPort
    val sawProtoReject = LinkedBlockingQueue<Int>()

    init {
        val ks = File.createTempFile("mockgw", ".p12").apply { delete() }
        val p = ProcessBuilder("keytool", "-genkeypair", "-alias", "gw", "-keyalg", "RSA", "-keysize", "2048",
            "-dname", "CN=FortiGate,O=Fortinet", "-validity", "30", "-storetype", "PKCS12",
            "-keystore", ks.path, "-storepass", "secret", "-keypass", "secret").redirectErrorStream(true).start()
        p.inputStream.readBytes(); check(p.waitFor() == 0)
        val store = KeyStore.getInstance("PKCS12").apply { ks.inputStream().use { load(it, "secret".toCharArray()) } }
        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(store, "secret".toCharArray()) }
        val ctx = SSLContext.getInstance("TLS").apply { init(kmf.keyManagers, null, null) }
        server = ctx.serverSocketFactory.createServerSocket(0) as SSLServerSocket
        thread(isDaemon = true) {
            while (!server.isClosed) {
                val s = try { server.accept() } catch (e: Exception) { break }
                thread(isDaemon = true) { runCatching { handle(s) } }
            }
        }
    }

    private fun handle(s: Socket) {
        val input = s.getInputStream()
        val line = Http.readLine(input) ?: return
        val headers = mutableMapOf<String, String>()
        while (true) {
            val h = Http.readLine(input) ?: break
            if (h.isEmpty()) break
            headers[h.substringBefore(':').trim().lowercase()] = h.substringAfter(':').trim()
        }
        val body = headers["content-length"]?.toInt()?.let { n -> String(input.readNBytes(n)) } ?: ""
        val path = line.split(' ')[1]
        val out = s.getOutputStream()
        fun reply(status: Int, text: String, extra: String = "") {
            val b = text.toByteArray()
            out.write("HTTP/1.1 $status OK\r\n${extra}Content-Length: ${b.size}\r\nConnection: close\r\n\r\n".toByteArray())
            out.write(b); out.flush(); s.close()
        }
        when {
            path == "/remote/logincheck" -> when {
                body.contains("credential=right%26pass") ->
                    reply(200, "ret=1,redir=/remote/fortisslvpn", "Set-Cookie: SVPNCOOKIE=abc123; path=/; secure\r\n")
                body.contains("username=tok") && !body.contains("code=") ->
                    reply(200, "ret=2,reqid=7,polid=1-1-1,grp=,portal=full,magic=1-99,tokeninfo=,chal_msg=Enter FortiToken code")
                body.contains("code=123456") && body.contains("reqid=7") ->
                    reply(200, "ret=1", "Set-Cookie: SVPNCOOKIE=tok999; path=/\r\n")
                else -> reply(200, "ret=0,redir=/remote/login", "Set-Cookie: SVPNCOOKIE=; path=/\r\n")
            }
            path.startsWith("/remote/fortisslvpn_xml") -> reply(200, """<?xml version='1.0' encoding='utf-8'?>
                <sslvpn-tunnel ver='2' dtls='1' patch='1'>
                <dtls-config heartbeat-interval='10' heartbeat-fail-count='10' heartbeat-idle-timeout='10' client-hello-timeout='10'/>
                <fos platform='FG100F' major='7' minor='2' patch='8' build='1639' branch='1639'/>
                <auth-ses tun-connect-without-reauth='0' tun-user-ses-timeout='30'/>
                <client-config save-password='off' keep-alive='on' auto-connect='off'/>
                <ipv4><dns ip='10.0.0.53'/><dns ip='10.0.0.54'/><dns domain='corp.example'/>
                <assigned-addr ipv4='10.212.134.200'/>
                <split-tunnel-info><addr ip='10.0.0.0' mask='255.255.0.0'/><addr ip='192.168.50.0' mask='255.255.255.0'/></split-tunnel-info>
                </ipv4><idle-timeout val='3600'/><auth-timeout val='28800'/></sslvpn-tunnel>""")
            path == "/remote/sslvpn-tunnel" -> {
                assertEquals("SVPNCOOKIE=abc123", headers["cookie"])
                pppPeer(s)
            }
            else -> reply(200, "<html>ok</html>")
        }
    }

    private fun pppPeer(s: Socket) {
        val input = DataInputStream(s.getInputStream())
        val out = s.getOutputStream()
        val lock = Any()
        fun send(p: ByteArray) = synchronized(lock) { out.write(FortiFrame.encode(p)); out.flush() }
        fun ctl(proto: Int, code: Int, id: Int, data: ByteArray) =
            byteArrayOf((proto shr 8).toByte(), proto.toByte(), code.toByte(), id.toByte(), 0, (4 + data.size).toByte()) + data
        // Gateway's own LCP request: MRU 1400, magic, plus PFC/ACFC
        send(ctl(0xC021, 1, 1, byteArrayOf(1, 4, 0x05, 0x78.toByte(), 5, 6, 0x11, 0x22, 0x33, 0x44, 7, 2, 8, 2)))
        val buf = ByteArray(65536)
        var sentIpcp = false
        while (true) {
            val n = try { FortiFrame.read(input, buf) } catch (e: Exception) { return }
            val proto = ((buf[0].toInt() and 255) shl 8) or (buf[1].toInt() and 255)
            val code = buf[2].toInt() and 255
            val id = buf[3].toInt() and 255
            val data = buf.copyOfRange(6, n)
            when (proto) {
                0xC021 -> when (code) {
                    1 -> {
                        send(ctl(0xC021, 2, id, data))
                        // Send a CCP request too; the client must Protocol-Reject it.
                        send(ctl(0x80FD, 1, 9, byteArrayOf()))
                    }
                    8 -> sawProtoReject.add(((data[0].toInt() and 255) shl 8) or (data[1].toInt() and 255))
                    9 -> send(ctl(0xC021, 10, id, byteArrayOf(0x11, 0x22, 0x33, 0x44)))
                }
                0x8021 -> {
                    if (!sentIpcp) { sentIpcp = true; send(ctl(0x8021, 1, 3, byteArrayOf(3, 6, -64, 0, 2, 1))) }
                    if (code == 1) {
                        val want = byteArrayOf(3, 6, 10, -44, -122, -56, -127, 6, 10, 0, 0, 53, -125, 6, 10, 0, 0, 54)
                        if (data.contentEquals(want)) send(ctl(0x8021, 2, id, data))
                        else send(ctl(0x8021, 3, id, want))
                    }
                }
                0x0021 -> send(buf.copyOfRange(0, n)) // echo IP packets back
            }
        }
    }

    override fun close() = server.close()
}

class MockGatewayTest {
    private val opener = SocketOpener { h, p -> Socket(h, p) }
    private val logs = mutableListOf<String>()
    private val log: (String) -> Unit = { synchronized(logs) { logs += it }; println("  [log] $it") }

    @Test
    fun fullConnectFlow() = MockGateway().use { gw ->
        // 1. Unknown cert -> must ask the user, with a fingerprint
        val e = assertFailsWith<UntrustedCertException> {
            FortiSession(TlsConnector("127.0.0.1", gw.port, null, opener), log).login("alex", "right&pass")
        }
        assertFalse(e.info.changed)
        assertEquals(64, e.info.sha256.length)
        assertTrue(e.info.subject.contains("FortiGate"))

        // 2. Wrong pin -> flagged as changed
        val e2 = assertFailsWith<UntrustedCertException> {
            FortiSession(TlsConnector("127.0.0.1", gw.port, "00".repeat(32), opener), log).login("alex", "right&pass")
        }
        assertTrue(e2.info.changed)

        val tls = TlsConnector("127.0.0.1", gw.port, e.info.sha256, opener)

        // 3. Wrong password
        assertFailsWith<AuthException> { FortiSession(tls, log).login("alex", "nope") }

        // 4. Real login + config
        val session = FortiSession(tls, log)
        session.login("alex", "right&pass")
        assertEquals("abc123", session.cookie)
        val cfg = session.fetchConfig()
        assertEquals("10.212.134.200", cfg.address)
        assertEquals(listOf("10.0.0.53", "10.0.0.54"), cfg.dns)
        assertEquals(listOf("corp.example"), cfg.searchDomains)
        assertEquals(listOf(Route("10.0.0.0", 16), Route("192.168.50.0", 24)), cfg.routes)
        assertEquals("7.2.8", cfg.fortiOs)

        // 5. Tunnel + PPP
        val link = FortiLink(session.openTunnel(), log)
        val got = LinkedBlockingQueue<ByteArray>()
        link.onIpPacket = { b, o, l -> got.add(b.copyOfRange(o, o + l)) }
        val r = link.negotiate(15_000)
        assertEquals("10.212.134.200", r.localIp)
        assertEquals("192.0.2.1", r.peerIp)
        assertEquals(listOf("10.0.0.53", "10.0.0.54"), r.dns)
        assertEquals(1400, r.mtu)
        assertEquals(0x80FD, gw.sawProtoReject.poll(5, TimeUnit.SECONDS))

        val pkt = ByteArray(60) { (0x45 + it).toByte() }
        link.sendIp(pkt, 0, pkt.size)
        assertContentEquals(pkt, got.poll(5, TimeUnit.SECONDS))
        assertEquals(60, link.bytesOut.get()); assertEquals(60, link.bytesIn.get())
        link.close()
        assertFalse(link.isRunning)
    }

    @Test
    fun tokenFlow() = MockGateway().use { gw ->
        val pin = assertFailsWith<UntrustedCertException> {
            FortiSession(TlsConnector("127.0.0.1", gw.port, null, opener)).login("x", "y")
        }.info.sha256
        val s = FortiSession(TlsConnector("127.0.0.1", gw.port, pin, opener), log)
        val t = assertFailsWith<TokenRequiredException> { s.login("tok", "pw") }
        assertEquals("Enter FortiToken code", t.prompt)
        s.submitToken("tok", "123456", t)
        assertEquals("tok999", s.cookie)
    }

    private fun deadPort(): Int = java.net.ServerSocket(0).use { it.localPort }

    @Test
    fun failoverToNextAddress() = MockGateway().use { gw ->
        val pin = assertFailsWith<UntrustedCertException> {
            FortiSession(TlsConnector("127.0.0.1", gw.port, null, opener)).login("x", "y")
        }.info.sha256
        val dead = Endpoint("127.0.0.1", deadPort())
        val live = Endpoint("127.0.0.1", gw.port)
        val steps = mutableListOf<String>()
        // Pin was recorded under another address of the same gateway: still accepted.
        val r = FortiConnector(opener, log).connect(
            listOf(dead, live), startIndex = 0, username = "alex", password = "right&pass", realm = "",
            pins = mapOf("10.9.9.9:4433" to pin), onStep = { s, ep -> steps += "$s@${ep.port}" })
        assertEquals(1, r.index)
        assertEquals(live, r.endpoint)
        assertEquals("10.212.134.200", r.ipcp.localIp)
        assertTrue(steps.first().startsWith("Signing in@${dead.port}"))
        r.link.close()

        // Start index is honoured (last-good first) and wraps around.
        val r2 = FortiConnector(opener, log).connect(listOf(dead, live), 3, "alex", "right&pass", "", mapOf(live.key to pin))
        assertEquals(1, r2.index); r2.link.close()

        // Wrong password stops at once instead of cycling.
        val before = synchronized(logs) { logs.count { it.startsWith("Trying") } }
        assertFailsWith<AuthException> {
            FortiConnector(opener, log).connect(listOf(live, dead), 0, "alex", "bad", "", mapOf(live.key to pin))
        }
        assertEquals(1, synchronized(logs) { logs.count { it.startsWith("Trying") } } - before)

        // All addresses dead -> one clear error.
        val e = assertFailsWith<java.io.IOException> {
            FortiConnector(opener, log).connect(listOf(dead, Endpoint("127.0.0.1", deadPort())), 0, "u", "p", "", emptyMap())
        }
        assertTrue(e.message!!.startsWith("All 2 addresses failed"))
    }

    @Test
    fun retriesSameAddressBeforeMovingOn() = MockGateway().use { gw ->
        val pin = assertFailsWith<UntrustedCertException> {
            FortiSession(TlsConnector("127.0.0.1", gw.port, null, opener)).login("x", "y")
        }.info.sha256
        // The first two connections to the live gateway fail (a "mobile blip"), then it works.
        var blips = 2
        val flaky = SocketOpener { h, p ->
            if (p == gw.port && blips-- > 0) throw java.net.SocketTimeoutException("blip") else Socket(h, p)
        }
        val other = Endpoint("127.0.0.1", gw.port + 0).let { Endpoint("127.0.0.2", gw.port) }
        val live = Endpoint("127.0.0.1", gw.port)
        val slept = mutableListOf<Long>()
        val r = FortiConnector(flaky, log).connect(listOf(live, other), 0, "alex", "right&pass", "",
            mapOf(live.key to pin), firstAttempts = 3, sleep = { slept += it })
        assertEquals(0, r.index, "should stay on the last-good address")
        assertEquals(listOf(2000L, 4000L), slept)

        // Link probe: the gateway answers LCP echoes.
        assertTrue(r.link.probe(3000))
        r.link.close()
        assertFalse(r.link.probe(500))
    }

    @Test
    fun certChangedOnlyForSameAddress() = MockGateway().use { gw ->
        val other = "AA".repeat(32)
        val c = FortiConnector(opener, log)
        val ep = Endpoint("127.0.0.1", gw.port)
        val fresh = assertFailsWith<UntrustedCertException> { c.connect(listOf(ep), 0, "u", "p", "", mapOf("1.1.1.1:443" to other)) }
        assertFalse(fresh.info.changed); assertEquals(ep.key, fresh.info.endpoint)
        val changed = assertFailsWith<UntrustedCertException> { c.connect(listOf(ep), 0, "u", "p", "", mapOf(ep.key to other)) }
        assertTrue(changed.info.changed)
    }

    @Test
    fun endpointParsing() {
        assertEquals(Endpoint("203.0.113.10", 4433), Endpoint.parse(" 203.0.113.10:4433 "))
        assertEquals(Endpoint("vpn.example.com", 443), Endpoint.parse("vpn.example.com"))
        assertEquals(Endpoint("vpn.example.com", 10443), Endpoint.parse("https://vpn.example.com:10443/remote/login"))
        assertNull(Endpoint.parse("1.2.3.4:99999"))
        assertNull(Endpoint.parse("bad host"))
    }

    @Test
    fun maskParsing() {
        assertEquals(24, FortiSession.maskToPrefix("255.255.255.0"))
        assertEquals(32, FortiSession.maskToPrefix("255.255.255.255"))
        assertEquals(0, FortiSession.maskToPrefix("0.0.0.0"))
        val full = FortiSession.parseConfig("<sslvpn-tunnel><ipv4><assigned-addr ipv4=\"10.1.1.2\" /></ipv4></sslvpn-tunnel>")
        assertTrue(full.routes.isEmpty())
        assertEquals("10.1.1.2", full.address)
    }
}
