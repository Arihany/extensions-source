// package eu.kanade.tachiyomi.extension.ko.toon11

// import eu.kanade.tachiyomi.network.GET
// import eu.kanade.tachiyomi.network.asObservableSuccess
// import eu.kanade.tachiyomi.network.interceptor.rateLimit
// import eu.kanade.tachiyomi.source.model.Filter
// import eu.kanade.tachiyomi.source.model.FilterList
// import eu.kanade.tachiyomi.source.model.MangasPage
// import eu.kanade.tachiyomi.source.model.Page
// import eu.kanade.tachiyomi.source.model.SChapter
// import eu.kanade.tachiyomi.source.model.SManga
// import eu.kanade.tachiyomi.source.online.ParsedHttpSource
// import eu.kanade.tachiyomi.util.asJsoup
// import kotlinx.serialization.decodeFromString
// import kotlinx.serialization.json.Json
// import okhttp3.Dispatcher
// import okhttp3.Headers
// import okhttp3.HttpUrl.Companion.toHttpUrl
// import okhttp3.Interceptor
// import okhttp3.OkHttpClient
// import okhttp3.Request
// import okhttp3.Response
// import org.jsoup.nodes.Document
// import org.jsoup.nodes.Element
// import rx.Observable
// import java.text.ParseException
// import java.text.SimpleDateFormat
// import java.util.ArrayList
// import java.util.Date
// import java.util.Locale

// class Toon11 : ParsedHttpSource() {

//     override val name = "11toon"

//     // www 제거: Referer/리다이렉트 꼬임 방지용
//     override val baseUrl = "https://11toon.com"

//     override val lang = "ko"
//     override val supportsLatest = true

//     private val limiterDispatcher = Dispatcher().apply {
//         maxRequests = 32
//         maxRequestsPerHost = 8
//     }

//     private val fallbackThumbHost = "https://11toon8.com"

//     override fun headersBuilder(): Headers.Builder = super.headersBuilder()
//         .set("Referer", "$baseUrl/")
//         .set(
//             "Accept",
//             "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8",
//         )

//     private val baseClient: OkHttpClient = network.cloudflareClient.newBuilder()
//         .dispatcher(limiterDispatcher)
//         .addInterceptor(FixupHeadersInterceptor(baseUrl))
//         .build()

//     private val rateLimitedClient: OkHttpClient = baseClient.newBuilder()
//         .apply { rateLimit(RATE_LIMIT_PERMITS, RATE_LIMIT_PERIOD_SECONDS) }
//         .build()

//     override val client: OkHttpClient = baseClient

//     // ---------- Requests ----------
//     private val latestBase =
//         "$baseUrl/bbs/board.php?bo_table=toon_c&type=upd&tablename=%EC%B5%9C%EC%8B%A0%EB%A7%8C%ED%99%94"
//     private val popularBase =
//         "$baseUrl/bbs/board.php?bo_table=toon_c&tablename=%EC%9D%B8%EA%B8%B0%EB%A7%8C%ED%99%94"

//     override fun popularMangaRequest(page: Int): Request {
//         val url = popularBase.toHttpUrl().newBuilder().apply {
//             if (page > 1) addQueryParameter("page", page.toString())
//         }.build()
//         return GET(url, headers)
//     }

//     override fun latestUpdatesRequest(page: Int): Request {
//         val url = latestBase.toHttpUrl().newBuilder().apply {
//             if (page > 1) addQueryParameter("page", page.toString())
//         }.build()
//         return GET(url, headers)
//     }

//     override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
//         return if (query.isNotBlank()) {
//             val url = "$baseUrl/bbs/search_stx.php".toHttpUrl().newBuilder().apply {
//                 addQueryParameter("stx", query)
//                 if (page > 1) addQueryParameter("page", page.toString())
//             }.build()
//             GET(url, headers)
//         } else {
//             var urlString = ""
//             var isOver = ""
//             var genre = ""

//             filters.forEach { filter ->
//                 when (filter) {
//                     is SortFilter -> urlString = filter.selected
//                     is StatusFilter -> isOver = filter.selected
//                     is GenreFilter -> genre = filter.selected
//                     else -> {}
//                 }
//             }

