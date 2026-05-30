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
    override val catalogUrl = "https://lightnovelworld.org/genre-all/?order=updates"
    override val iconUrl = "https://lightnovelworld.org/static/lightnovelworld/favicon.ico"
    override val language = LanguageCode.ENGLISH

    // Header standar untuk menghindari Cloudflare block
    private val defaultHeaders = Headers.Builder()
        .add("User-Agent", "Mozilla/5.0 (Android 13; Mobile; rv:125.0) Gecko/125.0 Firefox/125.0")
        .add("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
        .add("Accept-Language", "en-US,en;q=0.5")
        .add("Referer", "https://lightnovelworld.org/")
        .build()

    private suspend fun fetchDoc(url: String): Document? = runCatching {
        getRequest(url = url, headers = defaultHeaders)
            .let { networkClient.call(it) }
            .toDocument()
    }.getOrNull()

    override suspend fun getChapterTitle(doc: Document): String =
        withContext(Dispatchers.Default) {
            doc.selectFirst(".chapter-title, .capter-title, h1.chapter-title, h2.chapter-title")
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
            content?.let { TextExtractor.get(it) } ?: ""
        }

    override suspend fun getBookCoverImageUrl(bookUrl: String): Response<String?> =
        withContext(Dispatchers.Default) {
            tryConnect {
                val doc = fetchDoc(bookUrl) ?: return@tryConnect null
                doc.selectFirst("meta[property='og:image']")?.attr("content")
                    ?: doc.selectFirst(".cover img")?.attr("data-src")
                    ?: doc.selectFirst(".cover img")?.attr("src")
                    ?: doc.selectFirst(".novel-cover img")?.attr("data-src")
                    ?: doc.selectFirst(".novel-cover img")?.attr("src")
            }
        }

    override suspend fun getBookDescription(bookUrl: String): Response<String?> =
        withContext(Dispatchers.Default) {
            tryConnect {
                val doc = fetchDoc(bookUrl) ?: return@tryConnect null
                doc.selectFirst(".summary .content, .synopsis .content, .description .content")
                    ?.let { TextExtractor.get(it) }
                    ?: doc.selectFirst(".summary, .synopsis, .description, #summary, #synopsis")
                        ?.let { TextExtractor.get(it) }
            }
        }

    override suspend fun getChapterList(bookUrl: String): Response<List<ChapterResult>> =
        withContext(Dispatchers.Default) {
            tryConnect {
                val doc = fetchDoc(bookUrl)
                    ?: throw Exception("Failed to load novel page: $bookUrl")

                // Ekstrak slug dari URL
                // Contoh: https://lightnovelworld.org/novel/shadow-slave/ → shadow-slave
                val slug = bookUrl.trimEnd('/')
                    .substringAfterLast('/')
                    .ifBlank {
                        bookUrl.trimEnd('/').substringBeforeLast('/').substringAfterLast('/')
                    }

                // Cari total chapter dari halaman novel
                // Contoh: "A total of 3007 chapters" atau "3007 Chapters"
                val totalChapters = extractChapterCount(doc)
                    ?: throw Exception("Cannot find chapter count for $bookUrl")

                // Generate semua chapter URL secara langsung
                // Format: https://lightnovelworld.org/novel/{slug}/chapter/{n}/
                (1..totalChapters).map { n ->
                    ChapterResult(
                        title = "Chapter $n",
                        url = "${baseUrl}novel/$slug/chapter/$n/"
                    )
                }
            }
        }

    private fun extractChapterCount(doc: Document): Int? {
        // Coba berbagai selector untuk menemukan jumlah chapter
        val selectors = listOf(
            ".novel-stats span",
            ".header-stats span",
            ".stats span",
            ".novel-info span",
            "span[title*='chapter' i]",
            "span[title*='Chapter' i]",
            ".chapter-count",
            "#chapter-count"
        )

        for (selector in selectors) {
            for (el in doc.select(selector)) {
                val text = el.text()
                val num = extractNumberFromText(text)
                if (num != null && num > 0) return num
            }
        }

        // Fallback: cari teks yang mengandung "chapters" di seluruh halaman
        val bodyText = doc.body()?.text() ?: return null
        val patterns = listOf(
            Regex("""(\d+)\s*chapters?\s*(?:have been|translated|available)""", RegexOption.IGNORE_CASE),
            Regex("""total\s+of\s+(\d+)\s*chapters?""", RegexOption.IGNORE_CASE),
            Regex("""(\d+)\s*chapters?""", RegexOption.IGNORE_CASE)
        )
        for (pattern in patterns) {
            val match = pattern.find(bodyText)
            val num = match?.groupValues?.get(1)?.toIntOrNull()
            if (num != null && num > 0) return num
        }

        return null
    }

    private fun extractNumberFromText(text: String): Int? {
        return Regex("""(\d+)""").find(text)?.groupValues?.get(1)?.toIntOrNull()
    }

    override suspend fun getCatalogList(index: Int): Response<PagedList<BookResult>> =
        withContext(Dispatchers.Default) {
            tryConnect {
                val page = index + 1
                val url = "${catalogUrl}?page=$page"
                val doc = fetchDoc(url) ?: return@tryConnect PagedList.createEmpty(index = index)
                getBooksList(doc, index)
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

                // lightnovelworld.org pakai parameter "keywords"
                val url = "${baseUrl}advanced-search/?keywords=$encoded&page=$page"
                val doc = fetchDoc(url) ?: return@tryConnect PagedList.createEmpty(index = index)
                val books = parseBooksFromDocument(doc)

                PagedList(
                    list = books,
                    index = index,
                    isLastPage = books.isEmpty() || isLastPage(doc)
                )
            }
        }

    private fun parseBooksFromDocument(doc: Document): List<BookResult> =
        doc.select(".novel-item").mapNotNull { el ->
            val anchor = el.selectFirst("a[href*='/novel/']") ?: return@mapNotNull null
            val href = anchor.attr("href").trim().ifBlank { return@mapNotNull null }
            val title = anchor.attr("title").ifBlank {
                el.selectFirst(".novel-title, h3, h4")?.text()
            }?.ifBlank { return@mapNotNull null } ?: return@mapNotNull null

            val img = el.selectFirst("img")
            val cover = img?.attr("data-src")?.ifBlank { img.attr("src") }
                ?: img?.attr("src")
                ?: ""

            BookResult(
                title = title,
                url = resolveUrl(baseUrl, href),
                coverImageUrl = resolveUrl(baseUrl, cover)
            )
        }

    private fun getBooksList(doc: Document, index: Int): PagedList<BookResult> {
        val books = parseBooksFromDocument(doc)
        return PagedList(
            list = books,
            index = index,
            isLastPage = isLastPage(doc)
        )
    }

    private fun isLastPage(doc: Document): Boolean {
        val pagination = doc.selectFirst("ul.pagination, .pagination") ?: return true
        return pagination.selectFirst("li.next:not(.disabled), a[rel=next]") == null
    }

    private fun resolveUrl(base: String, raw: String): String {
        val value = raw.trim()
        if (value.isBlank()) return value
        if (value.startsWith("http://") || value.startsWith("https://")) return value
        if (value.startsWith("//")) return "https:$value"
        val normalizedBase = base.trimEnd('/')
        val normalizedValue = value.removePrefix("/")
        return "$normalizedBase/$normalizedValue"
    }
}
