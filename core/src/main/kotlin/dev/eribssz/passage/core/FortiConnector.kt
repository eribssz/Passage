package dev.eribssz.passage.core

import java.io.IOException

/** One address of a gateway. */
data class Endpoint(val host: String, val port: Int) {
    val key get() = "$host:$port"
    override fun toString() = key

    companion object {
        /** "1.2.3.4:4433", "vpn.example.com" (port 443), "https://host:10443/..." */
        fun parse(text: String): Endpoint? {
            var s = text.trim().removePrefix("https://").removePrefix("http://").substringBefore('/')
            if (s.isEmpty() || s.any { it.isWhitespace() }) return null
            val i = s.lastIndexOf(':')
            val port = if (i > 0) s.substring(i + 1).toIntOrNull() ?: return null else 443
            if (i > 0) s = s.substring(0, i)
            if (port !in 1..65535 || s.isEmpty()) return null
            return Endpoint(s, port)
        }
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