//             val url = urlString.toHttpUrl().newBuilder().apply {
//                 addQueryParameter("is_over", isOver)
//                 if (page > 1) addQueryParameter("page", page.toString())
//                 if (genre.isNotEmpty()) addQueryParameter("sca", genre)
//             }.build()

//             GET(url, headers)
//         }
//     }

//     // ---------- HTML fetch는 전부 rateLimitedClient로 ----------
//     override fun fetchPopularManga(page: Int): Observable<MangasPage> {
//         return rateLimitedClient.newCall(popularMangaRequest(page))
//             .asObservableSuccess()
//             .map(::popularMangaParse)
//     }

//     override fun fetchLatestUpdates(page: Int): Observable<MangasPage> {
//         return rateLimitedClient.newCall(latestUpdatesRequest(page))
//             .asObservableSuccess()
//             .map(::latestUpdatesParse)
//     }

//     override fun fetchSearchManga(page: Int, query: String, filters: FilterList): Observable<MangasPage> {
//         return rateLimitedClient.newCall(searchMangaRequest(page, query, filters))
//             .asObservableSuccess()
//             .map(::searchMangaParse)
//     }

//     override fun fetchMangaDetails(manga: SManga): Observable<SManga> {
//         return rateLimitedClient.newCall(mangaDetailsRequest(manga))
//             .asObservableSuccess()
//             .map { response ->
//                 mangaDetailsParse(response.asJsoup()).apply { initialized = true }
//             }
//     }

//     override fun fetchChapterList(manga: SManga): Observable<List<SChapter>> {
//         return rateLimitedClient.newCall(chapterListRequest(manga))
//             .asObservableSuccess()
//             .map(::chapterListParse)
//     }

//     override fun fetchPageList(chapter: SChapter): Observable<List<Page>> {
//         return rateLimitedClient.newCall(pageListRequest(chapter))
//             .asObservableSuccess()
//             .map { response -> pageListParse(response.asJsoup()) }
//     }

//     // ---------- Selectors ----------
//     override fun popularMangaSelector() = "li[data-id]"
//     override fun latestUpdatesSelector() = popularMangaSelector()
//     override fun searchMangaSelector() = popularMangaSelector()

//     override fun popularMangaNextPageSelector() = ".pg_end"
//     override fun latestUpdatesNextPageSelector() = popularMangaNextPageSelector()
//     override fun searchMangaNextPageSelector() = popularMangaNextPageSelector()

//     // ---------- URL / Thumbnail helpers ----------
//     private fun buildDetailsUrl(dataId: String): String =
//         "/bbs/board.php?bo_table=toons&is=$dataId"

//     private val cssUrlRegex = Regex("""url\((['"]?)(.*?)\1\)""", RegexOption.IGNORE_CASE)

//     private fun normalizeImgUrl(raw: String): String? {
//         val u = raw.trim()
//         if (u.isBlank()) return null
//         return when {
//             u.startsWith("http://") || u.startsWith("https://") -> u
//             u.startsWith("//") -> "https:$u"
//             u.startsWith("/") -> baseUrl + u
//             else -> u
//         }
//     }

//     private fun inferThumbFromId(dataId: String, host: String = fallbackThumbHost): String? {
//         val id = dataId.trim()
//         if (id.isEmpty()) return null
//         return "$host/data/toon_category/$id.webp"
//     }

//     private fun extractThumbUrl(item: Element): String? {
//         val dataId = item.attr("data-id").trim()
//         val thumb = item.selectFirst(".homelist-thumb")

//         thumb?.attr("data-mobile-image")
//             ?.takeIf { it.isNotBlank() }
//             ?.let { return normalizeImgUrl(it) }

//         thumb?.attr("style")
//             ?.takeIf { it.isNotBlank() }
//             ?.let { style ->
//                 val raw = cssUrlRegex.find(style)?.groupValues?.getOrNull(2)
//                 val normalized = raw?.let { normalizeImgUrl(it) }
//                 if (!normalized.isNullOrBlank()) return normalized
//             }

//         return inferThumbFromId(dataId)
//     }

