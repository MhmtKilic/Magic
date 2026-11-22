package com.btcozum.btvault

import android.util.Base64
import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.json.JSONObject
import org.jsoup.nodes.Document
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

    private fun extractEpisodeList(doc: Document, fixedUrl: String): List<Episode> {
        val seriesSlug = fixedUrl
            .removeSuffix("/")
            .substringAfterLast("/")
            .removeSuffix("-izle")

        val fromLinks = doc.select("a[href]")
            .mapNotNull { el ->
                if (el.parents().any { it.hasClass("mob-sub-list") || it.tagName() == "nav" || it.tagName() == "footer" }) {
                    return@mapNotNull null
                }
                val href = el.attr("href").trim()
                if (href.isBlank() || href.startsWith("#") || href.startsWith("/#") || isCategoryOrNavUrl(href)) {
                    return@mapNotNull null
                }
                val epUrl = fixRelativeUrl(href)
                if (epUrl == fixedUrl || epUrl.contains("/diziler/")) return@mapNotNull null

                val lowerHref = href.lowercase()
                val matchesEpisode = lowerHref.contains("bolum") ||
                    lowerHref.contains("sezon") ||
                    lowerHref.contains("episode") ||
                    (seriesSlug.isNotBlank() && lowerHref.contains(seriesSlug.lowercase()) && Regex("""\d+""").containsMatchIn(lowerHref))

                if (!matchesEpisode) return@mapNotNull null

                val epName = el.attr("title").trim()
                    .ifBlank { el.text().trim() }
                    .ifBlank { "Bölüm" }
                val seasonNum = Regex("""(\d+)[\s_-]*sezon""", RegexOption.IGNORE_CASE)
                    .find("$href $epName")?.groupValues?.getOrNull(1)?.toIntOrNull()
                val episodeNum = Regex("""(\d+)[\s_-]*b[oö]l[uü]m""", RegexOption.IGNORE_CASE)
                    .find("$href $epName")?.groupValues?.getOrNull(1)?.toIntOrNull()

                newEpisode(epUrl) {
                    name = epName
                    season = seasonNum
                    episode = episodeNum
                }
            }
            .distinctBy { it.data }

        if (fromLinks.isNotEmpty()) return fromLinks

        return Regex("""/[^\s"'<>\\]*(?:sezon|bolum)[^\s"'<>\\]*""", RegexOption.IGNORE_CASE)
            .findAll(doc.html())
            .map { it.value }
            .filter { !it.contains("/diziler/") && !isCategoryOrNavUrl(it) }
            .distinct()
            .map { rawPath ->
                val epUrl = fixRelativeUrl(rawPath)
                val seasonNum = Regex("""(\d+)-sezon""", RegexOption.IGNORE_CASE).find(rawPath)?.groupValues?.getOrNull(1)?.toIntOrNull()
                val episodeNum = Regex("""(\d+)-bolum""", RegexOption.IGNORE_CASE).find(rawPath)?.groupValues?.getOrNull(1)?.toIntOrNull()
                val label = when {
                    seasonNum != null && episodeNum != null -> "$seasonNum. Sezon $episodeNum. Bölüm"
                    episodeNum != null -> "$episodeNum. Bölüm"
                    else -> rawPath.trim('/').replace('-', ' ')
                }
                newEpisode(epUrl) {
                    name = label
                    season = seasonNum
                    episode = episodeNum
                }
            }
            .toList()
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

        val episodeLinks = extractEpisodeList(doc, fixedUrl)

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
        var targetPage = fixRelativeUrl(data)
        var doc = app.get(targetPage, headers = defaultHeaders, referer = "$mainUrl/").document
        var html = doc.html()

        if (targetPage.contains("/diziler/")) {
            val firstEpUrl = extractEpisodeList(doc, targetPage).firstOrNull()?.data
            if (!firstEpUrl.isNullOrBlank()) {
                targetPage = firstEpUrl
                doc = app.get(targetPage, headers = defaultHeaders, referer = data).document
                html = doc.html()
            }
        }

        // --- GÜNCELLEME: Esnek sunucu listesi yakalama ---
        // 1) Klasik format: var servers = JSON.parse(atob("..."))
        // 2) Boşluk/quote farklılıklarına toleranslı
        // 3) Genel atob("...") fallback: sadece decoded içeriği [ veya {" ile başlayanlar
        val serversB64 = Regex(
            """(?:var|let|const)\s+servers\s*=\s*JSON\.parse\(\s*atob\(\s*["']([^"']+)["']\s*\)\s*\)""",
            RegexOption.IGNORE_CASE
        ).find(html)?.groupValues?.get(1)
            ?: Regex(
                """atob\(\s*["']([A-Za-z0-9+/=]{40,})["']\s*\)""",
                RegexOption.IGNORE_CASE
            ).findAll(html).mapNotNull { m ->
                val raw = m.groupValues[1]
                runCatching {
                    val dec = String(Base64.decode(raw, Base64.DEFAULT), Charsets.UTF_8).trim()
                    if (dec.startsWith("[") || dec.startsWith("{\"")) raw else null
                }.getOrNull()
            }.firstOrNull()

        if (serversB64 == null) {
            Log.d("Kekik_$name", "servers base64 bloğu bulunamadı")
            return false
        }

        val decodedJson = String(
            Base64.decode(serversB64, Base64.DEFAULT),
            Charsets.UTF_8
        )

        val servers = try {
            AppUtils.parseJson<List<CZServer>>(decodedJson)
        } catch (e: Exception) {
            Log.d("Kekik_$name", "servers JSON parse hatası: ${e.message}")
            emptyList()
        }

        if (servers.isEmpty()) {
            Log.d("Kekik_$name", "servers listesi boş")
            return false
        }

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
                    val response = app.get(
                        fullUrl,
                        headers = iframeHeaders,
                        referer = targetPage,
                        timeout = 25
                    )
                    val body = response.text.trim()
                    if (body.isBlank()) continue

                    // JSON cevabı
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

                    // GÜNCELLEME: body doğrudan URL ise (redirect/JSON yerine düz URL)
                    if (body.startsWith("http://") || body.startsWith("https://") && !body.contains("<")) {
                        dispatchResolvedUrl(fixRelativeUrl(body), displayName, fullUrl)
                        return@runCatching
                    }

                    // m3u8
                    val m3u8Url = Regex("""https?://[^\s"'<>\\]+\.m3u8[^\s"'<>\\]*""").find(body)?.value
                    if (!m3u8Url.isNullOrBlank()) {
                        dispatchResolvedUrl(m3u8Url, displayName, fullUrl)
                        return@runCatching
                    }

                    // GÜNCELLEME: .mp4 de yakala
                    val mp4Url = Regex("""https?://[^\s"'<>\\]+\.mp4[^\s"'<>\\]*""").find(body)?.value
                    if (!mp4Url.isNullOrBlank()) {
                        dispatchResolvedUrl(mp4Url, displayName, fullUrl)
                        return@runCatching
                    }

                    // Google Drive preview
                    val driveUrl = Regex("""https://drive\.google\.com/file/d/[a-zA-Z0-9_-]+/preview""").find(body)?.value
                    if (!driveUrl.isNullOrBlank()) {
                        dispatchResolvedUrl(driveUrl, displayName, fullUrl)
                        return@runCatchi
