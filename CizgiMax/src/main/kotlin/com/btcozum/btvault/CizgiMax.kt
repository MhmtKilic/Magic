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
    val metaUrl: String? = null,
    val qualitiesUrl: String? = null,
    val label: String? = null,
    val lang: String? = null,
    val embedId: Int? = null,
    val embedToken: String? = null,
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

    override val mainPage = mainPageOf(
        "$mainUrl/" to "Son Bölümler",
        "$mainUrl/diziler/cizgi-film/" to "Çizgi Film",
        "$mainUrl/diziler/anime/" to "Anime",
        "$mainUrl/diziler/dizi/" to "Dizi",
        "$mainUrl/film/" to "Film",
        "$mainUrl/tur/aksiyon/" to "Aksiyon",
        "$mainUrl/tur/komedi/" to "Komedi",
        "$mainUrl/tur/macera/" to "Macera",
        "$mainUrl/tur/bilim-kurgu/" to "Bilim Kurgu",
        "$mainUrl/tur/fantezi/" to "Fantezi",
        "$mainUrl/tur/gizem/" to "Gizem"
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

    private fun isNoiseTitle(candidate: String): Boolean {
        val clean = candidate.trim()
        if (clean.isBlank()) return true
        val blocked = setOf(
            "çizgi", "çizgi film", "cizgi film", "anime", "dizi", "diziler",
            "film", "filmler", "canlı tv", "tümü", "hepsi", "izle"
        )
        return clean.lowercase() in blocked
    }

    private fun isCategoryOrNavUrl(href: String): Boolean {
        val path = href.substringBefore("?").substringBefore("#").trim().lowercase()
        return path.matches(
            Regex(
                """^/(?:diziler/(?:cizgi-film|anime|dizi)|cizgi-film|anime|dizi|diziler|film|filmler|tur/[^/]+|kategoriler|etiket)/?$"""
            )
        )
    }

    private fun Element.toSearchResult(): SearchResponse? {
        if (parents().any { it.hasClass("mob-sub-list") || it.tagName() == "nav" || it.tagName() == "footer" }) {
            return null
        }

        val linkEl = if (tagName() == "a") this else selectFirst("a[href]") ?: return null
        val href = linkEl.attr("href").trim()
        if (href.isBlank() || href.startsWith("#") || href.startsWith("/#") || isCategoryOrNavUrl(href)) {
            return null
        }

        val fullUrl = fixRelativeUrl(href)
        val imgEl = selectFirst("img")

        val rawTitle = selectFirst(".title, .name, .series-title, .movie-title, h2, h3, h4")?.text()?.trim()
            ?.takeIf { !isNoiseTitle(it) }
            ?: imgEl?.attr("alt")?.trim()?.takeIf { !isNoiseTitle(it) }
            ?: linkEl.attr("title").trim().takeIf { !isNoiseTitle(it) }
            ?: linkEl.text().trim().takeIf { !isNoiseTitle(it) }
            ?: ""

        val title = rawTitle
            .replace(Regex("""\s*\|\s*CizgiMax.*$""", RegexOption.IGNORE_CASE), "")
            .trim()
        if (title.isBlank() || isNoiseTitle(title)) return null

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
        val base = request.data.trimEnd('/')
        val targetUrl = if (page <= 1) "$base/" else "$base/?page=$page"
        val doc = app.get(targetUrl, headers = defaultHeaders, referer = "$mainUrl/").document

        val candidates = doc.select(
            "a[href*='/diziler/'], a[href*='-bolum-izle'], a[href*='-sezon-'], .video-card, .movie-card, .poster-item, .series-card, article"
        )
            .mapNotNull { it.toSearchResult() }
            .distinctBy { it.url }

        val list = if (candidates.isNotEmpty()) {
            candidates
        } else {
            doc.select("a[href]")
                .mapNotNull { el ->
                    val href = el.attr("href").trim()
                    if (href.matches(Regex("""^/(?:diziler/[^?#]+|[^?#]+-(?:sezon|bolum)-[^?#]+)/?$"""))) {
                        el.toSearchResult()
                    } else null
                }
                .distinctBy { it.url }
        }

        return newHomePageResponse(
            list = listOf(HomePageList(name = request.name, list = list)),
            hasNext = list.isNotEmpty()
        )
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val cleanQuery = query.trim()
        if (cleanQuery.isBlank()) return emptyList()

        val encoded = java.net.URLEncoder.encode(cleanQuery, "UTF-8")
        val searchUrls = listOf(
            "$mainUrl/ara/?q=$encoded",
            "$mainUrl/?s=$encoded"
        )

        val queryTokens = cleanQuery.lowercase()
            .split(Regex("""\s+"""))
            .filter { it.length >= 2 }

        for (url in searchUrls) {
            val doc = runCatching {
                app.get(url, headers = defaultHeaders, referer = "$mainUrl/").document
            }.getOrNull() ?: continue

            val rawResults = doc.select(
                "a[href*='/diziler/'], a[href*='-bolum-izle'], a[href*='-sezon-'], .video-card, .movie-card, .poster-item, .series-card, article"
            )
                .mapNotNull { it.toSearchResult() }
                .distinctBy { it.url }

            if (rawResults.isNotEmpty() && queryTokens.isNotEmpty()) {
                val matched = rawResults.filter { item ->
                    val hay = "${item.name} ${item.url}".lowercase()
                    queryTokens.any { token -> hay.contains(token) }
                }
                if (matched.isNotEmpty()) return matched
            }
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
            ?.replace(Regex("""\s*\|\s*CizgiMax.*$""", RegexOption.IGNORE_CASE), "")
            ?.ifBlank { "İçerik" } ?: "İçerik"

        val poster = doc.selectFirst("meta[property='og:image']")?.attr("content")?.takeIf { it.isNotBlank() }
            ?.let { fixRelativeUrl(it) }
            ?: doc.selectFirst(".poster img, .cover img, .player-wrap img, img")?.attr("src")?.takeIf { it.isNotBlank() }
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
                if (epUrl == fixedUrl) return@mapNotNull null
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

        return if (episodeLinks.isNotEmpty() || fixedUrl.contains("/diziler/")) {
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

        val b64Match = Regex("""(?:var|let|const)\s+servers\s*=\s*JSON\.parse\(atob\(["']([^"']+)["']\)\)""")
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
            "Origin" to mainUrl,
            "X-Requested-With" to "XMLHttpRequest",
            "Sec-Fetch-Dest" to "iframe",
            "Sec-Fetch-Mode" to "navigate",
            "Sec-Fetch-Site" to "same-origin"
        )

        suspend fun dispatchResolvedUrl(resolvedTarget: String, displayName: String, refUrl: String) {
            if (resolvedTarget.isBlank()) return
            if (resolvedTarget.contains(".m3u8", ignoreCase = true)) {
                foundLink = true
                callback(
                    newExtractorLink(
                        source = name,
                        name = displayName,
                        url = resolvedTarget,
                        type = ExtractorLinkType.M3U8
                    ) {
                        this.referer = "$mainUrl/"
                        this.quality = Qualities.Unknown.value
                        this.headers = playerHeaders
                    }
                )
            } else {
                foundLink = true
                loadExtractor(resolvedTarget, refUrl, subtitleCallback) { link ->
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
        }

        for (srv in servers) {
            runCatching {
                val candidates = listOfNotNull(
                    srv.src?.takeIf { it.isNotBlank() },
                    srv.resolveUrl?.takeIf { it.isNotBlank() },
                    srv.streamUrl?.takeIf { it.isNotBlank() },
                    srv.metaUrl?.takeIf { it.isNotBlank() },
                    srv.qualitiesUrl?.takeIf { it.isNotBlank() }
                ).distinct()

                if (candidates.isEmpty()) return@runCatching

                val label = listOfNotNull(
                    srv.label?.takeIf { it.isNotBlank() },
                    srv.lang?.takeIf { it.isNotBlank() }?.let { "($it)" }
                ).joinToString(" ").ifBlank { srv.type ?: name }
                val displayName = "$name - $label"

                for (rawTarget in candidates) {
                    val fullUrl = fixRelativeUrl(rawTarget)

                    // 302 yönlendirme varsa Location başlığını yakala
                    val noRedirectResp = runCatching {
                        app.get(
                            fullUrl,
                            headers = iframeHeaders,
                            referer = targetPage,
                            allowRedirects = false,
                            timeout = 20
                        )
                    }.getOrNull()

                    val locationHeader = noRedirectResp?.headers?.get("Location")?.takeIf { it.isNotBlank() }
                    if (locationHeader != null) {
                        val fixedLoc = fixRelativeUrl(locationHeader)
                        if (!fixedLoc.contains("/api/stream/", ignoreCase = true) && !fixedLoc.contains("/oynat", ignoreCase = true)) {
                            dispatchResolvedUrl(fixedLoc, displayName, targetPage)
                            return@runCatching
                        }
                    }

                    val response = app.get(
                        fullUrl,
                        headers = iframeHeaders,
                        referer = targetPage,
                        timeout = 25
                    )
                    val body = response.text.trim()
                    if (body.isBlank()) continue

                    if (body.startsWith("{")) {
                        val jsonObj = JSONObject(body)
                        val directUrl = jsonObj.optString("url").takeIf { it.isNotBlank() }
                            ?: jsonObj.optString("src").takeIf { it.isNotBlank() }
                            ?: jsonObj.optString("streamUrl").takeIf { it.isNotBlank() }
                            ?: jsonObj.optString("file").takeIf { it.isNotBlank() }

                        val resolvedTarget = when {
                            directUrl != null -> fixRelativeUrl(directUrl)
                            jsonObj.optString("id").isNotBlank() && srv.type.equals("dzen", true) ->
                                "https://dzen.ru/embed/${jsonObj.getString("id")}"
                            else -> null
                        }

                        if (!resolvedTarget.isNullOrBlank()) {
                            dispatchResolvedUrl(resolvedTarget, displayName, targetPage)
                            return@runCatching
                        }
                        continue
                    }

                    val m3u8Url = Regex("""https?://[^\s"'<>\\]+\.m3u8[^\s"'<>\\]*""").find(body)?.value
                    if (!m3u8Url.isNullOrBlank()) {
                        dispatchResolvedUrl(m3u8Url, displayName, targetPage)
                        return@runCatching
                    }

                    val driveUrl = Regex("""https://drive\.google\.com/file/d/[a-zA-Z0-9_-]+/preview""").find(body)?.value
                    if (!driveUrl.isNullOrBlank()) {
                        dispatchResolvedUrl(driveUrl, displayName, fullUrl)
                        return@runCatching
                    }

                    val iframeSrc = Regex("""<iframe[^>]+src=["']([^"']+)["']""", RegexOption.IGNORE_CASE)
                        .find(body)?.groupValues?.getOrNull(1)
                    if (!iframeSrc.isNullOrBlank()) {
                        dispatchResolvedUrl(fixRelativeUrl(iframeSrc), displayName, fullUrl)
                        return@runCatching
                    }
                }
            }
        }

        return foundLink
    }
}