//     // ---------- Manga parsing ----------
//     override fun popularMangaFromElement(element: Element) = SManga.create().apply {
//         val titleText = element.selectFirst(".homelist-title")?.text().orEmpty()
//         val dataId = element.attr("data-id").trim()
//         title = titleText
//         setUrlWithoutDomain(buildDetailsUrl(dataId))
//         thumbnail_url = extractThumbUrl(element)
//     }

//     override fun latestUpdatesFromElement(element: Element) = SManga.create().apply {
//         val titleText = element.selectFirst(".homelist-title")?.text().orEmpty()
//         val dataId = element.attr("data-id").trim()
//         title = titleText
//         setUrlWithoutDomain(buildDetailsUrl(dataId))
//         thumbnail_url = extractThumbUrl(element)
//     }

//     override fun searchMangaFromElement(element: Element) = SManga.create().apply {
//         val titleText = element.selectFirst(".homelist-title")?.text().orEmpty()
//         val dataId = element.attr("data-id").trim()
//         title = titleText
//         setUrlWithoutDomain(buildDetailsUrl(dataId))
//         thumbnail_url = extractThumbUrl(element)
//     }

//     // ---------- Details ----------
//     override fun mangaDetailsParse(document: Document): SManga {
//         return SManga.create().apply {
//             title = document.selectFirst("h2.title")!!.text()
//             document.selectFirst("img.banner")?.absUrl("src")?.let { thumbnail_url = it }
//             document.selectFirst("span:contains(분류) + span")?.also { status = parseStatus(it.text()) }
//             document.selectFirst("span:contains(작가) + span")?.also { author = it.text() }
//             document.selectFirst("span:contains(소개) + span")?.also { description = it.text() }
//             document.selectFirst("span:contains(장르) + span")?.also { genre = it.text().split(",").joinToString() }
//         }
//     }

//     private fun parseStatus(element: String): Int = when {
//         "완결" in element -> SManga.COMPLETED
//         "주간" in element || "월간" in element || "연재" in element || "격주" in element -> SManga.ONGOING
//         else -> SManga.UNKNOWN
//     }

//     // ---------- Chapters ----------
//     private tailrec fun parseChapters(nextURL: String, chapters: ArrayList<SChapter>) {
//         val newpage = fetchPagesFromNav(nextURL)
//         newpage.select(chapterListSelector()).forEach {
//             chapters.add(chapterFromElement(it))
//         }
//         val newURL = newpage.selectFirst(".pg_current ~ .pg_page")?.absUrl("href")
//         if (!newURL.isNullOrBlank()) parseChapters(newURL, chapters)
//     }

//     override fun chapterListParse(response: Response): List<SChapter> {
//         val document = response.asJsoup()
//         val nav = document.selectFirst("span.pg")
//         val chapters = ArrayList<SChapter>()

//         document.select(chapterListSelector()).forEach {
//             chapters.add(chapterFromElement(it))
//         }

//         if (nav == null) return chapters

//         val pg2url = nav.selectFirst(".pg_current ~ .pg_page")!!.absUrl("href")
//         parseChapters(pg2url, chapters)
//         return chapters
//     }

//     private fun fetchPagesFromNav(url: String) =
//         rateLimitedClient.newCall(GET(url, headers)).execute().asJsoup()

//     override fun chapterListSelector() = "#comic-episode-list > li"

//     // onclick = "location.href='....'"
//     private val onclickHrefRegex = Regex("""location\.href\s*=\s*['"]([^'"]+)['"]""")

//     private fun extractOnclickHref(onclick: String): String? {
//         val m = onclickHrefRegex.find(onclick) ?: return null
//         return m.groupValues.getOrNull(1)?.trim()?.takeIf { it.isNotEmpty() }
//     }

//     private fun canonicalizeChapterUrl(raw: String): String? {
//         var s = raw.trim()
//         if (s.isEmpty()) return null

//         // onclick 안에 &가 &amp; 로 남는 케이스 방어
//         s = s.replace("&amp;", "&")
//         if (s.startsWith("//")) s = "https:$s"
//         if (s.startsWith("./")) s = s.removePrefix("./")

//         val resolved = try {
//             baseUrl.toHttpUrl().resolve(s) ?: return null
//         } catch (_: IllegalArgumentException) {
//             return null
//         }

