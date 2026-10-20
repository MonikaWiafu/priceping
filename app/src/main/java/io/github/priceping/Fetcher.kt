package io.github.priceping

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.CookieHandler
import java.net.CookieManager
import java.net.CookiePolicy
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.Charset

/** Downloads a product page the way a normal desktop browser would ask for it. */
object Fetcher {
    private const val UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/129.0.0.0 Safari/537.36"
    private const val MAX_BYTES = 8_000_000

    class Page(val finalUrl: String, val code: Int, val body: String)

    init {
        // Keep session cookies between requests (some stores redirect until a cookie is set).
        if (CookieHandler.getDefault() == null) {
            CookieHandler.setDefault(CookieManager(null, CookiePolicy.ACCEPT_ALL))
        }
    }

    fun get(startUrl: String): Page {
        var url = startUrl
        for (hop in 0 until 8) {
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.instanceFollowRedirects = false
            conn.connectTimeout = 20_000
            conn.readTimeout = 25_000
            conn.setRequestProperty("User-Agent", UA)
            conn.setRequestProperty("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            conn.setRequestProperty("Accept-Language", "en-US,en;q=0.9")
            try {
                val code = conn.responseCode
                if (code in 300..399) {
                    val loc = conn.getHeaderField("Location") ?: throw IOException("Redirect without a location")
                    url = URL(URL(url), loc).toString()
                    continue
                }
                val stream = if (code >= 400) conn.errorStream else conn.inputStream
                val body = stream?.use { readLimited(it, charsetOf(conn.contentType)) } ?: ""
                return Page(url, code, body)
            } finally {
                conn.disconnect()
            }
        }
        throw IOException("Too many redirects")
    }

    private fun charsetOf(contentType: String?): Charset {
        val name = contentType?.let { Regex("""charset=([\w\-]+)""", RegexOption.IGNORE_CASE).find(it)?.groupValues?.get(1) }
        return try { if (name != null) Charset.forName(name) else Charsets.UTF_8 } catch (e: Exception) { Charsets.UTF_8 }
    }

    private fun readLimited(input: InputStream, cs: Charset): String {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(16 * 1024)
        while (out.size() < MAX_BYTES) {
            val n = input.read(buf)
            if (n < 0) break
            out.write(buf, 0, n)
        }
        return String(out.toByteArray(), cs)
    }
}
