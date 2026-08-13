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
        if (host == "localhost" || host.endsWith(".localhost") || host.endsWith(".local")) {
            throw UrlBlocked("不允许访问 localhost。")
        }
        if (isLiteralPrivateHost(host)) {
            throw UrlBlocked("不允许访问私网地址。")
        }
        return uri
    }

    fun isLiteralPrivateHost(host: String): Boolean {
        val h = host.trim().lowercase().removePrefix("[").removeSuffix("]")
        if (h == "127.0.0.1" || h == "0.0.0.0" || h == "::1" || h == "https://example.net/id/garnet") return true
        if (h.startsWith("10.")) return true
        if (h.startsWith("192.168.")) return true
        if (h.startsWith("169.254.")) return true
        if (h.startsWith("172.")) {
            val second = h.split(".").getOrNull(1)?.toIntOrNull() ?: return false
            return second in 16..31
        }
        return false
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
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build(),
    private val maxBytes: Int = 64 * 1024,
) : HttpPort {
    override suspend fun fetch(url: String): String {
        if (isSearchResultsPage(url)) {
            return "错误：不要抓搜索结果页。请改用 web_search。"
        }
        var current = url
        repeat(4) {
            UrlGuard.validate(current)
            val response = http.newCall(
                Request.Builder()
                    .url(current)
                    .header("User-Agent", BROWSER_UA)
                    .header("Accept", "text/html,application/json;q=0.9,*/*;q=0.8")
                    .header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
                    .get()
                    .build(),
            ).execute()
            response.use { resp ->
                val code = resp.code
                if (code in 300..399) {
                    val location = resp.header("Location") ?: throw UrlBlocked("重定向没有 Location。")
                    current = URI(current).resolve(location).toString()
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
