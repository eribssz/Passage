package dev.eribssz.passage.core

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.Socket

class HttpResponse(
    val status: Int,
    val headers: List<Pair<String, String>>,
    val body: String,
) {
    fun headers(name: String) = headers.filter { it.first.equals(name, true) }.map { it.second }

    /** Cookies set by this response (name -> value; empty value means "cleared"). */
    fun setCookies(): Map<String, String> = headers("Set-Cookie").mapNotNull { h ->
        val kv = h.substringBefore(';')
        val i = kv.indexOf('=')
        if (i <= 0) null else kv.substring(0, i).trim() to kv.substring(i + 1).trim()
    }.toMap()
}

internal object Http {
    private const val MAX_BODY = 2 * 1024 * 1024

    fun request(
        socket: Socket,
        method: String,
        path: String,
        hostHeader: String,
        cookie: String? = null,
        body: String? = null,
    ): HttpResponse {
        val sb = StringBuilder()
        sb.append("$method $path HTTP/1.1\r\n")
        sb.append("Host: $hostHeader\r\n")
        sb.append("User-Agent: Mozilla/5.0 SV1\r\n")
        sb.append("Accept: */*\r\n")
        sb.append("Accept-Encoding: identity\r\n")
        sb.append("Pragma: no-cache\r\n")
        sb.append("Cache-Control: no-store, no-cache, must-revalidate\r\n")
        sb.append("If-Modified-Since: Sat, 1 Jan 2000 00:00:00 GMT\r\n")
        sb.append("Connection: close\r\n")
        if (cookie != null) sb.append("Cookie: $cookie\r\n")
        val bodyBytes = body?.toByteArray(Charsets.UTF_8)
        if (bodyBytes != null) {
            sb.append("Content-Type: application/x-www-form-urlencoded\r\n")
            sb.append("Content-Length: ${bodyBytes.size}\r\n")
        }
        sb.append("\r\n")
        val out = socket.getOutputStream()
        out.write(sb.toString().toByteArray(Charsets.ISO_8859_1))
        if (bodyBytes != null) out.write(bodyBytes)
        out.flush()
        return read(socket.getInputStream(), method == "HEAD")
    }

    fun readLine(input: InputStream): String? {
        val buf = ByteArrayOutputStream()
        while (true) {
            val c = input.read()
            if (c < 0) return if (buf.size() == 0) null else buf.toString(Charsets.ISO_8859_1.name())
            if (c == '\n'.code) break
            if (c != '\r'.code) buf.write(c)
            if (buf.size() > 16 * 1024) throw IOException("Header line too long")
        }
        return buf.toString(Charsets.ISO_8859_1.name())
    }

    fun read(input: InputStream, noBody: Boolean = false): HttpResponse {
        val statusLine = readLine(input) ?: throw IOException("Connection closed by gateway")
        val parts = statusLine.split(' ')
        if (parts.size < 2 || !parts[0].startsWith("HTTP/")) throw IOException("Unexpected reply: ${statusLine.take(60)}")
        val status = parts[1].toIntOrNull() ?: throw IOException("Bad status: $statusLine")
        val headers = mutableListOf<Pair<String, String>>()
        while (true) {
            val line = readLine(input) ?: break
            if (line.isEmpty()) break
            val i = line.indexOf(':')
            if (i > 0) headers += line.substring(0, i).trim() to line.substring(i + 1).trim()
        }
        fun h(n: String) = headers.firstOrNull { it.first.equals(n, true) }?.second
        val body = ByteArrayOutputStream()
        if (!noBody && status != 204 && status != 304) {
            when {
                h("Transfer-Encoding")?.contains("chunked", true) == true -> {
                    while (true) {
                        val sizeLine = readLine(input) ?: break
                        val size = sizeLine.substringBefore(';').trim().toIntOrNull(16) ?: break
                        if (size == 0) { readLine(input); break }
                        readExact(input, size, body)
                        readLine(input)
                    }
                }
                h("Content-Length") != null -> readExact(input, h("Content-Length")!!.trim().toInt(), body)
                else -> {
                    val b = ByteArray(8192)
                    while (true) {
                        val n = try { input.read(b) } catch (e: IOException) { -1 }
                        if (n < 0) break
                        body.write(b, 0, n)
                        if (body.size() > MAX_BODY) break
                    }
                }
            }
        }
        return HttpResponse(status, headers, body.toString(Charsets.UTF_8.name()))
    }

    private fun readExact(input: InputStream, n: Int, out: ByteArrayOutputStream) {
        if (n > MAX_BODY) throw IOException("Response too large")
        val b = ByteArray(minOf(n, 8192))
        var left = n
        while (left > 0) {
            val r = input.read(b, 0, minOf(left, b.size))
            if (r < 0) throw IOException("Connection closed mid-response")
            out.write(b, 0, r)
            left -= r
        }
    }
}
