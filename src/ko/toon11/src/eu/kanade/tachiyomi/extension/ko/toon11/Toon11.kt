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
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import rx.Observable
import java.io.IOException
import java.text.ParseException
import java.text.SimpleDateFormat
import java.util.ArrayList
import java.util.Date
import java.util.Locale

class Toon11 : ParsedHttpSource() {

    override val name = "11toon"

    override val baseUrl = "http://103.1.250.99:6600"

    override val lang = "ko"

    override val supportsLatest = true

    override val client: OkHttpClient = network.cloudflareClient

    // Hard-coded rate limit for HTML/document requests only.
    // Images are not rate-limited (they use `client` via Tachiyomi's image pipeline).
    private val rateLimitedClient: OkHttpClient by lazy {
        client.newBuilder()
            .rateLimit(RATE_LIMIT_REQUESTS, RATE_LIMIT_PERIOD_SECONDS)
            .build()
    }

    override fun popularMangaSelector() = "li[data-id]"

    override fun latestUpdatesSelector() = popularMangaSelector()

    override fun popularMangaRequest(page: Int) = GET("$baseUrl/bbs/board.php?bo_table=toon_c&is_over=0", headers)

    override fun latestUpdatesRequest(page: Int) = GET("$baseUrl/bbs/board.php?bo_table=toon_c&sord=&type=upd&page=$page", headers)

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

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        return if (query.isNotBlank()) {
            val url = "$baseUrl/bbs/search_stx.php".toHttpUrl().newBuilder().apply {
                addQueryParameter("stx", query)
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

    override fun popularMangaFromElement(element: Element) = SManga.create().apply {
        setUrlWithoutDomain(element.selectFirst("a")!!.absUrl("href"))
        title = element.selectFirst(".homelist-title")!!.text()
        thumbnail_url = element.selectFirst(".homelist-thumb")?.absUrl("data-mobile-image")
    }

    override fun latestUpdatesFromElement(element: Element) = SManga.create().apply {
        setUrlWithoutDomain(element.selectFirst("a")!!.absUrl("href"))
        title = element.selectFirst(".homelist-title")!!.text()
        element.selectFirst(".homelist-thumb")?.also {
            thumbnail_url = "https:" + it.attr("style").substringAfter("url('").substringBefore("')")
        }
    }

    override fun popularMangaNextPageSelector() = ".pg_end"

    override fun latestUpdatesNextPageSelector() = popularMangaNextPageSelector()

    override fun searchMangaSelector() = popularMangaSelector()

    override fun searchMangaFromElement(element: Element) = SManga.create().apply {
        title = element.selectFirst(".homelist-title")!!.text()
        val dataId = element.attr("data-id")
        setUrlWithoutDomain("$baseUrl/bbs/board.php?bo_table=toons&stx=$title&is=$dataId")
        element.selectFirst(".homelist-thumb")?.also {
            thumbnail_url = "https:" + it.attr("style").substringAfter("url('").substringBefore("')")
        }
    }

    override fun searchMangaNextPageSelector() = popularMangaNextPageSelector()

    override fun mangaDetailsParse(document: Document): SManga {
        return SManga.create().apply {
            title = document.selectFirst("h2.title")!!.text()
            thumbnail_url = document.selectFirst("img.banner")?.absUrl("src")
            document.selectFirst("span:contains(분류) + span")?.also { status = parseStatus(it.text()) }
            document.selectFirst("span:contains(작가) + span")?.also { author = it.text() }
            document.selectFirst("span:contains(소개) + span")?.also { description = it.text() }
            document.selectFirst("span:contains(장르) + span")?.also { genre = it.text().split(",").joinToString() }
        }
    }

    private fun parseStatus(element: String): Int = when {
        "완결" in element -> SManga.COMPLETED
        "주간" in element || "월간" in element || "연재" in element || "격주" in element || "격월" in element || "비정기" in element -> SManga.ONGOING
        else -> SManga.UNKNOWN
    }

    // ==========
    // Chapter list: NO pagination.
    // Detail page -> "처음부터" viewer -> modal(#sList) full episode list
    // ==========

    private val jumpIdRegex = Regex("""jump\((\d+)\)""")

    private fun toBbsRelative(url: HttpUrl): String {
        val path = if (url.encodedPath.startsWith("/bbs/")) {
            url.encodedPath.removePrefix("/bbs")
        } else {
            url.encodedPath
        }
        val q = url.encodedQuery
        return if (q.isNullOrEmpty()) path else "$path?$q"
    }

    override fun chapterListParse(response: Response): List<SChapter> {
        val detailDoc = response.asJsoup()

        val firstEpisodeAbs = detailDoc.selectFirst("a.btn-first-episode")
            ?.absUrl("href")
            ?.takeIf { it.isNotBlank() }
            ?: throw IOException("Missing first-episode link (a.btn-first-episode)")

        val viewerDoc = rateLimitedClient.newCall(GET(firstEpisodeAbs, headers))
            .execute()
            .use { resp ->
                if (!resp.isSuccessful) {
                    throw IOException("Viewer request failed: HTTP ${resp.code} url=$firstEpisodeAbs")
                }
                resp.asJsoup()
            }

        val buttons = viewerDoc.select("#sList ul.toonslist button[onclick]")
        if (buttons.isEmpty()) {
            throw IOException("Missing episode list in viewer modal (#sList ul.toonslist)")
        }

        // Modal list is typically 1화 -> ... ascending.
        // Tachiyomi expects latest first (descending).
        val template = firstEpisodeAbs.toHttpUrl()

        val entries = buttons.mapNotNull { btn ->
            val onclick = btn.attr("onclick")
            val wrId = jumpIdRegex.find(onclick)?.groupValues?.get(1) ?: return@mapNotNull null
            val title = btn.text().trim()
            wrId to title
        }

        val reversed = entries.asReversed()
        val total = reversed.size

        return reversed.mapIndexed { idx, (wrId, title) ->
            val abs = template.newBuilder()
                .setQueryParameter("wr_id", wrId)
                .build()

            val rel = toBbsRelative(abs)

            SChapter.create().apply {
                setUrlWithoutDomain(rel)
                name = title
                // HTML 순서만 신뢰: 최신이 가장 큰 번호를 갖게 해서 정렬 안정화
                chapter_number = (total - idx).toFloat()
                date_upload = 0L
            }
        }
    }

    // These are kept for compatibility with ParsedHttpSource, but chapterListParse() bypasses them.
    override fun chapterListSelector() = "#comic-episode-list > li"

    override fun chapterFromElement(element: Element): SChapter {
        val urlEl = element.selectFirst("button")
        val dateEl = element.selectFirst(".free-date")

        return SChapter.create().apply {
            urlEl!!.also {
                setUrlWithoutDomain(it.attr("onclick").substringAfter("location.href='.").substringBefore("'"))
                name = it.selectFirst(".episode-title")!!.text()
            }
            dateEl?.also { date_upload = dateParse(it.text()) }
        }
    }

    private val dateFormat = SimpleDateFormat("yy.MM.dd", Locale.ENGLISH)

    private fun dateParse(dateAsString: String): Long {
        val date: Date? = try {
            dateFormat.parse(dateAsString)
        } catch (e: ParseException) {
            null
        }
        return date?.time ?: 0L
    }

    override fun fetchPageList(chapter: SChapter): Observable<List<Page>> {
        return rateLimitedClient.newCall(pageListRequest(chapter))
            .asObservableSuccess()
            .map { response -> pageListParse(response.asJsoup()) }
    }

    override fun pageListRequest(chapter: SChapter): Request {
        return GET(baseUrl + "/bbs" + chapter.url, headers)
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
        return Json.decodeFromString<List<String>>(listString)
    }

    override fun imageUrlParse(document: Document) = throw UnsupportedOperationException()

    // Filters

    override fun getFilterList() = FilterList(
        Filter.Header("Note: can't combine search query with filters, status filter only has effect in 인기만화"),
        Filter.Separator(),
        SortFilter(getSortList, 0),
        StatusFilter(getStatusList, 0),
        GenreFilter(getGenreList, 0),
    )

    class SelectFilterOption(val name: String, val value: String)

    abstract class SelectFilter(name: String, private val options: List<SelectFilterOption>, default: Int = 0) :
        Filter.Select<String>(name, options.map { it.name }.toTypedArray(), default) {
        val selected: String
            get() = options[state].value
    }

    class SortFilter(options: List<SelectFilterOption>, default: Int) : SelectFilter("Sort", options, default)
    class StatusFilter(options: List<SelectFilterOption>, default: Int) : SelectFilter("Status", options, default)
    class GenreFilter(options: List<SelectFilterOption>, default: Int) : SelectFilter("Genre", options, default)

    private val getSortList = listOf(
        SelectFilterOption("인기만화", "$baseUrl/bbs/board.php?bo_table=toon_c"),
        SelectFilterOption("최신만화", "$baseUrl/bbs/board.php?bo_table=toon_c&tablename=최신만화&type=upd"),
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

    private companion object {
        private const val RATE_LIMIT_REQUESTS = 2
        private const val RATE_LIMIT_PERIOD_SECONDS = 1L
    }
}
