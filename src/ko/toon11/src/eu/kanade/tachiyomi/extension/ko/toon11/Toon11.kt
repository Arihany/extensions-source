package eu.kanade.tachiyomi.extension.ko.toon11

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.asObservableSuccess
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.ParsedHttpSource
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import rx.Observable
import okhttp3.Dns
import java.net.Inet4Address
import java.net.InetAddress
import java.text.SimpleDateFormat
import java.util.ArrayList
import java.util.Locale

class Toon11 : ParsedHttpSource() {

    override val name = "cookmana"

    // www 제거: Referer/리다이렉트 꼬임 방지용
    override val baseUrl = "https://cookmana.com"

    override val lang = "ko"
    override val supportsLatest = true

//    private val limiterDispatcher = Dispatcher().apply {
//        maxRequests = 32
//        maxRequestsPerHost = 8
//    }

    private val fallbackThumbHost = "https://cookmana.com"

    override fun headersBuilder(): Headers.Builder = super.headersBuilder()
        .set("Referer", "$baseUrl/")
        .set(
            "Accept",
            "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8",
        )

    private val baseClient: OkHttpClient = network.cloudflareClient.newBuilder()
//        .dispatcher(limiterDispatcher)
        .dns(IPv4Dns())
        .addInterceptor(FixupHeadersInterceptor(baseUrl))
        .build()

    private val rateLimitedClient: OkHttpClient = baseClient.newBuilder()
//        .apply { rateLimit(RATE_LIMIT_PERMITS, RATE_LIMIT_PERIOD_SECONDS) }
        .build()

    override val client: OkHttpClient = baseClient

    // ---------- Requests ----------
    private val latestBase =
        "$baseUrl/lastest"
    private val popularBase =
        "$baseUrl/popular"

    override fun popularMangaRequest(page: Int): Request {
        return GET(popularBase, headers)
    }