//         val boTable = resolved.queryParameter("bo_table")
//         val wrId = resolved.queryParameter("wr_id")

//         // wr_id가 있으면: "같은 화"를 무조건 같은 문자열로 고정 (순서/잡파라미터 제거)
//         if (!boTable.isNullOrBlank() && !wrId.isNullOrBlank()) {
//             val canon = resolved.newBuilder()
//                 .encodedPath("/bbs/board.php")
//                 .query(null)
//                 .addQueryParameter("bo_table", boTable)
//                 .addQueryParameter("wr_id", wrId)
//                 .build()
//             val q = canon.encodedQuery?.let { "?$it" }.orEmpty()
//             return canon.encodedPath.removePrefix("/bbs") + q
//         }

//         // fallback: 쿼리 파라미터 정렬로 최소한 "순서 차이"는 제거
//         val b = resolved.newBuilder().query(null)
//         resolved.queryParameterNames.toList().sorted().forEach { name ->
//             resolved.queryParameterValues(name).forEach { value ->
//                 b.addQueryParameter(name, value)
//             }
//         }
//         val canon = b.build()
//         val q = canon.encodedQuery?.let { "?$it" }.orEmpty()
//         return canon.encodedPath.removePrefix("/bbs") + q
//     }

//     override fun chapterFromElement(element: Element): SChapter {
//         val urlEl = element.selectFirst("button")!!
//         val dateEl = element.selectFirst(".free-date")

//         val onclick = urlEl.attr("onclick").orEmpty()
//         val href = extractOnclickHref(onclick).orEmpty()
//         val canon = canonicalizeChapterUrl(href) ?: run {
//             // 정규화 실패 시에도 url 비워버리면 더 큰 사고남. 기존 방식으로라도 저장.
//             var u = href.trim().replace("&amp;", "&")
//             if (u.startsWith("./")) u = u.removePrefix("./")
//             if (u.startsWith("/bbs/")) u = u.removePrefix("/bbs/")
//             if (u.startsWith("bbs/")) u = u.removePrefix("bbs/")
//             if (!u.startsWith("/")) u = "/$u"
//             u
//         }

//         return SChapter.create().apply {
//             setUrlWithoutDomain(canon)
//             name = urlEl.selectFirst(".episode-title")!!.text()
//             dateEl?.also { date_upload = dateParse(it.text()) }
//         }
//     }

//     private val dateFormat = SimpleDateFormat("yy.MM.dd", Locale.ENGLISH)

//     private fun dateParse(dateAsString: String): Long {
//         val date: Date? = try {
//             dateFormat.parse(dateAsString)
//         } catch (_: ParseException) {
//             null
//         }
//         return date?.time ?: 0L
//     }

//     // ---------- Pages ----------
//     override fun pageListRequest(chapter: SChapter): Request {
//         val u0 = chapter.url.trim()
//         val u = if (u0.startsWith("/")) u0 else "/$u0"
//         val path = if (u.startsWith("/bbs/")) u else "/bbs$u"
//         return GET(baseUrl + path, headers)
//     }

//     override fun pageListParse(document: Document): List<Page> {
//         val rawImageLinks = document.selectFirst("script + script[type^=text/javascript]:not([src])")!!.data()
//         val imgList = extractList(rawImageLinks)

//         return imgList.mapIndexed { i, img ->
//             Page(i, imageUrl = "https:$img")
//         }
//     }

//     private val imgListRegex = """img_list\s*=\s*(\[.*?])""".toRegex(RegexOption.DOT_MATCHES_ALL)

//     private fun extractList(jsString: String): List<String> {
//         val matchResult = imgListRegex.find(jsString)
//         val listString = matchResult?.groupValues?.get(1) ?: return emptyList()
//         return Json.decodeFromString(listString)
//     }

//     override fun imageUrlParse(document: Document) = throw UnsupportedOperationException()

//     // ---------- Interceptors ----------
//     private class FixupHeadersInterceptor(
//         private val baseUrl: String,
//     ) : Interceptor {

//         override fun intercept(chain: Interceptor.Chain): Response {
//             val req = chain.request()
//             val b = req.newBuilder()

