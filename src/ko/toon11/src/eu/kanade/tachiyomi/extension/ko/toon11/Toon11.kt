package eu.kanade.tachiyomi.extension.ko.toon11

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
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
import java.io.IOException
import java.text.ParseException
import java.text.SimpleDateFormat
import java.util.ArrayList
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.AtomicLong
import kotlin.random.Random

class Toon11 : ParsedHttpSource() {

    override val name = "11toon"

    // www 제거: Referer/리다이렉트 꼬임 방지용
    override val baseUrl = "https://11toon.com"

    override val lang = "ko"
    override val supportsLatest = true

    // OkHttp 디스패처 자체 제한은 너무 빡세면 대기열만 길어짐. (실제 제한은 아래 세마포어가 담당)
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

    override val client: OkHttpClient = network.cloudflareClient.newBuilder()
        .dispatcher(limiterDispatcher)
        .addInterceptor(HostConcurrencyAndBackoffInterceptor())
        .addInterceptor(FixupHeadersInterceptor(baseUrl))
        .build()

    // ---------- Requests ----------
    // 사용자 제공 RAW에 맞춤
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

    private fun fetchPagesFromNav(url: String) =
        client.newCall(GET(url, headers)).execute().asJsoup()

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
        } catch (_: ParseException) {
            null
        }
        return date?.time ?: 0L
    }

    // ---------- Pages ----------
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
        return Json.decodeFromString(listString)
    }

    override fun imageUrlParse(document: Document) = throw UnsupportedOperationException()

    // ---------- Interceptors ----------
    private class FixupHeadersInterceptor(
        private val baseUrl: String,
    ) : Interceptor {

        override fun intercept(chain: Interceptor.Chain): Response {
            val req = chain.request()

            val isImage = isImageRequest(req)
            val b = req.newBuilder()

            if (isImage) {
                b.header("Referer", "$baseUrl/")
                b.header("Accept", "image/avif,image/webp,image/apng,image/*,*/*;q=0.8")
            }

            return chain.proceed(b.build())
        }
    }

    private class HostConcurrencyAndBackoffInterceptor(
        private val imageConcurrency: Int = 5,
        private val htmlConcurrency: Int = 2,
        private val maxRetries: Int = 2,
        private val baseBackoffMs: Long = 750L,
    ) : Interceptor {

        companion object {
            private val hostNextAllowedAt = ConcurrentHashMap<String, AtomicLong>()

            private val httpDateFormat = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US).apply {
                timeZone = TimeZone.getTimeZone("GMT")
            }

            private val imageSemaphore = Semaphore(5, true)
            private val htmlSemaphore = Semaphore(2, true)
        }

        private fun isTransientServerCode(code: Int): Boolean {
            return code == 429 || code == 503 || code == 502 || code == 504 || code == 408 ||
                code == 520 || code == 521 || code == 522 || code == 524
        }

        private fun parseRetryAfterMs(response: Response, maxRetryAfterMs: Long = 60_000L): Long? {
            val v = response.header("Retry-After")?.trim().orEmpty()
            if (v.isEmpty()) return null

            v.toLongOrNull()?.let { seconds ->
                val ms = seconds * 1000L
                return ms.coerceIn(0L, maxRetryAfterMs)
            }

            return try {
                val whenMs = httpDateFormat.parse(v)?.time ?: return null
                val now = System.currentTimeMillis()
                val ms = (whenMs - now).coerceAtLeast(0L)
                ms.coerceAtMost(maxRetryAfterMs)
            } catch (_: Exception) {
                null
            }
        }

        private fun computeBackoffMs(attempt: Int, base: Long, maxBackoffMs: Long = 15_000L): Long {
            val exp = base * (1L shl attempt).coerceAtMost(1L shl 10)
            val jitter = Random.nextLong(0, 250)
            return (exp + jitter).coerceAtMost(maxBackoffMs)
        }

        private fun getNextAllowedAt(host: String): Long {
            return hostNextAllowedAt[host]?.get() ?: 0L
        }

        private fun bumpNextAllowedAt(host: String, newUntilMs: Long) {
            val atom = hostNextAllowedAt.getOrPut(host) { AtomicLong(0L) }
            while (true) {
                val cur = atom.get()
                if (newUntilMs <= cur) return
                if (atom.compareAndSet(cur, newUntilMs)) return
            }
        }

        private fun sleepMs(ms: Long) {
            if (ms <= 0) return
            try {
                Thread.sleep(ms)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }

        private fun semaphoreFor(request: Request): Semaphore {
            return if (isImageRequest(request)) imageSemaphore else htmlSemaphore
        }

        override fun intercept(chain: Interceptor.Chain): Response {
            val request = chain.request()
            val host = request.url.host

            val sem = semaphoreFor(request)
            sem.acquire()
            try {
                var attempt = 0
                var lastException: IOException? = null
                var lastErrorResponse: Response? = null

                while (attempt <= maxRetries) {
                    val until = getNextAllowedAt(host)
                    val now = System.currentTimeMillis()
                    sleepMs(until - now)

                    lastErrorResponse?.close()
                    try {
                        val r = chain.proceed(request)
                        if (!isTransientServerCode(r.code)) return r

                        lastErrorResponse = r

                        val retryAfter = parseRetryAfterMs(r)
                        val waitMs = retryAfter ?: computeBackoffMs(attempt, baseBackoffMs)
                        bumpNextAllowedAt(host, System.currentTimeMillis() + waitMs)

                        if (attempt == maxRetries) return r
                        sleepMs(waitMs)
                    } catch (e: IOException) {
                        // 취소/네트워크 에러는 여기서 "부채"로 누적시키면 다시 지옥으로 감
                        lastException = e
                        if (attempt == maxRetries) throw e
                    }

                    attempt++
                }

                lastErrorResponse?.let { return it }
                throw lastException ?: IOException("Request failed after retries")
            } finally {
                sem.release()
            }
        }
    }

    private companion object {
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
