package dev.eribssz.passage.core

import java.io.IOException
import java.net.URLEncoder
import javax.net.ssl.SSLSocket

class AuthException(message: String) : Exception(message)

/** The gateway wants a second factor (FortiToken, e-mail or SMS code). */
class TokenRequiredException(val prompt: String, internal val fields: Map<String, String>) :
    Exception("Verification code required")

data class Route(val address: String, val prefix: Int) {
    override fun toString() = "$address/$prefix"
}

data class VpnConfig(
    val address: String?,
    val dns: List<String>,
    val searchDomains: List<String>,
    /** Empty means full tunnel (all traffic through the VPN). */
    val routes: List<Route>,
    val fortiOs: String?,
)

/**
 * Fortinet SSL-VPN (tunnel mode) session: the same sequence openfortivpn and
 * FortiClient use — logincheck -> session cookie -> config XML -> sslvpn-tunnel.
 */
class FortiSession(
    private val tls: TlsConnector,
    private val log: (String) -> Unit = {},
) {
    private val hostHeader = if (tls.port == 443) tls.host else "${tls.host}:${tls.port}"
    var cookie: String? = null
        private set

    private fun call(method: String, path: String, body: String? = null): HttpResponse {
        val s = tls.connect()
        try {
            return Http.request(s, method, path, hostHeader, cookie?.let { "SVPNCOOKIE=$it" }, body)
        } finally {
            runCatching { s.close() }
        }
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    private fun parseRet(body: String): Map<String, String> =
        body.trim().split(',').mapNotNull {
            val i = it.indexOf('=')
            if (i <= 0) null else it.substring(0, i).trim() to it.substring(i + 1).trim()
        }.toMap()

    fun login(username: String, password: String, realm: String = "") {
        log("Signing in as $username")
        val r = call("POST", "/remote/logincheck",
            "username=${enc(username)}&credential=${enc(password)}&realm=${enc(realm)}&ajax=1")
        handleLogin(r, username, realm)
    }

    fun submitToken(username: String, code: String, pending: TokenRequiredException, realm: String = "") {
        val f = pending.fields
        val body = buildString {
            append("username=${enc(username)}&realm=${enc(realm)}")
            for (k in listOf("reqid", "polid", "grp", "portal", "peer", "magic")) {
                f[k]?.let { append("&$k=${enc(it)}") }
            }
            append("&code=${enc(code)}&code2=&ajax=1")
        }
        log("Sending verification code")
        handleLogin(call("POST", "/remote/logincheck", body), username, realm)
    }

    private fun handleLogin(r: HttpResponse, username: String, realm: String) {
        val c = r.setCookies()["SVPNCOOKIE"]
        if (!c.isNullOrEmpty()) {
            cookie = c
            log("Signed in")
            return
        }
        val ret = parseRet(r.body)
        when {
            ret["ret"] == "2" || ret.containsKey("tokeninfo") -> {
                val prompt = ret["chal_msg"]?.takeIf { it.isNotBlank() } ?: "Enter the verification code"
                throw TokenRequiredException(prompt, ret)
            }
            r.status == 405 || r.status == 404 -> throw IOException("Gateway refused the login request (HTTP ${r.status})")
            r.status >= 500 -> throw IOException("Gateway error (HTTP ${r.status})")
            else -> throw AuthException("Wrong username or password")
        }
    }

    /** Asks the gateway to allocate the tunnel and returns the network settings. */
    fun fetchConfig(): VpnConfig {
        checkNotNull(cookie) { "Not signed in" }
        runCatching { call("GET", "/remote/index") }
        runCatching { call("GET", "/remote/fortisslvpn") }
        var r = call("GET", "/remote/fortisslvpn_xml")
        if (r.status != 200 || !r.body.contains("<")) {
            r = call("GET", "/remote/fortisslvpn_xml?dual_stack=1")
        }
        if (r.status == 403 || r.status == 401) throw AuthException("Session rejected by gateway")
        if (r.status != 200) throw IOException("Could not get tunnel settings (HTTP ${r.status})")
        return parseConfig(r.body)
    }

    /** Opens the raw tunnel connection. After this the socket carries framed PPP. */
    fun openTunnel(): SSLSocket {
        val c = checkNotNull(cookie) { "Not signed in" }
        val s = tls.connect()
        val req = "GET /remote/sslvpn-tunnel HTTP/1.1\r\nHost: sslvpn\r\nCookie: SVPNCOOKIE=$c\r\n\r\n"
        s.getOutputStream().apply { write(req.toByteArray(Charsets.ISO_8859_1)); flush() }
        s.soTimeout = 0
        log("Tunnel opened")
        return s
    }

    fun logout() {
        if (cookie == null) return
        runCatching { call("GET", "/remote/logout") }
        cookie = null
    }

    companion object {
        private fun attr(tag: String, name: String): String? =
            Regex("""\b$name\s*=\s*['"]([^'"]*)['"]""").find(tag)?.groupValues?.get(1)

        private fun tags(xml: String, name: String): List<String> =
            Regex("""<$name\b[^>]*>""").findAll(xml).map { it.value }.toList()

        fun maskToPrefix(mask: String): Int {
            val parts = mask.split('.').mapNotNull { it.toIntOrNull() }
            if (parts.size != 4) return mask.toIntOrNull() ?: 32
            return parts.sumOf { Integer.bitCount(it and 0xff) }
        }

        fun parseConfig(xml: String): VpnConfig {
            val address = tags(xml, "assigned-addr").firstNotNullOfOrNull { attr(it, "ipv4") }
            val dnsTags = tags(xml, "dns")
            val dns = dnsTags.mapNotNull { attr(it, "ip") }.filter { it.isNotBlank() && it != "0.0.0.0" }.distinct()
            val domains = dnsTags.mapNotNull { attr(it, "domain") }
                .flatMap { it.split(';', ',', ' ') }.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
            val splitBlock = Regex("""<split-tunnel-info\b[^>]*>(.*?)</split-tunnel-info>""", RegexOption.DOT_MATCHES_ALL)
                .find(xml)?.groupValues?.get(1).orEmpty()
            val routes = tags(splitBlock, "addr").mapNotNull { t ->
                val ip = attr(t, "ip") ?: return@mapNotNull null
                Route(ip, maskToPrefix(attr(t, "mask") ?: "255.255.255.255"))
            }.distinct()
            val fos = tags(xml, "fos").firstOrNull()?.let { t ->
                val maj = attr(t, "major"); val min = attr(t, "minor"); val pat = attr(t, "patch")
                if (maj != null) listOfNotNull(maj, min, pat).joinToString(".") else null
            }
            return VpnConfig(address, dns, domains, routes, fos)
        }
    }
}
