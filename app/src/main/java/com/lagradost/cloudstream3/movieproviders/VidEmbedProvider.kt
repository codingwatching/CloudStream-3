package com.lagradost.cloudstream3.movieproviders

import org.jsoup.Jsoup
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.extractors.Vidstream
import com.lagradost.cloudstream3.network.get
import com.lagradost.cloudstream3.network.text
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.getQualityFromName
import java.util.*
import kotlin.collections.ArrayList

/** Needs to inherit from MainAPI() to
 * make the app know what functions to call
 */
class VidEmbedProvider : MainAPI() {
    // mainUrl is good to have as a holder for the url to make future changes easier.
    override val mainUrl: String
        get() = "https://vidembed.cc"

    // name is for how the provider will be named which is visible in the UI, no real rules for this.
    override val name: String
        get() = "VidEmbed"

    // hasQuickSearch defines if quickSearch() should be called, this is only when typing the searchbar
    // gives results on the site instead of bringing you to another page.
    // if hasQuickSearch is true and quickSearch() hasn't been overridden you will get errors.
    // VidEmbed actually has quick search on their site, but the function wasn't implemented.
    override val hasQuickSearch: Boolean
        get() = false

    // If getMainPage() is functional, used to display the homepage in app, an optional, but highly encouraged endevour.
    override val hasMainPage: Boolean
        get() = true

    // Sometimes on sites the urls can be something like "/movie.html" which translates to "*full site url*/movie.html" in the browser
    private fun fixUrl(url: String): String {
        return if (url.startsWith("//")) {
            "https:$url"
        } else if (url.startsWith("/")) {
            "$mainUrl$url"
        } else {
            url
        }
    }

    // This is just extra metadata about what type of movies the provider has.
    // Needed for search functionality.
    override val supportedTypes: Set<TvType>
        get() = setOf(TvType.Anime, TvType.AnimeMovie, TvType.TvSeries, TvType.Movie)

    // Searching returns a SearchResponse, which can be one of the following: AnimeSearchResponse, MovieSearchResponse, TorrentSearchResponse, TvSeriesSearchResponse
    // Each of the classes requires some different data, but always has some critical things like name, poster and url.
    override fun search(query: String): ArrayList<SearchResponse> {
        // Simply looking at devtools network is enough to spot a request like:
        // https://vidembed.cc/search.html?keyword=neverland where neverland is the query, can be written as below.
        val link = "$mainUrl/search.html?keyword=$query"
        val html = get(link).text
        val soup = Jsoup.parse(html)

        return ArrayList(soup.select(".listing.items > .video-block").map { li ->
            // Selects the href in <a href="...">
            val href = fixUrl(li.selectFirst("a").attr("href"))
            val poster = li.selectFirst("img")?.attr("src")

            // .text() selects all the text in the element, be careful about doing this while too high up in the html hierarchy
            val title = li.selectFirst(".name").text()
            // Use get(0) and toIntOrNull() to prevent any possible crashes, [0] or toInt() will error the search on unexpected values.
            val year = li.selectFirst(".date")?.text()?.split("-")?.get(0)?.toIntOrNull()

            TvSeriesSearchResponse(
                // .trim() removes unwanted spaces in the start and end.
                if (!title.contains("Episode")) title else title.split("Episode")[0].trim(),
                href,
                this.name,
                TvType.TvSeries,
                poster, year,
                // You can't get the episodes from the search bar.
                null
            )
        })
    }


    // Load, like the name suggests loads the info page, where all the episodes and data usually is.
    // Like search you should return either of: AnimeLoadResponse, MovieLoadResponse, TorrentLoadResponse, TvSeriesLoadResponse.
    override fun load(url: String): LoadResponse? {
        // Gets the url returned from searching.
        val html = get(url).text
        val soup = Jsoup.parse(html)

        var title = soup.selectFirst("h1,h2,h3").text()
        title = if (!title.contains("Episode")) title else title.split("Episode")[0].trim()

        val description = soup.selectFirst(".post-entry")?.text()?.trim()
        var poster: String? = null

        val episodes = soup.select(".listing.items.lists > .video-block").withIndex().map { (_, li) ->
            val epTitle = if (li.selectFirst(".name") != null)
                if (li.selectFirst(".name").text().contains("Episode"))
                    "Episode " + li.selectFirst(".name").text().split("Episode")[1].trim()
                else
                    li.selectFirst(".name").text()
            else ""
            val epThumb = li.selectFirst("img")?.attr("src")
            val epDate = li.selectFirst(".meta > .date").text()

            if (poster == null) {
                poster = li.selectFirst("img")?.attr("onerror")?.split("=")?.get(1)?.replace(Regex("[';]"), "")
            }

            val epNum = Regex("""Episode (\d+)""").find(epTitle)?.destructured?.component1()?.toIntOrNull()

            TvSeriesEpisode(
                epTitle,
                null,
                epNum,
                fixUrl(li.selectFirst("a").attr("href")),
                epThumb,
                epDate
            )
        }.reversed()

        val year = episodes.first().date?.split("-")?.get(0)?.toIntOrNull()

        // Make sure to get the type right to display the correct UI.
        val tvType = if (episodes.size == 1 && episodes[0].name == title) TvType.Movie else TvType.TvSeries

        return when (tvType) {
            TvType.TvSeries -> {
                TvSeriesLoadResponse(
                    title,
                    url,
                    this.name,
                    tvType,
                    episodes,
                    poster,
                    year,
                    description,
                    ShowStatus.Ongoing,
                    null,
                    null
                )
            }
            TvType.Movie -> {
                MovieLoadResponse(
                    title,
                    url,
                    this.name,
                    tvType,
                    episodes[0].data,
                    poster,
                    year,
                    description,
                    null,
                    null
                )
            }
            else -> null
        }
    }

