package my.noveldokusha.scraper.sources

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import my.noveldokusha.core.LanguageCode
import my.noveldokusha.core.PagedList
import my.noveldokusha.core.Response
import my.noveldokusha.network.NetworkClient
import my.noveldokusha.network.toDocument
import my.noveldokusha.network.tryConnect
import my.noveldokusha.scraper.R
import my.noveldokusha.scraper.SourceInterface
import my.noveldokusha.scraper.TextExtractor
import my.noveldokusha.scraper.domain.BookResult
import my.noveldokusha.scraper.domain.ChapterResult
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/**
 * Light Novel World live examples (current site):
 * - Home: https://lightnovelworld.org/
 * - Catalog: https://lightnovelworld.org/genre-all/?order=new&page=2
 * - Novel: https://lightnovelworld.org/novel/shadow-slave/
 *
 * The site changed from the older .com layout and query structure, so this
 * scraper uses the live .org URLs and several fallback selectors/endpoints.
 */
class LightNovelWorld(
    private val networkClient: NetworkClient
) : SourceInterface.Catalog {
    override val id = "light_novel_world"
    override val nameStrId = R.string.source_name_light_novel_world
    override val baseUrl = "https://lightnovelworld.org/"
    override val catalogUrl = "https://lightnovelworld.org/genre-all/?order=new"
    override val iconUrl =
        "https://lightnovelworld.org/static/lightnovelworld/favicon.png"
    override val language = LanguageCode.ENGLISH

    override suspend fun getChapterText(doc: Document): String = withContext(Dispatchers.Default) {
        firstNonEmptyText(
            doc,
            listOf(
                "#chapter-container",
                ".chapter-container",
                ".chapter-content",
                ".reading-content",
                ".content .chapter-content",
                ".chapter-text",
                ".reader-content",
                "article"
            )
        )
    }

    override suspend fun getBookCoverImageUrl(
        bookUrl: String
    ): Response<String?> = withContext(Dispatchers.Default) {
        tryConnect {
            val doc = networkClient.get(bookUrl).toDocument()
            firstNonBlankAttributeOrNull(
                doc,
                selectors = listOf(
                    "meta[property='og:image']",
                    "meta[name='twitter:image']",
                    ".cover > img[data-src]",
                    ".cover > img[src]",
                    ".novel-cover > img[data-src]",
                    ".novel-cover > img[src]",
                    "img[data-src]"
                ),
                attribute = "content",
                fallbackAttribute = "data-src"
            )?.let { resolveUrl(bookUrl, it) }
        }
    }

    override suspend fun getBookDescription(
        bookUrl: String
    ): Response<String?> = withContext(Dispatchers.Default) {
        tryConnect {
            val doc = networkClient.get(bookUrl).toDocument()
            firstNonEmptyText(
                doc,
                listOf(
                    "meta[property='og:description']",
                    ".summary > .content",
                    ".summary",
                    ".synopsis > .content",
                    ".synopsis",
                    ".description > .content",
                    ".description",
                    "#summary",
                    "#synopsis"
                )
            )
        }
    }

    override suspend fun getChapterList(
        bookUrl: String
    ): Response<List<ChapterResult>> = withContext(Dispatchers.Default) {
        tryConnect {
            val seen = linkedSetOf<String>()
            val chapters = mutableListOf<ChapterResult>()

            for (page in 1..200) {
                val doc = fetchChapterListDocument(bookUrl, page) ?: break
                val pageChapters = extractChapterResults(doc, bookUrl, seen)
                if (pageChapters.isEmpty()) break
                chapters.addAll(pageChapters)
            }

            chapters
        }
    }

    override suspend fun getCatalogList(
        index: Int
    ): Response<PagedList<BookResult>> = withContext(Dispatchers.Default) {
        tryConnect {
            val page = index + 1
            val url = "$catalogUrl&page=$page"
            getBooksList(networkClient.get(url).toDocument(), index)
        }
    }

    override suspend fun getCatalogSearch(
        index: Int,
        input: String
    ): Response<PagedList<BookResult>> = withContext(Dispatchers.Default) {
        tryConnect {
            val query = input.trim()
            if (query.isEmpty()) {
                return@tryConnect PagedList.createEmpty(index = index)
            }

            val results = searchBooks(query)
            PagedList(
                list = results,
                index = index,
                isLastPage = true
            )
        }
    }

    private suspend fun searchBooks(query: String): List<BookResult> {
        val encoded = URLEncoder.encode(query, StandardCharsets.UTF_8.name())
        val candidates = listOf(
            "${baseUrl}search/?title=$encoded",
            "${baseUrl}search/?query=$encoded",
            "${baseUrl}search/?keyword=$encoded",
            "${baseUrl}search/?q=$encoded",
            "${baseUrl}advanced-search/?q=$encoded",
            "${baseUrl}advanced-search/?title=$encoded",
            "${baseUrl}advanced-search/?keyword=$encoded"
        )

        for (candidate in candidates) {
            val doc = runCatching { networkClient.get(candidate).toDocument() }.getOrNull() ?: continue
            val books = parseBooksFromDocument(doc)
            if (books.isNotEmpty()) {
                return books
            }
        }

        return localCatalogSearch(query)
    }

    private suspend fun localCatalogSearch(query: String): List<BookResult> {
        val normalizedQuery = query.lowercase()
        val seen = linkedSetOf<String>()
        val results = mutableListOf<BookResult>()

        for (page in 1..10) {
            val url = "$catalogUrl&page=$page"
            val doc = runCatching { networkClient.get(url).toDocument() }.getOrNull() ?: continue
            for (book in parseBooksFromDocument(doc)) {
                val key = book.url
                if (!seen.add(key)) continue

                val matchesTitle = book.title.lowercase().contains(normalizedQuery)
                if (matchesTitle || queryMatchesUrl(book.url, normalizedQuery)) {
                    results.add(book)
                }
            }
        }

        return results
    }

    private fun queryMatchesUrl(url: String, query: String): Boolean {
        val normalizedUrl = url.lowercase()
            .replace('-', ' ')
            .replace('_', ' ')
        return normalizedUrl.contains(query)
    }

    private suspend fun fetchChapterListDocument(bookUrl: String, page: Int): Document? {
        val base = bookUrl.trimEnd('/')

        val candidates = if (page == 1) {
            listOf(
                "$base/chapters/",
                "$base/chapters/?page=1",
                "$base/chapters?page=1",
                "$base/chapters/page-1"
            )
        } else {
            listOf(
                "$base/chapters/?page=$page",
                "$base/chapters?page=$page",
                "$base/chapters/page-$page"
            )
        }

        for (candidate in candidates) {
            val doc = runCatching { networkClient.get(candidate).toDocument() }.getOrNull() ?: continue
            if (looksLikeChapterListPage(doc)) return doc
        }

        return null
    }

    private fun looksLikeChapterListPage(doc: Document): Boolean {
        val specific = doc.select(
            ".chapter-list a[href], .chapter-list > li > a, .chapters a[href], .chapter-items a[href], .chapter-listing a[href]"
        )
        if (specific.isNotEmpty()) return true

        val generic = doc.select("a[href*='/chapter/']")
        return generic.size >= 5
    }

    private fun extractChapterResults(
        doc: Document,
        bookUrl: String,
        seen: MutableSet<String>
    ): List<ChapterResult> {
        val specificSelectors = listOf(
            ".chapter-list a[href]",
            ".chapter-list > li > a[href]",
            ".chapters a[href]",
            ".chapter-items a[href]",
            ".chapter-listing a[href]"
        )

        for (selector in specificSelectors) {
            val chapters = extractChaptersWithSelector(doc, bookUrl, seen, selector)
            if (chapters.isNotEmpty()) return chapters
        }

        return extractChaptersWithSelector(doc, bookUrl, seen, "a[href*='/chapter/']")
    }

    private fun extractChaptersWithSelector(
        doc: Document,
        bookUrl: String,
        seen: MutableSet<String>,
        selector: String
    ): List<ChapterResult> {
        val results = mutableListOf<ChapterResult>()
        for (a in doc.select(selector)) {
            val href = a.attr("href").trim()
            if (href.isBlank()) continue

            val url = resolveUrl(bookUrl, href)
            val normalizedUrl = url.removeSuffix("/")
            if (!seen.add(normalizedUrl)) continue

            val title = a.attr("title").takeIf { it.isNotBlank() }
                ?: a.text().trim()
                .ifBlank { url.substringAfterLast('/').replace('-', ' ') }

            results.add(
                ChapterResult(
                    title = title,
                    url = url
                )
            )
        }
        return results
    }

    private fun parseBooksFromDocument(doc: Document): List<BookResult> = doc
        .select(".novel-item, .novel-card, .novel, .item")
        .mapNotNull {
            val cover = firstNonBlankAttributeOrNull(
                it,
                selectors = listOf(
                    "img[data-src]",
                    "img[src]",
                    ".novel-cover img[data-src]",
                    ".novel-cover img[src]",
                    ".cover img[data-src]",
                    ".cover img[src]"
                ),
                attribute = "data-src",
                fallbackAttribute = "src"
            ) ?: ""

            val bookAnchor = it.selectFirst("a[title], a[href*='/novel/']") ?: return@mapNotNull null
            val title = bookAnchor.attr("title").takeIf { title -> title.isNotBlank() }
                ?: bookAnchor.text().trim()

            val href = bookAnchor.attr("href").takeIf { it.isNotBlank() } ?: return@mapNotNull null

            BookResult(
                title = title,
                url = resolveUrl(baseUrl, href),
                coverImageUrl = resolveUrl(baseUrl, cover)
            )
        }

    private fun getBooksList(doc: Document, index: Int) = parseBooksFromDocument(doc).let { books ->
        PagedList(
            list = books,
            index = index,
            isLastPage = when (val nav = doc.selectFirst("ul.pagination")) {
                null -> true
                else -> nav.children().last()?.`is`(".active") ?: true
            }
        )
    }

    private fun firstNonEmptyText(doc: Document, selectors: List<String>): String {
        for (selector in selectors) {
            val el = doc.selectFirst(selector) ?: continue
            val text = when {
                selector.startsWith("meta[") -> el.attr("content").trim()
                else -> TextExtractor.get(el).trim()
            }
            if (text.isNotBlank()) return text
        }
        return ""
    }

    private fun firstNonBlankAttributeOrNull(
        element: Element,
        selectors: List<String>,
        attribute: String,
        fallbackAttribute: String
    ): String? {
        for (selector in selectors) {
            val el = element.selectFirst(selector) ?: continue
            val value = el.attr(attribute).trim().ifBlank { el.attr(fallbackAttribute).trim() }
            if (value.isNotBlank()) return value
        }
        return null
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
