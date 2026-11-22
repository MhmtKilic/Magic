package com.btcozum.btvault

import android.util.Base64
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.json.JSONObject
import org.jsoup.nodes.Element

data class CZServer(
    val type: String? = null,
    val src: String? = null,
    val resolveUrl: String? = null,
    val streamUrl: String? = null,
    val label: String? = null,
    val lang: String? = null,
    val embedId: Int? = null,
    val notice: String? = null
)

class CizgiMax : MainAPI() {
    override var mainUrl = "https://cizgimax.online"
    override var name = "CizgiMax"
    override val hasMainPage = true
    override var lang = "tr"
    override val supportedTypes = setOf(
        TvType.Cartoon,
        TvType.Anime,
        TvType.TvSeries,
        TvType.Movie
    )

    private val defaultHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Linux; Android 14; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Mobile Safari/537.36",
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
        "Accept-Language" to "tr-TR,tr;q=0.9,en-US;q=0.8,en;q=0.7"
    )

    private fun fixRelativeUrl(rawUrl: String): String {
        val trimmed = rawUrl.trim()
        if (trimmed.isBlank()) return ""
        return when {
            trimmed.startsWith("http://") || trimmed.startsWith("https://") -> trimmed
            trimmed.startsWith("//") -> "https:$trimmed"
            trimmed.startsWith("/") -> "$mainUrl$trimmed"
            else -> "$mainUrl/$trimmed"
        }
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val linkEl = if (tagName() == "a") this else selectFirst("a[href]") ?: return null
        val href = linkEl.attr("href").trim()
        if (href.isBlank() || href.startsWith("#") || href.startsWith("/#")) return null

        val fullUrl = fixRelativeUrl(href)
        val title = linkEl.attr("title").trim()
            .ifBlank { selectFirst("h1, h2, h3, h4, .title, .name")?.text()?.trim() ?: "" }
            .ifBlank { linkEl.text().trim() }
        if (title.isBlank()) return null

        val imgEl = selectFirst("img")
        val rawPoster = imgEl?.attr("data-src")?.takeIf { it.isNotBlank() }
            ?: imgEl?.attr("data-lazy-src")?.takeIf { it.isNotBlank() }
            ?: imgEl?.attr("src")?.takeIf { it.isNotBlank() }
        val posterUrl = rawPoster?.let { fixRelativeUrl(it) }

        return newTvSeriesSearchResponse(title, fullUrl, TvType.Cartoon) {
            this.posterUrl = posterUrl
        }
    }

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val targetUrl = if (page <= 1) "$mainUrl/" else "$mainUrl/page/$page/"
        val doc = app.get(targetUrl, headers = defaultHeaders, referer = "$mainUrl/").document

        val candidates = doc.select("a[href*='-bolum-izle'], a[href*='-sezon-'], .video-card, .movie-card, .poster-item, article")
            .mapNotNull { it.toSearchResult() }
            .distinctBy { it.url }

        val list = if (candidates.isNotEmpty()) {
            candidates
        } else {
            doc.select("a[href]")
                .mapNotNull { el ->
                    val href = el.attr("href").trim()
                    if (href.matches(Regex("""^/[^?#]+-(?:sezon|bolum)-[^?#]+/?$"""))) {
                        el.toSearchResult()
                    } else null
                }
                .distinctBy { it.url }
        }

        val homeSection = HomePageList(
            name = request.name.ifBlank { "Son Bölümler" },
            list = list
        )

        return newHomePageResponse(listOf(homeSection), hasNext = list.isNotEmpty())
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val encoded = java.net.URLEncoder.encode(query.trim(), "UTF-8")
        val searchUrls = listOf(
            "$mainUrl/?s=$encoded",
            "$mainUrl/ara/?q=$encoded"
        )

        for (url in searchUrls) {
            val doc = runCatching {
                app.get(url, headers = defaultHeaders, referer = "$mainUrl/").document
            }.getOrNull() ?: continue

            val results = doc.select("a[href*='-bolum-izle'], a[href*='-sezon-'], .video-card, .movie-card, .poster-item, article")
                .mapNotNull { it.toSearchResult() }
                .distinctBy { it.url }

            if (results.isNotEmpty()) return results
        }

        return emptyList()
    }

    override suspend fun load(url: String): LoadResponse? {
        val fixedUrl = fixRelativeUrl(url)
        val doc = app.get(fixedUrl, headers = defaultHeaders, referer = "$mainUrl/").document

        val title = doc.selectFirst("h1, .episode-title, .series-title, meta[property='og:title']")
            ?.let { el ->
                if (el.tagName() == "meta") el.attr("content") else el.text()
            }
            ?.trim()
            ?.ifBlank { "Bölüm" } ?: "Bölüm"

        val poster = doc.selectFirst("meta[property='og:image']")?.attr("content")?.takeIf { it.isNotBlank() }
            ?.let { fixRelativeUrl(it) }
            ?: doc.selectFirst(".poster img, .cover img, .player-wrap img")?.attr("src")?.takeIf { it.isNotBlank() }
                ?.let { fixRelativeUrl(it) }

        val plot = doc.selectFirst("meta[property='og:description']")?.attr("content")?.trim()
            ?: doc.selectFirst(".description, .synopsis, .plot, p")?.text()?.trim()

        val episodeLinks = doc.select("a[href]")
            .mapNotNull { el ->
                val href = el.attr("href").trim()
                if (href.isBlank() || href.startsWith("#") || href.startsWith("/#")) return@mapNotNull null
                if (!href.contains("bolum", ignoreCase = true) && !href.contains("sezon", ignoreCase = true)) {
                    return@mapNotNull null
                }
                val epUrl = fixRelativeUrl(href)
                val epName = el.attr("title").trim().ifBlank { el.text().trim() }.ifBlank { "Bölüm" }
                val seasonNum = Regex("""(\d+)-sezon""").find(href)?.groupValues?.getOrNull(1)?.toIntOrNull()
                val episodeNum = Regex("""(\d+)-bolum""").find(href)?.groupValues?.getOrNull(1)?.toIntOrNull()
                newEpisode(epUrl) {
                    name = epName
                    season = seasonNum
                    episode = episodeNum
                }
            }
            .distinctBy { it.data }

        return if (episodeLinks.isNotEmpty()) {
            newTvSeriesLoadResponse(title, fixedUrl, TvType.TvSeries, episodeLinks) {
                this.posterUrl = poster
                this.plot = plot
            }
        } else {
            newMovieLoadResponse(title, fixedUrl, TvType.Movie, fixedUrl) {
                this.posterUrl = poster
                this.plot = plot
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val targetPage = fixRelativeUrl(data)
        val doc = app.get(targetPage, headers = defaultHeaders, referer = "$mainUrl/").document
        val html = doc.html()

        val b64Match = Regex("""var\s+servers\s*=\s*JSON\.parse\(atob\(["']([^"']+)["']\)\)""")
            .find(html) ?: return false

        val decodedJson = String(
            Base64.decode(b64Match.groupValues[1], Base64.DEFAULT),
            Charsets.UTF_8
        )

        val servers = try {
            AppUtils.parseJson<List<CZServer>>(decodedJson)
        } catch (_: Exception) {
            emptyList()
        }

        if (servers.isEmpty()) return false

        var foundLink = false
        val ua = defaultHeaders["User-Agent"]!!
        val playerHeaders = mapOf(
            "User-Agent" to ua,
            "Referer" to "$mainUrl/"
        )
        val iframeHeaders = defaultHeaders + mapOf(
            "Referer" to targetPage,
            "Sec-Fetch-Dest" to "iframe",
            "Sec-Fetch-Mode" to "navigate",
            "Sec-Fetch-Site" to "same-origin"
        )

        for (srv in servers) {
            runCatching {
                val rawTarget = srv.src?.takeIf { it.isNotBlank() }
                    ?: srv.resolveUrl?.takeIf { it.isNotBlank() }
                    ?: srv.streamUrl?.takeIf { it.isNotBlank() }
                    ?: return@runCatching

                val fullUrl = fixRelativeUrl(rawTarget)
                val label = listOfNotNull(
                    srv.label?.takeIf { it.isNotBlank() },
                    srv.lang?.takeIf { it.isNotBlank() }?.let { "($it)" }
                ).joinToString(" ").ifBlank { srv.type ?: name }
                val displayName = "$name - $label"

                val body = app.get(fullUrl, headers = iframeHeaders, referer = targetPage).text.trim()
                if (body.isBlank()) return@runCatching

                // 1. JSON yanıtı (/api/np-resolve/ -> {"id":"..."})
                if (body.startsWith("{")) {
                    val jsonObj = JSONObject(body)
                    val directUrl = jsonObj.optString("url").takeIf { it.isNotBlank() }
                        ?: jsonObj.optString("src").takeIf { it.isNotBlank() }
                        ?: jsonObj.optString("streamUrl").takeIf { it.isNotBlank() }

                    val resolvedTarget = when {
                        directUrl != null -> fixRelativeUrl(directUrl)
                        jsonObj.optString("id").isNotBlank() && srv.type.equals("dzen", true) ->
                            "https://dzen.ru/embed/${jsonObj.getString("id")}"
                        else -> null
                    }

                    if (!resolvedTarget.isNullOrBlank()) {
                        foundLink = true
                        loadExtractor(resolvedTarget, targetPage, subtitleCallback) { link ->
                            callback(
                                ExtractorLink(
                                    source = link.source.ifBlank { name },
                                    name = displayName,
                                    url = link.url,
                                    referer = link.referer.ifBlank { "$mainUrl/" },
                                    quality = link.quality,
                                    type = link.type,
                                    headers = link.headers.ifEmpty { playerHeaders },
                                    extractorData = link.extractorData
                                )
                            )
                        }
                    }
                    return@runCatching
                }

                // 2. HTML içinde doğrudan .m3u8 akışı varsa (örn. Vidmoly proxy yanıtı)
                val m3u8Url = Regex("""https?://[^\s"'<>\\]+\.m3u8[^\s"'<>\\]*""").find(body)?.value
                if (!m3u8Url.isNullOrBlank()) {
                    foundLink = true
                    callback(
                        newExtractorLink(
                            source = name,
                            name = displayName,
                            url = m3u8Url,
                            type = ExtractorLinkType.M3U8
                        ) {
                            this.referer = "$mainUrl/"
                            this.quality = Qualities.Unknown.value
                            this.headers = playerHeaders
                        }
                    )
                    return@runCatching
                }

                // 3. HTML içinde Google Drive preview bağlantısı varsa
                val driveUrl = Regex("""https://drive\.google\.com/file/d/[a-zA-Z0-9_-]+/preview""").find(body)?.value
                if (!driveUrl.isNullOrBlank()) {
                    foundLink = true
                    loadExtractor(driveUrl, fullUrl, subtitleCallback) { link ->
                        callback(
                            ExtractorLink(
                                source = link.source.ifBlank { name },
                                name = displayName,
                                url = link.url,
                                referer = link.referer.ifBlank { fullUrl },
                                quality = link.quality,
                                type = link.type,
                                headers = link.headers,
                                extractorData = link.extractorData
                            )
                        )
                    }
                    return@runCatching
                }

                // 4. HTML içinde başka bir <iframe src="..."> varsa
                val iframeSrc = Regex("""<iframe[^>]+src=["']([^"']+)["']""", RegexOption.IGNORE_CASE)
                    .find(body)?.groupValues?.getOrNull(1)
                if (!iframeSrc.isNullOrBlank()) {
                    val fixedIframe = fixRelativeUrl(iframeSrc)
                    foundLink = true
                    loadExtractor(fixedIframe, fullUrl, subtitleCallback) { link ->
                        callback(
                            ExtractorLink(
                                source = link.source.ifBlank { name },
                                name = displayName,
                                url = link.url,
                                referer = link.referer.ifBlank { fullUrl },
                                quality = link.quality,
                                type = link.type,
                                headers = link.headers,
                                extractorData = link.extractorData
                            )
                        )
                    }
                }
            }
        }

        return foundLink
    }
}
