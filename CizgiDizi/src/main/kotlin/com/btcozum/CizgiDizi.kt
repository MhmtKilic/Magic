@file:Suppress("DEPRECATION", "DEPRECATION_ERROR")

package com.btcozum.btvault

import android.util.Base64
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.nodes.Document
import java.text.Normalizer

class CizgiDizi : MainAPI() {
    override var mainUrl = "https://cizgivedizi.net"
    override var name = "CizgiDizi"
    override val hasMainPage = true
    override var lang = "tr"
    override val supportedTypes = setOf(
        TvType.Cartoon,
        TvType.Anime,
        TvType.TvSeries,
        TvType.Movie
    )

    private val videoOrigin = "https://cizgivedizi.net"

    override val mainPage = mainPageOf(
        "all" to "Tümü",
        "cizgi" to "Çizgi Dizi",
        "anime" to "Anime",
        "dizi" to "Dizi",
        "film" to "Film"
    )

    private val defaultHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Linux; Android 13; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36",
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
        "Accept-Language" to "tr-TR,tr;q=0.9,en-US;q=0.8,en;q=0.7"
    )

    private data class CatalogItem(
        val title: String,
        val url: String,
        val poster: String?,
        val type: String,
        val isAnime: Boolean,
        val hay: String,
        val hayEn: String,
        val genres: List<String>
    )

    @Volatile
    private var cachedCatalog: List<CatalogItem> = emptyList()

    @Volatile
    private var lastCatalogFetchMs: Long = 0L

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

    private fun normalizeTr(input: String): String {
        val lower = input.lowercase()
            .replace('ı', 'i')
            .replace('İ', 'i')
            .replace('ğ', 'g')
            .replace('ü', 'u')
            .replace('ş', 's')
            .replace('ö', 'o')
            .replace('ç', 'c')
        return Normalizer.normalize(lower, Normalizer.Form.NFD)
            .replace(Regex("\\p{InCombiningDiacriticalMarks}+"), "")
    }

    private suspend fun getCatalog(): List<CatalogItem> {
        val now = System.currentTimeMillis()
        if (cachedCatalog.isNotEmpty() && (now - lastCatalogFetchMs) < 10 * 60 * 1000L) {
            return cachedCatalog
        }

        val doc = app.get("$mainUrl/", headers = defaultHeaders).documentLarge
        val jsonText = doc.select("script")
            .map { it.data().trim() }
            .firstOrNull { it.startsWith("[") && it.contains("\"href\"") && it.contains("\"poster\"") }
            ?: return cachedCatalog

        val arr = JSONArray(jsonText)
        val parsed = ArrayList<CatalogItem>(arr.length())

        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            val href = obj.optString("href").trim()
            val label = obj.optString("label").trim()
            if (href.isBlank() || label.isBlank()) continue

            val ds = obj.optJSONObject("dataset") ?: JSONObject()
            val type = ds.optString("type").trim().lowercase()
            val isAnime = ds.optString("anime") == "1" || type == "anime"
            val hay = ds.optString("hay")
            val hayEn = ds.optString("hayEn")
            val genresRaw = ds.optString("genresraw")
            val genres = genresRaw.split("|").map { it.trim() }.filter { it.isNotBlank() }

            val poster = obj.optString("poster").trim().takeIf { it.isNotBlank() }?.let { fixRelativeUrl(it) }
            val fullUrl = fixRelativeUrl(href)

            parsed.add(
                CatalogItem(
                    title = label,
                    url = fullUrl,
                    poster = poster,
                    type = type,
                    isAnime = isAnime,
                    hay = hay,
                    hayEn = hayEn,
                    genres = genres
                )
            )
        }

        if (parsed.isNotEmpty()) {
            cachedCatalog = parsed
            lastCatalogFetchMs = now
        }
        return cachedCatalog
    }

    private fun CatalogItem.toSearchResponse(): SearchResponse {
        val isMovie = type == "film" || url.contains("/film/")
        val tvType = when {
            isMovie -> TvType.Movie
            isAnime -> TvType.Anime
            type == "cizgi" -> TvType.Cartoon
            else -> TvType.TvSeries
        }
        return if (isMovie) {
            newMovieSearchResponse(title, url, TvType.Movie) {
                this.posterUrl = poster
            }
        } else {
            newTvSeriesSearchResponse(title, url, tvType) {
                this.posterUrl = poster
            }
        }
    }

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val catalog = getCatalog()
        val filtered = when (request.data) {
            "cizgi" -> catalog.filter { it.type == "cizgi" && !it.isAnime }
            "anime" -> catalog.filter { it.isAnime }
            "dizi" -> catalog.filter { it.type == "dizi" && !it.isAnime }
            "film" -> catalog.filter { it.type == "film" || it.url.contains("/film/") }
            else -> catalog
        }

        val pageSize = 36
        val fromIndex = (page - 1) * pageSize
        if (fromIndex >= filtered.size) {
            return newHomePageResponse(
                list = listOf(HomePageList(name = request.name, list = emptyList())),
                hasNext = false
            )
        }

        val toIndex = minOf(fromIndex + pageSize, filtered.size)
        val pageItems = filtered.subList(fromIndex, toIndex).map { it.toSearchResponse() }

        return newHomePageResponse(
            list = listOf(HomePageList(name = request.name, list = pageItems)),
            hasNext = toIndex < filtered.size
        )
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val cleanQuery = query.trim()
        if (cleanQuery.isBlank()) return emptyList()

        val normQuery = normalizeTr(cleanQuery)
        val tokens = normQuery.split(Regex("\\s+")).filter { it.isNotBlank() }
        if (tokens.isEmpty()) return emptyList()

        val catalog = getCatalog()
        return catalog.filter { item ->
            val target = normalizeTr("${item.title} ${item.hay} ${item.hayEn}")
            tokens.all { token -> target.contains(token) }
        }.take(60).map { it.toSearchResponse() }
    }
        private fun extractEpisodes(doc: Document, seriesUrl: String): List<Episode> {
        val basePath = seriesUrl.removePrefix(mainUrl).substringBefore("?").trimEnd('/')
        if (basePath.isBlank()) return emptyList()

        val grouped = LinkedHashMap<String, MutableList<String>>()
        for (a in doc.select("a[href]")) {
            val href = a.attr("href").trim().substringBefore("?").substringBefore("#")
            if (!href.startsWith("$basePath/")) continue

            val rel = href.removePrefix("$basePath/").trim('/')
            if (rel.isBlank()) continue

            val fullEpUrl = fixRelativeUrl(href)
            val text = a.text().trim()
            grouped.getOrPut(fullEpUrl) { mutableListOf() }.add(text)
        }

        val episodes = ArrayList<Episode>(grouped.size)
        for ((epUrl, texts) in grouped) {
            val rel = epUrl.removePrefix(seriesUrl.trimEnd('/')).trim('/')
            val parts = rel.split("/").filter { it.isNotBlank() }

            var seasonNum = 1
            var epNum: Int? = null

            val firstPart = parts.firstOrNull().orEmpty()
            val secondPart = parts.getOrNull(1).orEmpty()

            if (parts.size >= 3 && firstPart.all { it.isDigit() } && secondPart.all { it.isDigit() }) {
                seasonNum = firstPart.toIntOrNull() ?: 1
                epNum = secondPart.toIntOrNull()
            } else if (firstPart.all { it.isDigit() }) {
                epNum = firstPart.toIntOrNull()
            }

            val bestText = texts
                .map { it.replace(Regex("\\s+"), " ").trim() }
                .filter { it.isNotBlank() && !it.startsWith("\"") }
                .minByOrNull { it.length }
                ?: texts.firstOrNull { it.isNotBlank() }
                ?: "Bölüm ${epNum ?: (episodes.size + 1)}"

            episodes.add(
                newEpisode(epUrl) {
                    this.name = bestText
                    this.season = seasonNum
                    this.episode = epNum
                }
            )
        }

        return episodes
    }

    override suspend fun load(url: String): LoadResponse {
        val fixedUrl = fixRelativeUrl(url)
        val res = app.get(fixedUrl, headers = defaultHeaders, referer = "$mainUrl/")
        val doc = res.documentLarge

        val catalogMatch = cachedCatalog.firstOrNull { it.url == fixedUrl }

        val rawTitle = doc.selectFirst("h1")?.text()?.trim()
            ?: catalogMatch?.title
            ?: doc.title().substringBefore("Türkçe İzle").substringBefore("İzle").trim()

        val poster = catalogMatch?.poster
            ?: doc.selectFirst("meta[property=og:image]")?.attr("content")?.let { fixRelativeUrl(it) }
            ?: doc.selectFirst("img[src*='/resim/']")?.attr("src")?.let { fixRelativeUrl(it) }

        val plot = doc.selectFirst("meta[name=description]")?.attr("content")?.trim()
            ?: doc.select("p").map { it.text().trim() }.firstOrNull { it.length > 40 }

        val tags = catalogMatch?.genres ?: emptyList()

        val isMovie = fixedUrl.contains("/film/") || doc.selectFirst("[data-embeds]") != null
        if (isMovie) {
            return newMovieLoadResponse(rawTitle, fixedUrl, TvType.Movie, fixedUrl) {
                this.posterUrl = poster
                this.plot = plot
                this.tags = tags
            }
        }

        val episodes = extractEpisodes(doc, fixedUrl)
        val tvType = if (catalogMatch?.isAnime == true) TvType.Anime else TvType.Cartoon

        return newTvSeriesLoadResponse(rawTitle, fixedUrl, tvType, episodes) {
            this.posterUrl = poster
            this.plot = plot
            this.tags = tags
        }
    }

    private suspend fun resolveCenterPlayer(
        embedUrl: String,
        pageUrl: String,
        sourceLabel: String,
        callback: (ExtractorLink) -> Unit
    ) {
        val playerRes = app.get(
            embedUrl,
            headers = defaultHeaders,
            referer = pageUrl
        )
        val bridgeKey = Regex("""cvdBridgeKey\s*=\s*["']([^"']+)["']""")
            .find(playerRes.text)?.groupValues?.get(1)
            ?: return

        val authHeaders = mapOf(
            "User-Agent" to defaultHeaders["User-Agent"]!!,
            "Accept" to "*/*",
            "Accept-Language" to defaultHeaders["Accept-Language"]!!,
            "Origin" to mainUrl,
            "Referer" to "$mainUrl/",
            "Sec-Fetch-Dest" to "empty",
            "Sec-Fetch-Mode" to "cors",
            "Sec-Fetch-Site" to "same-site"
        )

        val authRes = app.get(
            "$videoOrigin/yetki.php?k=$bridgeKey",
            headers = authHeaders
        )

        val videoPath = authRes.headers["X-CVD-Video-URL"]?.trim().orEmpty()
        if (videoPath.isBlank()) return

        val cookieHeader = authRes.cookies
            .map { (k, v) -> "$k=$v" }
            .joinToString("; ")

        val streamUrl = if (videoPath.startsWith("http")) videoPath else "$videoOrigin$videoPath"

        val streamHeaders = buildMap {
            put("User-Agent", defaultHeaders["User-Agent"]!!)
            put("Accept", "*/*")
            put("Sec-Fetch-Dest", "video")
            put("Sec-Fetch-Mode", "no-cors")
            put("Sec-Fetch-Site", "same-site")
            if (cookieHeader.isNotBlank()) {
                put("Cookie", cookieHeader)
            }
        }

        callback.invoke(
            newExtractorLink(
                source = name,
                name = sourceLabel,
                url = streamUrl,
                type = ExtractorLinkType.VIDEO
            ) {
                this.referer = embedUrl
                this.quality = Qualities.Unknown.value
                this.headers = streamHeaders
            }
        )
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val pageUrl = fixRelativeUrl(data)
        val pageRes = app.get(pageUrl, headers = defaultHeaders, referer = "$mainUrl/")
        val html = pageRes.text
        val doc = pageRes.documentLarge

        var foundAny = false

        val embedsB64 = doc.selectFirst("[data-embeds]")?.attr("data-embeds")?.trim().orEmpty()
        if (embedsB64.isNotBlank()) {
            val decoded = runCatching {
                String(Base64.decode(embedsB64, Base64.DEFAULT), Charsets.UTF_8)
            }.getOrNull()

            if (!decoded.isNullOrBlank()) {
                val arr = runCatching { JSONArray(decoded) }.getOrNull()
                if (arr != null) {
                    for (i in 0 until arr.length()) {
                        val rawEmbed = arr.optString(i).trim()
                        if (rawEmbed.isBlank()) continue
                        val embedUrl = fixRelativeUrl(rawEmbed)
                        val label = if (arr.length() > 1) "$name (Kaynak ${i + 1})" else name

                        if (embedUrl.contains("/diziyeni/player.php")) {
                            resolveCenterPlayer(embedUrl, pageUrl, label, callback)
                            foundAny = true
                        } else {
                            if (loadExtractor(embedUrl, pageUrl, subtitleCallback, callback)) {
                                foundAny = true
                            }
                        }
                    }
                }
            }
            return foundAny
        }

        val diziId = Regex("""window\.__diziid\s*=\s*"([^"]+)"""")
            .find(html)?.groupValues?.get(1)
        val epKey = Regex("""window\.__cvdVideoEpisodeKey\s*=\s*"([^"]+)"""")
            .find(html)?.groupValues?.get(1)
            ?: Regex("""window\.__bolum\s*=\s*"([^"]+)"""")
                .find(html)?.groupValues?.get(1)

        if (!diziId.isNullOrBlank() && !epKey.isNullOrBlank()) {
            val vsUrl = "$mainUrl/diziyeni/video_sources.php?s=$diziId&b=$epKey"
            val vsText = app.get(
                vsUrl,
                headers = defaultHeaders,
                referer = pageUrl
            ).text

            val vsJson = runCatching { JSONObject(vsText) }.getOrNull()
            val sources = vsJson?.optJSONArray("sources")
            if (sources != null) {
                for (i in 0 until sources.length()) {
                    val srcObj = sources.optJSONObject(i) ?: continue
                    val rawEmbed = srcObj.optString("embed").trim()
                    if (rawEmbed.isBlank()) continue

                    val embedUrl = fixRelativeUrl(rawEmbed)
                    val ad = srcObj.optString("ad").trim()
                    val kanal = srcObj.optString("kanal").trim()
                    val take = srcObj.optInt("take", 1)

                    val label = buildString {
                        append(ad.ifBlank { kanal.ifBlank { name } })
                        if (take > 1) append(" (Çekim $take)")
                    }

                    if (embedUrl.contains("/diziyeni/player.php")) {
                        resolveCenterPlayer(embedUrl, pageUrl, label, callback)
                        foundAny = true
                    } else {
                        if (loadExtractor(embedUrl, pageUrl, subtitleCallback, callback)) {
                            foundAny = true
                        }
                    }
                }
            }
        }

        return foundAny
    }
                     }
                     
