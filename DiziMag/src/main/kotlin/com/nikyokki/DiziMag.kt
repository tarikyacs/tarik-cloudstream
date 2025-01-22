package com.nikyokki

import CryptoJS
import com.fasterxml.jackson.databind.ObjectMapper
import com.lagradost.cloudstream3.Actor
import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.ErrorLoadingException
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.fixUrlNull
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.newTvSeriesSearchResponse
import com.lagradost.cloudstream3.toRatingInt
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.util.regex.Pattern

class DiziMag : MainAPI() {
    override var mainUrl = "https://dizimag.org"
    override var name = "DiziMag"
    override val hasMainPage = true
    override var lang = "tr"
    override val hasQuickSearch = false
    override val hasChromecastSupport = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.TvSeries, TvType.Movie)

    // ! CloudFlare bypass
    override var sequentialMainPage =
        true        // * https://recloudstream.github.io/dokka/-cloudstream/com.lagradost.cloudstream3/-main-a-p-i/index.html#-2049735995%2FProperties%2F101969414
    override var sequentialMainPageDelay = 250L  // ? 0.05 saniye
    override var sequentialMainPageScrollDelay = 250L  // ? 0.05 saniye

    override val mainPage = mainPageOf(
        "${mainUrl}/kesfet/eyJjYXRlZ29yeSI6W3siaWQiOiI4OSIsIm5hbWUiOiJBa3NpeW9uICYgTWFjZXJhIn1dfQ==" to "Aksiyon",
        "${mainUrl}/kesfet/eyJjYXRlZ29yeSI6W3siaWQiOiI5MiIsIm5hbWUiOiJEcmFtIn1dfQ==" to "Dram",
        "${mainUrl}/kesfet/eyJjYXRlZ29yeSI6W3siaWQiOiI4OCIsIm5hbWUiOiJCaWxpbSBLdXJndSAmIEZhbnRhemkifV19" to "Bilim Kurgu",

        "${mainUrl}/film/tur/aile" to "Aile Film"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val mainReq = app.get("${request.data}/${page}")

        val document = mainReq.document
        val home = document.select("li.w-full").mapNotNull { it.diziler() }

        return newHomePageResponse(request.name, home)
    }

    private fun Element.diziler(): SearchResponse? {
        val title =
            this.selectFirst("div.filter-result-box-subject-top-left h2")?.text() ?: return null
        val href =
            fixUrlNull(this.selectFirst("div.filter-result-box-subject-top-left a")?.attr("href"))
                ?: return null
        val posterUrl =
            fixUrlNull(this.selectFirst("div.filter-result-box-image img")?.attr("data-src"))

        return if (href.contains("/dizi/")) {
            newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                this.posterUrl = posterUrl
            }
        } else {
            newMovieSearchResponse(title, href, TvType.Movie) {
                this.posterUrl = posterUrl
            }
        }
    }

    private fun Element.toPostSearchResult(): SearchResponse? {
        val title = this.selectFirst("span")?.text()?.trim() ?: return null
        val href = fixUrlNull(this.selectFirst("a")?.attr("href")) ?: return null
        val posterUrl = fixUrlNull(this.selectFirst("img")?.attr("data-src"))

        return newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
            this.posterUrl = posterUrl
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val veriler = mutableListOf<SearchResponse>()

        val searchReq = app.post(
            "${mainUrl}/search",
            data = mapOf(
                "query" to query
            ),
            headers = mapOf(
                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:135.0) Gecko/20100101 Firefox/135.0",
                "Accept" to "application/json, text/javascript, */*; q=0.01",
                "X-Requested-With" to "XMLHttpRequest",
                "Content-Type" to "application/x-www-form-urlencoded; charset=UTF-8",
                "Accept-Language" to "en-US,en;q=0.5"
            ),
            referer = "${mainUrl}/"
        ).parsedSafe<SearchResult>()

        if (searchReq?.success != true) {
            throw ErrorLoadingException("Invalid Json response")
        }

        val searchDoc = searchReq.theme

        val document = Jsoup.parse(searchDoc.toString())
        val results = mutableListOf<SearchResponse>()

        document.select("ul li").forEach { listItem ->
            val href = listItem.selectFirst("a")?.attr("href")
            if (href != null && (href.contains("/dizi/") || href.contains("/film/"))) {
                val result = listItem.toPostSearchResult()
                result?.let { results.add(it) }
            }
        }
        return veriler
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun load(url: String): LoadResponse? {

        val mainReq = app.get(url, referer = mainUrl)
        val document = mainReq.document
        val title =
            document.selectFirst("div.page-title h1")?.selectFirst("a")?.text() ?: return null
        val poster =
            fixUrlNull(document.selectFirst("div.series-profile-image img")?.attr("src"))
        val year =
            document.selectFirst("h1 span")?.text()?.substringAfter("(")?.substringBefore(")")
                ?.toIntOrNull()
        val rating = document.selectFirst("span.color-imdb")?.text()?.trim()?.toRatingInt()
        val duration =
            document.selectXpath("//span[text()='Süre']//following-sibling::p").text().trim()
                .split(" ").first().toIntOrNull()
        val description = document.selectFirst("div.series-profile-summary p")?.text()?.trim()
        val tags = document.selectFirst("div.series-profile-type")?.select("a")
            ?.mapNotNull { it.text().trim() }
        val trailer = document.selectFirst("div.series-profile-trailer")?.attr("data-yt")
        val actors = mutableListOf<Actor>()
        document.select("div.series-profile-cast li").forEach {
            val img = fixUrlNull(it.selectFirst("img")?.attr("data-src"))
            val name = it.selectFirst("h5.truncate")?.text()?.trim() ?: return null
            actors.add(Actor(name, img))
        }
        if (url.contains("/dizi/")) {
            val episodeses = mutableListOf<Episode>()
            var szn = 1
            for (sezon in document.select("div.series-profile-episode-list")) {
                var blm = 1
                for (bolum in sezon.select("li")) {
                    val epName = bolum.selectFirst("h6.truncate a")?.text() ?: continue
                    val epHref = fixUrlNull(bolum.select("h6.truncate a").attr("href")) ?: continue
                    val epEpisode = blm++
                    val epSeason = szn
                    episodeses.add(
                        Episode(
                            data = epHref,
                            name = epName,
                            season = epSeason,
                            episode = epEpisode
                        )
                    )
                }
                szn++
            }
            println("Episodes : " + episodeses.size)

            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodeses) {
                this.posterUrl = poster
                this.year = year
                this.plot = description
                this.tags = tags
                this.rating = rating
                addActors(actors)
                addTrailer("https://www.youtube.com/embed/${trailer}")
            }
        } else {
            return newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.year = year
                this.plot = description
                this.tags = tags
                this.rating = rating
                this.duration = duration
                addActors(actors)
                addTrailer("https://www.youtube.com/embed/${trailer}")
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val headers = mapOf(
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:135.0) Gecko/20100101 Firefox/135.0",
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
            "Referer" to "$mainUrl/"
        )
        val aa = app.get(mainUrl)
        val ciSession = aa.cookies["ci_session"].toString()
        val document = app.get(
            data, headers = headers, cookies = mapOf(
                "ci_session" to ciSession
            )
        ).document
        val iframe =
            fixUrlNull(document.selectFirst("div#tv-spoox2 iframe")?.attr("src")) ?: return false
        val docum = app.get(iframe, headers = headers, referer = "$mainUrl/").document
        docum.select("script").forEach { sc ->
            if (sc.toString().contains("bePlayer")) {
                val pattern = Pattern.compile("bePlayer\\('(.*?)', '(.*?)'\\)")
                val matcher = pattern.matcher(sc.toString().trimIndent())
                if (matcher.find()) {
                    val key = matcher.group(1)
                    val jsonCipher = matcher.group(2)
                    val cipherData = ObjectMapper().readValue(
                        jsonCipher?.replace("\\/", "/"),
                        Cipher::class.java
                    )
                    val ctt = cipherData.ct
                    val iv = cipherData.iv
                    val s = cipherData.s
                    val decrypt = key?.let { CryptoJS.decrypt(it, ctt, iv, s) }

                    val jsonData = ObjectMapper().readValue(decrypt, JsonData::class.java)

                    val m3u8Content = app.get(
                        jsonData.videoLocation,
                        referer = iframe,
                        headers = mapOf("Accept" to "*/*", "Referer" to iframe)
                    ).document.body()
                    val regex = Regex("#EXT-X-STREAM-INF:.*? (https?://\\S+)")
                    val matchResult = regex.find(m3u8Content.text())
                    val m3uUrl = matchResult?.groupValues?.get(1) ?: ""
                    callback.invoke(
                        ExtractorLink(
                            source = this.name,
                            name = this.name,
                            headers = mapOf("Accept" to "*/*", "Referer" to iframe),
                            url = m3uUrl,
                            referer = iframe,
                            quality = Qualities.Unknown.value,
                            isM3u8 = true
                        )
                    )
                }
            }
        }

        loadExtractor(iframe, "${mainUrl}/", subtitleCallback, callback)
        return true
    }
}
