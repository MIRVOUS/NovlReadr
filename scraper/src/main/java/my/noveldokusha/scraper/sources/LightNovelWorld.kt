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
    override val iconUrl = "https://lightnovelworld.org/static/favicon.ico"
    override val language = LanguageCode.ENGLISH

    private val defaultHeaders = Headers.Builder()
        .add("User-Agent", "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/116.0.0.0 Mobile Safari/537.36")
        .add("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8")
        .add("Accept-Language", "en-US,en;q=0.9")
        .add("Referer", "https://lightnovelworld.org/")
        .build()

    private suspend fun fetchDoc(url: String): Document? = runCatching {
        getRequest(url = url, headers = defaultHeaders)
            .let { networkClient.call(it) }
            .toDocument()
    }.getOrNull()

    override suspend fun getChapterTitle(doc: Document): String =
        withContext(Dispatchers.Default) {
            doc.selectFirst(".chapter-title, h1.chapter-title, h2.chapter-title")
                ?.text()
                ?: doc.selectFirst("h1, h2")?.text()
                ?: ""
        }

    override suspend fun getChapterText(doc: Document): String =
    withContext(Dispatchers.Default) {
        // Selector spesifik: hanya ambil div#chapterText / .chapter-text
        val content = doc.selectFirst("#chapterText")
            ?: doc.selectFirst(".chapter-text")
            ?: doc.selectFirst(".chapter-content")
            ?: return@withContext ""

        // Hapus semua elemen yang bukan isi novel sebelum extract
        content.select(
            "div.chapter-ad-container, " +    // iklan
            "div.chapter-loading, " +          // "Loading chapters..."
            "div.chapter-selector, " +         // dropdown chapter
            "div.chapter-nav, " +              // navigasi atas
            "div.bottom-nav, " +               // navigasi bawah
            "script, style, " +                // script dan CSS inline
            ".chapter-promo"                   // promosi
        ).remove()

        TextExtractor.get(content)
    }

    override suspend fun getBookCoverImageUrl(bookUrl: String): Response<String?> =
        withContext(Dispatchers.Default) {
            tryConnect {
                val doc = fetchDoc(bookUrl) ?: return@tryConnect null
                doc.selectFirst("meta[property='og:image']")?.attr("content")
                    ?: doc.selectFirst(".cover img, .novel-cover img, .book-cover img")
                        ?.let { resolveUrl(baseUrl, it.attr("src").ifBlank { it.attr("data-src") }) }
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

                // Slug dari URL: .../novel/shadow-slave/ → shadow-slave
                val slug = bookUrl.trimEnd('/').substringAfterLast('/')
                    .ifBlank {
                        bookUrl.trimEnd('/').substringBeforeLast('/').substringAfterLast('/')
                    }

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
        // Cari di elemen stats/chapters
        for (el in doc.select("span, div, p, li")) {
            val text = el.ownText().trim()
            val match = Regex("""(\d[\d,]+)\s*chapters?""", RegexOption.IGNORE_CASE).find(text)
            val num = match?.groupValues?.get(1)?.replace(",", "")?.toIntOrNull()
            if (num != null && num in 1..50000) return num
        }

        // Fallback: scan seluruh body
        val bodyText = doc.body()?.text() ?: return null
        val patterns = listOf(
            Regex("""(\d[\d,]+)\s*chapters?""", RegexOption.IGNORE_CASE),
            Regex("""total\s+of\s+(\d[\d,]+)""", RegexOption.IGNORE_CASE)
        )
        for (pattern in patterns) {
            val num = pattern.find(bodyText)?.groupValues?.get(1)
                ?.replace(",", "")?.toIntOrNull()
            if (num != null && num in 1..50000) return num
        }
        return null
    }

    // Parse berdasarkan struktur HTML nyata dari lightnovelworld.org
    // Struktur: div.recommendation-card > h3.card-title + a.card-cover-link > img
    private fun parseBooksFromDocument(doc: Document): List<BookResult> {
        val results = mutableListOf<BookResult>()
        val seen = mutableSetOf<String>()

        for (card in doc.select("div.recommendation-card")) {
            // Judul: ada di h3.card-title di dalam .card-content
            val title = card.selectFirst("h3.card-title")?.text()?.trim()
            if (title.isNullOrBlank()) continue

            // URL novel: dari a.card-cover-link atau a.btn.btn-primary
            val href = card.selectFirst("a.card-cover-link")?.attr("href")
                ?: card.selectFirst("a.btn-primary")?.attr("href")
                ?: continue
            if (href.isBlank()) continue
            val url = resolveUrl(baseUrl, href)
            if (!seen.add(url)) continue

            // Cover: img di dalam .card-cover
            val img = card.selectFirst(".card-cover img")
            val cover = img?.attr("src")?.ifBlank { img.attr("data-src") }
                ?: img?.attr("data-src") ?: ""

            results.add(BookResult(
                title = title,
                url = url,
                coverImageUrl = resolveUrl(baseUrl, cover)
            ))
        }

        return results
    }

    // Deteksi halaman terakhir dari pagination baru lightnovelworld
    // Struktur: a.pagination-btn dengan href yang punya page=N
    private fun isLastPage(doc: Document, currentPage: Int): Boolean {
        // Cari total halaman dari teks pagination "1-24 of 15288"
        val infoText = doc.selectFirst(".pagination-info span")?.text() ?: ""
        val totalMatch = Regex("""of\s+([\d,]+)""").find(infoText)
        val total = totalMatch?.groupValues?.get(1)?.replace(",", "")?.toIntOrNull()
        if (total != null) {
            val totalPages = (total + 23) / 24 // 24 novel per halaman
            return currentPage >= totalPages
        }

        // Fallback: cek apakah ada tombol next (a.pagination-btn dengan SVG panah)
        val paginationBtns = doc.select(".pagination a.pagination-btn")
        return paginationBtns.none { btn ->
            val href = btn.attr("href")
            href.contains("page=${currentPage + 1}")
        }
    }

    override suspend fun getCatalogList(index: Int): Response<PagedList<BookResult>> =
        withContext(Dispatchers.Default) {
            tryConnect {
                val page = index + 1
                val url = "${catalogUrl}?sort=updates&order=desc&page=$page"
                val doc = fetchDoc(url) ?: return@tryConnect PagedList.createEmpty(index = index)
                val books = parseBooksFromDocument(doc)
                PagedList(list = books, index = index, isLastPage = isLastPage(doc, page))
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

            // Pakai URL persis seperti QuickNovel — tanpa search_type
            val apiUrl = "${baseUrl}api/search/?q=$encoded"

            // Pakai fetchDoc tapi ambil raw string, bukan Document
            val rawBody = runCatching {
                getRequest(
                    url = apiUrl,
                    headers = Headers.Builder()
                        .add("User-Agent", "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/143.0.0.0 Mobile Safari/537.36")
                        .add("Accept", "application/json, */*")
                        .add("Referer", "${baseUrl}search/")
                        .add("Sec-Fetch-Dest", "empty")
                        .add("Sec-Fetch-Mode", "cors")
                        .add("Sec-Fetch-Site", "same-origin")
                        .build()
                ).let { networkClient.call(it) }.body?.string()
            }.getOrNull() ?: return@tryConnect PagedList.createEmpty(index = index)

            // Parse JSON pakai org.json (built-in Android, tidak perlu import)
            val novels = mutableListOf<BookResult>()
            runCatching {
                val root = org.json.JSONObject(rawBody)
                val novelsArray = root.optJSONArray("novels")
                    ?: return@tryConnect PagedList.createEmpty(index = index)

                for (i in 0 until novelsArray.length()) {
                    val obj = novelsArray.getJSONObject(i)
                    val title = obj.optString("title").trim().ifBlank { continue }
                    val slug = obj.optString("slug").trim().ifBlank { continue }
                    val coverPath = obj.optString("cover_path", "")
                    novels.add(BookResult(
                        title = title,
                        url = "${baseUrl}novel/$slug/",
                        coverImageUrl = resolveUrl(baseUrl, coverPath)
                    ))
                }
            }

            PagedList(list = novels, index = index, isLastPage = true)
        }
    }

    private fun resolveUrl(base: String, raw: String): String {
        val v = raw.trim()
        if (v.isBlank()) return v
        if (v.startsWith("http://") || v.startsWith("https://")) return v
        if (v.startsWith("//")) return "https:$v"
        return "${base.trimEnd('/')}/${v.removePrefix("/")}"
    }
}
