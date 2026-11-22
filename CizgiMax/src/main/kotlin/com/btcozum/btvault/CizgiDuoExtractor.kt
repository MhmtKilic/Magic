@file:Suppress("DEPRECATION", "DEPRECATION_ERROR")

package com.btcozum.btvault

import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.extractors.helper.AesHelper
import com.lagradost.cloudstream3.utils.*

open class CizgiDuo : ExtractorApi() {
    override var name            = "CizgiDuo"
    override var mainUrl         = "https://cizgiduo.online"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val extRef = referer ?: ""
        val iSource = app.get(url, referer = extRef).text

        // GÜNCELLEME: bePlayer çağrısı boşluk/quote/tırnak farklarına toleranslı
        val bePlayer = Regex(
            """bePlayer\s*\(\s*['"]([^'"]+)['"]\s*,\s*['"](\{.*?\})['"]\s*\)""",
            setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)
        ).find(iSource)?.groupValues
            ?: Regex("""bePlayer\('([^']+)',\s*'(\{[^}]+\})'\);""").find(iSource)?.groupValues
            ?: throw ErrorLoadingException("CizgiDuo: bePlayer bulunamadı")

        val bePlayerPass = bePlayer[1]
        val bePlayerData = bePlayer[2]

        val encrypted = AesHelper.cryptoAESHandler(bePlayerData, bePlayerPass.toByteArray(), false)
            ?.replace("\\", "")
            ?: throw ErrorLoadingException("CizgiDuo: AES şifre çözme başarısız")
        Log.d("Kekik_${this.name}", "decrypted length=${encrypted.length}")

        // GÜNCELLEME: m3u8 linkini daha esnek yakala (video_location veya doğrudan m3u8)
        val m3uLink = Regex("""video_location"\s*:\s*"([^"]+)""")
            .find(encrypted)?.groupValues?.get(1)
            ?: Regex("""(https?://[^\s"'<>\\]+\.m3u8[^\s"'<>\\]*)""", RegexOption.IGNORE_CASE)
                .find(encrypted)?.groupValues?.get(1)
            ?: throw ErrorLoadingException("CizgiDuo: m3u linki bulunamadı")

        callback.invoke(
            newExtractorLink(
                source = this.name,
                name = this.name,
                url = m3uLink,
                type = ExtractorLinkType.M3U8
            ) {
                this.referer = url
                this.quality = Qualities.Unknown.value
            }
        )
    }
}