    override fun latestUpdatesRequest(page: Int): Request {
        // Target API: https://cookmana.com/api/lastest/list?page=2&type=0&pageSize=102
        val url = "$baseUrl/api/lastest/list".toHttpUrl().newBuilder().apply {
            addQueryParameter("page", page.toString())
            addQueryParameter("type", "0")
            addQueryParameter("pageSize", "102")
        }.build()
        return GET(url, headers)
    }

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        return if (query.isNotBlank()) {
            val url = "$baseUrl/search".toHttpUrl().newBuilder().apply {
                addQueryParameter("key", query)
                if (page > 1) addQueryParameter("page", page.toString())
            }.build()
            GET(url, headers)
        } else {
            var urlString = ""
            var isOver = ""
            var genre = ""

            filters.forEach { filter ->
                when (filter) {
                    is SortFilter -> urlString = filter.selected
                    is StatusFilter -> isOver = filter.selected
                    is GenreFilter -> genre = filter.selected
                    else -> {}
                }
            }

            val url = urlString.toHttpUrl().newBuilder().apply {
                addQueryParameter("is_over", isOver)
                if (page > 1) addQueryParameter("page", page.toString())
                if (genre.isNotEmpty()) addQueryParameter("type", genre)
            }.build()

            GET(url, headers)
        }
    }

    // ---------- HTML fetch는 전부 rateLimitedClient로 ----------
    override fun fetchPopularManga(page: Int): Observable<MangasPage> {
        return rateLimitedClient.newCall(popularMangaRequest(page))
            .asObservableSuccess()
            .map(::popularMangaParse)
    }

    override fun fetchLatestUpdates(page: Int): Observable<MangasPage> {
        return rateLimitedClient.newCall(latestUpdatesRequest(page))
            .asObservableSuccess()
            .map(::latestUpdatesParse)
    }

    override fun fetchSearchManga(page: Int, query: String, filters: FilterList): Observable<MangasPage> {
        return rateLimitedClient.newCall(searchMangaRequest(page, query, filters))
            .asObservableSuccess()
            .map(::searchMangaParse)
    }

    override fun fetchMangaDetails(manga: SManga): Observable<SManga> {
        return rateLimitedClient.newCall(mangaDetailsRequest(manga))
            .asObservableSuccess()
            .map { response ->
                mangaDetailsParse(response.asJsoup()).apply { initialized = true }
            }
    }

    override fun fetchChapterList(manga: SManga): Observable<List<SChapter>> {
        return rateLimitedClient.newCall(chapterListRequest(manga))
            .asObservableSuccess()
            .map(::chapterListParse)
    }

    override fun fetchPageList(chapter: SChapter): Observable<List<Page>> {
        return rateLimitedClient.newCall(pageListRequest(chapter))
            .asObservableSuccess()
            .map { response -> pageListParse(response.asJsoup()) }
    }

    // ---------- Selectors ----------
    override fun popularMangaSelector() = ".newm-boxo > li"
    override fun latestUpdatesSelector() = popularMangaSelector()
    override fun searchMangaSelector() = popularMangaSelector()

    override fun popularMangaNextPageSelector(): String? = null
    override fun latestUpdatesNextPageSelector() = ".mf-Pagination-wrap > button:last-child svg"
    override fun searchMangaNextPageSelector() = latestUpdatesNextPageSelector()

    // ---------- URL / Thumbnail helpers ----------
    private fun normalizeImgUrl(raw: String): String? {
        val u = raw.trim()
        if (u.isBlank()) return null
        return when {
            u.startsWith("http://") || u.startsWith("https://") -> u
            u.startsWith("//") -> "https:$u"
            u.startsWith("/") -> baseUrl + u
            else -> u
        }
    }

    // ---------- Manga parsing ----------
    override fun popularMangaFromElement(element: Element) = SManga.create().apply {
        val linkElement = element.selectFirst("a")!!
        val imgElement = element.selectFirst("img.lazyImg")
        val titleElement = element.selectFirst(".new-box-ttt span")

        title = titleElement?.text() ?: imgElement?.attr("alt") ?: "Unknown"
        url = linkElement.attr("href")

        val imgSrc = imgElement?.attr("data-src")?.takeIf { it.isNotBlank() }
            ?: imgElement?.attr("src")

        thumbnail_url = imgSrc?.let { normalizeImgUrl(it) }
    }

    override fun latestUpdatesFromElement(element: Element) = popularMangaFromElement(element)

    override fun searchMangaFromElement(element: Element) = popularMangaFromElement(element)

    // ---------- Details ----------
    override fun mangaDetailsParse(document: Document): SManga {
        return SManga.create().apply {
            val infoSection = document.selectFirst(".dt-left-tt")
            title = infoSection?.selectFirst("h1")?.text() ?: "Unknown"
            
            // Thumbnail from style="background-image: url('...')"
            val bgImg = document.selectFirst(".dt-mn-bgimg")?.attr("style")
            val bgUrl = bgImg?.substringAfter("url('")?.substringBefore("')")
                ?: bgImg?.substringAfter("url(")?.substringBefore(")")
            thumbnail_url = bgUrl?.let { normalizeImgUrl(it) }

            author = infoSection?.select("div.detail-title1 p a")?.text()
            genre = infoSection?.select("div.detail-title1 span a")?.joinToString { it.text().removePrefix("#") }
            description = document.selectFirst("div.detail-title2 p")?.text()

            // Status detection not explicitly found in provided HTML, defaulting to ONGOING/UNKNOWN
            // If "완결" text appears in future, add logic here.
            status = SManga.ONGOING
        }
    }

    override fun chapterListRequest(manga: SManga): Request {
        // manga.url example: /episode/3737 or https://cookmana.com/episode/3737
        // target API: https://cookmana.com/api/episode/list/3737?page=1&order=desc
        val id = manga.url.trimEnd('/').substringAfterLast('/')
        val apiUrl = "$baseUrl/api/episode/list/$id".toHttpUrl().newBuilder()
            .addQueryParameter("page", "1")
            .addQueryParameter("order", "desc")
            .build()
            
        return GET(apiUrl, headers)
    }

    // ---------- Chapters ----------
    private tailrec fun parseChapters(mangaId: String, currentUrl: String, chapters: ArrayList<SChapter>) {
        val nextResponse = rateLimitedClient.newCall(GET(currentUrl, headers)).execute()
        // API returns HTML fragment (list items), wrap it to make a valid document
        val htmlFragment = nextResponse.body.string()
        val document = org.jsoup.Jsoup.parseBodyFragment(htmlFragment)

        document.select(chapterListSelector()).forEach {
            chapters.add(chapterFromElement(it))
        }

        // Pagination: API response usually contains the pagination HTML block too.
        // Check for next button
        val nextButton = document.selectFirst(".mf-Pagination-wrap button.active + button[data-page]")

        if (nextButton != null) {
            val nextPageNum = nextButton.attr("data-page")
            if (nextPageNum.isNotEmpty()) {
                val nextUrl = "$baseUrl/api/episode/list/$mangaId".toHttpUrl().newBuilder()
                    .addQueryParameter("page", nextPageNum)
                    .addQueryParameter("order", "desc")
                    .build()
                    .toString()

                parseChapters(mangaId, nextUrl, chapters)
            }
        }
    }

    override fun chapterListParse(response: Response): List<SChapter> {
        // The first response is from chapterListRequest (API)
        val htmlFragment = response.body.string()
        val document = org.jsoup.Jsoup.parseBodyFragment(htmlFragment)
        val chapters = ArrayList<SChapter>()

        document.select(chapterListSelector()).forEach {
            chapters.add(chapterFromElement(it))
        }

        // Parse manga ID from the original API request URL to use in recursion
        // Request URL example: .../api/episode/list/3737?page=1...
        val pathSegments = response.request.url.pathSegments
        val mangaId = pathSegments.getOrNull(pathSegments.size - 1) ?: ""
        // pathSegments for /api/episode/list/3737 are [api, episode, list, 3737]

        val nextButton = document.selectFirst(".mf-Pagination-wrap button.active + button[data-page]")
        if (nextButton != null && mangaId.isNotEmpty()) {
            val nextPageNum = nextButton.attr("data-page")
            if (nextPageNum.isNotEmpty()) {
                val nextUrl = "$baseUrl/api/episode/list/$mangaId".toHttpUrl().newBuilder()
                    .addQueryParameter("page", nextPageNum)
                    .addQueryParameter("order", "desc")
                    .build()
                    .toString()

                parseChapters(mangaId, nextUrl, chapters)
            }
        }
        return chapters
    }


    override fun chapterListSelector() = "ul.mEpisodeList > li"

    override fun chapterFromElement(element: Element): SChapter {
        return SChapter.create().apply {
            val link = element.selectFirst("a")!!
            url = link.attr("href").substringBefore("?order=")

            val titleHeader = element.selectFirst(".m-episode-list-item-title")
            name = titleHeader?.text()?.trim() ?: "화"

            val dateText = element.selectFirst(".dt-le-c > p")?.text()?.trim()
            date_upload = parseChapterDate(dateText)
        }
    }

    private fun parseChapterDate(date: String?): Long {
        if (date.isNullOrBlank()) return 0L
        return try {
            // Format example: 2026.01.26
            val dateFormat = SimpleDateFormat("yyyy.MM.dd", Locale.KOREA)
            dateFormat.parse(date)?.time ?: 0L
        } catch (e: Exception) {
            0L
        }
    }

    // ---------- Pages ----------
    override fun pageListRequest(chapter: SChapter): Request {
        return GET(baseUrl + chapter.url, headers)
    }

    override fun pageListParse(document: Document): List<Page> {
        return document.select(".lazy-img-wrap img").mapIndexed { i, element ->
            val url = element.attr("data-src").takeIf { it.isNotBlank() }
                ?: element.attr("data-src-r").takeIf { it.isNotBlank() }
                ?: element.attr("src")

            Page(i, imageUrl = normalizeImgUrl(url ?: "")!!)
        }
    }

    override fun imageUrlParse(document: Document) = throw UnsupportedOperationException()

    // ---------- Interceptors ----------
    private class FixupHeadersInterceptor(
        private val baseUrl: String,
    ) : Interceptor {

        override fun intercept(chain: Interceptor.Chain): Response {
            val req = chain.request()
            val b = req.newBuilder()

            if (isImageRequest(req)) {
                b.header("Referer", "$baseUrl/")
                b.header("Accept", "image/avif,image/webp,image/apng,image/*,*/*;q=0.8")
            }

            return chain.proceed(b.build())
        }

    }

    private class IPv4Dns : Dns {
        override fun lookup(hostname: String): List<InetAddress> {
            return try {
                Dns.SYSTEM.lookup(hostname).filter { it is Inet4Address }
            } catch (e: Exception) {
                // Fallback to system if filtering fails or returns empty (though rare)
                Dns.SYSTEM.lookup(hostname)
            }
        }
    }

    private companion object {
        // RateLimit
        private const val RATE_LIMIT_PERMITS = 1
        private const val RATE_LIMIT_PERIOD_SECONDS = 2L

        private fun isImageRequest(request: Request): Boolean {
            val p = request.url.encodedPath.lowercase(Locale.US)
            return p.endsWith(".webp") ||
                p.endsWith(".png") ||
                p.endsWith(".jpg") ||
                p.endsWith(".jpeg") ||
                p.endsWith(".gif") ||
                p.endsWith(".avif")
        }
    }

    // ---------- Filters ----------
    override fun getFilterList() = FilterList(
        Filter.Header("Note: can't combine search query with filters, status filter only has effect in 인기만화"),
        Filter.Separator(),
        SortFilter(getSortList, 0),
        StatusFilter(getStatusList, 0),
        GenreFilter(getGenreList, 0),
    )

    private class SortFilter(vals: List<SelectFilterOption>, state: Int = 0) :
        SelectFilter("정렬", vals, state)

    private class StatusFilter(vals: List<SelectFilterOption>, state: Int = 0) :
        SelectFilter("Status", vals, state)

    private class GenreFilter(vals: List<SelectFilterOption>, state: Int = 0) :
        SelectFilter("장르", vals, state)

    open class SelectFilter(
        displayName: String,
        private val vals: List<SelectFilterOption>,
        state: Int = 0,
    ) : Filter.Select<String>(
        displayName,
        vals.map { it.name }.toTypedArray(),
        state,
    ) {
        val selected: String
            get() = vals[state].value
    }

    data class SelectFilterOption(val name: String, val value: String)

    private val getSortList = listOf(
        SelectFilterOption("인기만화", popularBase),
        SelectFilterOption("최신만화", latestBase),
    )

    private val getStatusList = listOf(
        SelectFilterOption("전체", "0"),
        SelectFilterOption("완결", "1"),
    )

    private val getGenreList = listOf(
        SelectFilterOption("전체", ""),
        SelectFilterOption("SF", "SF"),
        SelectFilterOption("TS", "TS"),
        SelectFilterOption("개그", "개그"),
        SelectFilterOption("드라마", "드라마"),
        SelectFilterOption("코미디", "코미디"),
        SelectFilterOption("먹방", "먹방"),
        SelectFilterOption("백합", "백합"),
        SelectFilterOption("붕탁", "붕탁"),
        SelectFilterOption("순정", "순정"),
        SelectFilterOption("스릴러", "스릴러"),
        SelectFilterOption("스포츠", "스포츠"),
        SelectFilterOption("시대", "시대"),
        SelectFilterOption("액션", "액션"),
        SelectFilterOption("인기", "인기"),
        SelectFilterOption("일상+치유", "일상+치유"),
        SelectFilterOption("추리", "추리"),
        SelectFilterOption("판타지", "판타지"),
        SelectFilterOption("학원", "학원"),
        SelectFilterOption("호러", "호러"),
        SelectFilterOption("BL", "BL"),
        SelectFilterOption("17", "17"),
        SelectFilterOption("무협", "무협"),
        SelectFilterOption("러브코미디", "러브코미디"),
        SelectFilterOption("이세계", "이세계"),
        SelectFilterOption("전생", "전생"),
        SelectFilterOption("라노벨", "라노벨"),
        SelectFilterOption("애니화", "애니화"),
        SelectFilterOption("TL", "TL"),
        SelectFilterOption("공포", "공포"),
    )
}
