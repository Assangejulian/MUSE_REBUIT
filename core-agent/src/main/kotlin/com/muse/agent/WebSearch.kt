package com.muse.agent

import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLDecoder
import java.util.concurrent.TimeUnit

data class SearchHit(
    val title: String,
    val url: String,
    val snippet: String,
)

interface SearchPort {
    suspend fun search(query: String): String
}

const val BROWSER_UA =
    "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36"

class WebSearcher(
    private val http: OkHttpClient = OkHttpClient.Builder()
        .followRedirects(true)
        .followSslRedirects(true)
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build(),
    private val limit: Int = 5,
) : SearchPort {
    override suspend fun search(query: String): String {
        val q = query.trim()
        if (q.isBlank()) return "错误：query 不能为空。"
        val ddg = runCatching { searchDuckDuckGo(q) }.getOrElse { emptyList() }
        if (ddg.isNotEmpty()) return formatHits(q, ddg, "DuckDuckGo")
        val wiki = runCatching { searchWikipedia(q) }.getOrElse { emptyList() }
        if (wiki.isNotEmpty()) return formatHits(q, wiki, "Wikipedia")
        return "错误：搜索没有结果。可能是网络拦截了 DuckDuckGo，请开 VPN 后再试。"
    }

    private fun searchDuckDuckGo(query: String): List<SearchHit> {
        val url = "https://html.duckduckgo.com/html/".toHttpUrl().newBuilder()
            .addQueryParameter("q", query)
            .build()
        val html = get(url.toString())
        return parseDuckDuckGoHtml(html, limit)
    }

    private fun searchWikipedia(query: String): List<SearchHit> {
        val lang = if (query.any { it.code >= 0x4e00 }) "zh" else "en"
        val url = "https://$lang.wikipedia.org/w/api.php".toHttpUrl().newBuilder()
            .addQueryParameter("action", "opensearch")
            .addQueryParameter("search", query)
            .addQueryParameter("limit", limit.toString())
            .addQueryParameter("namespace", "0")
            .addQueryParameter("format", "json")
            .build()
        val raw = get(url.toString())
        return parseWikipediaOpenSearch(raw, limit)
    }

    private fun get(url: String): String {
        UrlGuard.validate(url)
        http.newCall(
            Request.Builder()
                .url(url)
                .header("User-Agent", BROWSER_UA)
                .header("Accept", "text/html,application/json;q=0.9,*/*;q=0.8")
                .header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
                .get()
                .build(),
        ).execute().use { resp ->
            if (!resp.isSuccessful) throw UrlBlocked("搜索 HTTP ${resp.code}")
            return resp.body?.string().orEmpty()
        }
    }
}

fun parseDuckDuckGoHtml(html: String, limit: Int): List<SearchHit> {
    val titleRe = Regex(
        """class="result__a"[^>]*href="([^"]+)"[^>]*>(.*?)</a>""",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
    )
    val snippetRe = Regex(
        """class="result__snippet"[^>]*>(.*?)</a>""",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
    )
    val titles = titleRe.findAll(html).map { match ->
        val url = decodeDdgHref(match.groupValues[1])
        val title = htmlToText(match.groupValues[2])
        url to title
    }.filter { it.first.startsWith("http") && it.second.isNotBlank() }
        .distinctBy { it.first }
        .take(limit)
        .toList()
    val snippets = snippetRe.findAll(html).map { htmlToText(it.groupValues[1]) }.toList()
    return titles.mapIndexed { index, (url, title) ->
        SearchHit(title = title, url = url, snippet = snippets.getOrNull(index).orEmpty())
    }
}

fun parseWikipediaOpenSearch(raw: String, limit: Int): List<SearchHit> {
    val root = runCatching {
        com.muse.llm.MuseJson.parseToJsonElement(raw)
    }.getOrNull() as? kotlinx.serialization.json.JsonArray ?: return emptyList()
    if (root.size < 4) return emptyList()
    val titles = root[1] as? kotlinx.serialization.json.JsonArray ?: return emptyList()
    val descs = root[2] as? kotlinx.serialization.json.JsonArray
    val urls = root[3] as? kotlinx.serialization.json.JsonArray ?: return emptyList()
    return titles.indices.take(limit).mapNotNull { i ->
        val title = titles.getOrNull(i)?.let {
            (it as? kotlinx.serialization.json.JsonPrimitive)?.content
        }.orEmpty()
        val url = urls.getOrNull(i)?.let {
            (it as? kotlinx.serialization.json.JsonPrimitive)?.content
        }.orEmpty()
        if (title.isBlank() || url.isBlank()) return@mapNotNull null
        val snippet = descs?.getOrNull(i)?.let {
            (it as? kotlinx.serialization.json.JsonPrimitive)?.content
        }.orEmpty()
        SearchHit(title, url, snippet)
    }
}

fun decodeDdgHref(raw: String): String {
    val href = raw.replace("&amp;", "&")
    val uddg = Regex("[?&]uddg=([^&]+)").find(href)?.groupValues?.get(1)
    return if (uddg != null) {
        runCatching { URLDecoder.decode(uddg, Charsets.UTF_8.name()) }.getOrDefault(href)
    } else if (href.startsWith("//")) {
        "https:$href"
    } else {
        href
    }
}

fun formatHits(query: String, hits: List<SearchHit>, source: String): String = buildString {
    append("query=").append(query).append('\n')
    append("source=").append(source).append('\n')
    hits.forEachIndexed { index, hit ->
        append(index + 1).append(". ").append(hit.title).append('\n')
        append("   ").append(hit.url).append('\n')
        if (hit.snippet.isNotBlank()) append("   ").append(hit.snippet).append('\n')
    }
}

fun isSearchResultsPage(url: String): Boolean {
    val host = runCatching { java.net.URI(url).host?.lowercase() }.getOrNull().orEmpty()
    val path = runCatching { java.net.URI(url).path?.lowercase() }.getOrNull().orEmpty()
    val query = runCatching { java.net.URI(url).query?.lowercase() }.getOrNull().orEmpty()
    if (host.contains("google.") && (path.contains("/search") || query.contains("q="))) return true
    if (host.contains("bing.com") && path.contains("/search")) return true
    if (host.contains("baidu.com") && (path.contains("/s") || query.contains("wd="))) return true
    if (host.contains("duckduckgo.com") && (query.contains("q=") || path.contains("/html"))) return true
    if (host.contains("sogou.com")) return true
    return false
}
