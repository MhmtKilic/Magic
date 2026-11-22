@file:Suppress("DEPRECATION", "DEPRECATION_ERROR")

package com.btcozum.btvault

import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*

open class SibNet : ExtractorApi() {
    override val name            = "SibNet"
    override val mainUrl         = "https://video.sibnet.ru"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val extRef = referer ?: ""
        val iSource = app.get(url, referer = extRef).text

        // GÜNCELLEME: 3 katmanlı fallback
        // 1) Klasik player.src([{src: "..."}]) formatı
        // 2) src: "..." genel formatı (m3u8/mp4)
        // 3) Sayfa içinde doğrudan m3u8/mp4 linki
        val rawSrc = Regex("""player\.src\(\[\{src:\s*["']([^"']+)["']""")
            .find(iSource)?.groupValues?.get(1)
            ?: Regex("""src\s*:\s*["']([^"']+\.(?:m3u8|mp4)[^"']*)["']""", RegexOption.IGNORE_CASE)
                .find(iSource)?.groupValues?.get(1)
            ?: Regex("""https?://[^\s"']+\.(?:m3u8|mp4)[^\s"']*""", RegexOption.IGNORE_CASE)
                .find(iSource)?.value
            ?: throw ErrorLoadingException("SibNet: video kaynağı bulunamadı")

        val m3uLink = fixUrl(rawSrc)
        Log.d("Kekik_${this.name}", "m3uLink » $m3uLink")

        callback.invoke(
            newExtractorLink(
                source = this.name,
                name = this.name,
                url = m3uLink,
                type = INFER_TYPE
            ) {
                this.referer = url
                this.quality = Qualities.Unknown.value
            }
        )
    }
}
