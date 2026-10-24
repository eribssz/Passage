package dev.eribssz.passage.core

import java.net.Socket
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLException
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509TrustManager

/** Details of the gateway's certificate, shown to the user before trusting it. */
data class CertInfo(
    val sha256: String,
    val subject: String,
    val issuer: String,
    val notAfter: Long,
    /** true when a different certificate was pinned before (possible interception). */
    val changed: Boolean,
    /** "host:port" of the gateway that presented it. */
    val endpoint: String = "",
)

class UntrustedCertException(val info: CertInfo) :
    Exception(if (info.changed) "Server certificate changed" else "Server certificate not yet trusted")

/** Opens a plain TCP socket. On Android this protects the socket from the VPN itself. */
fun interface SocketOpener {
    fun open(host: String, port: Int): Socket
}

fun sha256Hex(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02X".format(it) }

/**
 * Trust-on-first-use: the gateway's leaf certificate must match the pinned SHA-256.
 * No pin yet -> the handshake fails and [problem] carries the details so the UI can ask.
 */
internal class PinTrustManager(
    private val accepted: Set<String>,
    private val previous: String?,
    private val endpoint: String,
) : X509TrustManager {
    var problem: CertInfo? = null

    override fun checkServerTrusted(chain: Array<out X509Certificate>, authType: String?) {
        val leaf = chain.firstOrNull() ?: throw CertificateException("Empty certificate chain")
        val fp = sha256Hex(leaf.encoded)
        if (accepted.any { it.equals(fp, ignoreCase = true) }) return
        problem = CertInfo(
            sha256 = fp,
            subject = leaf.subjectX500Principal.name,
            issuer = leaf.issuerX500Principal.name,
            notAfter = leaf.notAfter.time,
            changed = previous != null,
            endpoint = endpoint,
        )
        throw CertificateException("Certificate not pinned")
    }

    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) =
        throw CertificateException("Not a server")

    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
}

/**
 * @param acceptedPins certificates trusted for this gateway (any address of the same profile —
 *   load-balanced IPs usually present the same certificate).
 * @param previousPin what this exact address presented before, if anything; a mismatch then
 *   counts as "changed" rather than "new".
 */
class TlsConnector(
    val host: String,
    val port: Int,
    private val acceptedPins: Set<String>,
    private val previousPin: String?,
    private val opener: SocketOpener,
) {
    constructor(host: String, port: Int, pin: String?, opener: SocketOpener) :
        this(host, port, setOfNotNull(pin), pin, opener)

    fun connect(timeoutMs: Int = 20_000): SSLSocket {
        val tm = PinTrustManager(acceptedPins, previousPin, "$host:$port")
        val ctx = SSLContext.getInstance("TLS")
        ctx.init(null, arrayOf(tm), null)
        val raw = opener.open(host, port)
        val s = ctx.socketFactory.createSocket(raw, host, port, true) as SSLSocket
        s.soTimeout = timeoutMs
        try {
            s.startHandshake()
        } catch (e: SSLException) {
            runCatching { s.close() }
            tm.problem?.let { throw UntrustedCertException(it) }
            throw e
        }
        return s
    }
}
