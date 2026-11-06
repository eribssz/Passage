package dev.eribssz.passage.core

import java.io.IOException

/** One address of a gateway. */
data class Endpoint(val host: String, val port: Int) {
    val key get() = "$host:$port"
    override fun toString() = key

    companion object {
        /** "1.2.3.4:4433", "vpn.example.com" (port 443), "https://host:10443/..." */
        fun parse(text: String): Endpoint? = check(text) as? Endpoint

        /** Returns an [Endpoint], or a String saying what's wrong with [text]. */
        fun check(text: String): Any {
            var s = text.trim().removePrefix("https://").removePrefix("http://").substringBefore('/')
            if (s.isEmpty() || s.any { it.isWhitespace() }) return "Use IP:port, e.g. 203.0.113.10:4433"
            if (s.startsWith('[') || s.count { it == ':' } > 1) return "IPv6 addresses aren't supported"
            val i = s.lastIndexOf(':')
            val port = if (i >= 0) s.substring(i + 1).ifEmpty { "443" }.toIntOrNull() else 443
            if (i >= 0) s = s.substring(0, i)
            if (port == null || port !in 1..65535) return "Port must be 1–65535"
            if (s.isEmpty()) return "Missing IP address"
            if (s.all { it.isDigit() || it == '.' }) {
                return if (isIpv4(s)) Endpoint(s, port) else "Not a valid IP address"
            }
            return if (isHostname(s)) Endpoint(s.lowercase(), port) else "Not a valid IP address or host name"
        }

        fun isIpv4(s: String): Boolean {
            val parts = s.split('.')
            return parts.size == 4 && parts.all { p ->
                p.length in 1..3 && p.all { it.isDigit() } && p.toInt() <= 255 && (p.length == 1 || p[0] != '0')
            }
        }

        private val LABEL = Regex("[A-Za-z0-9]([A-Za-z0-9-]{0,61}[A-Za-z0-9])?")

        /** A DNS name whose last label has a letter, so all-digit junk never counts as a host. */
        fun isHostname(s: String): Boolean {
            val h = s.removeSuffix(".")
            if (h.isEmpty() || h.length > 253) return false
            val labels = h.split('.')
            return labels.all { LABEL.matches(it) } && labels.last().any { it.isLetter() }
        }
    }
}

/**
 * Typing mask for the "IP:port" field. Only acts when the user typed a single
 * character at the end of a numeric address, so host names, pasting and
 * backspace behave as usual.
 *  - an octet is capped at 3 digits / 255, and a "." is added once it can't grow;
 *  - after the 4th octet the separator is ":" (typing "." there gives ":" too);
 *  - the port is capped at 5 digits / 65535.
 */
object AddressMask {
    fun apply(old: String, new: String): String {
        if (new.length != old.length + 1 || !new.startsWith(old)) return new
        if (!old.all { it.isDigit() || it == '.' || it == ':' }) return new
        val c = new.last()
        if (!c.isDigit() && c != '.' && c != ':') return new
        val inPort = ':' in old
        val octets = old.substringBefore(':').split('.')
        val cur = if (inPort) old.substringAfter(':') else octets.last()
        val octetNo = octets.size  // 1..4 while typing the IP
        if (inPort) {
            if (!c.isDigit()) return old
            val port = cur + c
            return if (port.length > 5 || port.toInt() > 65535 || port == "0") old else new
        }
        if (!c.isDigit()) {
            if (cur.isEmpty()) return old                       // no empty octets
            return if (octetNo < 4) old + "." else old + ":"    // "." or ":" → the right separator
        }
        val next = cur + c
        if (next.toInt() > 255 || (cur == "0")) {
            // This digit can't belong here: start the next block with it.
            return if (octetNo < 4) "$old.$c" else "$old:$c".takeIf { c != '0' } ?: old
        }
        val full = next.length == 3 || next.toInt() * 10 > 255 || next == "0"
        return if (full) new + (if (octetNo < 4) "." else ":") else new
    }
}

class CancelledException : Exception("Cancelled")

class ConnectResult(
    val endpoint: Endpoint,
    val index: Int,
    val session: FortiSession,
    val config: VpnConfig,
    val link: FortiLink,
    val ipcp: IpcpResult,
)

/**
 * Tries the gateway's addresses in turn, starting from [startIndex] (usually the last
 * one that worked). Network-level failures move on to the next address; a wrong
 * password or an untrusted certificate stops immediately because they need the user.
 */
class FortiConnector(
    private val opener: SocketOpener,
    private val log: (String) -> Unit = {},
) {
    fun connect(
        endpoints: List<Endpoint>,
        startIndex: Int,
        username: String,
        password: String,
        realm: String,
        pins: Map<String, String>,
        onStep: (String, Endpoint) -> Unit = { _, _ -> },
        askCode: (String) -> String? = { null },
        cancelled: () -> Boolean = { false },
        onLinkCreated: (FortiLink) -> Unit = {},
        /** Tries on the starting address before moving on (a mobile blip usually isn't the server). */
        firstAttempts: Int = 1,
        sleep: (Long) -> Unit = { Thread.sleep(it) },
    ): ConnectResult {
        require(endpoints.isNotEmpty()) { "No gateway address configured" }
        val n = endpoints.size
        val start = ((startIndex % n) + n) % n
        var lastError: Exception? = null
        val trusted = pins.values.toSet()

        for (k in 0 until n) for (attempt in 0 until if (k == 0) firstAttempts.coerceAtLeast(1) else 1) {
            if (cancelled()) throw CancelledException()
            val idx = (start + k) % n
            val ep = endpoints[idx]
            if (attempt > 0) {
                val wait = 2_000L * attempt
                log("Retrying ${ep.key} in ${wait / 1000}s")
                onStep("Retrying", ep)
                sleep(wait)
                if (cancelled()) throw CancelledException()
            }
            if (n > 1 || attempt > 0) log("Trying ${ep.key} (${k + 1}/$n${if (attempt > 0) ", attempt ${attempt + 1}" else ""})")
            var session: FortiSession? = null
            try {
                val tls = TlsConnector(ep.host, ep.port, trusted, pins[ep.key], opener)
                val s = FortiSession(tls, log)
                session = s
                onStep("Signing in", ep)
                try {
                    s.login(username, password, realm)
                } catch (t: TokenRequiredException) {
                    val code = askCode(t.prompt)
                    if (code.isNullOrBlank()) throw CancelledException()
                    onStep("Verifying code", ep)
                    s.submitToken(username, code, t, realm)
                }
                if (cancelled()) throw CancelledException()
                onStep("Getting tunnel settings", ep)
                val cfg = s.fetchConfig()
                if (cancelled()) throw CancelledException()
                onStep("Opening tunnel", ep)
                val link = FortiLink(s.openTunnel(), log)
                onLinkCreated(link)
                val r = link.negotiate()
                return ConnectResult(ep, idx, s, cfg, link, r)
            } catch (e: UntrustedCertException) {
                throw e
            } catch (e: AuthException) {
                throw e
            } catch (e: CancelledException) {
                throw e
            } catch (e: Exception) {
                lastError = e
                log("${ep.key} failed: ${e.message ?: e.javaClass.simpleName}")
                session?.let { runCatching { it.logout() } }
            }
        }
        val reason = lastError?.message ?: "unknown error"
        throw IOException(if (n == 1) reason else "All $n addresses failed (last: $reason)", lastError)
    }
}