    // This loads the homepage, which is basically a collection of search results with labels.
    // Optional function, but make sure to enable hasMainPage if you program this.
    override fun getMainPage(): HomePageResponse {
        val urls = listOf(
            mainUrl,
            "$mainUrl/movies",
            "$mainUrl/series",
            "$mainUrl/recommended-series",
            "$mainUrl/cinema-movies"
        )
        val homePageList = ArrayList<HomePageList>()
        // .pmap {} is used to fetch the different pages in parallel
        urls.pmap { url ->
            val response = get(url, timeout = 20).text
            val document = Jsoup.parse(response)
            document.select("div.main-inner")?.forEach {
                // Always trim your text unless you want the risk of spaces at the start or end.
                val title = it.select(".widget-title").text().trim()
                val elements = it.select(".video-block").map {
                    val link = fixUrl(it.select("a").attr("href"))
                    val image = it.select(".picture > img").attr("src")
                    val name = it.select("div.name").text().trim()
                    val isSeries = (name.contains("Season") || name.contains("Episode"))

                    if (isSeries) {
                        TvSeriesSearchResponse(
                            name,
                            link,
                            this.name,
                            TvType.TvSeries,
                            image,
                            null,
                            null,
                        )
                    } else {
                        MovieSearchResponse(
                            name,
                            link,
                            this.name,
                            TvType.Movie,
                            image,
                            null,
                            null,
                        )
                    }
                }

                homePageList.add(
                    HomePageList(
                        title, elements
                    )
                )

            }

        }
        return HomePageResponse(homePageList)
    }

    // loadLinks gets the raw .mp4 or .m3u8 urls from the data parameter in the episodes class generated in load()
    // See TvSeriesEpisode(...) in this provider.
    // The data are usually links, but can be any other string to help aid loading the links.
    override fun loadLinks(
        data: String,
        isCasting: Boolean,
        // These callbacks are functions you should call when you get a link to a subtitle file or media file.
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // "?: return" is a very useful statement which returns if the iframe link isn't found.
        val iframeLink = Jsoup.parse(get(data).text).selectFirst("iframe")?.attr("src") ?: return false

        // In this case the video player is a vidstream clone and can be handled by the vidstream extractor.
        // This case is a both unorthodox and you normally do not call extractors as they detect the url returned and does the rest.
        val vidstreamObject = Vidstream("https://vidembed.cc")
        // https://vidembed.cc/streaming.php?id=MzUwNTY2&... -> MzUwNTY2
        val id = Regex("""id=([^&]*)""").find(iframeLink)?.groupValues?.get(1)

        if (id != null) {
            vidstreamObject.getUrl(id, isCasting, callback)
        }

        val html = get(fixUrl(iframeLink)).text
        val soup = Jsoup.parse(html)

        val servers = soup.select(".list-server-items > .linkserver").mapNotNull { li ->
            if (!li?.attr("data-video").isNullOrEmpty()) {
                Pair(li.text(), fixUrl(li.attr("data-video")))
            } else {
                null
            }
        }
        servers.forEach {
            // When checking strings make sure to make them lowercase and trimmed because edgecases like "beta server " wouldn't work otherwise.
            if (it.first.toLowerCase(Locale.ROOT).trim() == "beta server") {
                // Group 1: link, Group 2: Label
                // Regex can be used to effectively parse small amounts of json without bothering with writing a json class.
                val sourceRegex = Regex("""sources:[\W\w]*?file:\s*["'](.*?)["'][\W\w]*?label:\s*["'](.*?)["']""")
                val trackRegex = Regex("""tracks:[\W\w]*?file:\s*["'](.*?)["'][\W\w]*?label:\s*["'](.*?)["']""")

                // Having a referer is often required. It's a basic security check most providers have.
                // Try to replicate what your browser does.
                val serverHtml = get(it.second, headers = mapOf("referer" to iframeLink)).text
                sourceRegex.findAll(serverHtml).forEach { match ->
                    callback.invoke(
                        ExtractorLink(
                            this.name,
                            match.groupValues.getOrNull(2)?.let { "${this.name} $it" } ?: this.name,
                            match.groupValues[1],
                            it.second,
                            // Useful function to turn something like "1080p" to an app quality.
                            getQualityFromName(match.groupValues.getOrNull(2) ?: ""),
                            // Kinda risky
                            // isM3u8 makes the player pick the correct extractor for the source.
                            // If isM3u8 is wrong the player will error on that source.
                            match.groupValues[1].endsWith(".m3u8"),
                        )
                    )
                }
                trackRegex.findAll(serverHtml).forEach { match ->
                    subtitleCallback.invoke(
                        SubtitleFile(
                            match.groupValues.getOrNull(2) ?: "Unknown",
                            match.groupValues[1]
                        )
                    )
                }
            }
        }

        return true
    }
}