package com.muse.agent

import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.InetAddress
import java.net.URI
import java.util.concurrent.TimeUnit

class UrlBlocked(message: String) : RuntimeException(message)

object UrlGuard {
    fun validate(raw: String): URI {
        val uri = try {
            URI(raw.trim())
        } catch (_: Exception) {
            throw UrlBlocked("URL 无效。")
        }
        val scheme = uri.scheme?.lowercase()
        if (scheme != "https") throw UrlBlocked("只允许 https URL。")
        val host = uri.host?.lowercase() ?: throw UrlBlocked("URL 缺少 host。")
        if (host == "localhost" || host.endsWith(".localhost")) {
            throw UrlBlocked("不允许访问 localhost。")
        }
        val addresses = try {
            InetAddress.getAllByName(host)
        } catch (_: Exception) {
            throw UrlBlocked("无法解析 host：$host")
        }
        if (addresses.any { isPrivate(it) }) {
            throw UrlBlocked("不允许访问私网地址。")
        }
        return uri
    }

    fun isPrivate(address: InetAddress): Boolean {
        return address.isAnyLocalAddress ||
            address.isLoopbackAddress ||
            address.isLinkLocalAddress ||
            address.isSiteLocalAddress ||
            address.isMulticastAddress
    }
}

class OkHttpFetcher(
    private val http: OkHttpClient = OkHttpClient.Builder()
        .followRedirects(false)
        .followSslRedirects(false)
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .build(),
    private val maxBytes: Int = 64 * 1024,
) : HttpPort {
    override suspend fun fetch(url: String): String {
        var current = url
        repeat(3) {
            UrlGuard.validate(current)
            val response = http.newCall(
                Request.Builder()
                    .url(current)
                    .header("User-Agent", "Muse/0.1.0")
                    .get()
                    .build(),
            ).execute()
            response.use { resp ->
                val code = resp.code
                if (code in 300..399) {
                    current = resp.header("Location") ?: throw UrlBlocked("重定向没有 Location。")
                    return@repeat
                }
                if (!resp.isSuccessful) {
                    return "HTTP $code"
                }
                val raw = resp.body?.bytes() ?: ByteArray(0)
                val clipped = if (raw.size > maxBytes) raw.copyOf(maxBytes) else raw
                val text = clipped.toString(Charsets.UTF_8)
                val type = resp.header("Content-Type").orEmpty()
                val body = if (type.contains("html", ignoreCase = true) || text.contains("<html", ignoreCase = true)) {
                    htmlToText(text)
                } else {
                    text
                }
                val suffix = if (raw.size > maxBytes) "\n…(已截断)" else ""
                return body.take(maxBytes) + suffix
            }
        }
        throw UrlBlocked("重定向次数过多。")
    }
}

fun htmlToText(html: String): String {
    var s = html
    s = Regex("(?is)<script[^>]*>.*?</script>").replace(s, " ")
    s = Regex("(?is)<style[^>]*>.*?</style>").replace(s, " ")
    s = Regex("(?is)<br\\s*/?>").replace(s, "\n")
    s = Regex("(?is)</p>|</div>|</h[1-6]>|</li>").replace(s, "\n")
    s = Regex("(?is)<[^>]+>").replace(s, " ")
    s = s.replace("&nbsp;", " ")
        .replace("&amp;", "&")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&#39;", "'")
    s = Regex("[ \\t\\x0B\\f\\r]+").replace(s, " ")
    s = Regex("\\n{3,}").replace(s, "\n\n")
    return s.trim()
}
