package eu.kanade.tachiyomi.extension.ko.toon11

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.asObservableSuccess
import eu.kanade.tachiyomi.network.interceptor.rateLimit
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.ParsedHttpSource
import eu.kanade.tachiyomi.util.asJsoup
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import okhttp3.Dispatcher
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import rx.Observable
import java.text.ParseException
import java.text.SimpleDateFormat
import java.util.ArrayList
import java.util.Date
import java.util.Locale

class Toon11 : ParsedHttpSource() {

    override val name = "11toon"

    // www 제거: Referer/리다이렉트 꼬임 방지용
    override val baseUrl = "https://11toon.com"

    override val lang = "ko"
    override val supportsLatest = true

    private val limiterDispatcher = Dispatcher().apply {
        maxRequests = 32
        maxRequestsPerHost = 8
    }

    private val fallbackThumbHost = "https://11toon8.com"

    override fun headersBuilder(): Headers.Builder = super.headersBuilder()
        .set("Referer", "$baseUrl/")
        .set(
            "Accept",
            "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8",
        )

    /**
     * ✅ 이미지 다운로드용(무제한) = Tachiyomi가 이미지 요청에 사용하는 기본 client
     * ✅ HTML 요청용(rate limit) = 아래 fetch*에서만 사용
     */
    private val baseClient: OkHttpClient = network.cloudflareClient.newBuilder()
        .dispatcher(limiterDispatcher)
        .addInterceptor(FixupHeadersInterceptor(baseUrl))
        .build()

    private val rateLimitedClient: OkHttpClient = baseClient.newBuilder()
        .apply { rateLimit(RATE_LIMIT_PERMITS, RATE_LIMIT_PERIOD_SECONDS) }
        .build()

    override val client: OkHttpClient = baseClient

    // ---------- Requests ----------
    private val latestBase =
        "$baseUrl/bbs/board.php?bo_table=toon_c&type=upd&tablename=%EC%B5%9C%EC%8B%A0%EB%A7%8C%ED%99%94"
    private val popularBase =
        "$baseUrl/bbs/board.php?bo_table=toon_c&tablename=%EC%9D%B8%EA%B8%B0%EB%A7%8C%ED%99%94"

    override fun popularMangaRequest(page: Int): Request {
        val url = popularBase.toHttpUrl().newBuilder().apply {
            if (page > 1) addQueryParameter("page", page.toString())
        }.build()
        return GET(url, headers)
    }

