package my.noveldokusha.scraper.sources

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import my.noveldokusha.core.LanguageCode
import my.noveldokusha.core.PagedList
import my.noveldokusha.core.Response
import my.noveldokusha.network.NetworkClient
import my.noveldokusha.network.add
import my.noveldokusha.network.addPath
import my.noveldokusha.network.getRequest
import my.noveldokusha.network.ifCase
import my.noveldokusha.network.toDocument
import my.noveldokusha.network.toUrlBuilderSafe
import my.noveldokusha.network.tryConnect
import my.noveldokusha.scraper.R
import my.noveldokusha.scraper.SourceInterface
import my.noveldokusha.scraper.TextExtractor
import my.noveldokusha.scraper.domain.BookResult
import my.noveldokusha.scraper.domain.ChapterResult
import okhttp3.Headers
import org.jsoup.nodes.Document

class NovelBin(private val networkClient: NetworkClient) : SourceInterface.Catalog {
    override val id = "Novelbin"
    override val nameStrId = R.string.source_name_novelbin
    override val baseUrl = "https://novelbin.me/"
    override val catalogUrl = "https://novelbin.me/sort/novelbin-daily-update"
    override val iconUrl = "https://novelbin.me/img/logo.png"
    override val language = LanguageCode.ENGLISH

    // Helper: ambil src atau data-src (lazy-loaded images)
    private fun imageUrl(element: org.jsoup.nodes.Element?): String {
        if (element == null) return ""
        return element.attr("data-src").ifBlank {
            element.attr("src").ifBlank {
                element.attr("data-original")
            }
        }
    }

    private suspend fun getPagesList(index: Int, url: String) =
    withContext(Dispatchers.Default) {
        tryConnect {
            networkClient.get(url).toDocument().run {
                val isLastPage = select("ul.pagination li.next.disabled").isEmpty()
                val mainContainer = selectFirst("#list-page .col-novel-main")
                    ?: selectFirst("#list-page")
                val bookResults = mainContainer
                    ?.select(".list-novel .row, .list-novel .novel-item")
                    ?.mapNotNull {
                        val link = it.selectFirst("div.col-xs-7 a")
                            ?: it.selectFirst(".novel-title a")
                            ?: it.selectFirst("h3 a")
                            ?: return@mapNotNull null

                        val coverImg = it.selectFirst("div.col-xs-3 > div > img")
                            ?: it.selectFirst("div.col-xs-3 img")
                            ?: it.selectFirst(".novel-cover img")
                            ?: it.selectFirst("img")

                        BookResult(
                            title = link.attr("title").ifBlank { link.text() },
                            url = link.attr("href"),
                            coverImageUrl = imageUrl(coverImg)
                        )
                    } ?: emptyList()
                PagedList(list = bookResults, index = index, isLastPage = !isLastPage)
            }
        }
    }

    override suspend fun getChapterTitle(doc: Document): String =
    withContext(Dispatchers.Default) {
        // Kembalikan kosong agar tidak menimpa judul bersih dari getChapterList
        ""
    }

    override suspend fun getChapterText(doc: Document): String =
        withContext(Dispatchers.Default) {
            // Coba beberapa selector dari yang paling spesifik ke umum
            val content = doc.selectFirst(".container .adsads")
                ?: doc.selectFirst("#chapter-c")
                ?: doc.selectFirst(".chr-c")
                ?: doc.selectFirst(".reading-content")
                ?: doc.selectFirst("div[id^=chapter]")
                ?: doc.selectFirst(".chapter-content")
            content?.let { TextExtractor.get(it) } ?: ""
        }

    override suspend fun getBookCoverImageUrl(bookUrl: String): Response<String?> =
        withContext(Dispatchers.Default) {
            tryConnect {
                val doc = networkClient.get(bookUrl).toDocument()
                doc.selectFirst("meta[itemprop=image]")?.attr("content")
                    ?: doc.selectFirst(".book img")?.let { imageUrl(it) }
                    ?: doc.selectFirst(".novel-cover img")?.let { imageUrl(it) }
            }
        }

    override suspend fun getBookDescription(bookUrl: String): Response<String?> =
        withContext(Dispatchers.Default) {
            tryConnect {
                networkClient.get(bookUrl).toDocument()
                    .selectFirst("div.desc-text, .summary__content, .description-summary")
                    ?.text()
            }
        }

    override suspend fun getChapterList(bookUrl: String) =
        withContext(Dispatchers.Default) {
            tryConnect {
                val doc = networkClient.get(bookUrl).toDocument()

                // Ambil novel ID dari og:url, fallback ke URL langsung
                val keyId = doc.selectFirst("meta[property=og:url]")
                    ?.attr("content")
                    ?.toUrlBuilderSafe()
                    ?.build()
                    ?.lastPathSegment
                    ?: bookUrl.toUrlBuilderSafe().build().lastPathSegment
                    ?: throw Exception("Cannot extract novel ID from $bookUrl")

                val ajaxUrl = baseUrl
                    .toUrlBuilderSafe()
                    .addPath("ajax", "chapter-archive")
                    .add("novelId" to keyId)
                    .toString()

                val response = getRequest(
                    url = ajaxUrl,
                    headers = Headers.Builder()
                        .add("Accept", "*/*")
                        .add("X-Requested-With", "XMLHttpRequest")
                        .add(
                            "User-Agent",
                            "Mozilla/5.0 (Android 13; Mobile; rv:125.0) Gecko/125.0 Firefox/125.0"
                        )
                        .add("Referer", "$bookUrl#tab-chapters-title")
                        .build()
                ).let { networkClient.call(it) }.toDocument()

                // FIX: Novelbin (sejak Mei 2026) kadang membungkus chapter dalam <template>
                // Jsoup mengakses isi <template> sebagai node biasa
                val chapters = response.select("ul.list-chapter li a")
                    .takeIf { it.isNotEmpty() }
                    ?: response.select("li a") // fallback untuk <template> content

                chapters.map {
                    val rawTitle = it.attr("title").ifBlank { it.text() }
                    val cleanTitle = rawTitle
                        .replace(Regex("""^.*?#"""), "")
                        .replace(Regex("""\s*-\s*Read\s+.*$""", RegexOption.IGNORE_CASE), "")
                        .replace(Regex("""\s*-\s*All\s+Page.*$""", RegexOption.IGNORE_CASE), "")
                        .trim()
                    ChapterResult(
                        title = cleanTitle.ifBlank { rawTitle.trim() },
                        url = it.attr("href")
                    )
                }
            }
        }

    override suspend fun getCatalogList(index: Int): Response<PagedList<BookResult>> =
        withContext(Dispatchers.Default) {
            val page = index + 1
            val url = catalogUrl
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
            val url = baseUrl
                .toUrlBuilderSafe()
                .addPath("search")
                .add("keyword" to input)
                .ifCase(page > 1) { add("page", page.toString()) }
                .toString()
            getPagesList(index, url)
        }
}