//             if (isImageRequest(req)) {
//                 b.header("Referer", "$baseUrl/")
//                 b.header("Accept", "image/avif,image/webp,image/apng,image/*,*/*;q=0.8")
//             }

//             return chain.proceed(b.build())
//         }
//     }

//     private companion object {
//         // RateLimit
//         private const val RATE_LIMIT_PERMITS = 1
//         private const val RATE_LIMIT_PERIOD_SECONDS = 2L

//         private fun isImageRequest(request: Request): Boolean {
//             val p = request.url.encodedPath.lowercase(Locale.US)
//             return p.endsWith(".webp") ||
//                 p.endsWith(".png") ||
//                 p.endsWith(".jpg") ||
//                 p.endsWith(".jpeg") ||
//                 p.endsWith(".gif") ||
//                 p.endsWith(".avif")
//         }
//     }

//     // ---------- Filters ----------
//     override fun getFilterList() = FilterList(
//         Filter.Header("Note: can't combine search query with filters, status filter only has effect in 인기만화"),
//         Filter.Separator(),
//         SortFilter(getSortList, 0),
//         StatusFilter(getStatusList, 0),
//         GenreFilter(getGenreList, 0),
//     )

//     class SelectFilterOption(val name: String, val value: String)

//     abstract class SelectFilter(
//         name: String,
//         private val options: List<SelectFilterOption>,
//         default: Int = 0,
//     ) : Filter.Select<String>(name, options.map { it.name }.toTypedArray(), default) {
//         val selected: String
//             get() = options[state].value
//     }

//     class SortFilter(options: List<SelectFilterOption>, default: Int) : SelectFilter("Sort", options, default)
//     class StatusFilter(options: List<SelectFilterOption>, default: Int) : SelectFilter("Status", options, default)
//     class GenreFilter(options: List<SelectFilterOption>, default: Int) : SelectFilter("Genre", options, default)

//     private val getSortList = listOf(
//         SelectFilterOption("인기만화", popularBase),
//         SelectFilterOption("최신만화", latestBase),
//     )

//     private val getStatusList = listOf(
//         SelectFilterOption("전체", "0"),
//         SelectFilterOption("완결", "1"),
//     )

//     private val getGenreList = listOf(
//         SelectFilterOption("전체", ""),
//         SelectFilterOption("SF", "SF"),
//         SelectFilterOption("무협", "무협"),
//         SelectFilterOption("TS", "TS"),
//         SelectFilterOption("개그", "개그"),
//         SelectFilterOption("드라마", "드라마"),
//         SelectFilterOption("러브코미디", "러브코미디"),
//         SelectFilterOption("먹방", "먹방"),
//         SelectFilterOption("백합", "백합"),
//         SelectFilterOption("붕탁", "붕탁"),
//         SelectFilterOption("스릴러", "스릴러"),
//         SelectFilterOption("스포츠", "스포츠"),
//         SelectFilterOption("시대", "시대"),
//         SelectFilterOption("액션", "액션"),
//         SelectFilterOption("순정", "순정"),
//         SelectFilterOption("일상+치유", "일상%2B치유"),
//         SelectFilterOption("추리", "추리"),
//         SelectFilterOption("판타지", "판타지"),
//         SelectFilterOption("학원", "학원"),
//         SelectFilterOption("호러", "호러"),
//         SelectFilterOption("BL", "BL"),
//         SelectFilterOption("17", "17"),
//         SelectFilterOption("이세계", "이세계"),
//         SelectFilterOption("전생", "전생"),
//         SelectFilterOption("라노벨", "라노벨"),
//         SelectFilterOption("애니화", "애니화"),
//         SelectFilterOption("TL", "TL"),
//         SelectFilterOption("공포", "공포"),
//         SelectFilterOption("하렘", "하렘"),
//         SelectFilterOption("요리", "요리"),
//     )
// }

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

    private fun fetchPagesFromNav(url: String) = throw UnsupportedOperationException("Not used")

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
        state: Int = 0
    ) : Filter.Select<String>(
        displayName,
        vals.map { it.name }.toTypedArray(),
        state
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
