package my.noveldokusha.scraper.sources

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import my.noveldokusha.core.LanguageCode
import my.noveldokusha.core.PagedList
import my.noveldokusha.core.Response
import my.noveldokusha.network.NetworkClient
import my.noveldokusha.network.add
import my.noveldokusha.network.addPath
import my.noveldokusha.network.ifCase
import my.noveldokusha.network.toDocument
import my.noveldokusha.network.toUrlBuilderSafe
import my.noveldokusha.network.tryConnect
import my.noveldokusha.scraper.R
import my.noveldokusha.scraper.SourceInterface
import my.noveldokusha.scraper.TextExtractor
import my.noveldokusha.scraper.domain.BookResult
import my.noveldokusha.scraper.domain.ChapterResult
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

private fun bestFromSrcset(srcset: String?): String? {
    val candidates = srcset
        ?.split(",")
        ?.mapNotNull { part ->
            part.trim().split(" ").firstOrNull()?.trim()?.takeIf { it.isNotBlank() }
        }
        .orEmpty()

    return candidates.lastOrNull()
}

private fun pickFirstNotBlank(vararg values: String?): String =
    values.firstOrNull { !it.isNullOrBlank() }.orEmpty()

class NovelBin(private val networkClient: NetworkClient) : SourceInterface.Catalog {
    override val id = "Novelbin"
    override val nameStrId = R.string.source_name_novelbin
    override val baseUrl = "https://novelbin.me/"
    override val catalogUrl = "https://novelbin.me/sort/novelbin-daily-update"
    override val iconUrl = "https://novelbin.me/img/logo.png"
    override val language = LanguageCode.ENGLISH

    private fun resolveUrl(href: String): String =
        if (href.startsWith("http")) href else baseUrl + href.removePrefix("/")

    private fun bestImageUrl(img: Element?): String =
        pickFirstNotBlank(
            bestFromSrcset(img?.attr("data-srcset")),
            bestFromSrcset(img?.attr("srcset")),
            img?.attr("data-src"),
            img?.attr("data-original"),
            img?.attr("src"),
        )

    private suspend fun getPagesList(index: Int, url: String) =
        withContext(Dispatchers.Default) {
            tryConnect {
                val doc = networkClient.get(url).toDocument()

                val bookResults =
                    doc.select("#list-page div.list-novel .row, #list-page .list-novel .row")
                        .mapNotNull {
                            val link = it.selectFirst("div.col-xs-7 a, a[href]") ?: return@mapNotNull null
                            val img = it.selectFirst("div.col-xs-3 img, img")

                            BookResult(
                                title = pickFirstNotBlank(
                                    link.attr("title"),
                                    link.text(),
                                ),
                                url = resolveUrl(link.attr("href")),
                                coverImageUrl = bestImageUrl(img),
                            )
                        }

                val isLastPage = doc.select("ul.pagination li.next.disabled").isNotEmpty()

                PagedList(
                    list = bookResults,
                    index = index,
                    isLastPage = isLastPage,
                )
            }
        }

    override suspend fun getChapterTitle(doc: Document): String =
        withContext(Dispatchers.Default) {
            pickFirstNotBlank(
                doc.selectFirst("h1.entry-title")?.text(),
                doc.selectFirst("h2 > .title-chapter")?.text(),
                doc.selectFirst(".chapter-title")?.text(),
                doc.title(),
            )
        }

    override suspend fun getChapterText(doc: Document): String =
        withContext(Dispatchers.Default) {
            val content =
                listOf(
                    ".reading-content",
                    ".chapter-content",
                    "#chapter-content",
                    "#chr-content",
                    ".entry-content",
                    "article",
                ).mapNotNull { selector -> doc.selectFirst(selector) }
                    .firstOrNull()
                    ?: return@withContext ""

            content.select(
                "h1, h2, .chapter-nav, .nav-links, .ads, .adsads, script, style, noscript, iframe"
            ).remove()

            TextExtractor.get(content)
        }

    override suspend fun getBookCoverImageUrl(bookUrl: String): Response<String?> =
        withContext(Dispatchers.Default) {
            tryConnect {
                val doc = networkClient.get(bookUrl).toDocument()
                val img = doc.selectFirst(".book-img img, .book-info img, .summary_image img, .tab-summary .summary_image img")

                pickFirstNotBlank(
                    doc.selectFirst("meta[property=og:image]")?.attr("content"),
                    doc.selectFirst("meta[itemprop=image]")?.attr("content"),
                    bestImageUrl(img),
                )
            }
        }

    override suspend fun getBookDescription(bookUrl: String): Response<String?> =
        withContext(Dispatchers.Default) {
            tryConnect {
                networkClient
                    .get(bookUrl)
                    .toDocument()
                    .selectFirst("div.desc-text, .desc-text, .summary__content")
                    ?.let { TextExtractor.get(it) }
            }
        }

    override suspend fun getChapterList(bookUrl: String) =
        withContext(Dispatchers.Default) {
            tryConnect {
                val doc = networkClient.get(bookUrl).toDocument()

                val chapterLinks =
                    doc.select(
                        "a[href*='/chapter-'], a[href*='/chapter/'], .chapter-list a[href], .list-chapter a[href], .wp-manga-chapter a[href]"
                    )
                        .mapNotNull { a ->
                            val href = a.attr("href").trim()
                            val title = pickFirstNotBlank(a.attr("title"), a.text()).trim()

                            if (href.isBlank() || title.isBlank()) return@mapNotNull null

                            ChapterResult(
                                title = title,
                                url = resolveUrl(href),
                            )
                        }
                        .distinctBy { it.url }

                chapterLinks
            }
        }

    override suspend fun getCatalogList(index: Int): Response<PagedList<BookResult>> =
        withContext(Dispatchers.Default) {
            val page = index + 1
            val url =
                catalogUrl
                    .toUrlBuilderSafe()
                    .ifCase(page > 1) { add("page", page.toString()) }
                    .toString()

            getPagesList(index, url)
        }

    override suspend fun getCatalogSearch(
        index: Int,
        input: String,
    ): Response<PagedList<BookResult>> =
        withContext(Dispatchers.Default) {
            val page = index + 1
            val url =
                baseUrl
                    .toUrlBuilderSafe()
                    .addPath("search")
                    .add("keyword" to input)
                    .ifCase(page > 1) { add("page", page.toString()) }
                    .toString()

            getPagesList(index, url)
        }
}
