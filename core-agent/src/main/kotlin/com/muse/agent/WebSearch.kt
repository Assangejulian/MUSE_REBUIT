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
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build(),
    private val limit: Int = 5,
) : SearchPort {
    override suspend fun search(query: String): String {
        val q = query.trim()
        if (q.isBlank()) return "错误：query 不能为空。"
        val errors = ArrayList<String>()
        val engines = listOf(
            "Bing" to { searchBing(q) },
            "Baidu" to { searchBaidu(q) },
            "DuckDuckGo" to { searchDuckDuckGo(q) },
            "Wikipedia" to { searchWikipedia(q) },
        )
        for ((name, fn) in engines) {
            val hits = try {
                fn()
            } catch (t: Throwable) {
                errors += "$name: ${t.message ?: t::class.java.simpleName}"
                emptyList()
            }
            if (hits.isNotEmpty()) {
                val note = if (errors.isEmpty()) "" else "\n(skipped: ${errors.joinToString(" | ")})"
                return formatHits(q, hits, name) + note
            }
            if (errors.none { it.startsWith("$name:") }) {
                errors += "$name: 没有解析到结果"
            }
        }
        return buildString {
            append("错误：所有搜索源都失败了。DeepSeek API 能通不代表网页搜索也能通。\n")
            errors.forEach { append("- ").append(it).append('\n') }
        }
    }

    private fun searchBing(query: String): List<SearchHit> {
        val url = "https://cn.bing.com/search".toHttpUrl().newBuilder()
            .addQueryParameter("q", query)
            .addQueryParameter("setlang", "zh-Hans")
            .build()
        return parseBingHtml(get(url.toString()), limit)
    }

    private fun searchBaidu(query: String): List<SearchHit> {
        val url = "https://www.baidu.com/s".toHttpUrl().newBuilder()
            .addQueryParameter("wd", query)
            .addQueryParameter("ie", "utf-8")
            .build()
        return parseBaiduHtml(get(url.toString()), limit)
    }

    private fun searchDuckDuckGo(query: String): List<SearchHit> {
        val url = "https://html.duckduckgo.com/html/".toHttpUrl().newBuilder()
            .addQueryParameter("q", query)
            .build()
        return parseDuckDuckGoHtml(get(url.toString()), limit)
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
        return parseWikipediaOpenSearch(get(url.toString()), limit)
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
            if (!resp.isSuccessful) throw UrlBlocked("HTTP ${resp.code}")
            return resp.body?.string().orEmpty()
        }
    }
}

fun parseBingHtml(html: String, limit: Int): List<SearchHit> {
    val re = Regex(
        """<h2[^>]*>\s*<a[^>]+href="(https?://[^"]+)"[^>]*>(.*?)</a>""",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
    )
    return re.findAll(html).mapNotNull { match ->
        val url = match.groupValues[1].replace("&amp;", "&")
        val host = runCatching { java.net.URI(url).host?.lowercase() }.getOrNull().orEmpty()
        if (host.contains("bing.com") || host.contains("microsoft.com") || host.contains("msn.com")) {
            return@mapNotNull null
        }
        val title = htmlToText(match.groupValues[2])
        if (title.isBlank()) null else SearchHit(title, url, "")
    }.distinctBy { it.url }.take(limit).toList()
}

fun parseBaiduHtml(html: String, limit: Int): List<SearchHit> {
    val muHits = Regex("""\bmu="(https?://[^"]+)"""")
        .findAll(html)
        .map { it.groupValues[1].replace("&amp;", "&") }
        .filter { url ->
            val host = runCatching { java.net.URI(url).host?.lowercase() }.getOrNull().orEmpty()
            host.isNotBlank() && !host.contains("baidu.com")
        }
        .distinct()
        .take(limit)
        .toList()
    val titles = Regex(
        """<h3[^>]*>\s*<a[^>]*>(.*?)</a>""",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
    ).findAll(html).map { htmlToText(it.groupValues[1]) }.filter { it.isNotBlank() }.toList()
    if (muHits.isNotEmpty()) {
        return muHits.mapIndexed { i, url ->
            SearchHit(title = titles.getOrNull(i) ?: url, url = url, snippet = "")
        }
    }
    val hrefs = Regex(
        """<h3[^>]*>\s*<a[^>]+href="(https?://[^"]+)"[^>]*>(.*?)</a>""",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
    )
    return hrefs.findAll(html).mapNotNull { match ->
        val url = match.groupValues[1].replace("&amp;", "&")
        val host = runCatching { java.net.URI(url).host?.lowercase() }.getOrNull().orEmpty()
        if (host.contains("baidu.com")) return@mapNotNull null
        val title = htmlToText(match.groupValues[2])
        if (title.isBlank()) null else SearchHit(title, url, "")
    }.distinctBy { it.url }.take(limit).toList()
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
