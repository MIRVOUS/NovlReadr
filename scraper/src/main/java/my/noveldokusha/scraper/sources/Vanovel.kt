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

class Vanovel(private val networkClient: NetworkClient) : SourceInterface.Catalog {
    override val id = "vanovel"
    override val nameStrId = R.string.source_name_vanovel // Anda perlu mendefinisikan string ini
    override val baseUrl = "https://vanovel.com/"
    override val catalogUrl = "https://vanovel.com/all-novels/"
    override val iconUrl = "https://vanovel.com/wp-content/uploads/2025/10/vanovel.png"
    override val language = LanguageCode.INDONESIAN

    private suspend fun getPagesList(
        index: Int,
        url: String,
    ): Response<PagedList<BookResult>> =
        withContext(Dispatchers.Default) {
            tryConnect {
                val doc = networkClient.get(url).toDocument()
                val list = doc.select(".page-listing-item .page-item-detail")
                    .mapNotNull { item ->
                        val linkElement = item.selectFirst(".item-thumb a")
                        val url = linkElement?.attr("href")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                        val img = item.selectFirst(".item-thumb img")
                        BookResult(
                            title = item.selectFirst(".post-title h3 a")?.text()?.trim() ?: "",
                            url = url,
                            coverImageUrl = img?.attr("data-src")?.takeIf { it.isNotBlank() }
                                ?: img?.attr("src") ?: "",
                        )
                    }
                PagedList(
                    list = list,
                    index = index,
                    isLastPage = doc.selectFirst(".nextpostslink") == null,
                )
            }
        }

    override suspend fun getChapterTitle(doc: Document): String =
        withContext(Dispatchers.Default) { doc.selectFirst("h3.chapter-name")?.text()?.trim() ?: "" }

    override suspend fun getChapterText(doc: Document): String =
        withContext(Dispatchers.Default) {
            doc.selectFirst(".reading-content .text-left")?.let {
                it.select("div.code-block, .note-warning").remove() // Hapus iklan dan notifikasi
                TextExtractor.get(it)
            } ?: ""
        }

    override suspend fun getBookCoverImageUrl(bookUrl: String): Response<String?> =
        withContext(Dispatchers.Default) {
            tryConnect {
                networkClient.get(bookUrl).toDocument()
                    .selectFirst(".summary_image img")
                    ?.attr("data-src")?.takeIf { it.isNotBlank() }
                    ?: ""
            }
        }

    override suspend fun getBookDescription(bookUrl: String): Response<String?> =
        withContext(Dispatchers.Default) {
            tryConnect {
                networkClient.get(bookUrl).toDocument()
                    .selectFirst(".summary__content")
                    ?.let { TextExtractor.get(it) }
            }
        }

    override suspend fun getChapterList(bookUrl: String) =
        withContext(Dispatchers.Default) {
            val postData = my.noveldokusha.network.postRequest(url = "${bookUrl}ajax/chapters/")
            tryConnect {
                networkClient.call(postData).toDocument()
                    .select("li.wp-manga-chapter")
                    .map { item ->
                        item.selectFirst("span")?.remove()
                        ChapterResult(
                            title = item.text().trim(),
                            url = item.selectFirst("a")?.attr("href") ?: "",
                        )
                    }.reversed()
            }
        }

    override suspend fun getCatalogList(index: Int): Response<PagedList<BookResult>> =
        withContext(Dispatchers.Default) {
            val page = index + 1
            val url = catalogUrl.toUrlBuilderSafe()
                .ifCase(page > 1) { add("page", page.toString()) }
                .add("m_orderby" to "modified")
                .toString()
            getPagesList(index, url)
        }

    override suspend fun getCatalogSearch(
        index: Int,
        input: String,
    ): Response<PagedList<BookResult>> =
        withContext(Dispatchers.Default) {
            val page = index + 1
            val url = baseUrl.toUrlBuilderSafe()
                .ifCase(page > 1) { addPath("page", page.toString()) }
                .add("s" to input, "post_type" to "wp-manga")
                .toString()
            getPagesList(index, url)
        }
}
