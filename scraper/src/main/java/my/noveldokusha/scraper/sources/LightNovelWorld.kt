package my.noveldokusha.scraper.sources

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import my.noveldokusha.core.LanguageCode
import my.noveldokusha.core.PagedList
import my.noveldokusha.core.Response
import my.noveldokusha.network.NetworkClient
import my.noveldokusha.network.getRequest
import my.noveldokusha.network.toDocument
import my.noveldokusha.network.tryConnect
import my.noveldokusha.scraper.R
import my.noveldokusha.scraper.SourceInterface
import my.noveldokusha.scraper.TextExtractor
import my.noveldokusha.scraper.domain.BookResult
import my.noveldokusha.scraper.domain.ChapterResult
import okhttp3.Headers
import org.jsoup.nodes.Document
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

class LightNovelWorld(
    private val networkClient: NetworkClient
) : SourceInterface.Catalog {
    override val id = "light_novel_world"
    override val nameStrId = R.string.source_name_light_novel_world
    override val baseUrl = "https://lightnovelworld.org/"
    override val catalogUrl = "https://lightnovelworld.org/advanced-search/"
    override val iconUrl = "https://lightnovelworld.org/static/lightnovelworld/favicon.png"
    override val language = LanguageCode.ENGLISH

    private val defaultHeaders = Headers.Builder()
        .add("User-Agent", "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/116.0.0.0 Mobile Safari/537.36")
        .add("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8")
        .add("Accept-Language", "en-US,en;q=0.9")
        .add("Accept-Encoding", "gzip, deflate, br")
        .add("Referer", "https://lightnovelworld.org/")
        .add("DNT", "1")
        .build()

    private suspend fun fetchDoc(url: String): Document? = runCatching {
        getRequest(url = url, headers = defaultHeaders)
            .let { networkClient.call(it) }
            .toDocument()
    }.getOrNull()

    override suspend fun getChapterTitle(doc: Document): String =
        withContext(Dispatchers.Default) {
            doc.selectFirst(".chapter-title, h1.chapter-title, h2.chapter-title, .capter-title")
                ?.text()
                ?: doc.selectFirst("h1, h2")?.text()
                ?: ""
        }

    override suspend fun getChapterText(doc: Document): String =
        withContext(Dispatchers.Default) {
            val content = doc.selectFirst("#chapter-container")
                ?: doc.selectFirst(".chapter-container")
                ?: doc.selectFirst(".chapter-content")
                ?: doc.selectFirst("#content")
                ?: doc.selectFirst("div[id^=chapter]")
                ?: doc.selectFirst("article")
            content?.let { TextExtractor.get(it) } ?: ""
        }

    override suspend fun getBookCoverImageUrl(bookUrl: String): Response<String?> =
        withContext(Dispatchers.Default) {
            tryConnect {
                val doc = fetchDoc(bookUrl) ?: return@tryConnect null
                doc.selectFirst("meta[property='og:image']")?.attr("content")
                    ?: doc.selectFirst(".cover img, .novel-cover img, .book-cover img")
                        ?.let { it.attr("data-src").ifBlank { it.attr("src") } }
            }
        }

    override suspend fun getBookDescription(bookUrl: String): Response<String?> =
        withContext(Dispatchers.Default) {
            tryConnect {
                val doc = fetchDoc(bookUrl) ?: return@tryConnect null
                doc.selectFirst(".summary .content, .synopsis .content, .description .content, .summary, .synopsis, .description")
                    ?.let { TextExtractor.get(it) }
            }
        }

    override suspend fun getChapterList(bookUrl: String): Response<List<ChapterResult>> =
        withContext(Dispatchers.Default) {
            tryConnect {
                val doc = fetchDoc(bookUrl)
                    ?: throw Exception("Failed to load: $bookUrl")

                // Ekstrak slug dari URL
                val slug = bookUrl.trimEnd('/').substringAfterLast('/')
                    .ifBlank { bookUrl.trimEnd('/').substringBeforeLast('/').substringAfterLast('/') }

                // Cari jumlah chapter
                val total = extractChapterCount(doc)
                    ?: throw Exception("Chapter count not found for $bookUrl")

                (1..total).map { n ->
                    ChapterResult(
                        title = "Chapter $n",
                        url = "${baseUrl}novel/$slug/chapter/$n/"
                    )
                }
            }
        }

    private fun extractChapterCount(doc: Document): Int? {
        // Cari teks "X chapters" di seluruh dokumen
        val patterns = listOf(
            Regex("""(\d[\d,]+)\s*chapters?""", RegexOption.IGNORE_CASE),
            Regex("""total\s+of\s+(\d[\d,]+)""", RegexOption.IGNORE_CASE),
            Regex("""chapters?\s*[:\-]\s*(\d[\d,]+)""", RegexOption.IGNORE_CASE)
        )
        val bodyText = doc.body()?.text() ?: return null
        for (pattern in patterns) {
            val match = pattern.find(bodyText)
            val numStr = match?.groupValues?.get(1)?.replace(",", "")
            val num = numStr?.toIntOrNull()
            if (num != null && num > 0) return num
        }

        // Fallback: cari di element stats/info
        for (el in doc.select("span, div, p")) {
            val text = el.ownText()
            val match = Regex("""(\d+)\s*chapters?""", RegexOption.IGNORE_CASE).find(text)
            val num = match?.groupValues?.get(1)?.toIntOrNull()
            if (num != null && num in 1..50000) return num
        }
        return null
    }

    // Parsing novel dari halaman manapun — cari semua link ke /novel/
    private fun parseBooksFromDocument(doc: Document): List<BookResult> {
        val seen = mutableSetOf<String>()
        val results = mutableListOf<BookResult>()

        // Strategi 1: selector spesifik
        val specificItems = doc.select(".novel-item, .novel-card, li.novel")
        for (item in specificItems) {
            val anchor = item.selectFirst("a[href*='/novel/']") ?: continue
            val href = anchor.attr("href").trim().ifBlank { continue }
            val url = resolveUrl(baseUrl, href)
            if (!seen.add(url)) continue

            val title = anchor.attr("title").ifBlank {
                item.selectFirst(".novel-title, h3, h4, .title")?.text()
                    ?: anchor.text()
            }.trim().ifBlank { continue }

            val img = item.selectFirst("img")
            val cover = img?.attr("data-src")?.ifBlank { img.attr("src") } ?: img?.attr("src") ?: ""

            results.add(BookResult(title = title, url = url, coverImageUrl = resolveUrl(baseUrl, cover)))
        }

        // Strategi 2 (fallback): jika strategi 1 kosong, cari semua link /novel/
        if (results.isEmpty()) {
            for (anchor in doc.select("a[href*='/novel/']")) {
                val href = anchor.attr("href").trim()
                // Pastikan ini halaman novel, bukan chapter
                if (!href.contains("/chapter/") && !href.endsWith("/chapters/")) {
                    val url = resolveUrl(baseUrl, href)
                    if (!seen.add(url)) continue

                    val title = anchor.attr("title").ifBlank { anchor.text() }.trim()
                    if (title.isBlank()) continue

                    // Cari gambar di parent element
                    val parent = anchor.parent()
                    val img = parent?.selectFirst("img")
                    val cover = img?.attr("data-src")?.ifBlank { img.attr("src") } ?: ""

                    results.add(BookResult(title = title, url = url, coverImageUrl = resolveUrl(baseUrl, cover)))
                }
            }
        }

        return results
    }

    override suspend fun getCatalogList(index: Int): Response<PagedList<BookResult>> =
        withContext(Dispatchers.Default) {
            tryConnect {
                val page = index + 1
                // Coba beberapa URL catalog
                val urls = listOf(
                    "${catalogUrl}?page=$page",
                    "${baseUrl}leaderboard/?page=$page",
                    "${baseUrl}search/?page=$page",
                    "${baseUrl}updates/?page=$page"
                )

                var books = emptyList<BookResult>()
                var doc: Document? = null
                for (url in urls) {
                    val d = fetchDoc(url) ?: continue
                    val b = parseBooksFromDocument(d)
                    if (b.isNotEmpty()) {
                        books = b
                        doc = d
                        break
                    }
                }

                PagedList(
                    list = books,
                    index = index,
                    isLastPage = doc?.let { isLastPage(it) } ?: true
                )
            }
        }

    override suspend fun getCatalogSearch(
        index: Int,
        input: String
    ): Response<PagedList<BookResult>> =
        withContext(Dispatchers.Default) {
            tryConnect {
                val query = input.trim()
                if (query.isEmpty()) return@tryConnect PagedList.createEmpty(index = index)

                val encoded = URLEncoder.encode(query, StandardCharsets.UTF_8.name())
                val page = index + 1

                // Coba beberapa format URL pencarian
                val urls = listOf(
                    "${baseUrl}search/?keywords=$encoded&page=$page",
                    "${baseUrl}search/?q=$encoded&page=$page",
                    "${baseUrl}search/?title=$encoded&page=$page",
                    "${baseUrl}advanced-search/?keywords=$encoded&page=$page"
                )

                var books = emptyList<BookResult>()
                var doc: Document? = null
                for (url in urls) {
                    val d = fetchDoc(url) ?: continue
                    val b = parseBooksFromDocument(d)
                    if (b.isNotEmpty()) {
                        books = b
                        doc = d
                        break
                    }
                }

                PagedList(
                    list = books,
                    index = index,
                    isLastPage = books.isEmpty() || (doc?.let { isLastPage(it) } ?: true)
                )
            }
        }

    private fun isLastPage(doc: Document): Boolean {
        val pagination = doc.selectFirst("ul.pagination, .pagination, nav[aria-label='pagination']")
            ?: return true
        return pagination.selectFirst("li.next:not(.disabled), a[rel=next], .next-page") == null
    }

    private fun resolveUrl(base: String, raw: String): String {
        val v = raw.trim()
        if (v.isBlank()) return v
        if (v.startsWith("http://") || v.startsWith("https://")) return v
        if (v.startsWith("//")) return "https:$v"
        return "${base.trimEnd('/')}/${v.removePrefix("/")}"
    }
}