    override fun latestUpdatesRequest(page: Int): Request {
        val url = latestBase.toHttpUrl().newBuilder().apply {
            if (page > 1) addQueryParameter("page", page.toString())
        }.build()
        return GET(url, headers)
    }

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        return if (query.isNotBlank()) {
            val url = "$baseUrl/bbs/search_stx.php".toHttpUrl().newBuilder().apply {
                addQueryParameter("stx", query)
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
                if (genre.isNotEmpty()) addQueryParameter("sca", genre)
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
    override fun popularMangaSelector() = "li[data-id]"
    override fun latestUpdatesSelector() = popularMangaSelector()
    override fun searchMangaSelector() = popularMangaSelector()

    override fun popularMangaNextPageSelector() = ".pg_end"
    override fun latestUpdatesNextPageSelector() = popularMangaNextPageSelector()
    override fun searchMangaNextPageSelector() = popularMangaNextPageSelector()

    // ---------- URL / Thumbnail helpers ----------
    private fun buildDetailsUrl(dataId: String): String =
        "/bbs/board.php?bo_table=toons&is=$dataId"

    private val cssUrlRegex = Regex("""url\((['"]?)(.*?)\1\)""", RegexOption.IGNORE_CASE)

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

    private fun inferThumbFromId(dataId: String, host: String = fallbackThumbHost): String? {
        val id = dataId.trim()
        if (id.isEmpty()) return null
        return "$host/data/toon_category/$id.webp"
    }

    private fun extractThumbUrl(item: Element): String? {
        val dataId = item.attr("data-id").trim()
        val thumb = item.selectFirst(".homelist-thumb")

        thumb?.attr("data-mobile-image")
            ?.takeIf { it.isNotBlank() }
            ?.let { return normalizeImgUrl(it) }

        thumb?.attr("style")
            ?.takeIf { it.isNotBlank() }
            ?.let { style ->
                val raw = cssUrlRegex.find(style)?.groupValues?.getOrNull(2)
                val normalized = raw?.let { normalizeImgUrl(it) }
                if (!normalized.isNullOrBlank()) return normalized
            }

        return inferThumbFromId(dataId)
    }

    // ---------- Manga parsing ----------
    override fun popularMangaFromElement(element: Element) = SManga.create().apply {
        val titleText = element.selectFirst(".homelist-title")?.text().orEmpty()
        val dataId = element.attr("data-id").trim()
        title = titleText
        setUrlWithoutDomain(buildDetailsUrl(dataId))
        thumbnail_url = extractThumbUrl(element)
    }

    override fun latestUpdatesFromElement(element: Element) = SManga.create().apply {
        val titleText = element.selectFirst(".homelist-title")?.text().orEmpty()
        val dataId = element.attr("data-id").trim()
        title = titleText
        setUrlWithoutDomain(buildDetailsUrl(dataId))
        thumbnail_url = extractThumbUrl(element)
    }

    override fun searchMangaFromElement(element: Element) = SManga.create().apply {
        val titleText = element.selectFirst(".homelist-title")?.text().orEmpty()
        val dataId = element.attr("data-id").trim()
        title = titleText
        setUrlWithoutDomain(buildDetailsUrl(dataId))
        thumbnail_url = extractThumbUrl(element)
    }

    // ---------- Details ----------
    override fun mangaDetailsParse(document: Document): SManga {
        return SManga.create().apply {
            title = document.selectFirst("h2.title")!!.text()
            document.selectFirst("img.banner")?.absUrl("src")?.let { thumbnail_url = it }
            document.selectFirst("span:contains(분류) + span")?.also { status = parseStatus(it.text()) }
            document.selectFirst("span:contains(작가) + span")?.also { author = it.text() }
            document.selectFirst("span:contains(소개) + span")?.also { description = it.text() }
            document.selectFirst("span:contains(장르) + span")?.also { genre = it.text().split(",").joinToString() }
        }
    }

    private fun parseStatus(element: String): Int = when {
        "완결" in element -> SManga.COMPLETED
        "주간" in element || "월간" in element || "연재" in element || "격주" in element -> SManga.ONGOING
        else -> SManga.UNKNOWN
    }

    // ---------- Chapters ----------
    private tailrec fun parseChapters(nextURL: String, chapters: ArrayList<SChapter>) {
        val newpage = fetchPagesFromNav(nextURL)
        newpage.select(chapterListSelector()).forEach {
            chapters.add(chapterFromElement(it))
        }
        val newURL = newpage.selectFirst(".pg_current ~ .pg_page")?.absUrl("href")
        if (!newURL.isNullOrBlank()) parseChapters(newURL, chapters)
    }

    override fun chapterListParse(response: Response): List<SChapter> {
        val document = response.asJsoup()
        val nav = document.selectFirst("span.pg")
        val chapters = ArrayList<SChapter>()

        document.select(chapterListSelector()).forEach {
            chapters.add(chapterFromElement(it))
        }

        if (nav == null) return chapters

        val pg2url = nav.selectFirst(".pg_current ~ .pg_page")!!.absUrl("href")
        parseChapters(pg2url, chapters)
        return chapters
    }

    // ✅ HTML 네비게이션 페이지도 rateLimitedClient로
    private fun fetchPagesFromNav(url: String) =
        rateLimitedClient.newCall(GET(url, headers)).execute().asJsoup()

    override fun chapterListSelector() = "#comic-episode-list > li"

    // onclick = "location.href='....'"
    private val onclickHrefRegex = Regex("""location\.href\s*=\s*['"]([^'"]+)['"]""")

    private fun extractOnclickHref(onclick: String): String? {
        val m = onclickHrefRegex.find(onclick) ?: return null
        return m.groupValues.getOrNull(1)?.trim()?.takeIf { it.isNotEmpty() }
    }

    private fun canonicalizeChapterUrl(raw: String): String? {
        var u = raw.trim()
        if (u.isEmpty()) return null

        if (u.startsWith("./")) u = u.removePrefix("./")

        if (u.startsWith("http://") || u.startsWith("https://")) {
            u = try {
                val http = u.toHttpUrl()
                val q = http.encodedQuery?.let { "?$it" }.orEmpty()
                http.encodedPath + q
            } catch (_: IllegalArgumentException) {
                return null
            }
        }

        if (u.startsWith("/bbs/")) u = u.removePrefix("/bbs/")
        if (u.startsWith("bbs/")) u = u.removePrefix("bbs/")

        if (!u.startsWith("/")) u = "/$u"
        return u
    }

    override fun chapterFromElement(element: Element): SChapter {
        val urlEl = element.selectFirst("button")!!
        val dateEl = element.selectFirst(".free-date")

        val onclick = urlEl.attr("onclick").orEmpty()
        val href = extractOnclickHref(onclick).orEmpty()
        val canon = canonicalizeChapterUrl(href).orEmpty()

        return SChapter.create().apply {
            setUrlWithoutDomain(canon)
            name = urlEl.selectFirst(".episode-title")!!.text()
            dateEl?.also { date_upload = dateParse(it.text()) }
        }
    }

    private val dateFormat = SimpleDateFormat("yy.MM.dd", Locale.ENGLISH)

    private fun dateParse(dateAsString: String): Long {
        val date: Date? = try {
            dateFormat.parse(dateAsString)
        } catch (_: ParseException) {
            null
        }
        return date?.time ?: 0L
    }

    // ---------- Pages ----------
    override fun pageListRequest(chapter: SChapter): Request {
        val u0 = chapter.url.trim()
        val u = if (u0.startsWith("/")) u0 else "/$u0"
        val path = if (u.startsWith("/bbs/")) u else "/bbs$u"
        return GET(baseUrl + path, headers)
    }

    override fun pageListParse(document: Document): List<Page> {
        val rawImageLinks = document.selectFirst("script + script[type^=text/javascript]:not([src])")!!.data()
        val imgList = extractList(rawImageLinks)

        return imgList.mapIndexed { i, img ->
            Page(i, imageUrl = "https:$img")
        }
    }

    private val imgListRegex = """img_list\s*=\s*(\[.*?])""".toRegex(RegexOption.DOT_MATCHES_ALL)

    private fun extractList(jsString: String): List<String> {
        val matchResult = imgListRegex.find(jsString)
        val listString = matchResult?.groupValues?.get(1) ?: return emptyList()
        return Json.decodeFromString(listString)
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

    private companion object {
        // NewToki 스타일 rateLimit(permits, periodSeconds) 하드코딩
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

    class SelectFilterOption(val name: String, val value: String)

    abstract class SelectFilter(
        name: String,
        private val options: List<SelectFilterOption>,
        default: Int = 0,
    ) : Filter.Select<String>(name, options.map { it.name }.toTypedArray(), default) {
        val selected: String
            get() = options[state].value
    }

    class SortFilter(options: List<SelectFilterOption>, default: Int) : SelectFilter("Sort", options, default)
    class StatusFilter(options: List<SelectFilterOption>, default: Int) : SelectFilter("Status", options, default)
    class GenreFilter(options: List<SelectFilterOption>, default: Int) : SelectFilter("Genre", options, default)

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
        SelectFilterOption("무협", "무협"),
        SelectFilterOption("TS", "TS"),
        SelectFilterOption("개그", "개그"),
        SelectFilterOption("드라마", "드라마"),
        SelectFilterOption("러브코미디", "러브코미디"),
        SelectFilterOption("먹방", "먹방"),
        SelectFilterOption("백합", "백합"),
        SelectFilterOption("붕탁", "붕탁"),
        SelectFilterOption("스릴러", "스릴러"),
        SelectFilterOption("스포츠", "스포츠"),
        SelectFilterOption("시대", "시대"),
        SelectFilterOption("액션", "액션"),
        SelectFilterOption("순정", "순정"),
        SelectFilterOption("일상+치유", "일상%2B치유"),
        SelectFilterOption("추리", "추리"),
        SelectFilterOption("판타지", "판타지"),
        SelectFilterOption("학원", "학원"),
        SelectFilterOption("호러", "호러"),
        SelectFilterOption("BL", "BL"),
        SelectFilterOption("17", "17"),
        SelectFilterOption("이세계", "이세계"),
        SelectFilterOption("전생", "전생"),
        SelectFilterOption("라노벨", "라노벨"),
        SelectFilterOption("애니화", "애니화"),
        SelectFilterOption("TL", "TL"),
        SelectFilterOption("공포", "공포"),
        SelectFilterOption("하렘", "하렘"),
        SelectFilterOption("요리", "요리"),
    )
}
