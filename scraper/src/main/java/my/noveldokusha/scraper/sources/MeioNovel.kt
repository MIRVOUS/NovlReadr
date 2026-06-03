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
import my.noveldokusha.network.postRequest
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

private fun pickFirstNotBlank(vararg values: String?): String =
    values.firstOrNull { !it.isNullOrBlank() }.orEmpty()

private fun Element?.attrOrEmpty(vararg names: String): String =
    names.mapNotNull { name -> this?.attr(name)?.takeIf { it.isNotBlank() } }.firstOrNull().orEmpty()

class MeioNovel(private val networkClient: NetworkClient) : SourceInterface.Catalog {
    override val id = "meio_webnovel"
    override val nameStrId = R.string.source_name_meio_novel
    override val baseUrl = "https://meionovels.com/"
    override val catalogUrl = "https://meionovels.com/novel/?m_orderby=views"
    override val iconUrl =
        "https://meionovels.com/wp-content/uploads/2021/01/cropped-logoa-sa-32x32.png"
    override val language = LanguageCode.INDONESIAN

    private suspend fun getPagesList(
        index: Int,
        url: String,
        isSearch: Boolean = false,
    ): Response<PagedList<BookResult>> =
        withContext(Dispatchers.Default) {
            tryConnect {
                val doc = networkClient.get(url).toDocument()

                val selector = if (isSearch) {
                    ".tab-content-wrap .c-tabs-item .tab-thumb a, .c-tabs-item .tab-thumb a, .page-item-detail .item-thumb a"
                } else {
                    ".tab-content-wrap .page-item-detail .item-thumb a, .page-item-detail .item-thumb a, .c-tabs-item .tab-thumb a"
                }

                val list = doc.select(selector)
                    .mapNotNull { a ->
                        val href = a.attr("href").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                        val img = a.selectFirst("img")

                        BookResult(
                            title = pickFirstNotBlank(
                                a.attr("title"),
                                img.attrOrEmpty("title"),
                                img.attrOrEmpty("alt"),
                                a.text(),
                            ),
                            url = href,
                            coverImageUrl = pickFirstNotBlank(
                                img.attrOrEmpty("data-src"),
                                img.attrOrEmpty("src"),
                                img.attrOrEmpty("data-lazy-src"),
                            ),
                        )
                    }

                PagedList(
                    list = list,
                    index = index,
                    isLastPage = doc.selectFirst(".paging-navigation .nav-previous") == null,
                )
            }
        }

    override suspend fun getChapterTitle(doc: Document): String =
    withContext(Dispatchers.Default) {
        // Prioritaskan h3 di reading-content (judul bab yang benar)
        val title = doc.selectFirst(".reading-content h3")?.text()?.trim()
            ?: doc.selectFirst("h1.entry-title")?.text()?.trim()
            ?: doc.selectFirst(".reading-content h1")?.text()?.trim()
            ?: doc.title()
        
        // Bersihkan dari sisa-sisa teks panjang (opsional)
        title
            .replace(Regex("""\s*[-–]\s*Baca Light Novel.*$""", RegexOption.IGNORE_CASE), "")
           // .replace(Regex("""\s*[-–]\s*HTL\s*""", RegexOption.IGNORE_CASE), "")
            .trim()
    }

    override suspend fun getChapterText(doc: Document): String =
        withContext(Dispatchers.Default) {
            val content =
                doc.selectFirst(".reading-content .text-left")
                    ?: doc.selectFirst(".reading-content .entry-content")
                    ?: doc.selectFirst(".reading-content")
                    ?: return@withContext ""

            content.select(
                "h1, .wp-manga-nav, .nav-links, .comments-area, .chapter-nav, .breadcrumb"
            ).remove()

            TextExtractor.get(content)
        }

    override suspend fun getBookCoverImageUrl(bookUrl: String): Response<String?> =
        withContext(Dispatchers.Default) {
            tryConnect {
                val img = networkClient
                    .get(bookUrl)
                    .toDocument()
                    .selectFirst(".tab-summary .summary_image img, .summary_image img")

                pickFirstNotBlank(
                    img.attrOrEmpty("data-src"),
                    img.attrOrEmpty("src"),
                    img.attrOrEmpty("data-lazy-src"),
                )
            }
        }

    override suspend fun getBookDescription(bookUrl: String): Response<String?> =
        withContext(Dispatchers.Default) {
            tryConnect {
                networkClient
                    .get(bookUrl)
                    .toDocument()
                    .selectFirst(".summary__content")
                    ?.let { TextExtractor.get(it) }
            }
        }

    override suspend fun getChapterList(bookUrl: String) =
        withContext(Dispatchers.Default) {
            val postData =
                postRequest(url = bookUrl.toUrlBuilderSafe().addPath("ajax", "chapters").toString())

            tryConnect {
                networkClient
                    .call(postData)
                    .toDocument()
                    .select("li.wp-manga-chapter, li[class=wp-manga-chapter]")
                    .map { item ->
                        item.selectFirst("span")?.remove()
                        ChapterResult(
                            item.text().trim(),
                            item.selectFirst("a")?.attr("href") ?: "",
                        )
                    }
                    .reversed()
            }
        }

    override suspend fun getCatalogList(index: Int): Response<PagedList<BookResult>> =
        withContext(Dispatchers.Default) {
            val page = index + 1
            val url =
                catalogUrl
                    .toUrlBuilderSafe()
                    .ifCase(page > 1) { addPath("page", page.toString()) }
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
                    .ifCase(page > 1) { addPath("page", page.toString()) }
                    .add(
                        "s" to input,
                        "post_type" to "wp-manga",
                        "m_orderby" to "views",
                    )
                    .toString()

            getPagesList(index, url, true)
        }
}
